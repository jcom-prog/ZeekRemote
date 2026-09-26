package com.openzeekr.app.ble

import android.content.Context
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * TEST harness for the BLE self-calibration flow (0x0190-0x0199) + a plain BLE lock/unlock toggle.
 *
 * Ported from the frozen zeekr-dk-ble project into openzeekr (which now carries the working,
 * instType-fixed BLE + RPA stack). It exists to answer the open question: does running the stock DK
 * "smart calibration" flow, under OUR clean-room key, engage the car's ranging/localization state
 * machine (which passive entry AND remote parking depend on)? See dk-selfcalib-test-harness.
 *
 * Two capture paths, because we can't lift the stock app's encrypted <vin>_SELF_CALIBRATION file:
 *  - [runCalibration]: drive the real flow (0x0190 start -> per-position 0x0192/0x0193 -> the car
 *    computes and pushes the 0x0194 200-byte table FOR OUR SESSION). We persist that table. This
 *    needs the phone at the 4 stock positions once, but the resulting table is valid under our key.
 *  - [replayCalibration]: re-send a previously captured table (0x0198 plaintext) + the model byte
 *    (0x0199) + PE-mode enable (0x0196) + the 0x0151 walk-away enable, as stock does on each authed
 *    reconnect. No walking - but needs a table captured once via [runCalibration].
 *
 * [lock]/[unlock] fire the ordinary DK control frames (0x0110) over the same BLE session, so a tester
 * can watch whether behaviour differs before/after calibration.
 *
 * Nothing here runs unless a button calls it - it holds no background job and never auto-arms.
 */
