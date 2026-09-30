package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelockApproachEvidenceTest {
    @Test fun delayedRelockConfirmationRetainsArrivalAfterPhoneStopsMoving() {
        val evidence = RelockApproachEvidence()
        val policy = ProximityDecisionPolicy()
        for (time in 1_000L..3_000L step 200L) evidence.observe(time, -66, true)
        for (time in 3_200L..4_200L step 200L) evidence.observe(time, -71, false)
        policy.resetLocked()
        assertFalse(policy.shouldUnlock(4_200L, -71, false, -86))
        evidence.restoreAfterVerifiedRelock(policy, 4_200L, -86)
        assertTrue(policy.shouldUnlock(4_200L, -71, false, -86))
    }

    @Test fun oldOrStationaryObservationsCannotAuthorizeArrival() {
        val evidence = RelockApproachEvidence()
        val policy = ProximityDecisionPolicy()
        for (time in 1_000L..3_000L step 200L) evidence.observe(time, -66, true)
        evidence.restoreAfterVerifiedRelock(policy, 9_000L, -86)
        assertFalse(policy.shouldUnlock(9_000L, -71, false, -86))
        for (time in 10_000L..12_000L step 200L) evidence.observe(time, -66, false)
        evidence.restoreAfterVerifiedRelock(policy, 12_000L, -86)
        assertFalse(policy.shouldUnlock(12_000L, -71, false, -86))
    }

    @Test fun leavingAgainInvalidatesThePreservedArrival() {
        val evidence = RelockApproachEvidence()
        val policy = ProximityDecisionPolicy()
        for (time in 1_000L..3_000L step 200L) evidence.observe(time, -66, true)
        evidence.observe(3_200L, -98, true)
        evidence.restoreAfterVerifiedRelock(policy, 3_200L, -86)
        assertFalse(policy.shouldUnlock(3_200L, -98, true, -86))
        assertFalse(policy.shouldUnlock(3_400L, -70, true, -86))
    }
}
