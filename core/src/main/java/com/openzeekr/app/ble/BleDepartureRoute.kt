package com.openzeekr.app.ble

/**
 * One unlock epoch of the BLE + walking departure route: [BleDepartureEvidence] builds the proof,
 * [DepartureProofLedger] holds it while the Lock command runs. This class owns the coupling so
 * the rules hold for every feed site in the controller:
 *
 *  - a ledger revocation also restarts the evidence, so a revoked proof cannot be re-issued by
 *    the very next sample (a full clear + deep-far window must be observed again);
 *  - feeds that cannot build evidence (sparse idle reads, confirmWalkAway reads, the reading
 *    right before actuation) can only revoke, never confirm;
 *  - a held proof authorizes a BLE Lock only together with a fresh reading that does not
 *    contradict it ([freshBleLockAuthorized]); a missing reading authorizes nothing.
 *
 * Cloud Lock is never authorized by this route: without a live radio there is no fresh reading
 * that could revoke a proof when the user walks back, so a cloud request keeps requiring GNSS.
 *
 * The route exists only for the lock threshold its parameters were replayed against
 * ([CALIBRATED_LOCK_RSSI]); other sensitivity presets keep the GNSS-only behaviour.
 */
internal class BleDepartureRoute private constructor(lockThreshold: Int) {
    private val evidence = BleDepartureEvidence(lockThreshold)
    private val ledger = DepartureProofLedger(lockThreshold,
        strongNearRssi = maxOf(LEDGER_STRONG_NEAR_RSSI, lockThreshold + 4))

    /** Categorical reason of the latest evidence decision; never contains a position. */
    val evidenceReason: String get() = evidence.reason

    /** Set when the latest feed revoked a held proof; cleared by the next feed. */
    var revokedReason: String? = null
        private set

    /** True when the latest feed created a new proof. */
    var newlyConfirmed: Boolean = false
        private set

    val hasProof: Boolean get() = ledger.hasProof

    /** A held proof within its age; the lock loop pairs it with [freshBleLockAuthorized]. */
    fun authorizesBleLock(nowMs: Long): Boolean = ledger.authorizesBleLock(nowMs)

    /** A fresh GATT reading on the continuous sampling path; true when a BLE Lock is authorized. */
    fun observe(nowMs: Long, rawRssi: Int, smoothedRssi: Int, moving: Boolean, steps: Long?): Boolean {
        newlyConfirmed = false
        revokeIfContradicted(nowMs, rawRssi, smoothedRssi)
        val decision = evidence.observe(nowMs, rawRssi, smoothedRssi, moving, steps)
        if (decision != BleDepartureEvidence.Decision.CONFIRMED) return false
        val hadProof = ledger.hasProof
        ledger.onConfirmed(nowMs)
        newlyConfirmed = !hadProof && ledger.hasProof
        return ledger.authorizesBleLock(nowMs)
    }

    /** A fresh reading that may only revoke (sparse or out-of-band reads). */
    fun revokeOnly(nowMs: Long, rawRssi: Int, smoothedRssi: Int) {
        newlyConfirmed = false
        revokeIfContradicted(nowMs, rawRssi, smoothedRssi)
    }

    /**
     * The reading taken immediately before a BLE Lock attempt. It is judged as raw (no smoothing
     * may hide a return), and the proof must still be held after it.
     */
    fun freshBleLockAuthorized(nowMs: Long, rawRssi: Int): Boolean {
        revokeOnly(nowMs, rawRssi, rawRssi)
        return ledger.authorizesBleLock(nowMs)
    }

    private fun revokeIfContradicted(nowMs: Long, rawRssi: Int, smoothedRssi: Int) {
        revokedReason = null
        val hadProof = ledger.hasProof
        ledger.onSample(nowMs, rawRssi, smoothedRssi)
        if (hadProof && !ledger.hasProof) {
            revokedReason = ledger.revokedReason
            evidence.restart()
        }
    }

    companion object {
        /** The lock threshold (sensitivity preset "far") the private field replay covered. */
        const val CALIBRATED_LOCK_RSSI = -82
        private const val LEDGER_STRONG_NEAR_RSSI = -78

        /** Null when the preset's thresholds were never validated for this route. */
        fun forLockThreshold(lockThreshold: Int): BleDepartureRoute? =
            if (lockThreshold == CALIBRATED_LOCK_RSSI) BleDepartureRoute(lockThreshold) else null
    }
}
