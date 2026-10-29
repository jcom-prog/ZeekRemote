package com.openzeekr.app

import android.content.Context
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ble.PhoneStatusProvider
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.remote.AuthRepository
import com.openzeekr.app.remote.CapabilityHolder
import com.openzeekr.app.remote.InboxRepository
import com.openzeekr.app.remote.OtaRepository
import com.openzeekr.app.remote.JourneyRepository
import com.openzeekr.app.remote.NavRepository
import com.openzeekr.app.remote.RemoteControlRepository
import com.openzeekr.app.remote.SentryRepository
import com.openzeekr.app.remote.ShareRepository
import com.openzeekr.app.remote.VehicleStatusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny manual DI container — one instance held by [App]. */
class Deps(context: Context) {
    private val appCtx = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Installed app version (shown in the header and in Settings > About). */
    val appVersion: String =
        runCatching { appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName }.getOrNull() ?: ""
    val config: ConfigStore = ConfigStore.get(context)
        .also {
            com.openzeekr.app.util.Logx.attachDiagnosticJournal(
                java.io.File(appCtx.filesDir, "proximity-diagnostics.log"))
            com.openzeekr.app.util.Logx.setHttp(it.current().logHttp)
            com.openzeekr.app.util.Logx.setBle(it.current().logBle)
        }
    val apiClient: ApiClient = ApiClient.get(config)

    val auth = AuthRepository(config, apiClient)
    /** Renews the cloud sign-in on 079012, ahead of expiry, or on request; never touches the key. */
    val session = com.openzeekr.app.net.SessionRenewer(config, appScope).also { renewer ->
        com.openzeekr.app.net.TokenExpirySignal.listener = { renewer.onTokenExpired() }
        renewer.maybeRenewAhead()
    }
    val control = RemoteControlRepository(config, apiClient, onSharedCarRemoved = {
        com.openzeekr.app.util.CarNotifier.notify(appCtx, com.openzeekr.app.net.model.InboxMessage(
            id = "shared-car-removed", title = "Shared car no longer available",
            body = "The shared car is no longer in your Zeekr account, so its digital key in this app was " +
                "switched off. Wrong? Tap Settings > Refresh sign-in (do not sign out).",
            category = null, redirectUrl = null, imageUrl = null, timeMs = System.currentTimeMillis(), read = false))
    })
    val sentry = SentryRepository(config, apiClient)
    /** Journey log: trip history (distance / energy / duration) with CSV export. */
    val journey = JourneyRepository(config, apiClient)
    /** Member message center (charging done, abnormal parking, alarms, OTA, …). */
    val inbox = InboxRepository(config, apiClient)
    val ota = OtaRepository(config, apiClient)
    val share = ShareRepository(config, apiClient)
    val pendingInvites = MutableStateFlow<List<com.openzeekr.app.net.model.ShareInvite>>(emptyList())
    suspend fun refreshInvites() {
        if (config.current().accessToken.isBlank()) {
            pendingInvites.value = emptyList()
            return
        }
        when (val result = share.pending()) {
            is com.openzeekr.app.remote.CallResult.Ok -> pendingInvites.value = result.value
            is com.openzeekr.app.remote.CallResult.Err ->
                com.openzeekr.app.util.Logx.d("share", "refresh invitations failed: ${result.message}")
        }
    }
    /** Send-to-car: push a navigation POI to the car (also drives the geo:/nav intent handler). */
    val nav = NavRepository(config, apiClient)
    /** FCM push registrar: registers our device token with the message-centre so the car's pushes
     *  (esp. security alarms) arrive when the phone is asleep, instead of only the 5-min inbox poll. */
    val push = com.openzeekr.app.push.PushRegistrar(config, appScope)
    /** Car-side schedules: off-peak charging windows + departure/booking-travel preconditioning. */
    val schedule = com.openzeekr.app.remote.ScheduleRepository(config, apiClient)
    /** Live vehicle status (foreground poll, no push) — observed by the UI. */
    val vehicleState = VehicleStatusHolder(control, appScope)
    /** Per-VIN supported functions — drives which controls the UI shows. */
    val capabilities = CapabilityHolder(control, appScope)

