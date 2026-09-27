package com.openzeekr.app.ble

/**
 * Pure, time-based proximity decision policy.
 *
 * RSSI is noisy enough that a single threshold crossing must never actuate the car. This class keeps
 * the evidence needed to distinguish an actual journey from body-shadow/multipath bounce. It has no
 * Android or BLE dependencies, so captured field traces can be replayed in local unit tests.
 */
internal class ProximityDecisionPolicy {
    enum class ArmedDecision { NONE, ARRIVAL_CONFIRMED, LOCK }

    private var sawStrongNearWhileLocked = false
    private var departureObserved = false
    private var farSinceMs = UNSET_MS
    private var farQualified = false
    private var nearCandidateSinceMs = UNSET_MS
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
    private var manualStillRssi = 0
    private var manualNearPeakRssi = Int.MIN_VALUE
    private var manualWeakStillSinceMs = UNSET_MS
    private var manualWeakestRssi = 0
    private var manualWeakReturnMoving = false
    private var manualWeakNearSinceMs = UNSET_MS
    private var pendingUnlockFarSinceMs = UNSET_MS

    fun resetLocked() {
        sawStrongNearWhileLocked = false
        departureObserved = false
        farSinceMs = UNSET_MS
        farQualified = false
        nearCandidateSinceMs = UNSET_MS
        unlockQualifiedAtMs = UNSET_MS
        clearPresenceApproach()
        unlockConfirmedAtMs = UNSET_MS
        arrivalStrongSinceMs = UNSET_MS
        arrivalConfirmed = false
        resetAbortedArrivalCandidate()
        resetWalkAwayCandidate()
        departureLatched = false
        departureLinkEnded = false
        manualDeparture = false
        resetSameLinkReturn()
        pendingUnlockFarSinceMs = UNSET_MS
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
    fun shouldUnlock(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int): Boolean {
        if (departureLatched && !tryRearmSameLinkReturn(nowMs, rssi, moving, unlockThreshold)) {
            return false
        }
        expirePresenceApproach(nowMs)
        if (presenceApproachAtMs != UNSET_MS) {
            presencePeakRssi = maxOf(presencePeakRssi, rssi)
            val improved = presencePeakRssi >= presenceEntryRssi + PRESENCE_RISE_DB
            val crossedApproachBand = rssi >= unlockThreshold + PRESENCE_READY_MARGIN_DB
            if (improved && crossedApproachBand) {
                if (presenceNearSinceMs == UNSET_MS) presenceNearSinceMs = nowMs
                if (nowMs - presenceNearSinceMs >= PRESENCE_CONFIRM_MS &&
                    unlockQualifiedAtMs == UNSET_MS) {
                    unlockQualifiedAtMs = nowMs
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
            nearCandidateSinceMs = UNSET_MS
            unlockQualifiedAtMs = UNSET_MS
            return false
        }

        farSinceMs = UNSET_MS
        // Once we were already strongly at the car and subsequently went FAR, a later multipath
        // rebound is departure noise, not a second approach. A genuine new approach starts with a
        // fresh policy/session and therefore has no [departureObserved] latch.
        val qualifiedApproach = farQualified && !departureObserved && moving &&
            rssi >= unlockThreshold + UNLOCK_MARGIN_DB
        val freshDoorSession = !farQualified && !departureObserved && rssi >= STRONG_NEAR_RSSI
        if (qualifiedApproach || freshDoorSession) {
            if (nearCandidateSinceMs == UNSET_MS) nearCandidateSinceMs = nowMs
            val holdMs = if (qualifiedApproach) APPROACH_CONFIRM_MS else DOOR_CONFIRM_MS
            if (nowMs - nearCandidateSinceMs >= holdMs && unlockQualifiedAtMs == UNSET_MS) {
                unlockQualifiedAtMs = nowMs
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
        return unlockQualifiedAtMs != UNSET_MS
    }

    fun onUnlockConfirmed(nowMs: Long) {
        pendingUnlockFarSinceMs = UNSET_MS
        clearPresenceApproach()
        unlockConfirmedAtMs = nowMs
        arrivalStrongSinceMs = UNSET_MS
        arrivalConfirmed = false
        resetAbortedArrivalCandidate()
        resetWalkAwayCandidate()
    }

    /** Suppress every unlock decision from the moment walk-away locking starts. */
    fun onDepartureLockStarted() {
        pendingUnlockFarSinceMs = UNSET_MS
        departureLatched = true
        departureLinkEnded = false
        resetSameLinkReturn()
        sawStrongNearWhileLocked = false
        departureObserved = true
        farSinceMs = UNSET_MS
        farQualified = false
        nearCandidateSinceMs = UNSET_MS
        unlockQualifiedAtMs = UNSET_MS
        clearPresenceApproach()
        resetWalkAwayCandidate()
    }

    /** Confirmed manual lock shares the departure latch. */
    fun onManualLockConfirmed() {
        onDepartureLockStarted()
        manualDeparture = true
    }

    /** A later arrival may rearm only through [onPresenceMatch], never from connected RSSI alone. */
    fun onLinkEnded() {
        if (departureLatched) {
            departureLinkEnded = true
            manualDeparture = false
            resetSameLinkReturn()
        } else if (!departureObserved) {
            // A failed setup can observe FAR long enough to set farQualified, then disconnect
            // before the phone reaches the car. On a later fresh connection at the door the
            // motion sensor may already say STILL. FAR proof from the dead link must not disable
            // the guarded fresh-door fallback; preserve the separate departure protection.
            farSinceMs = UNSET_MS
            farQualified = false
            nearCandidateSinceMs = UNSET_MS
            unlockQualifiedAtMs = UNSET_MS
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

        if (manualDeparture) {
            manualNearPeakRssi = maxOf(manualNearPeakRssi, rssi)
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
            if (!moving) {
                manualReturnMoving = false
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
                } else {
                    manualStillSinceMs = UNSET_MS
                    manualStillReady = false
                    manualStillRssi = 0
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
                manualReturnMoving = true
                manualStillRssi = minOf(manualStillRssi, rssi)
            }
            if (nowMs - departureSameLinkFarQualifiedAtMs < SAME_LINK_RETURN_GUARD_MS ||
                rssi < unlockThreshold + UNLOCK_MARGIN_DB ||
                rssi < manualStillRssi + MANUAL_RETURN_RISE_DB) return false
        } else {
            val farBoundary = unlockThreshold - FAR_MARGIN_DB
            if (departureSameLinkFarQualifiedAtMs == UNSET_MS) {
                if (!moving || rssi > farBoundary) {
                    departureSameLinkFarSinceMs = UNSET_MS
                    return false
                }
                if (departureSameLinkFarSinceMs == UNSET_MS) departureSameLinkFarSinceMs = nowMs
                if (nowMs - departureSameLinkFarSinceMs < SAME_LINK_FAR_CONFIRM_MS) return false
                departureSameLinkFarQualifiedAtMs = nowMs
                return false
            }

            val separatedLongEnough =
                nowMs - departureSameLinkFarQualifiedAtMs >= SAME_LINK_RETURN_GUARD_MS
            val crossedArrivalEdge = rssi >= unlockThreshold + UNLOCK_MARGIN_DB
            if (!moving || !separatedLongEnough || !crossedArrivalEdge) return false
        }

        finishSameLinkReturn()
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
        nearCandidateSinceMs = UNSET_MS
        unlockQualifiedAtMs = UNSET_MS
    }

    private fun resetSameLinkReturn() {
        departureSameLinkFarSinceMs = UNSET_MS
        departureSameLinkFarQualifiedAtMs = UNSET_MS
        manualStillSinceMs = UNSET_MS
        manualStillReady = false
        manualReturnMoving = false
        manualStillRssi = 0
        manualNearPeakRssi = Int.MIN_VALUE
        manualWeakStillSinceMs = UNSET_MS
        manualWeakestRssi = 0
        manualWeakReturnMoving = false
        manualWeakNearSinceMs = UNSET_MS
    }

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

    private companion object {
        const val UNSET_MS = -1L
        const val STRONG_NEAR_RSSI = -72
        const val ARRIVAL_STRONG_RSSI = -72
        const val FAR_MARGIN_DB = 2
        const val UNLOCK_MARGIN_DB = 1
        const val FAR_BASELINE_MS = 2_500L
        const val APPROACH_CONFIRM_MS = 400L
        const val DOOR_CONFIRM_MS = 1_200L
        const val UNLOCK_EVIDENCE_TTL_MS = 5_000L
        // The offloaded presence hit + real motion + a rising signal already prove an approach.
        // Requiring another +4 dB after the DK handshake delayed the field-proven 0.1.33 cold start
        // by ~1.35 s. Keep the two-sample confirmation, but allow readiness at the configured Far
        // threshold; commands still cannot run before SESSION_READY.
        const val PRESENCE_READY_MARGIN_DB = 0
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
        const val WALK_AWAY_CONFIRM_MS = 2_000L
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
        const val MANUAL_WEAK_RETURN_RISE_DB = 8
        const val MANUAL_WEAK_RETURN_CONFIRM_MS = 400L
    }
}
