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
    private var walkAwayFarSinceMs = UNSET_MS
    private var walkAwayFarStartRssi = 0
    private var walkAwayWeakestRssi = 0

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
        resetWalkAwayCandidate()
    }

    /** Records hardware-offloaded arrival evidence before connect/handshake timing can erase it. */
    fun onPresenceMatch(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int) {
        expirePresenceApproach(nowMs)
        if (presenceApproachAtMs != UNSET_MS) {
            presencePeakRssi = maxOf(presencePeakRssi, rssi)
            return
        }
        // A first hit already strong at the door is ambiguous: it can be the start of a departure.
        // Only a moving, edge-range hit starts an arrival epoch. 0.1.24 began at -58 and is rejected;
        // both real 0.1.28 arrivals began at -83/-84 and are retained across the handshake/status 133.
        if (moving && rssi <= unlockThreshold + PRESENCE_ENTRY_MARGIN_DB) {
            presenceApproachAtMs = nowMs
            presenceEntryRssi = rssi
            presencePeakRssi = rssi
            presenceNearSinceMs = UNSET_MS
        }
    }

    /** Returns true only for a qualified approach or a fresh, stable session already at the door. */
    fun shouldUnlock(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int): Boolean {
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
        clearPresenceApproach()
        unlockConfirmedAtMs = nowMs
        arrivalStrongSinceMs = UNSET_MS
        arrivalConfirmed = false
        resetWalkAwayCandidate()
    }

    /**
     * Confirms arrival first, then requires sustained weak-and-receding evidence while moving.
     * A STILL sample or an RSSI recovery cancels the candidate immediately.
     */
    fun onUnlockedSample(nowMs: Long, rssi: Int, moving: Boolean, lockThreshold: Int): ArmedDecision {
        if (!arrivalConfirmed) {
            if (rssi >= ARRIVAL_STRONG_RSSI) {
                if (arrivalStrongSinceMs == UNSET_MS) arrivalStrongSinceMs = nowMs
                if (nowMs - arrivalStrongSinceMs >= ARRIVAL_CONFIRM_MS) {
                    arrivalConfirmed = true
                    resetWalkAwayCandidate()
                    return ArmedDecision.ARRIVAL_CONFIRMED
                }
            } else {
                arrivalStrongSinceMs = UNSET_MS
            }

            // If the user really reversed before reaching the car, still allow a safe lock, but never
            // during the immediate post-unlock bounce seen in the 0.1.20 field trace.
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
        const val PRESENCE_ENTRY_MARGIN_DB = 3
        const val PRESENCE_READY_MARGIN_DB = 4
        const val PRESENCE_RISE_DB = 1
        // Two consecutive fast-cadence observations. Requiring 400 ms made the result depend on
        // whether one noisy 200 ms sample landed just before SESSION_READY (the 0.1.28 failure).
        const val PRESENCE_CONFIRM_MS = 200L
        const val PRESENCE_TTL_MS = 35_000L
        const val ARRIVAL_CONFIRM_MS = 1_500L
        const val PRE_ARRIVAL_GRACE_MS = 15_000L
        const val WALK_AWAY_CONFIRM_MS = 2_000L
        const val WALK_AWAY_DROP_DB = 3
        const val WALK_AWAY_RECOVERY_DB = 3
    }
}
