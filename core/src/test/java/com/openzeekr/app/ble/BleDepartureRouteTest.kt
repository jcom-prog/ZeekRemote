package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic contract tests for [BleDepartureRoute], the coupling of evidence and proof ledger as
 * the controller uses it. Decision-rule checks only; no field evidence.
 */
class BleDepartureRouteTest {
    private class Walk(val route: BleDepartureRoute) {
        var t = 0L
        var steps = 0L
        var authorizedAt: Long? = null
        fun run(durationMs: Long, raw: Int, smoothed: Int = raw, moving: Boolean = true) {
            val end = t + durationMs
            while (t < end) {
                t += 200
                if (moving && t % 400 == 0L) steps++
                if (route.observe(t, raw, smoothed, moving, steps) && authorizedAt == null) authorizedAt = t
            }
        }
    }

    /** At the car, then a straight walk that stays deep-far: produces a held proof. */
    private fun departed(): Walk {
        val w = Walk(BleDepartureRoute.forLockThreshold(-82)!!)
        w.run(2_000, -62, moving = false)
        w.run(20_000, -92)
        assertNotNull("precondition: departure confirmed", w.authorizedAt)
        assertTrue(w.route.hasProof)
        return w
    }

    // ---- perspective 1: which presets may use the route ----

    @Test fun onlyTheReplayedLockThresholdGetsTheRoute() {
        assertNotNull(BleDepartureRoute.forLockThreshold(BleDepartureRoute.CALIBRATED_LOCK_RSSI))
        // "close" (-74) and "veryclose" (-66) put deep-far / strong bands at levels a shielded
        // phone beside the car can reach; they were never replayed, so they stay GNSS-only.
        assertNull(BleDepartureRoute.forLockThreshold(-74))
        assertNull(BleDepartureRoute.forLockThreshold(-66))
    }

    // ---- perspective 2: revocation must stick (third review F5) ----

    @Test fun revocationRestartsEvidenceSoTheNextSampleCannotReissueTheProof() {
        val w = departed()
        // Back near the car: -76 raw is below the evidence's strong band (-72) but at/above the
        // ledger's revocation band (-78). Before the fix the evidence stayed CONFIRMED and the
        // following deep sample re-issued a proof at once.
        w.route.revokeOnly(w.t + 200, -76, -90)
        assertEquals("strong_near", w.route.revokedReason)
        assertFalse(w.route.hasProof)
        w.t += 200
        w.authorizedAt = null
        w.run(5_000, -92)
        assertNull("a full new clear + deep window is required", w.authorizedAt)
        assertFalse(w.route.hasProof)
    }

    @Test fun recoveredSmoothedSignalRevokesThroughTheObservePathToo() {
        val w = departed()
        w.authorizedAt = null
        w.run(200, -86, smoothed = -80) // smoothed above the lock threshold
        assertFalse(w.route.hasProof)
        w.run(4_000, -92)
        assertNull(w.authorizedAt)
    }

    @Test fun revokeOnlyFeedsNeverConfirm() {
        val route = BleDepartureRoute.forLockThreshold(-82)!!
        var t = 0L
        repeat(10) { t += 200; route.revokeOnly(t, -62, -62) }
        repeat(200) { t += 200; route.revokeOnly(t, -95, -95) }
        assertFalse(route.hasProof)
        assertFalse(route.freshBleLockAuthorized(t + 200, -95))
    }

    // ---- perspective 3: the reading right before actuation (third review F1) ----

    @Test fun freshReadingAtTheCarBlocksTheLock() {
        val w = departed()
        assertFalse(w.route.freshBleLockAuthorized(w.t + 16_000, -70))
        assertFalse("revoked proofs stay revoked", w.route.freshBleLockAuthorized(w.t + 16_200, -95))
    }

    @Test fun freshReadingInTheRecoveryBandBlocksTheLock() {
        val w = departed()
        // -80 raw: weaker than strong-near but above the lock threshold, judged raw (no EMA).
        assertFalse(w.route.freshBleLockAuthorized(w.t + 3_000, -80))
    }

    @Test fun freshFarReadingKeepsTheHeldProofWithinItsAge() {
        val w = departed()
        assertTrue(w.route.freshBleLockAuthorized(w.t + 16_000, -94))
        assertFalse("proof older than 60 s authorizes nothing",
            w.route.freshBleLockAuthorized(w.t + DepartureProofLedger.MAX_PROOF_AGE_MS + 1_000, -94))
    }

    @Test fun nonAdvancingClockRevokes() {
        val w = departed()
        assertFalse(w.route.freshBleLockAuthorized(w.t, -94))
        assertEquals("time_not_advancing", w.route.revokedReason)
    }
}
