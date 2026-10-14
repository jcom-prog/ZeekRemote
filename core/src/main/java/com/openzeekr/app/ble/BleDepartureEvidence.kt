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
 *  - (0.3.0) the direction counts, not only the depth: no confirmation while the smoothed signal
 *    is [maxRiseDb] or more above the lowest level of the confirming window, and after
 *    a turn point - a vetoed Lock, or the link coming back after it was lost - the signal must
 *    recede from the level at that point, not from the at-the-car level. Walking back to the car
 *    with a flat deep signal (stress F08/F32) otherwise reads exactly like leaving. A stop alone
 *    is no turn point: the motion sensor reports walking up to 18 s late, so a 4.5 s stop at a
 *    garden gate would look like one (calibrated F22).
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
    private val veryDeepMarginDb: Int = VERY_DEEP_MARGIN_DB,
    private val veryDeepMs: Long = VERY_DEEP_MS,
    private val veryDeepStillToleranceMs: Long = VERY_DEEP_STILL_TOLERANCE_MS,
    private val stillDeepMarginDb: Int = STILL_DEEP_MARGIN_DB,
    private val gradualDescentMs: Long = GRADUAL_DESCENT_MS,
    private val maxRiseDb: Int = MAX_RISE_DB,
) {
    /** Which proof confirmed the latest departure (categorical, for the log and the journal). */
    var confirmKind: String = "none"
        private set
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
    private var veryDeepSinceMs = UNSET
    private var veryDeepStillSinceMs = UNSET
    private var belowLockSinceMs = UNSET
    private var descentMs = UNSET
    private var stillDeepSinceMs = UNSET
    private var confirmedAtMs = UNSET
    // Lowest smoothed level in each confirming window (the rising check).
    private var weakMin = 0
    private var veryDeepMin = 0
    private var stillDeepMin = 0
    // Smoothed level where the user may have turned back; null until a turn point (or after a
    // strong-near sample: back at the car, the at-the-car reference applies again).
    private var turnRefSmoothed: Int? = null
    private var turnAtNextSample = false
    // The arrival was proven by steps + rise, not by a strong-near sample (0.3.1).
    private var anchoredArrival = false

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
            veryDeepSinceMs = UNSET
            belowLockSinceMs = UNSET; descentMs = UNSET; stillDeepSinceMs = UNSET
            restartObservation()
            return decide("time_not_advancing")
        }
        lastSampleAtMs = nowMs
        val gap = previous != UNSET && nowMs - previous > maxGapMs
        if (turnAtNextSample) {
            turnAtNextSample = false
            if (rawRssi < strongNearRssi && smoothedRssi <= lockThreshold) turnRefSmoothed = smoothedRssi
        }

        // Very deep far alone, without steps or an observed arrival (field 05/10, 0.2.2/0.2.3):
        // with the screen off the S24+ step detector reported 2 steps for a whole walk away
        // (16:07), and a pass-by unlock at ~10 m never saw the car up close (17:18); GNSS could
        // not confirm either, so the car stayed open. At the car, even in a back pocket behind the
        // body, the field never read below about -86; a continuously observed run at
        // [veryDeepMarginDb] below the lock threshold for [veryDeepMs] is not standing at the car.
        // Only while walking: standing at the boot behind the car body with the phone in a back
        // pocket can read this deep for a while (simulator F06), walking that long cannot. A short
        // stop on the way does not restart the window (field 05/10 20:43: locking the garden gate
        // ~4.5 s, opening the back door ~1 s, 0.2.4 confirmed only 12 s after the last stop, when
        // the link was too weak for the Lock); a stop of more than [veryDeepStillToleranceMs] does.
        val deepNow = rawRssi < strongNearRssi && smoothedRssi <= lockThreshold - veryDeepMarginDb
        if (moving) veryDeepStillSinceMs = UNSET
        else if (veryDeepStillSinceMs == UNSET) veryDeepStillSinceMs = nowMs
        val stoppedTooLong = !moving && nowMs - veryDeepStillSinceMs > veryDeepStillToleranceMs
        veryDeepSinceMs = when {
            !deepNow || stoppedTooLong -> UNSET
            gap || veryDeepSinceMs == UNSET -> nowMs   // unobserved time is not evidence
            else -> veryDeepSinceMs
        }
        veryDeepMin = if (veryDeepSinceMs == nowMs) smoothedRssi else minOf(veryDeepMin, smoothedRssi)
        // Confirmed only while walking again, never during a stop.
        if (moving && veryDeepSinceMs != UNSET && nowMs - veryDeepSinceMs >= veryDeepMs &&
            !rising(smoothedRssi, veryDeepMin) && recededFromTurn(smoothedRssi)) {
            if (confirmedAtMs == UNSET) confirmedAtMs = nowMs
            confirmKind = "very_deep"
            return decide("confirmed")
        }

        // Motion sensor stuck at STILL during a real walk away (field 09/10 13:30, screen off: STILL
        // for 40 s while the signal fell -64 -> -104 in ~30 s; no Lock, the link dropped). Allowed
        // only for a GRADUAL fall - at least [gradualDescentMs] from the lock threshold down to the
        // very-deep level, as walking produces - and then [veryDeepMs] at [stillDeepMarginDb] or
        // deeper. Standing at the boot the body drops the signal in one step (simulator F06: about
        // 3.5 s), which never qualifies.
        // A short sampling gap (the post-unlock fast window ending, 13:30:23-29) keeps the descent
        // clock; a long one does not.
        val longGap = previous != UNSET && nowMs - previous > MAX_DESCENT_GAP_MS
        if (longGap || smoothedRssi > lockThreshold) belowLockSinceMs = UNSET
        else if (belowLockSinceMs == UNSET) belowLockSinceMs = nowMs
        if (!deepNow) descentMs = UNSET
        else if (descentMs == UNSET && belowLockSinceMs != UNSET) descentMs = nowMs - belowLockSinceMs
        val stillDeep = !gap && descentMs != UNSET && descentMs >= gradualDescentMs &&
            rawRssi < strongNearRssi && smoothedRssi <= lockThreshold - stillDeepMarginDb
        stillDeepSinceMs = when {
            !stillDeep -> UNSET
            stillDeepSinceMs == UNSET -> nowMs
            else -> stillDeepSinceMs
        }
        stillDeepMin = if (stillDeepSinceMs == nowMs) smoothedRssi else minOf(stillDeepMin, smoothedRssi)
        if (stillDeepSinceMs != UNSET && nowMs - stillDeepSinceMs >= veryDeepMs &&
            !rising(smoothedRssi, stillDeepMin)) {
            if (confirmedAtMs == UNSET) confirmedAtMs = nowMs
            confirmKind = "very_deep_gradual"
            return decide("confirmed")
        }

        if (rawRssi >= strongNearRssi) {
            sawStrongInEpoch = true
            anchoredArrival = false
            lastStrongAtMs = nowMs
            clearSinceMs = nowMs
            clearStartSteps = stepsSinceUnlock
            clearStartSmoothed = smoothedRssi
            strongRefSmoothed = smoothedRssi
            turnRefSmoothed = null
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
        if (weakSinceMs == UNSET) { weakSinceMs = nowMs; weakMin = smoothedRssi }
        else weakMin = minOf(weakMin, smoothedRssi)

        return when {
            nowMs - clearSinceMs < minClearMs -> decide("clear_too_short")
            nowMs - weakSinceMs < minWeakMs -> decide("weak_too_short")
            stepsSinceUnlock - baseSteps < (if (anchoredArrival) ANCHORED_MIN_STEPS else minSteps) ->
                decide("steps_too_few")
            // Absence of strong samples is not departure: the signal must actually have receded
            // from the last at-the-car level (strong reference) by a clear margin.
            smoothedRssi > (strongRefSmoothed ?: clearStartSmoothed) - minRecessionDb ->
                decide("not_receding")
            rising(smoothedRssi, weakMin) -> decide("rising")
            !recededFromTurn(smoothedRssi) -> decide("not_receding_from_turn")
            else -> {
                if (confirmedAtMs == UNSET) confirmedAtMs = nowMs
                confirmKind = "walk"
                decide("confirmed")
            }
        }
    }

    /**
     * Withdraws any confirmation and requires a complete new clear + deep-far window. Used when a
     * held proof was revoked elsewhere: the next sample must not simply re-issue it.
     */
    fun restart() {
        veryDeepSinceMs = UNSET
        belowLockSinceMs = UNSET; descentMs = UNSET; stillDeepSinceMs = UNSET
        veryDeepStillSinceMs = UNSET
        restartObservation()
        reason = "restarted"
    }

    /**
     * A Lock this evidence authorized was vetoed (the phone may be at or walking back to the car):
     * everything restarts and a new confirmation must recede from the level at the veto.
     */
    fun vetoed(smoothedRssi: Int) {
        restart()
        turnRefSmoothed = smoothedRssi
        reason = "vetoed"
    }

    /**
     * The link is back after it was lost (the phone came back into radio range, or a reconnect at
     * the edge): the first reading of the new session is a turn point.
     */
    fun linkRestored() { turnAtNextSample = true }

    /**
     * The phone was proven at the car without a strong-near sample (0.3.1 self-calibration): a walk
     * that ended with the signal risen by the last metres' worth, measured with the step counter
     * ([PhoneCalibration]). In a back pocket the field never read above about -77 at the door, so
     * the walk proof was never available and the departure always ended in an alarm (simulator
     * 14/10: 23 of 38 traced "alarm instead of Lock" cases). That level becomes the at-the-car
     * reference: the departure must still recede [minRecessionDb] from it and stay deep for the
     * whole window, and walk [ANCHORED_MIN_STEPS] steps instead of [minSteps]: from a back-pocket
     * level the deep-far band starts about 2 m from the car, and a slow walk away (simulator F04,
     * 0.6 m/s) otherwise completed the proof at its 5 m stop. A real strong-near sample keeps
     * precedence.
     */
    fun arrivalObserved(smoothedLevel: Int) {
        if (sawStrongInEpoch) return
        sawStrongInEpoch = true
        anchoredArrival = true
        strongRefSmoothed = smoothedLevel
        turnRefSmoothed = null
        restartObservation()
    }

    private fun rising(smoothedRssi: Int, windowMin: Int) = smoothedRssi >= windowMin + maxRiseDb

    private fun recededFromTurn(smoothedRssi: Int) =
        turnRefSmoothed?.let { smoothedRssi <= it - minRecessionDb } ?: true

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
        const val VERY_DEEP_MARGIN_DB = 13
        const val VERY_DEEP_MS = 12_000L
        const val VERY_DEEP_STILL_TOLERANCE_MS = 5_000L
        const val STILL_DEEP_MARGIN_DB = 18
        const val GRADUAL_DESCENT_MS = 10_000L
        const val MAX_DESCENT_GAP_MS = 8_000L
        /** 0.3.0: a smoothed rise above the window's lowest level that means "coming back". */
        const val MAX_RISE_DB = 5
        /** 0.3.1: steps the walk proof needs after an arrival proven by steps + rise (~14 m). */
        const val ANCHORED_MIN_STEPS = 20L
    }
}
