package com.openzeekr.app.ble

/**
 * Departure evidence from one unlock epoch of fresh connected-GATT RSSI plus walking.
 *
 * Purpose: a timely (~8 m) automatic Lock that does not depend on GNSS geometry, while a phone
 * that stays beside the car keeps the car unlocked for as long as it stays there.
 *
 * The only thing this class trusts is *continuously observed* behaviour:
 *  - a strong-near sample (the phone is demonstrably at the car) restarts the clear period;
 *  - a sampling gap, a non-advancing clock, STILL, or missing step data restarts everything,
 *    because unobserved time is not evidence of separation (0.1.38 idle-far-lock);
 *  - confirmation needs, all at once: [minClearMs] without any strong-near sample, the latest
 *    [minWeakMs] continuously at least [deepFarMarginDb] below the lock threshold (smoothed)
 *    while MOVING, at least [minSteps] new steps since the clear period started, and a signal
 *    that has RECEDED by at least [minRecessionDb] from the level at the last strong-near
 *    sample. Mere absence of strong samples is not departure.
 *
 * The strong-near boundary is never weaker than [minStrongGapDb] above the lock threshold, so a
 * preset whose lock threshold sits close to -72 dBm still has a meaningful near band.
 *
 * Known limit (not hidden): a walker whose body continuously shields the phone beside the car AND
 * whose signal decays like a departure can still satisfy this. The recession requirement makes
 * that much harder than mere shielding, but the parameters must still be calibrated against
 * recorded near-car intervals before any release; they are constructor arguments for that reason.
 *
 * Pure Kotlin; no Android, no coordinates, no RSSI-to-metre conversion.
 */
