package com.openzeekr.app.ble

/** One authenticated, decrypted 0x0121 status from the current key session. */
data class ObservedVehicleStatus(val sessionGeneration: Long, val centralLockCode: Int)

/**
 * The car locks itself (field: unlocked by approach, no door opened, the car relocked
 * after two minutes; the app still believed "unlocked" and sounded a false "car not locked" alarm).
 * The key session reports the central lock in its status push: 1 after an Unlock, 3 after a
 * Lock (every confirmed key command in the field logs showed this within ~0.1 s).
 *
 * Only a fresh 1 -> 3 transition inside the same authenticated session, seen at least
 * [MIN_AFTER_UNLOCK_MS] after our own confirmed Unlock, counts. Status pushes and receipts interleave:
 * the "1" of an Unlock arrives ~20 ms before its receipt, a stale "3" can
 * arrive before it, and a first status after a reconnect has no transition. The caller
 * also lets a manual Lock's own receipt land first (its "3" precedes the receipt by ~20 ms).
 * This only ever stops alarms/departure tracking; it never authorizes an Unlock or Lock.
 */
internal class CarSelfLockDetector {
    enum class Event { NONE, SELF_LOCK, REOPENED, LOCKED_WHILE_AWAY }

    private var generation = -1L
    private var lastCode = -1
    private var unlockAtMs = -1L
    /**
     * The app believes the car is locked: its own Lock or the car's self-lock (also a speed lock).
     * An unknown state (a new process) counts as locked, so a car found open re-arms the departure
     * watch (plan K2, simulator F37: after an app restart the open car was never watched again).
     */
    private var believedLocked = true

    /** The key link went down after our Unlock: the next session's first status is news. */
    private var linkLostSinceUnlock = false

    fun onUnlockConfirmed(nowMs: Long) { unlockAtMs = nowMs; believedLocked = false; linkLostSinceUnlock = false }

    fun onLinkLost() { if (unlockAtMs >= 0L) linkLostSinceUnlock = true }

    fun onLockedByUs() { unlockAtMs = -1L; believedLocked = true }

    /**
     * [Event.SELF_LOCK] for a fresh unlocked -> locked transition after our Unlock; [Event.REOPENED]
     * when, while the app believes the car is locked, the car reports unlocked without our own Unlock
     * (passive entry at the handle, the fob, the inside handle, the car unlocking on parking).
     * Field (0.2.0): Unlock by approach, drive; the car locked while driving (3),
     * unlocked when parked in a garage (2, then 1); the user walked away and nothing watched the
     * open car: the self-lock handling itself had cleared the "self-locked" state, and the "2" in
     * between was not seen as unlocked.
     */
    fun observe(status: ObservedVehicleStatus, nowMs: Long): Event {
        if (status.sessionGeneration != generation) { generation = status.sessionGeneration; lastCode = -1 }
        val previous = lastCode
        lastCode = status.centralLockCode
        val unlockedNow = isUnlocked(status.centralLockCode)
        // The first status of a new session also counts: the car may have been opened while the
        // link was down (re-review 0.1.60).
        if (believedLocked && unlockedNow && !isUnlocked(previous)) {
            believedLocked = false
            return Event.REOPENED
        }
        // Plan H1: the first status of a new session says LOCKED while the app believes the car is
        // open - it was locked while the link was down (fob, handle, another phone, the car itself).
        // Without this the app kept "open": false alarms, and no approach unlock (simulator F14, F40).
        if (!believedLocked && linkLostSinceUnlock && previous == -1 && status.centralLockCode == LOCKED &&
            unlockAtMs >= 0L && nowMs - unlockAtMs >= MIN_AFTER_UNLOCK_MS) {
            unlockAtMs = -1L
            believedLocked = true
            linkLostSinceUnlock = false
            return Event.LOCKED_WHILE_AWAY
        }
        if (unlockAtMs < 0L || nowMs - unlockAtMs < MIN_AFTER_UNLOCK_MS) return Event.NONE
        if (isUnlocked(previous) && status.centralLockCode == LOCKED) {
            unlockAtMs = -1L
            believedLocked = true
            return Event.SELF_LOCK
        }
        return Event.NONE
    }

    companion object {
        const val UNLOCKED = 1
        /** Seen between 3 and 1 when the car unlocked on parking (driver door first). */
        const val PARTLY_UNLOCKED = 2
        const val LOCKED = 3
        const val MIN_AFTER_UNLOCK_MS = 2_000L
        fun isUnlocked(code: Int) = code == UNLOCKED || code == PARTLY_UNLOCKED
    }
}

/**
 * Slow walk-away without a motion signal (field: walking slowly with stops,
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
    private var shallowSinceMs: Long? = null

    fun reset() { farSinceMs = null; deepReadings = 0; raised = false; shallowSinceMs = null }

    /** Feeds one fresh reading; true once when the alarm is due. */
    fun observe(nowMs: Long, rssi: Int, lockThreshold: Int): Boolean {
        // Only consistently deep readings count: one dip at the car must never start a clock that a
        // later link loss would turn into an audible alarm (review 0.1.60).
        if (rssi <= lockThreshold - DEEP_MARGIN_DB) {
            if (farSinceMs == null) farSinceMs = nowMs
            deepReadings++
            shallowSinceMs = null
        } else if (farSinceMs != null && rssi <= lockThreshold - NEUTRAL_MARGIN_DB &&
            nowMs - (shallowSinceMs ?: nowMs) < MAX_SHALLOW_RUN_MS) {
            // Slightly stronger readings 20-30 m away (fading) neither count nor restart the clock
            // while they last less than [MAX_SHALLOW_RUN_MS] (simulator 03/10: a +3 dB reading every
            // minute kept the alarm from ever sounding while the open car was 30 m away). They never
            // start a clock either. Time, not a count (0.3.2, simulation audit C): sampled at 5 Hz
            // while the car is open, "two in a row" was 0.4 s and the clock restarted all the time.
            if (shallowSinceMs == null) shallowSinceMs = nowMs
        } else {
            farSinceMs = null
            deepReadings = 0
            shallowSinceMs = null
        }
        val since = farSinceMs ?: return false
        if (raised || deepReadings < MIN_DEEP_READINGS || nowMs - since < ALARM_AFTER_MS) return false
        raised = true
        return true
    }

    /** No fresh readings any more (link lost): the clock stops, it does not keep running. */
    fun onReadingsStopped() { farSinceMs = null; deepReadings = 0; shallowSinceMs = null }

    /** How long the phone has been consistently clearly far (0 when not, or too few readings). */
    fun deepFarForMs(nowMs: Long): Long =
        if (deepReadings < MIN_DEEP_READINGS) 0L else farSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    companion object {
        const val DEEP_MARGIN_DB = 6
        /** Readings between lock-6 and lock-2 dB are neutral once a clock runs, ... */
        const val NEUTRAL_MARGIN_DB = 2
        /** ... but not for this long (at the 3 s idle cadence: not two in a row). */
        const val MAX_SHALLOW_RUN_MS = 2_000L
        const val MIN_DEEP_READINGS = 5
        const val ALARM_AFTER_MS = 60_000L
        /** A link loss after this much clear separation counts as a suspected departure (audible). */
        const val LINK_LOSS_FAR_MS = 20_000L
        /** Consistent clear separation that may stand in for the step count in the GNSS proof. */
        const val CORROBORATION_MS = 20_000L
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
