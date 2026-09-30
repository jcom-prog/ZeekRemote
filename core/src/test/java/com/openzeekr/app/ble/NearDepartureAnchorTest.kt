package com.openzeekr.app.ble

import org.junit.Assert.*
import org.junit.Test

class NearDepartureAnchorTest {
    private fun fix(time: Long, accuracy: Float = 3f) = DepartureFix(51.0, 5.0, accuracy, time)

    @Test fun poorEarlyLocationCanRecoverAfterSevenSecondsWhileStillNear() {
        val policy = NearDepartureAnchor(10_000L)
        policy.observe(-65, 10_000L)
        policy.observe(-64, 11_000L)
        assertEquals("accuracy_over_eight_meters", policy.rejection(fix(11_000L, 20f), 11_000L))
        // A later, independent near interval allows recovery without treating unlock time as a deadline.
        policy.observe(-63, 39_000L)
        policy.observe(-64, 40_000L)
        assertNull(policy.rejection(fix(40_000L), 40_000L))
    }

    @Test fun locationCompletionAfterWalkingAwayCannotEstablishReference() {
        val policy = NearDepartureAnchor(10_000L)
        policy.observe(-65, 10_000L)
        policy.observe(-65, 11_000L)
        policy.observe(-94, 11_100L)
        assertEquals("near_unverified", policy.rejection(fix(11_200L), 11_200L))
    }

    @Test fun linkLossAndStaleCachedFixCannotEstablishReference() {
        val policy = NearDepartureAnchor(10_000L)
        policy.observe(-65, 10_000L)
        policy.observe(-65, 11_000L)
        assertEquals("near_stale", policy.rejection(fix(14_000L), 14_000L))
        policy.observe(-65, 20_000L)
        policy.observe(-65, 21_000L)
        assertEquals("near_fix_stale", policy.rejection(fix(18_000L), 21_000L))
    }

    @Test fun oneStrongSpikeDoesNotEstablishReferenceAndSamplingGapsRestartHold() {
        val policy = NearDepartureAnchor(10_000L)
        policy.observe(-65, 10_000L)
        assertEquals("near_unverified", policy.rejection(fix(10_100L), 10_100L))
        policy.observe(-65, 14_000L)
        assertEquals("near_unverified", policy.rejection(fix(14_000L), 14_000L))
    }

    @Test fun providerWindowEndsEvenIfPhoneRemainsNear() {
        val policy = NearDepartureAnchor(10_000L)
        policy.observe(-65, 130_000L)
        policy.observe(-65, 131_000L)
        assertEquals("near_window_expired", policy.rejection(fix(131_000L), 131_000L))
    }

    @Test fun reversedSampleClockCannotCarryOldNearHoldForward() {
        val policy = NearDepartureAnchor(10_000L)
        policy.observe(-65, 12_000L)
        policy.observe(-65, 11_000L)
        assertEquals("near_unverified", policy.rejection(fix(12_000L), 12_000L))
    }
}
