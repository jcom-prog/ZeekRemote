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
    private var strongNearSamples = 0
    private var unlockQualifiedAtMs = UNSET_MS
    private var wasMoving = false
    private var movingStartRssi = 0
    private var movingBestRssi = 0

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
        strongNearSamples = 0
        unlockQualifiedAtMs = UNSET_MS
        wasMoving = false
        movingStartRssi = 0
        movingBestRssi = 0
        unlockConfirmedAtMs = UNSET_MS
        arrivalStrongSinceMs = UNSET_MS
        arrivalConfirmed = false
        resetWalkAwayCandidate()
    }

    /** Returns true only for a qualified approach or a fresh, stable session already at the door. */
    fun shouldUnlock(
        nowMs: Long,
        rssi: Int,
        moving: Boolean,
        unlockThreshold: Int,
        freshSessionHandshake: Boolean = false,
    ): Boolean {
        if (rssi >= STRONG_NEAR_RSSI) sawStrongNearWhileLocked = true

        // Motion alone has no direction. Anchor every new moving period and require a material RSSI
        // gain before treating it as an approach. This prevents picking up the phone at the car and
        // walking away from being misclassified as a new arrival.
        if (moving) {
            if (!wasMoving) {
                movingStartRssi = rssi
                movingBestRssi = rssi
            } else if (rssi > movingBestRssi) {
                movingBestRssi = rssi
            }
        } else {
            movingStartRssi = 0
            movingBestRssi = 0
        }
        wasMoving = moving

        if (rssi <= unlockThreshold - FAR_MARGIN_DB) {
            if (sawStrongNearWhileLocked) departureObserved = true
            if (farSinceMs == UNSET_MS) farSinceMs = nowMs
            if (nowMs - farSinceMs >= FAR_BASELINE_MS) farQualified = true
            nearCandidateSinceMs = UNSET_MS
            strongNearSamples = 0
            unlockQualifiedAtMs = UNSET_MS
            return false
        }

        farSinceMs = UNSET_MS
        // Once we were already strongly at the car and subsequently went FAR, a later multipath
        // rebound is departure noise, not a second approach. A genuine new approach starts with a
        // fresh policy/session and therefore has no [departureObserved] latch.
        val directionConfirmed = moving && movingBestRssi >= movingStartRssi + APPROACH_GAIN_DB
        val qualifiedApproach = farQualified && !departureObserved && directionConfirmed &&
            rssi >= unlockThreshold + UNLOCK_MARGIN_DB
        val freshDoorSession = freshSessionHandshake && !departureObserved && rssi >= STRONG_NEAR_RSSI
        if (qualifiedApproach || freshDoorSession) {
            if (nearCandidateSinceMs == UNSET_MS) nearCandidateSinceMs = nowMs
            if (freshDoorSession) strongNearSamples++
            val qualified = if (qualifiedApproach) {
                nowMs - nearCandidateSinceMs >= APPROACH_CONFIRM_MS
            } else {
                // Two independent door-range GATT readings corroborate the offloaded presence hit.
                // Do not demand 1.2 s continuously: body shadow during the handshake is normal.
                strongNearSamples >= DOOR_CONFIRM_SAMPLES
            }
            if (qualified && unlockQualifiedAtMs == UNSET_MS) {
                unlockQualifiedAtMs = nowMs
            }
        } else {
            nearCandidateSinceMs = UNSET_MS
            strongNearSamples = 0
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

    /** Final invariant immediately before the wire command: still at the door, or proven approach. */
    fun canSendUnlock(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int): Boolean {
        if (unlockQualifiedAtMs == UNSET_MS ||
            nowMs - unlockQualifiedAtMs > UNLOCK_EVIDENCE_TTL_MS ||
            rssi <= unlockThreshold - FAR_MARGIN_DB
        ) return false

        // A stationary phone at door range is safe. If it is moving, require the same positive
        // direction evidence as the approach decision; movement away can never pass this check.
        return !moving || movingBestRssi >= movingStartRssi + APPROACH_GAIN_DB
    }

    fun onUnlockConfirmed(nowMs: Long) {
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

    private companion object {
        const val UNSET_MS = -1L
        const val STRONG_NEAR_RSSI = -72
        const val ARRIVAL_STRONG_RSSI = -72
        const val FAR_MARGIN_DB = 2
        const val UNLOCK_MARGIN_DB = 1
        const val APPROACH_GAIN_DB = 3
        const val FAR_BASELINE_MS = 2_500L
        const val APPROACH_CONFIRM_MS = 400L
        const val DOOR_CONFIRM_SAMPLES = 2
        const val UNLOCK_EVIDENCE_TTL_MS = 5_000L
        const val ARRIVAL_CONFIRM_MS = 1_500L
        const val PRE_ARRIVAL_GRACE_MS = 15_000L
        const val WALK_AWAY_CONFIRM_MS = 2_000L
        const val WALK_AWAY_DROP_DB = 3
        const val WALK_AWAY_RECOVERY_DB = 3
    }
}
