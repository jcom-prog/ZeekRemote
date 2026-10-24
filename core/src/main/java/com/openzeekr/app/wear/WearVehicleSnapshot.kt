package com.openzeekr.app.wear

import com.openzeekr.app.net.model.VehicleStatusBean
import com.openzeekr.app.remote.LockView
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The compact vehicle view the phone mirrors to the watch over the Data Layer
 * ([WearKeyProtocol.PATH_SNAPSHOT]). It is NOT the car's reachability by BLE — the watch
 * tracks that itself from its own [com.openzeekr.app.ble.DkBleManager] — this is the cloud
 * side the watch can't see: whether the phone can reach the TSP cloud ([cloudOnline]) plus
 * the charge/lock fields the watch shows.
 *
 * [cloudOnline] gates the watch's cloud controls (climate / charging): when the phone can't
 * reach the car through the cloud, those chips grey out, while BLE lock/unlock stays live.
 *
 * Numbers only (percent, km, kW, degrees) - no position, no identifiers.
 */
@Serializable
data class WearVehicleSnapshot(
    /** The phone reached the TSP cloud on its last status poll (network + token OK). */
    val cloudOnline: Boolean = false,
    /** null = unknown. */
    val locked: Boolean? = null,
    val soc: Int? = null,
    val rangeKm: Int? = null,
    val charging: Boolean = false,
    val pluggedIn: Boolean = false,
    /** Charge-port flap open. */
    val portOpen: Boolean = false,
    val chargeKw: Double? = null,
    /** A/C / pre-climate currently running (drives the climate toggle). */
    val acOn: Boolean = false,
    /** Cabin interior temperature (°C), for the climate card. */
    val insideTempC: Double? = null,
    val updatedAt: Long = 0L,
) {
    fun encode(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(s: String): WearVehicleSnapshot =
            runCatching { JSON.decodeFromString<WearVehicleSnapshot>(s) }.getOrDefault(WearVehicleSnapshot())

        /** The phone's cloud status as the watch sees it. Matches the phone VehicleScreen: SOC is stateOfCharge
         *  (chargeLevel is blank on this car), range is the EV-only distance with the basic range as fallback,
         *  the lock state follows [LockView.cloudLocked] ("1" locked, "0" unlocked, else unknown). */
        fun from(bean: VehicleStatusBean?, cloudOnline: Boolean, nowMs: Long): WearVehicleSnapshot {
            val ev = bean?.additionalVehicleStatus?.electricVehicleStatus
            val socStr = ev?.stateOfCharge?.takeIf { it.isNotBlank() } ?: ev?.chargeLevel
            val rangeStr = ev?.distanceToEmptyOnBatteryOnly?.takeIf { it.isNotBlank() && it != "0" }
                ?: bean?.basicVehicleStatus?.distanceToEmpty
            return WearVehicleSnapshot(
                cloudOnline = cloudOnline && bean != null,
                locked = LockView.cloudLocked(bean?.additionalVehicleStatus?.drivingSafetyStatus?.centralLockingStatus),
                soc = socStr?.toFloatOrNull()?.toInt(),
                rangeKm = rangeStr?.toDoubleOrNull()?.toInt(),
                charging = ev?.chargingActive ?: false,
                pluggedIn = ev?.pluggedIn ?: false,
                portOpen = ev?.portOpen ?: false,
                chargeKw = ev?.chargePowerW?.let { it / 1000.0 },
                acOn = bean?.additionalVehicleStatus?.climateStatus?.acOn ?: false,
                insideTempC = bean?.additionalVehicleStatus?.climateStatus?.interiorTemp?.toDoubleOrNull(),
                updatedAt = nowMs,
            )
        }
    }
}

/**
 * Cloud-only actions the watch can ask the phone to run. Deliberately small (controls +
 * charging, no scheduling). The phone maps each to a [com.openzeekr.app.remote.Command] and
 * runs it BLE-first / cloud-fallback — see the phone's control relay.
 */
enum class WearControl {
    CLIMATE_ON, CLIMATE_OFF,
    CHARGE_START, CHARGE_STOP,
    CHARGE_PORT_OPEN, CHARGE_PORT_CLOSE,
    FLASH_HORN,
}