    val ble: DkBleManager = DkBleManager.get(context)
    // One device id for both the TSP transport (x-device-id) and the DK body,
    // as the stock app does (single getDeviceID). Also arm the BLE session if a
    // credential was already provisioned on a previous run.
    val dkIdentity: DkIdentity = DkIdentity.get(context).also { id ->
        if (config.current().deviceIdentifier != id.deviceId) config.update { it.copy(deviceIdentifier = id.deviceId) }
        id.credential()?.let { ble.setCredential(it) }
    }
    val provisioning = DkProvisioning(config, dkIdentity, ble)
    val lock = DkLockController(
        session = ble.session,
        refresh = { ble.refreshSession() },
        onUnlockConfirmed = { proximity.onExternalUnlockConfirmed("manual key control") },
        onLockConfirmed = { proximity.onExternalLockConfirmed("manual key control") },
    )
    val phoneStatus = PhoneStatusProvider(appCtx)
    // Remote parking: the car allows it only from a Digital Key 3.0 (iPhone Wallet/UWB) for now, so the
    // controller ignores every command (user decision 15/10: keep the screen, greyed, for when Android follows).
    val rpa = RpaController(ble.session, appScope, phoneStatus::stateByte, rssi = ble::pollRemoteRssi, available = false)
    /** Wakelock-free motion state (still vs moving) for proximity cadence gating. */
    val motion = com.openzeekr.app.ble.MotionMonitor(appCtx)
    /** Measurement version (battery plan 20/10): the fuel-gauge day book; sampled only at awake moments. */
    val batteryMeter = com.openzeekr.app.util.BatteryMeter(appCtx)
    val proximity: ProximityController = ProximityController(
        appCtx, config, lock, ble, motion, appScope,
        // Cloud lock fallback for the walk-away lock when BLE won't confirm — never leave the car open.
        // Without a cloud sign-in (e.g. the official app holds the session) these answer "not verified"
        // at once instead of sending requests that can only fail; the BLE key keeps working.
        cloudLock = { config.current().accessToken.isNotBlank() &&
            control.send(com.openzeekr.app.remote.Command.LOCK) is com.openzeekr.app.remote.CallResult.Ok },
        // Cloud lock-state probe for the out-of-range backstop: centralLockingStatus "1"=locked, "0"=unlocked.
        cloudIsLocked = {
            if (config.current().accessToken.isBlank()) null
            else (control.status() as? com.openzeekr.app.remote.CallResult.Ok)?.value
                ?.additionalVehicleStatus?.drivingSafetyStatus?.centralLockingStatus
                ?.let { it == "1" }
        },
        cloudLockSnapshot = {
            if (config.current().accessToken.isBlank()) null
            else (control.status() as? com.openzeekr.app.remote.CallResult.Ok)?.value?.let { status ->
                val state = status.additionalVehicleStatus?.drivingSafetyStatus?.centralLockingStatus
                if (state != "1" && state != "0") null
                else com.openzeekr.app.ble.CloudLockSnapshot(
                    locked = state == "1", updatedAtMs = status.updateTime)
            }
        },
    )
    /** 0.4.5: the Sentry guard automaton - reads the car's guard flag and status from the cloud, sends the guard
     *  command, notifies the owner; fed by the engine's confirmed Locks and Unlocks (see [ProximityController.lockEvents]). */
    val guard: com.openzeekr.app.guard.GuardAutomaton = com.openzeekr.app.guard.GuardAutomaton(
        appScope,
        io = object : com.openzeekr.app.guard.GuardIo {
            private val wakeLocks = com.openzeekr.app.ble.AndroidSafetyWakeLocks(appCtx)
            private var held: Any? = null
            override suspend fun readFlag(): Boolean? {
                if (config.current().accessToken.isBlank()) return null
                return (control.controlState() as? com.openzeekr.app.remote.CallResult.Ok)?.value
                    ?.get("vstdModeState")?.let { it == "1" }
            }
            override suspend fun readCar(): com.openzeekr.app.guard.GuardCar? {
                if (config.current().accessToken.isBlank()) return null
                val status = (control.status() as? com.openzeekr.app.remote.CallResult.Ok)?.value ?: return null
                val safety = status.additionalVehicleStatus?.drivingSafetyStatus
                val ev = status.additionalVehicleStatus?.electricVehicleStatus
                // Door, hood and tailgate flags: "0" = closed, anything else open; unknown (null) is not "open".
                fun closed(vararg flags: String?): Boolean? =
                    if (flags.all { it == null }) null else flags.all { it == null || it == "0" }
                val soc = (ev?.stateOfCharge?.toDoubleOrNull() ?: ev?.chargeLevel?.toDoubleOrNull())
                    ?.let { kotlin.math.round(it).toInt() }?.takeIf { it in 0..100 }
                return com.openzeekr.app.guard.GuardCar(
                    locked = safety?.centralLockingStatus?.let { it == "1" },
                    doorsClosed = closed(safety?.doorOpenStatusDriver, safety?.doorOpenStatusDriverRear,
                        safety?.doorOpenStatusPassenger, safety?.doorOpenStatusPassengerRear),
                    bonnetClosed = closed(safety?.engineHoodOpenStatus),
                    trunkClosed = closed(safety?.trunkOpenStatus),
                    socPct = soc,
                    charging = ev?.chargingActive == true,
                    // 0.4.6: the position for the quiet zones and the window state for the open-window notice.
                    lat = status.basicVehicleStatus?.position?.latitude,
                    lon = status.basicVehicleStatus?.position?.longitude,
                    positionTrusted = status.basicVehicleStatus?.position?.posCanBeTrusted,
                    // 0.4.7: one answer for "a window is open"; the opening in the top counts only on a car that reports one.
                    windowsOpen = status.additionalVehicleStatus?.climateStatus?.let { c ->
                        c.windowsOpen || (capabilities.state.value.sunroof && c.sunroofOpen) },
                )
            }
            override suspend fun send(on: Boolean): Boolean {
                if (config.current().accessToken.isBlank()) return false
                val cmd = if (on) com.openzeekr.app.remote.Command.SENTINEL_ON else com.openzeekr.app.remote.Command.SENTINEL_OFF
                return control.send(cmd) is com.openzeekr.app.remote.CallResult.Ok
            }
            override suspend fun closeWindows(): Boolean {
                if (config.current().accessToken.isBlank()) return false
                return control.send(com.openzeekr.app.remote.Command.WINDOW_CLOSE) is com.openzeekr.app.remote.CallResult.Ok
            }
            override fun notify(title: String, text: String, action: com.openzeekr.app.guard.GuardAction?) =
                com.openzeekr.app.guard.GuardNotifier.show(appCtx, title, text, action)
            override fun hold(active: Boolean) {
                if (active) { if (held == null) held = wakeLocks.acquire(6 * 60_000L) }
                else { wakeLocks.release(held); held = null }
            }
        },
        settings = {
            val c = config.current()
            com.openzeekr.app.guard.GuardSettings(
                com.openzeekr.app.guard.GuardMode.parse(c.guardMode), c.guardSocThresholdPct.coerceIn(0, 100),
                zones = c.guardZones.take(com.openzeekr.app.guard.GuardAutomaton.MAX_ZONES),
                skipInZones = c.guardSkipInZones, dailyCheckHour = c.guardDailyCheckHour,
                windowWarning = c.guardWindowWarning)
        },
        log = { com.openzeekr.app.util.Logx.d("guard", it) },
    ).also { guard ->
        proximity.lockEvents = { locked, source ->
            if (locked) guard.onLockConfirmed(source) else guard.onUnlockConfirmed(source)
        }
    }
    /** BLE-first, cloud-fallback dispatcher; confirmed unlocks centrally arm walk-away monitoring. */
    val vehicleControl = com.openzeekr.app.remote.VehicleControl(
        ble,
        control,
        onUnlockConfirmed = proximity::onExternalUnlockConfirmed,
        onLockConfirmed = proximity::onExternalLockConfirmed,
        onCloudLockAccepted = proximity::onExternalCloudLockAccepted,
    )
    /** Call after the base URL / sign algo changes so the HTTP client rebuilds. */
    fun onEndpointChanged() = apiClient.rebuild()
}
