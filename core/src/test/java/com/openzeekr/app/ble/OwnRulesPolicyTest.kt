package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.0b: the rise hold (round 4) and the walk credit (round 4c) of [ProximityDecisionPolicy] with the own rules on,
 * and their absence with the rules off (the live policy of the shadow mode). Unlock level -86; a reading just above
 * it (-85..-83) is "weak", -82 and up is "strong". The 0.4.0c doubt reference and GNSS gate are off here (a 13 dB jump
 * from the far level without own steps is a doubt there): see [GnssGatePolicyTest].
 */
class OwnRulesPolicyTest {
    private val unlock = -86

    /** A fresh policy that has qualified "far" (2.5 s at -98) and now walks in; returns the time after the far phase. */
    private fun farThen(policy: ProximityDecisionPolicy, own: Long?): Long {
        var t = 0L
        while (t <= 3_000L) { assertFalse(policy.shouldUnlock(t, -98, true, unlock, ownTotal = own, ownActive = own != null)); t += 200L }
        return t
    }

    /** Feeds [rssi] until the approach route qualifies or [maxMs] passes; returns the time of the first true, or null. */
    private fun feedUntilQualified(policy: ProximityDecisionPolicy, from: Long, rssi: Int, maxMs: Long, own: Long? = null,
                                   moving: Boolean = true): Long? {
        var t = from
        while (t <= from + maxMs) {
            if (policy.shouldUnlock(t, rssi, moving, unlock, ownTotal = own, ownActive = own != null, stepsRising = moving)) return t
            t += 200L
        }
        return null
    }

    @Test fun withoutTheOwnRulesAWeakApproachQualifiesAtOnce() {
        val policy = ProximityDecisionPolicy(ownRules = false)
        val t0 = farThen(policy, null)
        assertNotNull("the plain approach route opens at -85", feedUntilQualified(policy, t0, -85, 4_000L))
        assertEquals("approach", policy.unlockRoute)
        assertNull(policy.consumeRiseHoldEvent())
    }

    @Test fun theRiseHoldHoldsAWeakQualificationAndConfirmsOnTheRiseWhileWalking() {
        val policy = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        val t0 = farThen(policy, 100L)
        assertNull("held: no unlock at -85 within 4 s", feedUntilQualified(policy, t0, -85, 4_000L, own = 100L))
        val event = policy.consumeRiseHoldEvent()
        assertTrue("a hold began: $event", event != null && event.startsWith("held approach at -85"))
        // +1 dB is not enough, +2 dB while walking confirms.
        assertNull(feedUntilQualified(policy, t0 + 4_200L, -84, 1_000L, own = 100L))
        val at = feedUntilQualified(policy, t0 + 5_400L, -83, 1_000L, own = 100L)
        assertNotNull("confirmed on the +2 dB rise", at)
        assertEquals("approach_rise", policy.unlockRoute)
        assertTrue(policy.consumeRiseHoldEvent()?.startsWith("confirmed approach_rise") == true)
    }

    @Test fun theRiseHoldDropsOnAFallAndANewHoldBeginsLater() {
        val policy = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        val t0 = farThen(policy, 100L)
        assertNull(feedUntilQualified(policy, t0, -84, 2_000L, own = 100L))
        assertTrue(policy.consumeRiseHoldEvent()?.startsWith("held approach at -84") == true)
        // a 3 dB fall below the held level (still above the far level) drops the hold: the fade peak of a far unlock
        assertFalse(policy.shouldUnlock(t0 + 2_400L, -87, true, unlock, ownTotal = 100L, ownActive = true, stepsRising = true))
        assertTrue(policy.consumeRiseHoldEvent()?.startsWith("dropped (fell to -87") == true)
        // standing still, the rise does not confirm; walking again at the same level starts a fresh hold
        assertNull(feedUntilQualified(policy, t0 + 2_600L, -85, 1_000L, own = 100L, moving = false))
        assertNull(feedUntilQualified(policy, t0 + 3_800L, -85, 1_000L, own = 100L))
    }

    @Test fun aStrongQualificationIsNeverHeld() {
        val policy = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        val t0 = farThen(policy, 100L)
        assertNotNull("-82 is strong: no hold", feedUntilQualified(policy, t0, -82, 4_000L, own = 100L))
        assertEquals("approach", policy.unlockRoute)
    }

    @Test fun theWalkCreditSkipsTheHoldAfterTwelveOwnStepsSinceTheFarReading() {
        val policy = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        // far: -98 (<= unlock - 8) with the own total 100 at the last far reading
        val t0 = farThen(policy, 100L)
        // the owner walked 12 own steps since: the weak qualification opens at once
        val at = feedUntilQualified(policy, t0, -85, 4_000L, own = 112L)
        assertNotNull("credited: opens without the hold", at)
        assertEquals("approach", policy.unlockRoute)
        assertTrue(policy.consumeRiseHoldEvent()?.startsWith("credit approach at -85 dBm: 12 own steps since far") == true)
    }

    @Test fun elevenOwnStepsSinceTheFarReadingAreNotEnoughForTheCredit() {
        val policy = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        val t0 = farThen(policy, 100L)
        assertNull("11 steps: held", feedUntilQualified(policy, t0, -85, 4_000L, own = 111L))
        assertTrue(policy.consumeRiseHoldEvent()?.startsWith("held approach") == true)
    }

    @Test fun theFarReferenceExpiresAfterAMinute() {
        val policy = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        val t0 = farThen(policy, 100L)
        // 70 s in the band below the unlock level but above the far level: no new far reading
        var t = t0
        while (t < t0 + 70_000L) { assertFalse(policy.shouldUnlock(t, -90, true, unlock, ownTotal = 100L, ownActive = true)); t += 1_000L }
        assertNull("the far reading is older than 60 s: no credit, held", feedUntilQualified(policy, t, -85, 4_000L, own = 130L))
        assertTrue(policy.consumeRiseHoldEvent()?.startsWith("held approach") == true)
    }

    @Test fun withoutAnOwnCounterThereIsNoCreditButTheHoldStillWorks() {
        val policy = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        val t0 = farThen(policy, null)
        assertNull(feedUntilQualified(policy, t0, -85, 4_000L, own = null))
        assertTrue(policy.consumeRiseHoldEvent()?.startsWith("held approach") == true)
    }
}
