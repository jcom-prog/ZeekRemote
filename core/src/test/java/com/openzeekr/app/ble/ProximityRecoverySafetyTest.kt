package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generic policy boundaries. No practice recordings or extracted sequences. */
class ProximityRecoverySafetyTest {
    @Test fun `sustained separation and link end permit a later visit`() {
        val policy = ProximityDecisionPolicy()
        assertFalse(policy.shouldUnlock(0L, -60, false, -86))
        for (time in 200L..3_200L step 200L) {
            assertFalse(policy.shouldUnlock(time, -98, true, -86))
        }
        policy.onLinkEnded()
        assertFalse(policy.shouldUnlock(4_000L, -62, false, -86))
        assertTrue(policy.shouldUnlock(5_300L, -62, false, -86))
    }

    @Test fun `manual lock remains protected after link end`() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..3_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -98, true, -86))
        }
        policy.onLinkEnded()
        assertFalse(policy.shouldUnlock(4_000L, -62, false, -86))
        assertFalse(policy.shouldUnlock(5_500L, -62, false, -86))
    }

    @Test fun `unconfirmed departure requires a new observation window`() {
        val policy = ProximityDecisionPolicy()
        policy.onUnlockConfirmed(0L)
        for (time in 0L..2_000L step 200L) {
            policy.onUnlockedSample(time, -65, true, -82)
        }
        policy.onUnlockedSample(2_200L, -84, true, -82)
        policy.onUnlockedSample(4_400L, -90, true, -82)
        policy.onWalkAwayVerificationFailed()
        assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
            policy.onUnlockedSample(4_600L, -90, true, -82))
    }

    @Test fun `remaining close never acquires a time based lock even during motion`() {
        val policy = ProximityDecisionPolicy()
        policy.onUnlockConfirmed(0L)
        for (time in 0L..600_000L step 1_000L) {
            val decision = policy.onUnlockedSample(time, -65, true, -82)
            assertTrue(decision != ProximityDecisionPolicy.ArmedDecision.LOCK)
        }
    }

    @Test fun `a moving departure still produces a lock candidate while connected`() {
        val policy = ProximityDecisionPolicy()
        policy.onUnlockConfirmed(0L)
        for (time in 0L..2_000L step 200L) {
            policy.onUnlockedSample(time, -65, true, -82)
        }
        var last = ProximityDecisionPolicy.ArmedDecision.NONE
        for (time in 2_200L..5_800L step 200L) {
            val rssi = -83 - ((time - 2_200L) / 400L).toInt()
            last = policy.onUnlockedSample(time, rssi, true, -82)
        }
        assertEquals(ProximityDecisionPolicy.ArmedDecision.LOCK, last)
    }
}
