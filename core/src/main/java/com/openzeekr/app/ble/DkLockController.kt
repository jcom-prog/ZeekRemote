package com.openzeekr.app.ble

import com.openzeekr.app.util.Logx

/**
 * Digital-key lock / unlock over the BLE DK session.
 *
 * Backed by [RealDkSession]: [ensureSession] runs the DK handshake if needed,
 * then each call sends a `CMD_A2V_CONTROL` (0x0110) frame carrying the
 * VehicleCtrlCmd byte (UNLOCK=0x01 / LOCK=0x02), GCM-encrypted by the session.
 * Requires a provisioned credential (DkBleManager.setCredential).
 */
class DkLockController(
    private val session: DkSession,
    /**
     * Recover a stale DK session (drop the link, bring a fresh one up) and return true once ready.
     * Wired to [DkBleManager.refreshSession] in Deps; defaults to a no-op for callers that manage the
     * connection themselves. This is what turns a silent failure into a self-healing retry.
     */
    private val refresh: suspend () -> Boolean = { false },
    /** Called only after the car confirms an unlock, so the service can preserve authenticated key
     *  presence through the door-open -> vehicle-start transition. */
    private val onUnlockConfirmed: () -> Unit = {},
) {

    /** VehicleCtrlCmd.UNLOCK over DK (confirmed, with one self-healing retry). */
    suspend fun unlock(): Boolean = actuate(DkOpcodes.CTRL_UNLOCK, "UNLOCK")

    /** VehicleCtrlCmd.LOCK over DK (confirmed, with one self-healing retry). */
    suspend fun lock(): Boolean = actuate(DkOpcodes.CTRL_LOCK, "LOCK")

    /**
     * Send a 0x0110 control and WAIT for the car's confirmation (0x0111/0x0112), so a stale session
     * that silently swallows the frame is reported as a failure instead of a phantom success (the old
     * fire-and-forget [DkSession.sendFrame] returned true on the GATT write alone). On a non-confirmed,
     * non-rejected result (NO_RESPONSE / WRITE_FAILED = the car timed out its session while the link
     * stayed up) we do automatically what the user otherwise does by hand - refresh the session and
     * retry once. A REJECTED result is a real car-side "no" and is not retried.
     */
    private suspend fun actuate(ctrl: Byte, label: String): Boolean {
        Logx.d("lock", "$label requested")
        if (!ensureSession()) { Logx.w("lock", "$label: no live session (refresh failed)"); return false }
        var r = runCatching { session.control(ctrl, ACK_TIMEOUT_MS) }.getOrElse { ControlResult.WRITE_FAILED }
        Logx.d("lock", "$label -> $r")
        if (r == ControlResult.CONFIRMED) {
            if (ctrl == DkOpcodes.CTRL_UNLOCK) onUnlockConfirmed()
            return true
        }
        if (r == ControlResult.REJECTED) { Logx.w("lock", "$label rejected by the car"); return false }
        // NO_RESPONSE / WRITE_FAILED: the session went stale (car timed out its handshake epoch while
        // the GATT stayed up). Rebuild a fresh session and retry once.
        Logx.w("lock", "$label $r - refreshing the DK session and retrying once")
        if (!refresh()) { Logx.w("lock", "$label: session refresh failed"); return false }
        r = runCatching { session.control(ctrl, ACK_TIMEOUT_MS) }.getOrElse { ControlResult.WRITE_FAILED }
        Logx.d("lock", "$label (after refresh) -> $r")
        return (r == ControlResult.CONFIRMED).also { confirmed ->
            if (confirmed && ctrl == DkOpcodes.CTRL_UNLOCK) onUnlockConfirmed()
        }
    }

    /**
     * DEBUG: send `0x0110` with sub-opcode `CTRL_RPA_START (0x0A)` and report what the car replies.
     * The stock EU RPA flow never sends this (it drives RPA entirely on the 0x0113 channel), so this
     * is purely to observe the car's answer to the generic-control RPA-start byte. Non-committal:
     * one frame, no follow-up, no CMAC — worst case the car NAKs it.
     */
    suspend fun probeRpaStart(windowMs: Long = 4000): String {
        Logx.d("lock", "RPA-start probe (0x0110 / CTRL_RPA_START 0x0A) requested")
        ensureSession()
        return session.probeControl(DkProtocol.CTRL_RPA_START, windowMs)
            .also { Logx.d("lock", "RPA-start probe reply: $it") }
    }

    /** Ensure a live session: use the current one if established, else establish on the live GATT,
     *  else fall back to a full refresh (fresh link + handshake). Returns whether a session is up. */
    private suspend fun ensureSession(): Boolean {
        if (session.isEstablished) return true
        runCatching { session.establish() }
            .onSuccess { return true }
            .onFailure { Logx.w("lock", "establish failed: ${it.message} - refreshing") }
        return refresh()
    }

    private companion object {
        /** Wait for the car's 0x0111/0x0112 confirmation before reporting success. */
        const val ACK_TIMEOUT_MS = 3_000L
    }
}

/**
 * DK channel opcodes — now sourced from [DkProtocol] (reversed + capture-verified).
 * NOTE: the control cmdId is 0x0110 (not 0x0100), and UNLOCK=0x01 / LOCK=0x02
 * (the earlier placeholders had these swapped).
 */
object DkOpcodes {
    const val CMD_A2V_VEHICLE_CTRL = DkProtocol.CMD_A2V_CONTROL   // 0x0110

    const val CMD_A2V_RPA_REQ = DkProtocol.CMD_A2V_RPA_REQ
    const val CMD_V2A_RPA_STATUS = DkProtocol.CMD_V2A_RPA_STATUS
    const val CMD_V2A_RPA_CHALLENGE = DkProtocol.CMD_V2A_RPA_CHALLENGE
    const val CMD_A2V_RPA_ANSWER = DkProtocol.CMD_A2V_RPA_ANSWER
    const val CMD_V2A_RPA_SYNC = DkProtocol.CMD_V2A_RPA_SYNC
    const val CMD_A2V_RSSI_SYNC = DkProtocol.CMD_A2V_RSSI_SYNC

    const val CTRL_UNLOCK: Byte = DkProtocol.CTRL_UNLOCK   // 0x01
    const val CTRL_LOCK: Byte = DkProtocol.CTRL_LOCK       // 0x02
}
