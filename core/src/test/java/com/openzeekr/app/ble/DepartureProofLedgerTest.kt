package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle contract for holding a BLE departure proof while a Lock is executed. */
class DepartureProofLedgerTest {
    private val lock = -82
    private fun proven(at: Long = 10_000L) = DepartureProofLedger(lock).apply {
        onSample(at, -90, -90); onConfirmed(at)
    }

    @Test fun proofSurvivesStoppingAndWeakSamplesAfterDeparture() {
        val l = proven()
        for (t in 10_200L..20_000L step 200L) l.onSample(t, -93, -92)
        assertTrue(l.authorizesBleLock(20_000L))
    }

    @Test fun strongSampleRevokesAndIsNeverRestoredByWeakOnes() {
        val l = proven()
        l.onSample(10_200, -70, -84)
        l.onSample(10_400, -95, -95)
        assertFalse(l.authorizesBleLock(10_400))
        assertEquals("strong_near", l.revokedReason)
    }

    @Test fun smoothedRecoveryAboveLockThresholdRevokes() {
        val l = proven()
        l.onSample(10_200, -76, -80)
        assertFalse(l.authorizesBleLock(10_200))
        assertEquals("signal_recovered", l.revokedReason)
    }

    @Test fun nonAdvancingClockRevokes() {
        val l = proven()
        l.onSample(10_000, -90, -90)
        assertFalse(l.authorizesBleLock(10_000))
        assertEquals("time_not_advancing", l.revokedReason)
    }

    @Test fun proofExpires() {
        val l = proven()
        assertTrue(l.authorizesBleLock(10_000L + DepartureProofLedger.MAX_PROOF_AGE_MS))
        assertFalse(l.authorizesBleLock(10_001L + DepartureProofLedger.MAX_PROOF_AGE_MS))
        assertFalse("clock before proof", l.authorizesBleLock(9_999L))
    }

    @Test fun cloudLockNeedsLinkLossAfterTheProof() {
        val l = proven()
        assertFalse(l.authorizesCloudLock(11_000))
        l.onLinkLost(12_000)
        assertTrue(l.authorizesCloudLock(12_500))
    }

    @Test fun linkLossBeforeProofDoesNotCount() {
        val l = DepartureProofLedger(lock)
        l.onLinkLost(5_000)
        l.onSample(10_000, -90, -90); l.onConfirmed(10_000)
        assertFalse(l.authorizesCloudLock(10_500))
    }

    @Test fun newConfirmationAfterRevocationIsANewProofWithoutOldLinkLoss() {
        val l = proven()
        l.onLinkLost(11_000)
        l.onSample(12_000, -65, -70) // back at the car after a reconnect
        assertFalse(l.authorizesCloudLock(12_000))
        l.onSample(20_000, -91, -90); l.onConfirmed(20_000)
        assertTrue(l.authorizesBleLock(20_000))
        assertFalse("old link loss must not carry over", l.authorizesCloudLock(20_000))
    }

    @Test fun expiredProofDoesNotBlockALaterConfirmation() {
        val l = proven(at = 10_000L)
        val late = 10_000L + DepartureProofLedger.MAX_PROOF_AGE_MS + 5_000L
        assertFalse(l.authorizesBleLock(late))
        l.onSample(late, -90, -90)
        l.onConfirmed(late)
        assertTrue("a fresh confirmation must own a new proof", l.authorizesBleLock(late))
    }

    @Test fun strongerRevocationBoundaryIsRespected() {
        // The controller passes -78 (diagnostic strong-near) so a -77 reading revokes too.
        val l = DepartureProofLedger(lock, strongNearRssi = -78)
        l.onSample(10_000, -90, -90); l.onConfirmed(10_000)
        l.onSample(10_200, -77, -84)
        assertFalse(l.authorizesBleLock(10_200))
        assertEquals("strong_near", l.revokedReason)
    }

    @Test fun noProofAuthorizesNothing() {
        val l = DepartureProofLedger(lock)
        l.onLinkLost(1_000)
        assertFalse(l.authorizesBleLock(1_000))
        assertFalse(l.authorizesCloudLock(1_000))
        assertFalse(l.hasProof)
    }
}
