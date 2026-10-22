package com.openzeekr.app.ble

/**
 * Pure, time-based proximity decision policy.
 *
 * RSSI is noisy enough that a single threshold crossing must never actuate the car. This class keeps
 * the evidence needed to distinguish an actual journey from body-shadow/multipath bounce. It has no
 * Android or BLE dependencies, so captured field traces can be replayed in local unit tests.
 */
internal class ProximityDecisionPolicy(
    private val approachConfirm: ApproachConfirm = ApproachConfirm.UNBROKEN,
    /** The walk-back return at the ordinary approach level after a proven departure (0.3.2). */
    private val walkBackReturn: Boolean = true,
) {
    enum class ArmedDecision { NONE, ARRIVAL_CONFIRMED, LOCK }

    /**
     * How the walking approach route confirms the approach band (0.3.2). [UNBROKEN] holds it for
     * [APPROACH_CONFIRM_MS] without a single reading below it: at the 200 ms walking cadence three
     * readings in a row. Field (S24+ in the front trouser pocket, the usual carry): over
     * the last ~10 m the smoothed signal sat at the band edge and reached it twice for two readings,
     * each time followed by a 1-2 dB dip, so the car opened only at the door. [TWO_IN_A_ROW] and
     * [TWO_OF_THREE] (two of the latest three readings, without a FAR reading in between) accept that.
     */
    enum class ApproachConfirm { UNBROKEN, TWO_IN_A_ROW, TWO_OF_THREE }

    private var sawStrongNearWhileLocked = false
    private var departureObserved = false
    private var farSinceMs = UNSET_MS
    private var farQualified = false
    private var nearCandidateSinceMs = UNSET_MS
    // The latest readings of the walking approach (bit 0 = newest, 1 = inside the approach band).
    private var approachHits = 0
    private var approachLastHitAtMs = UNSET_MS
    private var unlockQualifiedAtMs = UNSET_MS

    // An offloaded presence hit is the earliest reliable proof that a sleeping phone entered the
    // car's radio range. Keep this evidence independent from the instantaneous motion state: a fast
    // walker can be STILL by the time GATT has connected and the DK handshake is ready. The candidate
    // is created only from an edge-range hit while moving, then needs a sustained RSSI improvement.
    private var presenceApproachAtMs = UNSET_MS
    private var presenceEntryRssi = 0
    private var presencePeakRssi = Int.MIN_VALUE
    private var presenceNearSinceMs = UNSET_MS

    private var unlockConfirmedAtMs = UNSET_MS
    private var arrivalStrongSinceMs = UNSET_MS
    private var arrivalConfirmed = false
    private var preArrivalPeakRssi = Int.MIN_VALUE
    private var abortedArrivalFarSinceMs = UNSET_MS
    private var abortedArrivalWeakestRssi = 0
    private var walkAwayFarSinceMs = UNSET_MS
    private var walkAwayFarStartRssi = 0
    private var walkAwayWeakestRssi = 0
    private var lastUnlockedSampleAtMs = UNSET_MS
    // A confirmed departure decision is terminal for the current BLE session. RSSI multipath can
    // rebound immediately after the lock (captured in the first 0.1.29 fast-walk test), but that is
    // not a new arrival. Rearming requires both a link end and a fresh offloaded presence event.
    private var departureLatched = false
    private var departureLinkEnded = false
    private var departureSameLinkFarSinceMs = UNSET_MS
    private var departureSameLinkFarQualifiedAtMs = UNSET_MS
    private var manualDeparture = false
    private var manualStillSinceMs = UNSET_MS
    private var manualStillReady = false
    private var manualReturnMoving = false
    private var manualReturnStrongReadings = 0
    private var manualLowestRssi = 0
    private var manualAtCarReadings = 0
    private var manualStillRssi = 0
    private var manualStillRebound = false
    private var manualNearPeakRssi = Int.MIN_VALUE
    private var manualWeakStillSinceMs = UNSET_MS
    private var manualWeakestRssi = 0
    private var manualWeakReturnMoving = false
    private var manualWeakNearSinceMs = UNSET_MS
    private var pendingUnlockFarSinceMs = UNSET_MS
    // Weakest reading of the current FAR baseline and the start of a motionless approach candidate.
    private var farFloorRssi = 0
    private var stillApproachSinceMs = UNSET_MS
    private var manualDeepFarSinceMs = UNSET_MS
    private var manualDeepFarReadings = 0
    private var manualDeepFarSeen = false
    private var manualDeepReturnSinceMs = UNSET_MS
    private var manualDeepReturnReadings = 0
    private var manualDeepLastReadingAtMs = UNSET_MS
    private var manualDeepFarLastAtMs = UNSET_MS
    private var deepReturnNotBeforeMs = UNSET_MS
    // After a pre-armed automatic Lock: the phone must first read beyond the unlock band once.
    private var deepReturnNeedsRecession = false
    /** After a signal-recognised return the unlock may be re-qualified until this time (no motion needed). */
    private var deepReturnUnlockUntilMs = UNSET_MS
    // Walk-back return (0.3.2, simulation audit B): the hold at the approach level, the weakest
    // reading since the Lock, the step total at the last reading near that floor (the turn: the
    // walk back rises out of it) and the step total at the car before the departure.
    private var walkBackSinceMs = UNSET_MS
    private var walkBackFloorRssi = Int.MAX_VALUE
    private var stepsAtWalkBackFloor: Long? = null
    private var stepsAtDepartureStart: Long? = null
    // Per reading (set by [shouldUnlock]): the step total and "counted steps are rising now".
    private var readingSteps: Long? = null
    private var readingStepsRising = false
    // 0.3.12, also per reading: a live step count shows the walk going on, how long it has stalled,
    // and the raw step total (the returns after a Lock below; simulator strong spot at 25 m).
    private var readingWalkingConfirmed = true
    private var readingStallAgeMs = 0L
    private var readingStepsTotal: Long? = null
    // The step total when the manual return's motion began, and the latest deep-far reading after a Lock.
    private var manualReturnStepsAtMotionStart: Long? = null
    private var lastDeepFarAtMs = UNSET_MS
    // The weakest reading since a manual Lock and the step total there (the turn), and a manual return
    // the step count showed standing: it stays standing until the count advances.
    private var manualFloorRssi = Int.MAX_VALUE
    private var manualStepsAtFloor: Long? = null
    private var manualStandingHeld = false
    private var manualStandingSteps: Long? = null
    // 0.3.28: the latest walking evidence a reading carried - a MOVING reading, or the raw step total advancing at a
    // live cadence (twice within [DEEP_RETURN_STEP_CADENCE_MS]; one late batch is an old walk, not this one).
    private var lastWalkEvidenceAtMs = UNSET_MS
    private var previousStepsTotal: Long? = null
    private var lastStepAdvanceAtMs = UNSET_MS
    /** Diagnostics: a signal return at the return level waited for walking evidence (read by the controller). */
    private var deepReturnHeldForWalk = false
    /** Which signal return qualified (deep_return or walk_back). */
    private var signalReturnRoute = "deep_return"
    /** The current arrival epoch replaced a Lock's departure latch (see [onPresenceMatch]). */
    private var arrivalEpochAfterLock = false
    private var selfLockReopenAtMs = UNSET_MS
    private var selfLockMinRssi = 0  // the smoothed level at the self-lock
    private var selfLockReopenReadings = 0
    // A proven away-and-back round trip: the authenticated link dropped far from the car (out of
    // range) while the car was locked, so a later fresh session that comes up sustained strong-near
    // is a genuine return to the door - even when the reconnect completes without an offloaded
    // presence approach (field: after a walk-away lock the link only re-established at
    // the door, standing still, and the connected-RSSI return was disabled because the departure
    // had ended the link). Sitting in or beside the car never drops the link out of range.
    private var awayRoundTripPending = false
    private var awayReturnReadings = 0
    /** Step count at that far link loss: walking back since then is shown by the step counter. */
    private var stepsAtFarLoss: Long? = null

    /** Which rule qualified the latest unlock (categorical, for the log and the journal). */
    var unlockRoute: String = "none"
        private set

    /** Set once when a return after a Lock was recognised from the signal alone (for the log). */
    var deepFarReturnRearmed = false
        private set

    internal fun isStrongNear(rssi: Int): Boolean = rssi >= STRONG_NEAR_RSSI

    fun resetLocked() {
        sawStrongNearWhileLocked = false
        departureObserved = false
        farSinceMs = UNSET_MS
        farQualified = false
        farFloorRssi = 0; stillApproachSinceMs = UNSET_MS
        nearCandidateSinceMs = UNSET_MS; clearApproachReadings()
        unlockQualifiedAtMs = UNSET_MS
        clearPresenceApproach()
        unlockConfirmedAtMs = UNSET_MS
        lastUnlockedSampleAtMs = UNSET_MS
        arrivalStrongSinceMs = UNSET_MS
        arrivalConfirmed = false
        resetAbortedArrivalCandidate()
        resetWalkAwayCandidate()
        departureLatched = false
        departureLinkEnded = false
        manualDeparture = false
        resetSameLinkReturn()
        arrivalEpochAfterLock = false
        pendingUnlockFarSinceMs = UNSET_MS
        deepReturnUnlockUntilMs = UNSET_MS
        awayRoundTripPending = false
        awayReturnReadings = 0
    }

    /** Records hardware-offloaded arrival evidence before connect/handshake timing can erase it. */
    fun onPresenceMatch(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int) {
        expirePresenceApproach(nowMs)
        if (departureLatched) {
            if (!departureLinkEnded || !isEligiblePresenceEntry(rssi, moving)) {
                return
            }
            // This is the first eligible presence event after the departure session ended: it owns a
            // new arrival epoch. Merely reconnecting or seeing an RSSI rebound cannot clear the latch.
            departureLatched = false
            departureLinkEnded = false
            manualDeparture = false
            arrivalEpochAfterLock = true
        }
        if (presenceApproachAtMs != UNSET_MS) {
            presencePeakRssi = maxOf(presencePeakRssi, rssi)
            return
        }
        // A first hit already strong at the door is ambiguous: it can be the start of a departure.
        // Admit a moving edge/mid-range hit provisionally, then require the rising, sustained proof
        // in shouldUnlock(). The old unlock-relative cut-off rejected the captured 0.1.34 cold
        // arrival at -82 by just 1 dB and could never recover, even though the signal then rose to
        // -61. The absolute strong-near boundary still rejects the 0.1.24 departure hit at -58.
        if (isEligiblePresenceEntry(rssi, moving)) {
            presenceApproachAtMs = nowMs
            presenceEntryRssi = rssi
            presencePeakRssi = rssi
            presenceNearSinceMs = UNSET_MS
        }
    }

    /** Returns true only for a qualified approach or a fresh, stable session already at the door. */
    fun shouldUnlock(
        nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int, steps: Long? = null,
        /** False when a live step counter shows the walk has stopped ([StepStallGate], 0.3.2). */
        walkingConfirmed: Boolean = true,
        /** Milliseconds since a live step count last advanced (0 while walking or unknown). */
        stepStallAgeMs: Long = 0L,
        /** Counted steps are rising now (at least 2 in the last 3 s; false without a step source). */
        stepsRising: Boolean = false,
        /** The raw step total, whatever route asked for [steps] (null without a step source). */
        stepsTotal: Long? = null,
    ): Boolean {
        readingSteps = steps; readingStepsRising = stepsRising
        readingWalkingConfirmed = walkingConfirmed; readingStallAgeMs = stepStallAgeMs; readingStepsTotal = stepsTotal
        // 0.3.28: walking evidence per reading, for the signal-only return after a Lock ([tryRearmManualDeepFarReturn]).
        // A count advance is a walk now only at a live cadence: the hub hands a whole walk over in one late batch
        // (field: the walk away 1665 s late; simulator stress F54: the walk back 47 s late, right before the return level).
        val total = stepsTotal ?: steps
        val stepped = total != null && previousStepsTotal?.let { total > it } == true
        previousStepsTotal = total
        if (stepped) {
            if (lastStepAdvanceAtMs != UNSET_MS && nowMs - lastStepAdvanceAtMs <= DEEP_RETURN_STEP_CADENCE_MS) lastWalkEvidenceAtMs = nowMs
            lastStepAdvanceAtMs = nowMs
        }
        if (moving) lastWalkEvidenceAtMs = nowMs
        if (departureLatched &&
            !tryRearmSameLinkReturn(nowMs, rssi, moving, unlockThreshold) &&
            !(awayRoundTripPending && tryRearmAwayReturn(nowMs, rssi, moving, unlockThreshold, steps))) {
            return false
        }
        // The key can wake while the user walks AWAY (security sleep at the car, car self-locked):
        // that presence hit opens an arrival epoch that lapses after [PRESENCE_TTL_MS], and the walk
        // back on the same link then had no route at all (simulator 03/10, 0.1.64: the car stayed
        // locked at the door). Keep the signal-only return after a Lock alive for this epoch.
        if (!departureLatched && arrivalEpochAfterLock && tryRearmManualDeepFarReturn(nowMs, rssi, unlockThreshold)) {
            finishSameLinkReturn()
            arrivalEpochAfterLock = false
            unlockQualifiedAtMs = nowMs; unlockRoute = signalReturnRoute
            deepReturnUnlockUntilMs = nowMs + DEEP_RETURN_UNLOCK_WINDOW_MS
            deepFarReturnRearmed = true
        }
        expirePresenceApproach(nowMs)
        if (presenceApproachAtMs != UNSET_MS) {
            presencePeakRssi = maxOf(presencePeakRssi, rssi)
            val improved = presencePeakRssi >= presenceEntryRssi + PRESENCE_RISE_DB
            // 0.3.2: an entry already at the band proves no rise. Standing at a door ~25 m
            // away (simulator F24, field: entry -84, two readings of -82 opened the
            // car) is told apart from a genuine mid-range arrival (field 0.1.28 / 0.1.34: entries
            // of -82..-87 while walking up) by the step counter: while a live count shows the user
            // standing, such an entry needs the door level; walking or no live count keeps +4.
            val entryNear = presenceEntryRssi > unlockThreshold - FAR_MARGIN_DB
            val crossedApproachBand = rssi >= unlockThreshold +
                if (entryNear && !walkingConfirmed) PRESENCE_NEAR_ENTRY_MARGIN_DB else PRESENCE_READY_MARGIN_DB
            if (improved && crossedApproachBand) {
                if (presenceNearSinceMs == UNSET_MS) presenceNearSinceMs = nowMs
                if (nowMs - presenceNearSinceMs >= PRESENCE_CONFIRM_MS &&
                    unlockQualifiedAtMs == UNSET_MS) {
                    unlockQualifiedAtMs = nowMs; unlockRoute = "presence"
                }
            } else {
                presenceNearSinceMs = UNSET_MS
            }
        }
        if (rssi >= STRONG_NEAR_RSSI) sawStrongNearWhileLocked = true

        if (rssi <= unlockThreshold - FAR_MARGIN_DB) {
            if (sawStrongNearWhileLocked) departureObserved = true
            if (farSinceMs == UNSET_MS) farSinceMs = nowMs
            if (nowMs - farSinceMs >= FAR_BASELINE_MS) farQualified = true
            // The floor of the latest FAR episode (review 0.1.63: not the lowest reading ever).
            farFloorRssi = if (farSinceMs == nowMs) rssi else minOf(farFloorRssi, rssi)
            stillApproachSinceMs = UNSET_MS
            nearCandidateSinceMs = UNSET_MS; clearApproachReadings()
            unlockQualifiedAtMs = UNSET_MS
            return false
        }

        farSinceMs = UNSET_MS
        // Once we were already strongly at the car and subsequently went FAR, a later multipath
        // rebound is departure noise, not a second approach. A genuine new approach starts with a
        // fresh policy/session and therefore has no [departureObserved] latch.
        val qualifiedApproach = farQualified && !departureObserved && moving && walkingConfirmed &&
            rssi >= unlockThreshold + UNLOCK_MARGIN_DB
        val freshDoorSession = !farQualified && !departureObserved && rssi >= STRONG_NEAR_RSSI
        // Approach while the motion sensor still says STILL (field 0.1.54 and the simulator: the
        // S24+ reported STILL for a whole walk to the car and the door stayed locked). Only a clear,
        // sustained rise from a FAR baseline to well inside the approach band counts; a turn or
        // a passer-by at 10-15 m does not lift a FAR floor this far for this long.
        val stillApproach = farQualified && !departureObserved && !moving &&
            rssi >= unlockThreshold + STILL_APPROACH_MARGIN_DB && rssi >= farFloorRssi + STILL_APPROACH_RISE_DB &&
            // 0.3.2: this route stands for "walking while AR says STILL". A live step count that
            // stopped more than [STILL_APPROACH_STEP_STALL_WINDOW_MS] ago says the walk is over: a
            // stop at the door still opens, a wait at 8 m half a minute later does not (simulator F14).
            stepStallAgeMs <= STILL_APPROACH_STEP_STALL_WINDOW_MS
        if (stillApproach) {
            if (stillApproachSinceMs == UNSET_MS) stillApproachSinceMs = nowMs
            if (nowMs - stillApproachSinceMs >= STILL_APPROACH_CONFIRM_MS && unlockQualifiedAtMs == UNSET_MS) {
                unlockQualifiedAtMs = nowMs; unlockRoute = "still"
            }
        } else stillApproachSinceMs = UNSET_MS
        val unbrokenApproach = qualifiedApproach && approachConfirm == ApproachConfirm.UNBROKEN
        if (approachConfirm != ApproachConfirm.UNBROKEN &&
            approachReadingsConfirm(nowMs, qualifiedApproach) && unlockQualifiedAtMs == UNSET_MS) {
            unlockQualifiedAtMs = nowMs; unlockRoute = "approach"
        }
        if (unbrokenApproach || freshDoorSession) {
            if (nearCandidateSinceMs == UNSET_MS) nearCandidateSinceMs = nowMs
            val holdMs = if (unbrokenApproach) APPROACH_CONFIRM_MS else DOOR_CONFIRM_MS
            if (nowMs - nearCandidateSinceMs >= holdMs && unlockQualifiedAtMs == UNSET_MS) {
                unlockQualifiedAtMs = nowMs; unlockRoute = if (unbrokenApproach) "approach" else "door"
            }
        } else {
            nearCandidateSinceMs = UNSET_MS
        }

        // A fresh deep-sleep connection can prove that the phone reached the door while the DK
        // handshake is still running. Preserve that qualified evidence briefly so SESSION_READY can
        // consume it; a FAR sample (above) invalidates it immediately and the TTL prevents a stale
        // close-range observation from unlocking on a later reconnect.
        if (unlockQualifiedAtMs != UNSET_MS && nowMs - unlockQualifiedAtMs > UNLOCK_EVIDENCE_TTL_MS) {
            unlockQualifiedAtMs = UNSET_MS
        }
        // A session renewal or cooldown can outlast the 5 s evidence; within the window a strong
        // reading re-qualifies without a motion edge (review 0.1.61).
        if (unlockQualifiedAtMs == UNSET_MS && deepReturnUnlockUntilMs != UNSET_MS && nowMs <= deepReturnUnlockUntilMs &&
            rssi >= unlockThreshold + MANUAL_DEEP_RETURN_MARGIN_DB) {
            unlockQualifiedAtMs = nowMs; unlockRoute = "deep_window"
        }
        return unlockQualifiedAtMs != UNSET_MS
    }

    fun onUnlockConfirmed(nowMs: Long) {
        lastUnlockedSampleAtMs = UNSET_MS
        arrivalEpochAfterLock = false
        deepReturnUnlockUntilMs = UNSET_MS
        pendingUnlockFarSinceMs = UNSET_MS
        clearPresenceApproach()
        unlockConfirmedAtMs = nowMs
        arrivalStrongSinceMs = UNSET_MS
        arrivalConfirmed = false
        resetAbortedArrivalCandidate()
        resetWalkAwayCandidate()
        awayRoundTripPending = false
    }

    /**
     * Suppress every unlock decision from the moment walk-away locking starts. [stepsAtCar]: the step
     * total at the car before this departure (the walk-back return measures the way back against it).
     */
    fun onDepartureLockStarted(stepsAtCar: Long? = null) {
        stepsAtDepartureStart = stepsAtCar
        pendingUnlockFarSinceMs = UNSET_MS
        arrivalEpochAfterLock = false
        awayRoundTripPending = false
        departureLatched = true
        // An automatic lock never inherits the manual routes of an earlier manual Lock on the same
        // link (review 0.1.61); onManualLockConfirmed sets it again right after this.
        manualDeparture = false
        deepReturnUnlockUntilMs = UNSET_MS
        departureLinkEnded = false
        resetSameLinkReturn()
        sawStrongNearWhileLocked = false
        departureObserved = true
        farSinceMs = UNSET_MS
        farQualified = false
        farFloorRssi = 0; stillApproachSinceMs = UNSET_MS
        nearCandidateSinceMs = UNSET_MS; clearApproachReadings()
        unlockQualifiedAtMs = UNSET_MS
        clearPresenceApproach()
        resetWalkAwayCandidate()
    }

    /**
     * An automatic Lock is only ever sent on a proven departure (BLE departure route or two GNSS
     * fixes clear of the car), so that departure counts as the "clearly far" half of a return:
     * walking back then rearms on the return level alone (simulator 03/10, "turn back at 9 m": the
     * Lock fired as the user turned and the car stayed locked at the door). Not within
     * [DEEP_RETURN_GUARD_MS] of the Lock: a post-lock multipath rebound (0.1.29, peak -84 for
     * 1.2 s) must not reopen the car, and the proof lapses like any other ([MANUAL_DEEP_PROOF_TTL_MS]).
     */
    fun onAutomaticLockProvenDeparture(nowMs: Long) {
        manualDeepFarSeen = true
        manualDeepFarLastAtMs = nowMs
        deepReturnNotBeforeMs = nowMs + DEEP_RETURN_GUARD_MS
        deepReturnNeedsRecession = true
    }

    /**
     * The car locked itself within 3 min of our approach unlock (the controller decides): a walk
     * up to the door in the next [SELF_LOCK_REOPEN_WINDOW_MS] may reopen it once (call after
     * [onManualLockConfirmed]).
     */
    fun armSelfLockReopen(nowMs: Long, rssi: Int) {
        selfLockReopenAtMs = nowMs
        selfLockMinRssi = rssi
        selfLockReopenReadings = 0
    }

    /**
     * Plan H1: the car was locked while the link was down and the phone is not at the car. A plain
     * locked car with the user away: the next walk up is a normal arrival (no manual-Lock latch).
     */
    fun onLockedWhileAway() = resetLocked()

    /** Confirmed manual lock shares the departure latch. */
    fun onManualLockConfirmed(stepsAtCar: Long? = null) {
        onDepartureLockStarted(stepsAtCar)
        manualDeparture = true
    }

    /** A later arrival may rearm only through [onPresenceMatch], never from connected RSSI alone. */
    fun onLinkEnded() {
        if (departureLatched) {
            departureLinkEnded = true
            manualDeparture = false
            resetSameLinkReturn()
        } else if (!departureObserved || farQualified) {
            // A failed setup can observe FAR long enough to set farQualified, then disconnect
            // before the phone reaches the car. On a later fresh connection at the door the
            // motion sensor may already say STILL. FAR proof from the dead link must not disable
            // the guarded fresh-door fallback. Likewise, a PREVIOUS observed departure cannot
            // suppress a new visit after a sustained FAR baseline and a completed link boundary.
            // A short FAR blip retains departureObserved (the wrong-direction regression).
            departureObserved = false
            sawStrongNearWhileLocked = false
            farSinceMs = UNSET_MS
            farQualified = false
            farFloorRssi = 0; stillApproachSinceMs = UNSET_MS
            nearCandidateSinceMs = UNSET_MS; clearApproachReadings()
            unlockQualifiedAtMs = UNSET_MS
        }
    }

    /**
     * The authenticated link dropped clearly far from the car while the car was locked: a physical
     * round trip is under way. Record it so a later fresh session that comes up sustained
     * strong-near can reopen the car once (see [awayRoundTripPending] and [tryRearmAwayReturn]).
     * Only called with real far evidence (a far reading at loss), so a wedged link beside the car
     * or a phone left inside it never arms this.
     */
    fun onFarLinkLossWhileLocked(steps: Long? = null) {
        if (departureLatched) {
            awayRoundTripPending = true
            awayReturnReadings = 0
            stepsAtFarLoss = steps
        }
    }

    /**
     * Rearm a genuine return while Android deliberately keeps the authenticated GATT link alive.
     *
     * 0.1.32 required link-down before a new arrival, so a user returning within three minutes was
     * visible at -44..-59 dBm but permanently suppressed. Link identity is not an arrival boundary:
     * require a stable FAR epoch, a quiet separation interval, and then a moving FAR -> NEAR crossing.
     * The separation interval is intentionally longer than the captured 0.1.29 post-lock multipath
     * rebound, preserving the departure latch's original anti-reunlock protection.
     */
    private fun tryRearmSameLinkReturn(
        nowMs: Long,
        rssi: Int,
        moving: Boolean,
        unlockThreshold: Int,
    ): Boolean {
        if (departureLinkEnded) return false // fresh offloaded presence owns this path

        // The car locked itself shortly after OUR approach unlock, before the user got in (field
        // Field: unlocked as the user locked a door ~25 m away, the car relocked
        // after 58 s, and at the door "stay locked after a self-lock" kept it shut). Walking up to
        // the door afterwards - a clear rise above the level at the self-lock, while moving - opens it again,
        // once. Sitting in the car or standing beside it gives no such rise and stays locked.
        if (selfLockReopenAtMs != UNSET_MS) {
            if (nowMs - selfLockReopenAtMs > SELF_LOCK_REOPEN_WINDOW_MS) {
                selfLockReopenAtMs = UNSET_MS
            } else {
                // Measured against the level AT the self-lock: sitting in the car (-48 there) would
                // need -42; standing at the gate a few metres away only a real walk to the door
                // clears the rise. No absolute strong-near level: a back pocket reads -76 at 1 m.
                if (moving && rssi >= unlockThreshold + SELF_LOCK_REOPEN_MARGIN_DB &&
                    rssi >= selfLockMinRssi + SELF_LOCK_REOPEN_RISE_DB)
                    selfLockReopenReadings++
                else selfLockReopenReadings = 0
                if (selfLockReopenReadings >= SELF_LOCK_REOPEN_READINGS) {
                    finishSameLinkReturn()
                    unlockQualifiedAtMs = nowMs; unlockRoute = "self_lock_return"
                    return true
                }
            }
        }

        // A return from clearly far away rearms after any Lock (manual, the car's own, or automatic):
        // simulator 03/10, "turn back at 9 m": after an automatic Lock the motion-gated route below
        // missed the walk back and the car stayed locked at the door.
        if (tryRearmManualDeepFarReturn(nowMs, rssi, unlockThreshold)) {
            finishSameLinkReturn()
            // The sustained strong return is itself the approach proof: no motion edge needed.
            unlockQualifiedAtMs = nowMs; unlockRoute = signalReturnRoute
            deepReturnUnlockUntilMs = nowMs + DEEP_RETURN_UNLOCK_WINDOW_MS
            deepFarReturnRearmed = true
            return true
        }
        if (manualDeparture) {
            manualNearPeakRssi = maxOf(manualNearPeakRssi, rssi)
            manualLowestRssi = minOf(manualLowestRssi, rssi)
            if (rssi < manualFloorRssi) manualFloorRssi = rssi
            readingStepsTotal?.let { if (rssi <= manualFloorRssi + WALK_BACK_FLOOR_BAND_DB) manualStepsAtFloor = it }
            // A motion sensor can report STILL during departure and allow far-sleep before the
            // ordinary FAR threshold is sampled. Admit that route only after a large drop from
            // the manual-lock near signal, a long stationary interval, a new motion edge, and a
            // sustained strong recovery. The ordinary FAR/STILL route below remains unchanged.
            if (departureSameLinkFarQualifiedAtMs == UNSET_MS &&
                tryRearmManualWeakSleep(nowMs, rssi, moving, unlockThreshold)) {
                return true
            }
            // Motion can report MOVING for seconds after a stop. A manual lock can only be
            // rearmed by a separate, observed FAR pause followed by a new motion edge.
            if (departureSameLinkFarQualifiedAtMs == UNSET_MS) {
                // The field return stopped around -89 dBm at ten metres. Requiring the generic
                // -92 dBm baseline made physical separation impossible to prove in that trace.
                // STILL + a fresh motion edge provide the extra directional proof on this route.
                if (rssi > unlockThreshold) {
                    departureSameLinkFarSinceMs = UNSET_MS
                    return false
                }
                if (departureSameLinkFarSinceMs == UNSET_MS) departureSameLinkFarSinceMs = nowMs
                if (nowMs - departureSameLinkFarSinceMs < SAME_LINK_FAR_CONFIRM_MS) return false
                departureSameLinkFarQualifiedAtMs = nowMs
            }
            // Back AT the car after a clear walk away, while moving: the stop below can be missed
            // (field, 0.1.65: Lock, walk to 10 m (-98), turning round there gave only
            // 0.3 s STILL, back at the door at -62 and the car stayed locked). The level is the
            // strong-near boundary: turning round at 10 m peaked at -78..-81, never near it.
            // Activity Recognition can miss the short walk back entirely (STILL all the way): then
            // a longer run at the car is required instead of the motion.
            if (tryRearmAtCarReturn(nowMs, rssi, moving, unlockThreshold)) return true
            // 0.3.12: a live step count that shows standing is no walk back - unless this walk already
            // counted [MANUAL_RETURN_MIN_WALK_STEPS] (the counter often says "stopped" at the car just
            // before the signal gets there). Counted from the later of the turn and the motion start:
            // walking on to a strong spot is no walk back (simulator F03/F16: the stop at 25 m reopened
            // the car), nor are steps counted before the motion showed (stress F12: late step packets
            // made 9 and the car opened during a stop at 10 m). Standing holds until the count advances.
            val walkRef = listOfNotNull(manualStepsAtFloor, manualReturnStepsAtMotionStart).maxOrNull()
            val standingByCount = !readingWalkingConfirmed && manualReturnMoving &&
                (readingStepsTotal?.let { s -> walkRef?.let { s - it < MANUAL_RETURN_MIN_WALK_STEPS } } ?: false)
            if (standingByCount) {
                manualStandingHeld = true
                manualStandingSteps = readingStepsTotal
            } else if (manualStandingHeld && (readingWalkingConfirmed ||
                    (readingStepsTotal?.let { s -> manualStandingSteps?.let { s > it } } ?: false))) {
                manualStandingHeld = false
            }
            if (!moving || standingByCount || manualStandingHeld) {
                manualReturnMoving = false
                manualReturnStrongReadings = 0
                // Preserve a FAR separation even if body orientation makes the signal rebound
                // slightly while stopped. It must remain outside the arrival crossing.
                if (rssi <= unlockThreshold + UNLOCK_MARGIN_DB) {
                    if (manualStillSinceMs == UNSET_MS) {
                        manualStillSinceMs = nowMs
                        manualStillRssi = rssi
                    } else {
                        manualStillRssi = minOf(manualStillRssi, rssi)
                    }
                    if (nowMs - manualStillSinceMs >= MANUAL_STILL_CONFIRM_MS) {
                        manualStillReady = true
                    }
                } else if (manualStillReady && rssi < STRONG_NEAR_RSSI) {
                    // Field test 0.1.55: a single -82 orientation rebound during a 13 s stop at
                    // ~20 m erased the proven pause and the walk back never rearmed. A completed
                    // pause is kept; only a strong at-the-car reading while standing voids it.
                    // The kept rebound is no distance proof: see the motion-edge check below.
                    manualStillRebound = true
                } else {
                    manualStillSinceMs = UNSET_MS
                    manualStillReady = false
                    manualStillRssi = 0
                    manualStillRebound = false
                }
                return false
            }
            if (!manualStillReady) {
                // Far-idle deliberately stops fast RSSI polling. The next MOVING callback can be
                // the first sample after a long confirmed pause, so use elapsed monotonic time
                // rather than requiring an otherwise impossible second STILL sample.
                if (manualStillSinceMs != UNSET_MS &&
                    nowMs - manualStillSinceMs >= MANUAL_STILL_CONFIRM_MS) {
                    manualStillReady = true
                } else {
                    manualStillSinceMs = UNSET_MS
                    manualStillRssi = 0
                    return false
                }
            }
            if (!manualReturnMoving) {
                if (manualStillRebound) {
                    // After a kept rebound the lagging EMA can still be in the arrival band while
                    // the user walks further away (review 0.1.56). The walk must start outside
                    // the band, and the return rise is measured from that motion start.
                    if (rssi > unlockThreshold + UNLOCK_MARGIN_DB) {
                        manualStillSinceMs = UNSET_MS
                        manualStillReady = false
                        manualStillRssi = 0
                        manualStillRebound = false
                        return false
                    }
                    manualStillRssi = rssi
                }
                manualReturnMoving = true
                manualReturnStepsAtMotionStart = readingStepsTotal
                manualStillRssi = minOf(manualStillRssi, rssi)
            }
            // This route proves no real departure (a pause at <= the unlock level can be 10 m with
            // the body between phone and car), so the return must keep rising past where turning
            // round at 10 m plateaus. Field (0.1.64): manual Lock, walk to 10 m (-97
            // behind the body), stand, turn round: -82/-81 and no higher reopened the car at 10 m.
            // The 0.1.35 field return rose on through -80 to -77. After a proven clear departure
            // (>= 4 s at <= -92, see [tryRearmManualDeepFarReturn]) the 0.1.64 level stays: one
            // reading at -82 (field: open at ~6 m).
            val provenFar = manualDeepFarSeen
            val margin = if (provenFar) MANUAL_DEEP_RETURN_MARGIN_DB else MANUAL_MOTION_RETURN_MARGIN_DB
            val needed = if (provenFar) 1 else MANUAL_MOTION_RETURN_MIN_READINGS
            if (rssi >= unlockThreshold + margin) manualReturnStrongReadings++
            else manualReturnStrongReadings = 0
            if (nowMs - departureSameLinkFarQualifiedAtMs < SAME_LINK_RETURN_GUARD_MS ||
                (provenFar && risingTooFast(nowMs, rssi, unlockThreshold)) ||
                manualReturnStrongReadings < needed ||
                rssi < manualStillRssi + MANUAL_RETURN_RISE_DB) return false
            // At that level the return itself is the approach proof (no extra hold: the field
            // return of 0.1.35 must still open before the door).
            finishSameLinkReturn()
            unlockQualifiedAtMs = nowMs; unlockRoute = "manual_return"
            return true
        } else {
            // After an automatic Lock only the strict routes reopen: the deep-far return above or
            // the car itself. Field (0.2.1): auto Lock, walking away indoors
            // (-93..-99), a 1.5 s peak to -80 from ~15 m crossed the old arrival edge (-85, moving)
            // and reopened the car behind the user. The same at-car level as after a manual Lock.
            manualLowestRssi = minOf(manualLowestRssi, rssi)
            if (departureSameLinkFarQualifiedAtMs == UNSET_MS) {
                if (rssi > unlockThreshold) {
                    departureSameLinkFarSinceMs = UNSET_MS
                    return false
                }
                if (departureSameLinkFarSinceMs == UNSET_MS) departureSameLinkFarSinceMs = nowMs
                if (nowMs - departureSameLinkFarSinceMs < SAME_LINK_FAR_CONFIRM_MS) return false
                departureSameLinkFarQualifiedAtMs = nowMs
            }
            return tryRearmAtCarReturn(nowMs, rssi, moving, unlockThreshold)
        }
    }

    /**
     * Back AT the car after a clear walk away (a FAR pause and a dip of [MANUAL_AT_CAR_DIP_DB]
     * below the unlock threshold): [MANUAL_AT_CAR_MIN_READINGS] readings at the strong-near level
     * while moving, [MANUAL_AT_CAR_STILL_MIN_READINGS] when Activity Recognition says STILL.
     */
    private fun tryRearmAtCarReturn(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int): Boolean {
        if (manualLowestRssi > unlockThreshold - MANUAL_AT_CAR_DIP_DB ||
            nowMs - departureSameLinkFarQualifiedAtMs < SAME_LINK_RETURN_GUARD_MS) {
            manualAtCarReadings = 0
            return false
        }
        if (rssi >= STRONG_NEAR_RSSI) manualAtCarReadings++ else manualAtCarReadings = 0
        val needed = if (moving) MANUAL_AT_CAR_MIN_READINGS else MANUAL_AT_CAR_STILL_MIN_READINGS
        if (manualAtCarReadings < needed) return false
        finishSameLinkReturn()
        unlockQualifiedAtMs = nowMs; unlockRoute = "at_car"
        return true
    }

    /**
     * Guarded reopen on a proven away-and-back round trip ([onFarLinkLossWhileLocked]). The link
     * already proved physical separation (it dropped far from the car); the only thing left to
     * verify is that the phone is now genuinely back at the door. Require a sustained strong-near
     * run; any reading back out of the near band resets it. A slow reconnect that completes only at
     * the door, standing still, still opens the car - the exact field failure.
     * Plan H2 (0.3.1): in a back pocket the door reads -76..-81, never strong-near, and the motion
     * sensor says STILL after the walk back (simulator F28: the car stayed locked at the door). When
     * the step counter shows the walk back since the far loss ([AWAY_RETURN_MIN_STEPS]), a sustained
     * door-level signal ([AWAY_RETURN_DOOR_MARGIN_DB] above the unlock threshold) is the return too.
     */
    private fun tryRearmAwayReturn(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int, steps: Long?): Boolean {
        if (rssi <= unlockThreshold - FAR_MARGIN_DB) { awayReturnReadings = 0; return false }
        val walkedBack = steps != null && stepsAtFarLoss?.let { steps - it >= AWAY_RETURN_MIN_STEPS } == true
        val atDoor = rssi >= STRONG_NEAR_RSSI || (walkedBack && rssi >= unlockThreshold + AWAY_RETURN_DOOR_MARGIN_DB)
        if (atDoor) awayReturnReadings++ else awayReturnReadings = 0
        val needed = if (moving) AWAY_RETURN_MOVING_READINGS else AWAY_RETURN_STILL_READINGS
        if (awayReturnReadings < needed) return false
        finishSameLinkReturn()
        unlockQualifiedAtMs = nowMs; unlockRoute = if (rssi >= STRONG_NEAR_RSSI) "away_return" else "away_return_steps"
        awayRoundTripPending = false; stepsAtFarLoss = null
        return true
    }

    /**
     * A return after a Lock (own or the car's self-lock), recognised from the signal alone. Field
     * Field (0.1.60): after the car locked itself the user walked to ~15 m (-92..-98 dBm) and
     * back; Activity Recognition said STILL for most of the walk both ways, the stand-still/motion-edge
     * route below voided itself at ~10 m and the car stayed locked at the door.
     * Departure: consistently clearly far, at least [MANUAL_DEEP_FAR_MARGIN_DB] beyond the unlock
     * threshold for [MANUAL_DEEP_FAR_CONFIRM_MS] ([MANUAL_DEEP_FAR_MIN_READINGS] readings or more;
     * any weaker-than-deep reading restarts it). In a pocket at the door the field showed -78..-86,
     * at ~10 m about -84..-89, so standing near the car or at 10 m never qualifies.
     * Return: then [MANUAL_DEEP_RETURN_CONFIRM_MS] of readings at least [MANUAL_DEEP_RETURN_MARGIN_DB]
     * stronger than the unlock threshold (an ordinary approach unlocks around the threshold).
     */
    private fun tryRearmManualDeepFarReturn(nowMs: Long, rssi: Int, unlockThreshold: Int): Boolean {
        signalReturnRoute = "deep_return"
        // Readings must be dense: across a far-sleep gap nothing is known, so a gap restarts every
        // run (review 0.1.61: two isolated dips 30 s apart must never prove a departure).
        val gap = manualDeepLastReadingAtMs != UNSET_MS && nowMs - manualDeepLastReadingAtMs > MANUAL_DEEP_MAX_GAP_MS
        manualDeepLastReadingAtMs = nowMs
        if (rssi < walkBackFloorRssi) walkBackFloorRssi = rssi
        if (rssi <= walkBackFloorRssi + WALK_BACK_FLOOR_BAND_DB) stepsAtWalkBackFloor = readingSteps
        val deep = rssi <= unlockThreshold - MANUAL_DEEP_FAR_MARGIN_DB
        if (deep) lastDeepFarAtMs = nowMs
        // The Lock fired around the lock threshold; noise at 8-10 m must not reopen at once
        // (review 0.1.63): a reading clearly outside the unlock band first, then the usual return.
        // That reading also counts inside the post-lock guard: simulator 03/10 (0.1.64), the Lock
        // landed as the user turned back at ~7 m, the only such reading fell in the guard and the
        // car stayed locked at the door. Without it only a return clearly at the car counts.
        if (deepReturnNeedsRecession && rssi <= unlockThreshold - FAR_MARGIN_DB) deepReturnNeedsRecession = false
        if (deepReturnNotBeforeMs != UNSET_MS && nowMs < deepReturnNotBeforeMs) return false
        if (manualDeepFarSeen) {
            // A proven departure is for the walk back, not for standing at the car minutes later.
            if (deep) manualDeepFarLastAtMs = nowMs
            // 0.3.12: while a live step count shows the user standing (at a spot where the signal is
            // strong, 25 m out), the far proof does not age - for at most [DEEP_FAR_STANDING_HOLD_MS]
            // after the latest deep-far reading, then it lapses as usual (a phone lying still for long
            // must not keep it); standing at the car is caught below.
            else if (!readingWalkingConfirmed &&
                (lastDeepFarAtMs == UNSET_MS || nowMs - lastDeepFarAtMs <= DEEP_FAR_STANDING_HOLD_MS)) manualDeepFarLastAtMs = nowMs
            else if (nowMs - manualDeepFarLastAtMs > MANUAL_DEEP_PROOF_TTL_MS) {
                manualDeepFarSeen = false
                manualDeepFarSinceMs = UNSET_MS
                manualDeepFarReadings = 0
            }
        }
        if (!manualDeepFarSeen) {
            if (deep) {
                if (manualDeepFarSinceMs == UNSET_MS || gap) { manualDeepFarSinceMs = nowMs; manualDeepFarReadings = 0 }
                manualDeepFarReadings++
                if (manualDeepFarReadings >= MANUAL_DEEP_FAR_MIN_READINGS &&
                    nowMs - manualDeepFarSinceMs >= MANUAL_DEEP_FAR_CONFIRM_MS) {
                    manualDeepFarSeen = true
                    manualDeepFarLastAtMs = nowMs
                }
            } else {
                manualDeepFarSinceMs = UNSET_MS
                manualDeepFarReadings = 0
            }
            return false
        }
        if (walkBackReturn && walkedBackToTheCar(nowMs, rssi, unlockThreshold, gap)) {
            signalReturnRoute = "walk_back"
            return true
        }
        // 0.3.12: the signal-only return is a walk. Simulator, strong spot at 25 m (front pocket -86.5):
        // standing there reopened the car from more than 20 m. No return while a live step count shows
        // the user standing, or while it has not advanced for [DEEP_RETURN_MAX_STALL_MS] (the stall
        // gate's own threshold follows the hub's packet interval, 5-9 s; the user stood 1 s after the
        // motion sensor's STILL). Without a step source neither blocks.
        if (!readingWalkingConfirmed || readingStallAgeMs > DEEP_RETURN_MAX_STALL_MS) {
            manualDeepReturnSinceMs = UNSET_MS
            manualDeepReturnReadings = 0
            return false
        }
        val returnMargin = if (deepReturnNeedsRecession) AUTO_LOCK_NEAR_RETURN_MARGIN_DB else MANUAL_DEEP_RETURN_MARGIN_DB
        // 0.3.28 (field 22/10, 0.3.24): after an automatic Lock the user stood 8-10 m from the car for over a minute
        // while the signal crept from -101 to -82, and two readings at -82 reopened the car behind a motionless phone
        // (Activity Recognition STILL for 84 s, no counted step for 88 s; the hub had handed over the walk away in one
        // late batch, so the stall gate above never armed). The signal-only return is a walk: it needs walking
        // evidence from the last [DEEP_RETURN_WALK_EVIDENCE_MS] - a MOVING reading, or the raw step total advancing
        // at a live cadence (see [shouldUnlock]). No level is exempt: in a world of deep fades a spot 10 m out reads
        // like the door for a second (simulator stress F54); the at-car route keeps its own four still readings at
        // the strong-near level. The cost is honest: a walk back that neither sensor reports (the 0.1.60 field shape)
        // stays locked until the door; the app's own step counter (0.4.0) is to close that gap.
        val walkEvidence = lastWalkEvidenceAtMs != UNSET_MS && nowMs - lastWalkEvidenceAtMs <= DEEP_RETURN_WALK_EVIDENCE_MS
        if (!walkEvidence) {
            if (rssi >= unlockThreshold + returnMargin) deepReturnHeldForWalk = true
            manualDeepReturnSinceMs = UNSET_MS
            manualDeepReturnReadings = 0
            return false
        }
        if (rssi < unlockThreshold + returnMargin) {
            manualDeepReturnSinceMs = UNSET_MS
            manualDeepReturnReadings = 0
            return false
        }
        if (manualDeepReturnSinceMs == UNSET_MS || gap) { manualDeepReturnSinceMs = nowMs; manualDeepReturnReadings = 0 }
        manualDeepReturnReadings++
        // The 1 s linked-watch cadence runs 988-1018 ms apart (GATT read time): field 
        // (0.1.63) two readings of -81/-82 at ~6 m were 995 ms apart, were refused, and the car
        // opened only at the door. The span is "about a second", not "at least 1000 ms".
        if (risingTooFast(nowMs, rssi, unlockThreshold)) return false
        return manualDeepReturnReadings >= MANUAL_DEEP_RETURN_MIN_READINGS &&
            nowMs - manualDeepReturnSinceMs >= MANUAL_DEEP_RETURN_CONFIRM_MS - SAMPLE_JITTER_MS
    }

    /**
     * 0.3.12: a real walk back rises for several seconds out of the deep-far level; a peak jumps there
     * (simulator: +15 dB for 1.5 s at 33 m while walking away, 1.4 s after a -94 reading). Within
     * [DEEP_RETURN_MIN_RISE_MS] of the latest deep-far reading no signal-only return counts - unless the
     * reading is at the at-car level ([AT_CAR_JUMP_MARGIN_DB] above the unlock threshold): a back pocket
     * stays deep behind the body all the way and jumps at the car itself.
     */
    private fun risingTooFast(nowMs: Long, rssi: Int, unlockThreshold: Int): Boolean =
        lastDeepFarAtMs != UNSET_MS && nowMs - lastDeepFarAtMs < DEEP_RETURN_MIN_RISE_MS &&
            rssi < unlockThreshold + AT_CAR_JUMP_MARGIN_DB

    /**
     * Walk-back return (0.3.2, simulation audit B): after a proven departure the walk back opens at
     * the ordinary approach level ([UNLOCK_MARGIN_DB] above the threshold) instead of
     * [MANUAL_DEEP_RETURN_MARGIN_DB] - a front pocket rarely reaches -82 before the door (simulator:
     * 110 front-pocket returns late). It needs counted steps rising on every reading (Activity
     * Recognition lingers on MOVING after a stop), [WALK_BACK_HOLD_MS] at that level (longer than the
     * field's 1.5 s post-lock multipath peak), a rise of [WALK_BACK_RISE_DB] over the weakest reading
     * since the Lock, and step odometry: since the last reading near that floor (the turn) at least
     * [WALK_BACK_STEP_SHARE_PCT] % of the steps from the car to it (a strong phone reads the approach
     * level 16-29 m out; on a 40 m errand the walker has then done a quarter of the way back). Both
     * odometry errors (a floor late on the way back, a turn inside the floor band) only delay it; the
     * deep return stays. No step source: no walk-back return.
     */
    private fun walkedBackToTheCar(nowMs: Long, rssi: Int, unlockThreshold: Int, gap: Boolean): Boolean {
        // The hub counter can hold back the first steps of a walk (simulator stress F29: 32 counted for
        // a 35 m walk, the way back fully counted, 66 % "back" at 20 m): count the way out generously.
        val away = stepsAtWalkBackFloor?.let { floor -> stepsAtDepartureStart?.let { floor - it + WALK_BACK_AWAY_ALLOWANCE_STEPS } }
        val back = readingSteps?.let { now -> stepsAtWalkBackFloor?.let { now - it } }
        val walkedBack = away != null && back != null && away > WALK_BACK_AWAY_ALLOWANCE_STEPS &&
            back * 100 >= away * WALK_BACK_STEP_SHARE_PCT
        if (!(readingStepsRising && !deepReturnNeedsRecession && walkedBack &&
                rssi >= unlockThreshold + UNLOCK_MARGIN_DB && rssi >= walkBackFloorRssi + WALK_BACK_RISE_DB)) {
            walkBackSinceMs = UNSET_MS
            return false
        }
        if (walkBackSinceMs == UNSET_MS || gap) walkBackSinceMs = nowMs
        if (nowMs - walkBackSinceMs < WALK_BACK_HOLD_MS) return false
        walkBackSinceMs = UNSET_MS
        return true
    }

    private fun tryRearmManualWeakSleep(
        nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int,
    ): Boolean {
        val weakEnough = rssi <= MANUAL_WEAK_SLEEP_RSSI &&
            manualNearPeakRssi >= rssi + MANUAL_WEAK_DROP_DB
        if (!moving) {
            manualWeakReturnMoving = false
            manualWeakNearSinceMs = UNSET_MS
            if (weakEnough) {
                if (manualWeakStillSinceMs == UNSET_MS) {
                    manualWeakStillSinceMs = nowMs
                    manualWeakestRssi = rssi
                } else {
                    manualWeakestRssi = minOf(manualWeakestRssi, rssi)
                }
            } else {
                manualWeakStillSinceMs = UNSET_MS
                manualWeakestRssi = 0
            }
            return false
        }
        if (!manualWeakReturnMoving) {
            // The first MOVING sample must still be weak. A strong first sample gives no proof
            // that the phone remained separate while RSSI polling was asleep.
            if (!weakEnough || manualWeakStillSinceMs == UNSET_MS ||
                nowMs - manualWeakStillSinceMs < MANUAL_WEAK_SLEEP_MS) return false
            manualWeakReturnMoving = true
            manualWeakestRssi = minOf(manualWeakestRssi, rssi)
        }
        val strongReturn = rssi >= maxOf(unlockThreshold + UNLOCK_MARGIN_DB,
            MANUAL_WEAK_RETURN_RSSI) && rssi >= manualWeakestRssi + MANUAL_WEAK_RETURN_RISE_DB
        if (!strongReturn) {
            manualWeakNearSinceMs = UNSET_MS
            return false
        }
        if (manualWeakNearSinceMs == UNSET_MS) manualWeakNearSinceMs = nowMs
        if (nowMs - manualWeakNearSinceMs < MANUAL_WEAK_RETURN_CONFIRM_MS) return false
        finishSameLinkReturn()
        return true
    }

    private fun finishSameLinkReturn() {
        departureLatched = false
        departureLinkEnded = false
        manualDeparture = false
        resetSameLinkReturn()
        sawStrongNearWhileLocked = false
        departureObserved = false
        farSinceMs = UNSET_MS
        farQualified = true
        nearCandidateSinceMs = UNSET_MS; clearApproachReadings()
        unlockQualifiedAtMs = UNSET_MS
    }

    private fun resetSameLinkReturn() {
        walkBackSinceMs = UNSET_MS
        walkBackFloorRssi = Int.MAX_VALUE
        stepsAtWalkBackFloor = null
        selfLockReopenAtMs = UNSET_MS
        selfLockReopenReadings = 0
        departureSameLinkFarSinceMs = UNSET_MS
        departureSameLinkFarQualifiedAtMs = UNSET_MS
        manualStillSinceMs = UNSET_MS
        manualStillReady = false
        manualReturnMoving = false
        manualFloorRssi = Int.MAX_VALUE
        manualStepsAtFloor = null
        manualStandingHeld = false
        manualStandingSteps = null
        manualReturnStrongReadings = 0
        manualLowestRssi = 0
        manualAtCarReadings = 0
        manualStillRssi = 0
        manualStillRebound = false
        manualNearPeakRssi = Int.MIN_VALUE
        manualWeakStillSinceMs = UNSET_MS
        manualWeakestRssi = 0
        manualWeakReturnMoving = false
        manualWeakNearSinceMs = UNSET_MS
        manualDeepFarSinceMs = UNSET_MS
        manualDeepFarReadings = 0
        manualDeepFarSeen = false
        manualDeepReturnSinceMs = UNSET_MS
        manualDeepReturnReadings = 0
        manualDeepLastReadingAtMs = UNSET_MS
        manualDeepFarLastAtMs = UNSET_MS
        deepReturnNotBeforeMs = UNSET_MS
        deepReturnNeedsRecession = false
        awayReturnReadings = 0
    }

    /** Reads and clears [deepFarReturnRearmed]. */
    fun consumeDeepFarReturnRearm(): Boolean = deepFarReturnRearmed.also { deepFarReturnRearmed = false }

    /** 0.3.28: reads and clears "a signal return at the return level waited for walking evidence". */
    fun consumeDeepReturnHeldForWalk(): Boolean = deepReturnHeldForWalk.also { deepReturnHeldForWalk = false }

    /** Starts a bounded departure guard for an unlock command that is already in flight. */
    fun onPendingUnlockStarted() {
        pendingUnlockFarSinceMs = UNSET_MS
    }

    /**
     * Cancels an in-flight arrival unlock only after sustained far-range movement. A single RSSI
     * dip cannot prove departure: 0.1.30 fast test 1 fell from -84 to -90 and rebounded to -86 in
     * about one second while the user was still approaching, cancelling a valid first command.
     */
    fun shouldCancelPendingUnlock(
        nowMs: Long,
        rssi: Int,
        moving: Boolean,
        unlockThreshold: Int,
        lockThreshold: Int,
    ): Boolean {
        val convincinglyFar = rssi <= minOf(lockThreshold, unlockThreshold - PENDING_UNLOCK_FAR_MARGIN_DB)
        if (!moving || !convincinglyFar) {
            pendingUnlockFarSinceMs = UNSET_MS
            return false
        }
        if (pendingUnlockFarSinceMs == UNSET_MS) pendingUnlockFarSinceMs = nowMs
        return nowMs - pendingUnlockFarSinceMs >= PENDING_UNLOCK_CANCEL_MS
    }

    /**
     * Confirms arrival first, then requires sustained weak-and-receding evidence while moving.
     * A STILL sample or an RSSI recovery cancels the candidate immediately.
     */
    fun onUnlockedSample(nowMs: Long, rssi: Int, moving: Boolean, lockThreshold: Int): ArmedDecision {
        val previousSampleAt = lastUnlockedSampleAtMs
        lastUnlockedSampleAtMs = nowMs
        if (previousSampleAt != UNSET_MS &&
            (nowMs <= previousSampleAt || nowMs - previousSampleAt > MAX_DEPARTURE_SAMPLE_GAP_MS)) {
            // Time without observations is not evidence of sustained separation. The idle-watch
            // path must gather a new, continuous window rather than consume an old FAR candidate.
            resetWalkAwayCandidate()
            resetAbortedArrivalDepartureEvidence()
            arrivalStrongSinceMs = UNSET_MS
            if (nowMs <= previousSampleAt) return ArmedDecision.NONE
        }
        if (!arrivalConfirmed) {
            preArrivalPeakRssi = maxOf(preArrivalPeakRssi, rssi)
            if (rssi >= ARRIVAL_STRONG_RSSI) {
                if (arrivalStrongSinceMs == UNSET_MS) arrivalStrongSinceMs = nowMs
                if (nowMs - arrivalStrongSinceMs >= ARRIVAL_CONFIRM_MS) {
                    arrivalConfirmed = true
                    resetAbortedArrivalCandidate()
                    resetWalkAwayCandidate()
                    return ArmedDecision.ARRIVAL_CONFIRMED
                }
            } else {
                arrivalStrongSinceMs = UNSET_MS
            }

            // An unlock can be followed by an intentional turnaround without the user ever reaching
            // the strong-near arrival band. Preserve that as a separate, conservative state instead
            // of throwing all departure evidence away for PRE_ARRIVAL_GRACE_MS. The captured 0.1.31
            // turnaround fell from -79 to -91 over 4.6 s. Require all of: an observation window,
            // sustained FAR samples, an 8 dB drop from the best post-unlock sample, a currently
            // deep-FAR signal, and current movement. A near return
            // resets it.
            if (rssi > lockThreshold) {
                resetAbortedArrivalDepartureEvidence()
            } else {
                if (abortedArrivalFarSinceMs == UNSET_MS) {
                    abortedArrivalFarSinceMs = nowMs
                    abortedArrivalWeakestRssi = rssi
                } else if (rssi < abortedArrivalWeakestRssi) {
                    abortedArrivalWeakestRssi = rssi
                }
                val observedLongEnough = nowMs - unlockConfirmedAtMs >= ABORTED_ARRIVAL_OBSERVE_MS
                val farLongEnough = nowMs - abortedArrivalFarSinceMs >= ABORTED_ARRIVAL_FAR_MS
                val materiallyReceding = preArrivalPeakRssi != Int.MIN_VALUE &&
                    abortedArrivalWeakestRssi <= preArrivalPeakRssi - ABORTED_ARRIVAL_DROP_DB
                val currentlyConvincinglyFar = rssi <= lockThreshold - ABORTED_ARRIVAL_FAR_MARGIN_DB
                if (observedLongEnough && farLongEnough && materiallyReceding &&
                    currentlyConvincinglyFar && moving) {
                    return ArmedDecision.LOCK
                }
            }

            // Keep the existing conservative fallback for a reversal that did not satisfy the
            // stronger aborted-arrival proof above.
            if (nowMs - unlockConfirmedAtMs < PRE_ARRIVAL_GRACE_MS) {
                resetWalkAwayCandidate()
                return ArmedDecision.NONE
            }
        }

        if (!moving) {
            resetWalkAwayCandidate()
            return ArmedDecision.NONE
        }

        if (walkAwayFarSinceMs == UNSET_MS) {
            if (rssi > lockThreshold) return ArmedDecision.NONE
            walkAwayFarSinceMs = nowMs
            walkAwayFarStartRssi = rssi
            walkAwayWeakestRssi = rssi
            return ArmedDecision.NONE
        }

        // BLE RSSI regularly rebounds by a few dB while the user keeps walking away (body shadow and
        // multipath). Do not erase otherwise valid departure evidence for that noise. A genuinely
        // strong recovery means the phone is close again and cancels the candidate.
        if (rssi >= lockThreshold + WALK_AWAY_RECOVERY_DB) {
            resetWalkAwayCandidate()
            return ArmedDecision.NONE
        }
        if (rssi < walkAwayWeakestRssi) walkAwayWeakestRssi = rssi

        val sustained = nowMs - walkAwayFarSinceMs >= WALK_AWAY_CONFIRM_MS
        val materiallyReceding = walkAwayWeakestRssi <= walkAwayFarStartRssi - WALK_AWAY_DROP_DB
        val currentlyFar = rssi <= lockThreshold
        return if (sustained && materiallyReceding && currentlyFar) ArmedDecision.LOCK
        else ArmedDecision.NONE
    }

    private fun resetWalkAwayCandidate() {
        walkAwayFarSinceMs = UNSET_MS
        walkAwayFarStartRssi = 0
        walkAwayWeakestRssi = 0
    }

    /** An unconfirmed RSSI candidate must gather new separation evidence before retrying. */
    fun onWalkAwayVerificationFailed() {
        resetWalkAwayCandidate()
        resetAbortedArrivalDepartureEvidence()
    }

    private fun resetAbortedArrivalDepartureEvidence() {
        abortedArrivalFarSinceMs = UNSET_MS
        abortedArrivalWeakestRssi = 0
    }

    private fun resetAbortedArrivalCandidate() {
        preArrivalPeakRssi = Int.MIN_VALUE
        resetAbortedArrivalDepartureEvidence()
    }

    private fun expirePresenceApproach(nowMs: Long) {
        if (presenceApproachAtMs != UNSET_MS && nowMs - presenceApproachAtMs > PRESENCE_TTL_MS) {
            clearPresenceApproach()
        }
    }

    private fun clearPresenceApproach() {
        presenceApproachAtMs = UNSET_MS
        presenceEntryRssi = 0
        presencePeakRssi = Int.MIN_VALUE
        presenceNearSinceMs = UNSET_MS
    }

    private fun isEligiblePresenceEntry(rssi: Int, moving: Boolean): Boolean =
        moving && rssi < STRONG_NEAR_RSSI

    /**
     * One walking-approach reading ([hit] = inside the approach band). True on a hit that makes two
     * hits among the latest two ([ApproachConfirm.TWO_IN_A_ROW]) or three readings; a FAR reading
     * clears the history (see [clearApproachReadings]), so a dip may only stay near the band.
     */
    private fun approachReadingsConfirm(nowMs: Long, hit: Boolean): Boolean {
        approachHits = ((approachHits shl 1) or (if (hit) 1 else 0)) and 0b111
        if (!hit) return false
        val previousHitAtMs = approachLastHitAtMs
        approachLastHitAtMs = nowMs
        val window = if (approachConfirm == ApproachConfirm.TWO_IN_A_ROW) 0b011 else 0b111
        return Integer.bitCount(approachHits and window) >= 2 &&
            previousHitAtMs != UNSET_MS && nowMs - previousHitAtMs >= APPROACH_MIN_HIT_GAP_MS
    }

    private fun clearApproachReadings() {
        approachHits = 0
        approachLastHitAtMs = UNSET_MS
    }

    private companion object {
        const val UNSET_MS = -1L
        const val STRONG_NEAR_RSSI = -72
        const val ARRIVAL_STRONG_RSSI = -72
        const val FAR_MARGIN_DB = 2
        const val UNLOCK_MARGIN_DB = 1
        const val FAR_BASELINE_MS = 2_500L
        const val APPROACH_CONFIRM_MS = 400L
        /** Two approach hits are separate readings, never one sample seen twice (cadence is 200 ms). */
        const val APPROACH_MIN_HIT_GAP_MS = 150L
        const val DOOR_CONFIRM_MS = 1_200L
        const val UNLOCK_EVIDENCE_TTL_MS = 5_000L
        // The offloaded presence hit + real motion + a rising signal already prove an approach.
        // Requiring another +4 dB after the DK handshake delayed the field-proven 0.1.33 cold start
        // by ~1.35 s. Keep the two-sample confirmation, but allow readiness at the configured Far
        // threshold; commands still cannot run before SESSION_READY.
        // 0.2.6: right after the wake/connect, two readings 4 dB inside the Far threshold. Field
        // Field: the link came up as the user locked a door ~25 m away and noise
        // just over the threshold opened the car 0.2 s after connecting. The captured 0.1.28
        // arrival (-83..-87, -82 twice during the handshake) still qualifies in time.
        const val PRESENCE_READY_MARGIN_DB = 4
        /** A presence entry already within [FAR_MARGIN_DB] of the threshold: the door level (0.3.2). */
        const val PRESENCE_NEAR_ENTRY_MARGIN_DB = 8
        const val PRESENCE_RISE_DB = 1
        // Two consecutive fast-cadence observations. Requiring 400 ms made the result depend on
        // whether one noisy 200 ms sample landed just before SESSION_READY (the 0.1.28 failure).
        const val PRESENCE_CONFIRM_MS = 200L
        const val PRESENCE_TTL_MS = 35_000L
        const val ARRIVAL_CONFIRM_MS = 1_500L
        const val PRE_ARRIVAL_GRACE_MS = 15_000L
        const val ABORTED_ARRIVAL_OBSERVE_MS = 4_000L
        const val ABORTED_ARRIVAL_FAR_MS = 1_500L
        const val ABORTED_ARRIVAL_DROP_DB = 8
        const val ABORTED_ARRIVAL_FAR_MARGIN_DB = 4
        // 0.2.14 C (Field: the Lock takes too long): confirm a receding departure sooner. The
        // independent GNSS position proof and the downstream WalkAwayLockConfirmation still gate the
        // actual Lock, so a shorter RSSI sustain only brings the candidate forward, never past the
        // "never lock while near" guard.
        const val WALK_AWAY_CONFIRM_MS = 1_200L
        const val MAX_DEPARTURE_SAMPLE_GAP_MS = 1_000L
        const val WALK_AWAY_DROP_DB = 3
        const val WALK_AWAY_RECOVERY_DB = 3
        const val PENDING_UNLOCK_FAR_MARGIN_DB = 3
        const val PENDING_UNLOCK_CANCEL_MS = 1_200L
        const val SAME_LINK_FAR_CONFIRM_MS = 2_500L
        const val SAME_LINK_RETURN_GUARD_MS = 3_000L
        const val MANUAL_STILL_CONFIRM_MS = 1_500L
        const val MANUAL_RETURN_RISE_DB = 2
        const val MANUAL_WEAK_SLEEP_RSSI = -78
        const val MANUAL_WEAK_DROP_DB = 15
        const val MANUAL_WEAK_SLEEP_MS = 10_000L
        const val MANUAL_WEAK_RETURN_RSSI = -70
        const val MANUAL_DEEP_FAR_MARGIN_DB = 6
        const val MANUAL_DEEP_FAR_MIN_READINGS = 2
        const val MANUAL_DEEP_FAR_CONFIRM_MS = 4_000L
        const val MANUAL_DEEP_RETURN_MARGIN_DB = 4
        /** Walk-back return (0.3.2): hold at the approach level, rise over the far point, share of the way back. */
        const val WALK_BACK_HOLD_MS = 2_000L
        const val WALK_BACK_RISE_DB = 6
        const val WALK_BACK_STEP_SHARE_PCT = 60L
        const val WALK_BACK_FLOOR_BAND_DB = 2
        const val WALK_BACK_AWAY_ALLOWANCE_STEPS = 12L
        const val MANUAL_DEEP_RETURN_CONFIRM_MS = 1_000L
        const val MANUAL_DEEP_RETURN_MIN_READINGS = 2
        /** Spread of the RSSI sampling cadence (GATT read time); see [tryRearmManualDeepFarReturn]. */
        const val SAMPLE_JITTER_MS = 150L
        /** After an automatic Lock with no clear recession: back at the car (pocket ~4-5 m); 8-10 m noise peaks at -80. */
        const val AUTO_LOCK_NEAR_RETURN_MARGIN_DB = 10
        /** Motion-gated return after a manual Lock / self-lock: past the 10 m turn-round plateau (-81). */
        const val MANUAL_MOTION_RETURN_MARGIN_DB = 6
        const val MANUAL_MOTION_RETURN_MIN_READINGS = 2
        /** The walk away must have dipped to the deep-far level (field: -98 at ~10 m; at the car -55..-81). */
        const val SELF_LOCK_REOPEN_WINDOW_MS = 300_000L
        const val SELF_LOCK_REOPEN_RISE_DB = 6
        const val SELF_LOCK_REOPEN_MARGIN_DB = 8
        const val SELF_LOCK_REOPEN_READINGS = 2
        const val MANUAL_AT_CAR_DIP_DB = 6
        const val MANUAL_AT_CAR_MIN_READINGS = 2
        const val MANUAL_AT_CAR_STILL_MIN_READINGS = 4
        // Away-and-back reopen (physical separation already proven by the far link loss): a sustained
        // strong-near run at the door. STILL needs more readings because a STILL reconnect at the
        // door is exactly the ambiguous field case.
        const val AWAY_RETURN_MOVING_READINGS = 2
        const val AWAY_RETURN_STILL_READINGS = 4
        /** H2: steps since the far link loss that prove the walk back (~10 m). */
        const val AWAY_RETURN_MIN_STEPS = 15L
        /** H2: the door level after a proven walk back, above the unlock threshold (-82 for "far"). */
        const val AWAY_RETURN_DOOR_MARGIN_DB = 4
        /** Linked-watch samples every 1 s; a longer silence is a far-sleep gap. */
        const val MANUAL_DEEP_MAX_GAP_MS = 2_500L
        const val MANUAL_DEEP_PROOF_TTL_MS = 60_000L
        /** 0.3.12: no signal-only return while a live step count has stalled this long. */
        const val DEEP_RETURN_MAX_STALL_MS = 4_500L
        /** 0.3.12: a signal-only return rises out of the deep-far level for at least this long. */
        const val DEEP_RETURN_MIN_RISE_MS = 3_000L
        /** 0.3.12: the at-car level above the unlock threshold (a back pocket at the door: -73..-77). */
        const val AT_CAR_JUMP_MARGIN_DB = 9
        /** 0.3.12: a manual return that counted this many steps since the turn and its motion start is a walk. */
        const val MANUAL_RETURN_MIN_WALK_STEPS = 8L
        /** 0.3.12: while standing the far proof is kept for at most this long after the latest deep-far reading. */
        const val DEEP_FAR_STANDING_HOLD_MS = 120_000L
        const val DEEP_RETURN_UNLOCK_WINDOW_MS = 30_000L
        const val DEEP_RETURN_GUARD_MS = 3_000L
        /** 0.3.28: the signal-only return after a Lock needs walking evidence this fresh (the hub's packet interval is 5-9 s). */
        const val DEEP_RETURN_WALK_EVIDENCE_MS = 10_000L
        /** 0.3.28: two count advances this close are a walk now; one batch after a longer silence is an old walk. */
        const val DEEP_RETURN_STEP_CADENCE_MS = 15_000L
        const val STILL_APPROACH_MARGIN_DB = 4
        const val STILL_APPROACH_RISE_DB = 10
        const val STILL_APPROACH_CONFIRM_MS = 3_000L
        const val STILL_APPROACH_STEP_STALL_WINDOW_MS = 10_000L
        const val MANUAL_WEAK_RETURN_RISE_DB = 8
        const val MANUAL_WEAK_RETURN_CONFIRM_MS = 400L
    }
}
