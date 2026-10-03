package com.openzeekr.app.ble

/** One authenticated, decrypted 0x0121 status from the current key session. */
data class ObservedVehicleStatus(val sessionGeneration: Long, val centralLockCode: Int)

/**
 * The car locks itself (field 03/10 12:28:44: unlocked by approach, no door opened, the car relocked
 * after two minutes; the app still believed "unlocked" and sounded a false "car not locked" alarm at
 * 12:29:30). The key session reports the central lock in its status push: 1 after an Unlock, 3 after a
 * Lock (every confirmed key command since 02/10 showed this within ~0.1 s).
 *
 * Only a fresh 1 -> 3 transition inside the same authenticated session, seen at least
 * [MIN_AFTER_UNLOCK_MS] after our own confirmed Unlock, counts. Status pushes and receipts interleave:
 * the "1" of an Unlock arrives ~20 ms before its receipt (03/10 12:26:41.958 vs .979), a stale "3" can
 * arrive before it (12:19:39.449), and a first status after a reconnect has no transition. The caller
 * also lets a manual Lock's own receipt land first (its "3" precedes the receipt by ~20 ms).
 * This only ever stops alarms/departure tracking; it never authorizes an Unlock or Lock.
 */
internal class CarSelfLockDetector {
    enum class Event { NONE, SELF_LOCK, REOPENED }

    private var generation = -1L
    private var lastCode = -1
    private var unlockAtMs = -1L
    private var selfLocked = false

    fun onUnlockConfirmed(nowMs: Long) { unlockAtMs = nowMs; selfLocked = false }

    fun onLockedByUs() { unlockAtMs = -1L; selfLocked = false }

    /**
     * [Event.SELF_LOCK] for a fresh unlocked -> locked transition after our Unlock; [Event.REOPENED]
     * when, after such a self-lock, the car reports unlocked again in the same session without our
     * own Unlock (passive entry at the handle, the fob or the inside handle; review 0.1.60).
     */
    fun observe(status: ObservedVehicleStatus, nowMs: Long): Event {
        if (status.sessionGeneration != generation) { generation = status.sessionGeneration; lastCode = -1 }
        val previous = lastCode
        lastCode = status.centralLockCode
        if (selfLocked && previous == LOCKED && status.centralLockCode == UNLOCKED) {
            selfLocked = false
            return Event.REOPENED
        }
        if (unlockAtMs < 0L || nowMs - unlockAtMs < MIN_AFTER_UNLOCK_MS) return Event.NONE
        if (previous == UNLOCKED && status.centralLockCode == LOCKED) {
            unlockAtMs = -1L
            selfLocked = true
            return Event.SELF_LOCK
        }
        return Event.NONE
    }

    companion object {
        const val UNLOCKED = 1
        const val LOCKED = 3
        const val MIN_AFTER_UNLOCK_MS = 2_000L
    }
}

/**
 * Slow walk-away without a motion signal (field 03/10 12:26:52-12:28:42: walking slowly with stops,
 * Activity Recognition stayed STILL for 1.5 min and the screen-off step assist delivered nothing).
 * Without movement the departure route cannot prove a departure, so no Lock is ever sent from this;
 * it only makes sure the user hears about an unlocked car: the phone has stayed clearly beyond the
 * lock threshold ([DEEP_MARGIN_DB]) for [ALARM_AFTER_MS] while the car is unlocked. Any reading that is
 * not clearly far restarts the clock. At most one alarm per unlock.
 */
internal class StationaryFarWatch {
    private var farSinceMs: Long? = null
    private var deepReadings = 0
    private var raised = false

    fun reset() { farSinceMs = null; deepReadings = 0; raised = false }

    /** Feeds one fresh reading; true once when the alarm is due. */
    fun observe(nowMs: Long, rssi: Int, lockThreshold: Int): Boolean {
        // Only consistently deep readings count: one dip at the car must never start a clock that a
        // later link loss would turn into an audible alarm (review 0.1.60).
        if (rssi <= lockThreshold - DEEP_MARGIN_DB) {
            if (farSinceMs == null) farSinceMs = nowMs
            deepReadings++
        } else {
            farSinceMs = null
            deepReadings = 0
        }
        val since = farSinceMs ?: return false
        if (raised || deepReadings < MIN_DEEP_READINGS || nowMs - since < ALARM_AFTER_MS) return false
        raised = true
        return true
    }

    /** No fresh readings any more (link lost): the clock stops, it does not keep running. */
    fun onReadingsStopped() { farSinceMs = null; deepReadings = 0 }

    /** How long the phone has been consistently clearly far (0 when not, or too few readings). */
    fun deepFarForMs(nowMs: Long): Long =
        if (deepReadings < MIN_DEEP_READINGS) 0L else farSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    companion object {
        const val DEEP_MARGIN_DB = 6
        const val MIN_DEEP_READINGS = 5
        const val ALARM_AFTER_MS = 60_000L
        /** A link loss after this much clear separation counts as a suspected departure (audible). */
        const val LINK_LOSS_FAR_MS = 20_000L
        /** Two consecutive idle safety reads beyond the lock threshold switch to fast sampling this long, */
        const val IDLE_FAR_FAST_MS = 30_000L
        /** ... at most this often per unlock (a shadowed phone at the car must not sample forever). */
        const val MAX_IDLE_FAST_WINDOWS = 3
    }
}

/** Measurement mode: fast sampling + logging for a fixed time, for calibration walks. */
internal object MeasurementMode {
    const val DURATION_MS = 5 * 60_000L
    fun active(untilElapsedMs: Long, nowElapsedMs: Long): Boolean = untilElapsedMs > nowElapsedMs
}
