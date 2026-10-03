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
    private var generation = -1L
    private var lastCode = -1
    private var unlockAtMs = -1L

    fun onUnlockConfirmed(nowMs: Long) { unlockAtMs = nowMs }

    fun onLockedByUs() { unlockAtMs = -1L }

    /** True exactly when [status] is a fresh unlocked -> locked transition after our Unlock. */
    fun observe(status: ObservedVehicleStatus, nowMs: Long): Boolean {
        if (status.sessionGeneration != generation) { generation = status.sessionGeneration; lastCode = -1 }
        val previous = lastCode
        lastCode = status.centralLockCode
        if (unlockAtMs < 0L || nowMs - unlockAtMs < MIN_AFTER_UNLOCK_MS) return false
        val selfLock = previous == UNLOCKED && status.centralLockCode == LOCKED
        if (selfLock) unlockAtMs = -1L
        return selfLock
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
 * lock threshold ([DEEP_MARGIN_DB]) for [ALARM_AFTER_MS] while the car is unlocked. Any reading back
 * inside the lock threshold restarts the clock. At most one alarm per unlock.
 */
internal class StationaryFarWatch {
    private var farSinceMs: Long? = null
    private var raised = false

    fun reset() { farSinceMs = null; raised = false }

    /** Feeds one fresh reading; true once when the alarm is due. */
    fun observe(nowMs: Long, rssi: Int, lockThreshold: Int): Boolean {
        when {
            rssi <= lockThreshold - DEEP_MARGIN_DB -> if (farSinceMs == null) farSinceMs = nowMs
            rssi > lockThreshold -> farSinceMs = null
        }
        val since = farSinceMs ?: return false
        if (raised || nowMs - since < ALARM_AFTER_MS) return false
        raised = true
        return true
    }

    /** How long the phone has been clearly far (0 when not). */
    fun deepFarForMs(nowMs: Long): Long = farSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    companion object {
        const val DEEP_MARGIN_DB = 6
        const val ALARM_AFTER_MS = 60_000L
        /** A link loss after this much clear separation counts as a suspected departure (audible). */
        const val LINK_LOSS_FAR_MS = 20_000L
        /** An idle safety read beyond the lock threshold switches to fast sampling this long. */
        const val IDLE_FAR_FAST_MS = 30_000L
    }
}

/** Measurement mode: fast sampling + logging for a fixed time, for calibration walks. */
internal object MeasurementMode {
    const val DURATION_MS = 5 * 60_000L
    fun active(untilElapsedMs: Long, nowElapsedMs: Long): Boolean = untilElapsedMs > nowElapsedMs
}