class CalibrationTestController(
    context: Context,
    private val ble: DkBleManager,
    private val scope: CoroutineScope,
) {
    enum class Phase { IDLE, CONNECTING, RUNNING, DONE, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val busy: Boolean = false,
        val message: String = "",
        /** True once a 200-byte table has been captured + persisted (replay is then possible). */
        val hasTable: Boolean = false,
        /** Step 1..totalSteps while [runCalibration] walks the positions (0 = not stepping). */
        val step: Int = 0,
        val totalSteps: Int = 0,
    )

    private val _state = MutableStateFlow(State(hasTable = false))
    val state: StateFlow<State> = _state

    /** Persisted captured table (plaintext 200B) + hash (4B), our own copy - not the stock file. */
    private val tableFile = File(context.filesDir, "selfcalib_table.bin")
    private val hashFile = File(context.filesDir, "selfcalib_hash.bin")

    private var job: Job? = null

    init {
        _state.value = _state.value.copy(hasTable = tableFile.exists())
    }

    /**
     * The stock 4-step positions, in the REAL order observed running stock smart-calibration at the
     * car (2026-09-20, owner). The type byte we send with each 0x0192 (calibLoc) is the 1-based index
     * IN THIS ORDER, so the order matters: the car labels each RSSI measurement by position, and a
     * mislabelled position poisons the fitted table.
     *
     * The four points fit the phone's RSSI->distance+direction model:
     *  1 door handle  = the passive-entry "at the handle" near threshold (ties to the 0x182 handle
     *                   ranging round) - RIGHT side, ~0 m.
     *  2 6 m left     = far-field, driver side (distance decay + left/right direction).
     *  3 6 m rear     = far-field, behind (front/back direction).
     *  4 charger      = "inside the cabin" reference (don't-lock / allow-drive threshold).
     */
    val stepPrompts: List<String> = listOf(
        "1/4  Phone flat on the DRIVER's DOOR HANDLE. Then tap Continue.",
        "2/4  Outside, ~6 m to the LEFT of the car. Then tap Continue.",
        "3/4  Outside, ~6 m behind the REAR of the car. Then tap Continue.",
        "4/4  Inside the car, phone on the WIRELESS CHARGING pad. Then tap Continue.",
    )

    /** Signalled by the UI when the tester has reached the current position (advances [runCalibration]). */
    @Volatile private var stepGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Tester tapped "Continue" / "I'm in position" for the current calibration step. */
    fun advanceStep() { stepGate?.complete(Unit) }

    // ---------------- run the real flow (capture a table under our key) ----------------

    /**
     * Drive the self-calibration measurement and persist the 0x0194 table the car computes for us.
     * Steps are user-paced: at each position we wait for [advanceStep] (the UI "Continue" button),
     * then send 0x0192 for that position. After the last step we wait for the 0x0194 result.
     */
    fun runCalibration() {
        if (_state.value.busy) return
        job = scope.launch {
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "Starting calibration (0x0190)…", totalSteps = stepPrompts.size) }
            // 0x0190 CALIBRATION_START (type 0 = begin the self-cal session).
            val startErr = session.calibStart(0)
            if (startErr != 0) {
                // errCode -1 = the car sent NO 0x0191 at all (silent). Before, that meant a car-side
                // capability gate - but that verdict predated the instType fix; the self-cal command
                // frames now go out as SELF_CALIB_INST_TYPE (INST_CON). If it is STILL silent here,
                // the gate is real; if it now answers, the old silence was just the wrong instType.
                finishErr(if (startErr == -1)
                    "Car ignored calibration start (no 0x0191). If still silent with INST_CON, this is a " +
                    "real car-side DK3.0/ranging gate; if it now answers, the old silence was the instType."
                else
                    "Car rejected calibration start (0x0191 errCode=$startErr)."); return@launch
            }
            // Per-position measurement, user-paced. type byte = 1-based position index (see stepPrompts).
            for (i in stepPrompts.indices) {
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                stepGate = gate
                setState { it.copy(step = i + 1, message = stepPrompts[i]) }
                // Wait (up to 3 min) for the tester to reach the position and tap Continue.
                if (withTimeoutOrNull(180_000) { gate.await() } == null) {
                    finishErr("Timed out waiting for position ${i + 1}."); return@launch
                }
                stepGate = null
                setState { it.copy(message = "Measuring position ${i + 1}/${stepPrompts.size} (0x0192)…") }
                val locErr = session.calibLoc((i + 1).toByte())
                Logx.d("carprox", "self-cal position ${i + 1} -> 0x0193 errCode=$locErr")
                delay(400) // let the car settle a beat between positions
            }
            // The car pushes the finished 200-byte table (0x0194) once the measurement completes.
            setState { it.copy(step = 0, message = "Waiting for the car to compute the table (0x0194)…") }
            val result = session.calibAwaitTable(30_000)
            if (result == null || result.first.size < 200) {
                finishErr("The car did not return a full calibration table (0x0194). On a non-DK3.0 car " +
                    "the self-cal often does not complete for a software key."); return@launch
            }
            val (table, hash) = result
            runCatching { tableFile.writeBytes(table); if (hash.isNotEmpty()) hashFile.writeBytes(hash) }
                .onFailure { Logx.w("carprox", "persist table failed: ${it.message}") }
            // Finalise like stock: PE-mode enable (0x0196) + model push (0x0199) + walk-away enable (0x0151).
            val peErr = session.calibSetPeMode(1)
            session.calibSendModel(1)
            runCatching { session.sendCustomCommand(DkProtocol.CUST_TYPE_WALK_AWAY_LOCK, true) }
            setState { it.copy(phase = Phase.DONE, busy = false, hasTable = true, step = 0,
                message = "Calibration captured (${table.size}B) + finalised (PE errCode=$peErr). Now walk away " +
                    "to test auto-lock, or try Remote Parking.") }
            Logx.d("carprox", "self-cal captured ${table.size}B + finalised (PE=$peErr)")
        }
    }

    // ---------------- replay a captured table (no walking) ----------------

    /**
     * Re-send a previously captured table the way stock does on each reconnect: 0x0198 (plaintext
     * table) + 0x0199 (model byte) + 0x0196 (PE-mode enable) + 0x0151 (walk-away enable). Requires a
     * table captured once via [runCalibration].
     */
    fun replayCalibration() {
        if (_state.value.busy) return
        job = scope.launch {
            if (!tableFile.exists()) { finishErr("No captured table yet - run Calibration once first."); return@launch }
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            val table = runCatching { tableFile.readBytes() }.getOrNull()
            if (table == null || table.isEmpty()) { finishErr("Stored table unreadable."); return@launch }
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "Replaying calibration (0x0198/0x0199/0x0196)…") }
            session.calibSendSelfData(table)
            delay(200)
            session.calibSendModel(1)
            delay(200)
            val peErr = session.calibSetPeMode(1)
            delay(200)
            runCatching { session.sendCustomCommand(DkProtocol.CUST_TYPE_WALK_AWAY_LOCK, true) }
            setState { it.copy(phase = Phase.DONE, busy = false,
                message = "Replayed ${table.size}B table + walk-away enable (PE errCode=$peErr). Walk away to test.") }
        }
    }

    // ---------------- plain BLE lock / unlock ----------------

    fun lock() = control(DkProtocol.CTRL_LOCK, "LOCK")
    fun unlock() = control(DkProtocol.CTRL_UNLOCK, "UNLOCK")

    private fun control(ctrl: Byte, label: String) {
        if (_state.value.busy) return
        job = scope.launch {
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "$label over BLE (0x0110)…") }
            val res = runCatching { session.control(ctrl, 3000L) }.getOrElse { ControlResult.WRITE_FAILED }
            val ok = res == ControlResult.CONFIRMED
            setState { it.copy(phase = if (ok) Phase.DONE else Phase.ERROR, busy = false,
                message = "$label -> $res") }
            Logx.d("carprox", "test $label -> $res")
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        stepGate?.cancel(); stepGate = null
        setState { it.copy(phase = Phase.IDLE, busy = false, step = 0, message = "Cancelled.") }
    }

    // ---------------- helpers ----------------

    private fun realSession(): RealDkSession? =
        (ble.session as? RealDkSession) ?: run { finishErr("No DK session type."); null }

    /** Ensure we have a live, established DK session; kick a connect + wait up to ~25 s if needed. */
    private suspend fun ensureSession(): Boolean {
        if (ble.state.value == DkBleManager.State.SESSION_READY) return true
        if (!ble.hasCredential) { finishErr("No DK key provisioned - provision the key first (Setup)."); return false }
        setState { it.copy(phase = Phase.CONNECTING, busy = true, message = "Connecting to the car over BLE…") }
        if (ble.state.value == DkBleManager.State.IDLE || ble.state.value == DkBleManager.State.ERROR) {
            runCatching { if (!ble.reconnectLast()) ble.connect(null) }
        }
        val ready = withTimeoutOrNull(25_000) {
            var s = ble.state.value
            while (s != DkBleManager.State.SESSION_READY) { delay(300); s = ble.state.value }
            true
        } ?: false
        if (!ready) finishErr("Could not establish a DK session (state=${ble.state.value}, ${ble.lastError ?: "no error"}). " +
            "Stand next to the car and make sure Bluetooth is on.")
        return ready
    }

    private fun finishErr(msg: String) {
        setState { it.copy(phase = Phase.ERROR, busy = false, step = 0, message = msg) }
        Logx.w("carprox", "calib test: $msg")
    }

    private inline fun setState(transform: (State) -> State) { _state.value = transform(_state.value) }
}
