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

    private var unlockConfirmedAtMs = UNSET_MS
    private var arrivalStrongSinceMs = UNSET_MS
    private var arrivalConfirmed = false
    private var walkAwayFarSinceMs = UNSET_MS
    private var walkAwayFarStartRssi = 0

    fun resetLocked() {
        sawStrongNearWhileLocked = false
        departureObserved = false
        farSinceMs = UNSET_MS
        farQualified = false
        nearCandidateSinceMs = UNSET_MS
        unlockQualifiedAtMs = UNSET_MS
        unlockConfirmedAtMs = UNSET_MS
        arrivalStrongSinceMs = UNSET_MS
        arrivalConfirmed = false
        resetWalkAwayCandidate()
    }

    /** Returns true only for a qualified approach or a fresh, stable session already at the door. */
    fun shouldUnlock(nowMs: Long, rssi: Int, moving: Boolean, unlockThreshold: Int): Boolean {
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
        val qualifiedApproach = farQualified && moving && rssi >= unlockThreshold + UNLOCK_MARGIN_DB
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

        if (!moving || rssi > lockThreshold) {
            resetWalkAwayCandidate()
            return ArmedDecision.NONE
        }

        if (walkAwayFarSinceMs == UNSET_MS) {
            walkAwayFarSinceMs = nowMs
            walkAwayFarStartRssi = rssi
            return ArmedDecision.NONE
        }

        val sustained = nowMs - walkAwayFarSinceMs >= WALK_AWAY_CONFIRM_MS
        val materiallyReceding = rssi <= walkAwayFarStartRssi - WALK_AWAY_DROP_DB
        return if (sustained && materiallyReceding) ArmedDecision.LOCK else ArmedDecision.NONE
    }

    private fun resetWalkAwayCandidate() {
        walkAwayFarSinceMs = UNSET_MS
        walkAwayFarStartRssi = 0
    }

    private companion object {
        const val UNSET_MS = -1L
        const val STRONG_NEAR_RSSI = -72
        const val ARRIVAL_STRONG_RSSI = -72
        const val FAR_MARGIN_DB = 2
        const val UNLOCK_MARGIN_DB = 3
        const val FAR_BASELINE_MS = 2_500L
        const val APPROACH_CONFIRM_MS = 800L
        const val DOOR_CONFIRM_MS = 1_200L
        const val UNLOCK_EVIDENCE_TTL_MS = 5_000L
        const val ARRIVAL_CONFIRM_MS = 1_500L
        const val PRE_ARRIVAL_GRACE_MS = 15_000L
        const val WALK_AWAY_CONFIRM_MS = 2_000L
        const val WALK_AWAY_DROP_DB = 3
    }
}
