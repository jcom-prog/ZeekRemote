package com.openzeekr.app.ble

/**
 * Holds one BLE departure proof for one unlock epoch while the Lock command is executed.
 *
 * A proof from [BleDepartureEvidence] must survive what normally follows a real departure
 * (the walker stops, the link drops out of range, a BLE retry reconnects) but must be revoked by
 * anything that suggests the phone is back near the car. A revoked proof is never restored;
 * only a new confirmation from [BleDepartureEvidence] (which itself restarts) creates a new one.
 *
 * Rules, all on monotonic time:
 *  - a fresh raw sample >= strong-near, or a smoothed sample above the lock threshold, revokes;
 *  - a non-advancing clock revokes (replayed or reordered data cannot keep a proof alive);
 *  - a proof older than [maxProofAgeMs] no longer authorizes anything;
 *  - a BLE Lock may use a held proof while the link is up;
 *  - a cloud Lock additionally requires that the link went down *after* the proof (the phone
 *    left radio range), so a car-side BLE failure beside the car cannot be bypassed via cloud.
 */
internal class DepartureProofLedger(
    private val lockThreshold: Int,
    private val strongNearRssi: Int = BleDepartureEvidence.STRONG_NEAR_RSSI,
    private val maxProofAgeMs: Long = MAX_PROOF_AGE_MS,
) {
    private var proofAtMs = UNSET
    private var lastSampleAtMs = UNSET
    private var linkLostAfterProofAtMs = UNSET

    var revokedReason: String? = null
        private set

    /** A new confirmation (after any revocation) starts a new proof; it never extends an old one. */
    fun onConfirmed(nowMs: Long) {
        if (proofAtMs == UNSET && (lastSampleAtMs == UNSET || nowMs >= lastSampleAtMs)) {
            proofAtMs = nowMs
            linkLostAfterProofAtMs = UNSET
        }
    }

    fun onSample(nowMs: Long, rawRssi: Int, smoothedRssi: Int) {
        if (lastSampleAtMs != UNSET && nowMs <= lastSampleAtMs) {
            revoke("time_not_advancing"); return
        }
        lastSampleAtMs = nowMs
        if (proofAtMs == UNSET || nowMs < proofAtMs) return
        when {
            rawRssi >= strongNearRssi -> revoke("strong_near")
            smoothedRssi > lockThreshold -> revoke("signal_recovered")
        }
    }

    fun onLinkLost(nowMs: Long) {
        if (proofAtMs != UNSET && nowMs >= proofAtMs && linkLostAfterProofAtMs == UNSET)
            linkLostAfterProofAtMs = nowMs
    }

    fun authorizesBleLock(nowMs: Long): Boolean = held(nowMs)

    fun authorizesCloudLock(nowMs: Long): Boolean =
        held(nowMs) && linkLostAfterProofAtMs != UNSET

    val hasProof: Boolean get() = proofAtMs != UNSET

    private fun held(nowMs: Long): Boolean =
        proofAtMs != UNSET && nowMs >= proofAtMs && nowMs - proofAtMs <= maxProofAgeMs

    private fun revoke(reason: String) {
        if (proofAtMs == UNSET) return
        revokedReason = reason
        proofAtMs = UNSET
        linkLostAfterProofAtMs = UNSET
    }

    companion object {
        private const val UNSET = -1L
        const val MAX_PROOF_AGE_MS = 60_000L
    }
}