internal class BleDepartureEvidence(
    private val lockThreshold: Int,
    strongNearRssi: Int = STRONG_NEAR_RSSI,
    private val minClearMs: Long = MIN_CLEAR_MS,
    private val minWeakMs: Long = MIN_WEAK_MS,
    private val minSteps: Long = MIN_STEPS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val minRecessionDb: Int = MIN_RECESSION_DB,
    private val deepFarMarginDb: Int = DEEP_FAR_MARGIN_DB,
) {
    private val strongNearRssi: Int = maxOf(strongNearRssi, lockThreshold + MIN_STRONG_GAP_DB)
    enum class Decision { NONE, CONFIRMED }

    private var lastSampleAtMs = UNSET
    private var sawStrongInEpoch = false
    private var clearSinceMs = UNSET
    private var clearStartSteps: Long? = null
    private var clearStartSmoothed = 0
    // Smoothed level at the latest strong-near sample: the "at the car" reference for recession.
    // Unlike the time windows, a not-moving restart or sampling gap does not erase it.
    private var strongRefSmoothed: Int? = null
    private var weakSinceMs = UNSET
    private var confirmedAtMs = UNSET

    /** Categorical reason for the latest decision; never contains a position. */
    var reason: String = "no_samples"
        private set

    /** Monotonic time of the latest strong-near sample, or null if none in this epoch. */
    var lastStrongAtMs: Long? = null
        private set

    val confirmedAt: Long? get() = confirmedAtMs.takeIf { it != UNSET }

    /**
     * @param nowMs monotonic receipt time of a *fresh* GATT reading (never a cached value)
     * @param rawRssi the reading itself; @param smoothedRssi the controller's EMA after it
     * @param stepsSinceUnlock cumulative steps in this epoch, null when no step source exists
     */
    fun observe(
        nowMs: Long, rawRssi: Int, smoothedRssi: Int, moving: Boolean, stepsSinceUnlock: Long?,
    ): Decision {
        val previous = lastSampleAtMs
        if (previous != UNSET && nowMs <= previous) {
            // Duplicate or backward time cannot extend any window; keep the old sample time so a
            // replayed callback cannot shift the continuity boundary forward either.
            restartObservation()
            return decide("time_not_advancing")
        }
        lastSampleAtMs = nowMs
        val gap = previous != UNSET && nowMs - previous > maxGapMs

        if (rawRssi >= strongNearRssi) {
            sawStrongInEpoch = true
            lastStrongAtMs = nowMs
            clearSinceMs = nowMs
            clearStartSteps = stepsSinceUnlock
            clearStartSmoothed = smoothedRssi
            strongRefSmoothed = smoothedRssi
            weakSinceMs = UNSET
            return decide("strong_near")
        }
        if (!sawStrongInEpoch) return decide("arrival_not_observed")
        if (gap) {
            restartObservation()
            return decide("sampling_gap")
        }
        if (!moving) {
            restartObservation()
            return decide("not_moving")
        }
        if (stepsSinceUnlock == null) {
            restartObservation()
            return decide("steps_unavailable")
        }
        if (clearSinceMs == UNSET) {
            clearSinceMs = nowMs
            clearStartSteps = stepsSinceUnlock
            clearStartSmoothed = smoothedRssi
        }
        val baseSteps = clearStartSteps
        if (baseSteps == null || stepsSinceUnlock < baseSteps) {
            // A step source that appeared or reset mid-window gives no walked-distance proof.
            clearSinceMs = nowMs
            clearStartSteps = stepsSinceUnlock
            clearStartSmoothed = smoothedRssi
            weakSinceMs = UNSET
            return decide("steps_restarted")
        }
        if (smoothedRssi > lockThreshold) {
            weakSinceMs = UNSET
            return decide("not_weak")
        }
        // The timed window counts only continuous DEEP-far time while walking.
        if (smoothedRssi > lockThreshold - deepFarMarginDb) {
            weakSinceMs = UNSET
            return decide("not_deep_far")
        }
        if (weakSinceMs == UNSET) weakSinceMs = nowMs

        return when {
            nowMs - clearSinceMs < minClearMs -> decide("clear_too_short")
            nowMs - weakSinceMs < minWeakMs -> decide("weak_too_short")
            stepsSinceUnlock - baseSteps < minSteps -> decide("steps_too_few")
            // Absence of strong samples is not departure: the signal must actually have receded
            // from the last at-the-car level (strong reference) by a clear margin.
            smoothedRssi > (strongRefSmoothed ?: clearStartSmoothed) - minRecessionDb ->
                decide("not_receding")
            else -> {
                if (confirmedAtMs == UNSET) confirmedAtMs = nowMs
                decide("confirmed")
            }
        }
    }

    /**
     * Withdraws any confirmation and requires a complete new clear + deep-far window. Used when a
     * held proof was revoked elsewhere: the next sample must not simply re-issue it.
     */
    fun restart() {
        restartObservation()
        reason = "restarted"
    }

    private fun restartObservation() {
        clearSinceMs = UNSET
        clearStartSteps = null
        weakSinceMs = UNSET
        confirmedAtMs = UNSET
    }

    private fun decide(why: String): Decision {
        reason = why
        // Any newer sample that no longer satisfies every condition withdraws the confirmation:
        // a pending command must not be authorized by evidence the latest reading contradicts.
        if (why != "confirmed") confirmedAtMs = UNSET
        return if (why == "confirmed") Decision.CONFIRMED else Decision.NONE
    }

    companion object {
        private const val UNSET = -1L
        const val STRONG_NEAR_RSSI = -72
        const val MIN_STRONG_GAP_DB = 8
        // Provisional values. They must be calibrated by private replay of recorded near-car
        // intervals (no confirmation allowed) and departures (lock distance) before release.
        const val MIN_CLEAR_MS = 6_000L
        const val MIN_WEAK_MS = 8_000L
        const val MIN_STEPS = 8L
        const val MAX_GAP_MS = 1_000L
        const val MIN_RECESSION_DB = 6
        const val DEEP_FAR_MARGIN_DB = 3
    }
}
