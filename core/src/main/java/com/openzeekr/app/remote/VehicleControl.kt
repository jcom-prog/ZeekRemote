package com.openzeekr.app.remote

import com.openzeekr.app.ble.ControlResult
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkProtocol
import com.openzeekr.app.net.model.RemoteControlResponse
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.util.Logx

/**
 * BLE-first, cloud-fallback command dispatcher.
 *
 * Actions the DK session can actuate directly (a one-byte control inside a 0x0110 frame — see
 * [DkProtocol]'s CTRL_* table) are tried over BLE **when the key session is live**, reading the car's
 * confirmation; on any BLE failure/reject, or when there's no live session, or when the action has no
 * BLE equivalent, we fall back to the cloud ([RemoteControlRepository.send]).
 *
 * Only actions the app actually exposes are mapped to a BLE byte. Engine/remote-start (0x08), panic
 * (0x05) and key-inside/outside (0x09/0x0B) are intentionally left cloud/none. These BLE bytes are from
 * reverse-engineering and not all verified at the car yet — hence the cloud fallback on failure.
 */
class VehicleControl(
    private val ble: DkBleManager,
    private val cloud: RemoteControlRepository,
) {
    /** DK 0x0110 control byte for a command, or null if it must go via the cloud. */
    private fun bleByte(cmd: Command): Byte? = when (cmd) {
        Command.UNLOCK -> DkProtocol.CTRL_UNLOCK               // 0x01
        Command.LOCK -> DkProtocol.CTRL_LOCK                   // 0x02
        Command.FLASH_HORN -> DkProtocol.CTRL_CAR_LOCATOR      // 0x03 (combined locator; FLASH/HONK stay cloud)
        Command.FRONT_TRUNK -> DkProtocol.CTRL_HOOD_UNLOCK     // 0x04
        Command.TRUNK_UNLOCK -> DkProtocol.CTRL_TRUNK_UNLOCK   // 0x06
        Command.TRUNK_LOCK -> DkProtocol.CTRL_TRUNK_LOCK       // 0x07
        Command.WINDOW_OPEN -> DkProtocol.CTRL_WINDOW_DOWN     // 0x0D (open = down)
        Command.WINDOW_CLOSE -> DkProtocol.CTRL_WINDOW_UP      // 0x0C (close = up)
        // NOTE: WINDOW_VENT is intentionally NOT mapped to DK 0x12. 0x12 (CTRL_VENTILATION) is the
        // car's *cabin/AC* ventilation, not a window crack — at the car it ACKs CONFIRMED but the
        // windows don't move. Real window-vent is the cloud RWS "ventilate" command, so let it fall
        // through to the cloud path.
        Command.CHARGE_LID_OPEN -> DkProtocol.CTRL_CHARGE_LID  // 0x13
        else -> null
    }

    /** True if [cmd] can be actuated over BLE right now (mapped byte + live session). */
    fun bleAvailable(cmd: Command): Boolean =
        bleByte(cmd) != null && ble.state.value == DkBleManager.State.SESSION_READY

    suspend fun send(
        cmd: Command,
        extraParams: List<ServiceParameter> = emptyList(),
    ): CallResult<RemoteControlResponse> {
        val b = bleByte(cmd)
        // BLE path: only when a byte is mapped AND the key session is up. extraParams (temperature,
        // SOC, …) have no BLE representation, so a command carrying them always goes cloud.
        if (b != null && extraParams.isEmpty() && ble.state.value == DkBleManager.State.SESSION_READY) {
            val r = runCatching { ble.session.control(b, BLE_ACK_TIMEOUT_MS) }
                .getOrDefault(ControlResult.WRITE_FAILED)
            Logx.d("ctl", "${cmd.name}: BLE control 0x%02x -> $r".format(b))
            if (r == ControlResult.CONFIRMED) {
                if (cmd == Command.UNLOCK) ble.noteUnlockConfirmed()
                return CallResult.Ok(RemoteControlResponse(serviceId = cmd.serviceId, status = "ok (key)"))
            }
            Logx.d("ctl", "${cmd.name}: BLE $r — falling back to cloud")
        }
        return cloud.send(cmd, extraParams)
    }

    companion object {
        private const val BLE_ACK_TIMEOUT_MS = 1_500L
    }
}
