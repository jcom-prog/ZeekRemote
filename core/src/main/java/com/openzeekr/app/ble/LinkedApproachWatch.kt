package com.openzeekr.app.ble

/**
 * Keeps the approach observable while the key link is up and the car is still locked.
 *
 * Field test 02/10 19:53–19:56 (0.1.54, phone in trouser pocket, stop-and-go walk with 5 s stops):
 * the session was READY at 63 m, Activity Recognition kept reporting STILL for two minutes while
 * the user walked to the car and stood there ~20 s, and FAR sleep read no RSSI in between (one
 * safety read at 60 s). At the car the pocketed phone read only -80..-82 dBm, which the distance
 * model calls ~8 m (FAR), so the NEAR cadence never engaged either. No unlock.
 *
 * On phones whose only wake source is Activity Recognition (the S24+ step detector is not
 * wake-up), steps are only reported while the CPU is awake. So while the link is up, the car is
 * locked and the signal says the car is not far, hold the CPU and sample once a second: the step
 * assist can then report MOVING within a step or two and the normal approach rules decide. Outside
 * that band, FAR sleep re-checks every [LINKED_SLEEP_MS] instead of once a minute. Both are bounded
 * by the existing security sleep (2 min stationary drops the BLE session).
 *
 * This changes only when RSSI and steps are observed. It does not change any unlock rule: an
 * unlock still requires the motion and threshold evidence of [ProximityDecisionPolicy].
 */
internal object LinkedApproachWatch {
    /** Smoothed RSSI at or above which a locked, linked key stays awake to observe an approach. */
    const val WATCH_RSSI = -96
    /** Sample period inside the watch band. */
    const val WATCH_POLL_MS = 1_000L
    /** FAR sleep re-check while linked and locked, outside the band. */
    const val LINKED_SLEEP_MS = 5_000L

    /**
     * Upper bound on holding the CPU per linked session without any MOVING, independent of the
     * security sleep (which needs a confirmed STILL and so does not bound an UNKNOWN motion state).
     */
    const val WATCH_MAX_MS = 150_000L

    fun applies(sessionReady: Boolean, unlocked: Boolean, wakesOnSteps: Boolean): Boolean =
        sessionReady && !unlocked && !wakesOnSteps

    /** Hold the CPU only when a step assist can actually report MOVING, and only for a bounded time. */
    fun holdAwake(sessionReady: Boolean, unlocked: Boolean, wakesOnSteps: Boolean, smoothedRssi: Int,
                  hasStepAssist: Boolean = true, watchingForMs: Long = 0L): Boolean =
        applies(sessionReady, unlocked, wakesOnSteps) && hasStepAssist &&
            smoothedRssi >= WATCH_RSSI && watchingForMs < WATCH_MAX_MS
}
