package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.0c (study round 4f): the doubt reference and the GNSS gate of [ProximityDecisionPolicy] with the own rules on.
 * Unlock level -86: -82 and up is "strong" (no 0.4.0b rise hold), -74 and up is the at-car level that escapes a doubt,
 * -94 and below is far. Distances are metres from the car's last position; the gate's far line is 15 m.
 */
class GnssGatePolicyTest {
    private val unlock = -86

    private fun policy() = ProximityDecisionPolicy(ownRules = true)

    private fun feed(policy: ProximityDecisionPolicy, t: Long, rssi: Int, own: Long? = 100L, moving: Boolean = true,
                     walkAgeMs: Long? = null, gnssM: Double? = null, gnssAccM: Double? = null, gnssLatestM: Double? = gnssM,
                     gnssTrendM: Double? = null, gnssFixes: Int = 0): Boolean =
        policy.shouldUnlock(t, rssi, moving, unlock, ownTotal = own, ownActive = own != null, stepsRising = moving,
            walkAgeMs = walkAgeMs, gnssFromCarM = gnssM, gnssAccM = gnssAccM ?: gnssM?.let { 6.0 }, gnssLatestM = gnssLatestM,
            gnssTrendM = gnssTrendM, gnssFixes = gnssFixes)

    /** 3 s far at -98 (the 0.4.0b far reference); returns the time after it. */
    private fun farThen(policy: ProximityDecisionPolicy, own: Long?, from: Long = 0L): Long {
        var t = from
        while (t <= from + 3_000L) { assertFalse(feed(policy, t, -98, own = own)); t += 200L }
        return t
    }

    /** Walks in from the far level without a level jump (-92, -89: still below the band); returns the time after. */
    private fun walkIn(policy: ProximityDecisionPolicy, from: Long, own: Long?): Long {
        var t = from
        for (r in listOf(-92, -92, -89, -89)) { assertFalse(feed(policy, t, r, own = own)); t += 200L }
        return t
    }

    @Test fun aUsableFarFixVetoesAStrongApproachAndTheVetoLiftsWithoutAFix() {
        val p = policy()
        var t = walkIn(p, farThen(p, 100L), 100L)
        var qualified = false
        repeat(5) { if (feed(p, t, -82, gnssM = 25.0, gnssLatestM = 24.0)) qualified = true; t += 200L }
        assertFalse("25 m from the car: vetoed", qualified)
        val hold = p.consumeRiseHoldEvent()
        assertTrue("held by the gate: $hold", hold != null && hold.startsWith("held approach at -82 dBm") && hold.endsWith("(gate)"))
        assertTrue(p.consumeGnssEvent()?.startsWith("veto approach at -82 dBm: 25 m acc 6 from the car") == true)
        // no usable fix any more: the held route confirms on the next walking reading
        assertTrue(feed(p, t, -82))
        assertEquals("approach_rise", p.unlockRoute)
    }

    @Test fun theVetoNeedsTheMedianAndTheLatestFixFar() {
        val p = policy()
        var t = walkIn(p, farThen(p, 100L), 100L)
        var at: Long? = null
        repeat(5) { if (at == null && feed(p, t, -82, gnssM = 25.0, gnssLatestM = 12.0)) at = t; t += 200L }
        assertNotNull("the latest fix is near: no veto", at)
        assertEquals("approach", p.unlockRoute)
        assertNull(p.consumeGnssEvent())
        // a near fix never holds
        val q = policy()
        t = walkIn(q, farThen(q, 100L), 100L)
        at = null
        repeat(5) { if (at == null && feed(q, t, -82, gnssM = 8.0)) at = t; t += 200L }
        assertNotNull(at)
        assertNull(q.consumeGnssEvent())
    }

    @Test fun aFreshLinkInTheBandWithAYoungWalkIsADoubtThatHoldsAStrongPresenceRouteUntilAFixDecides() {
        val p = policy()
        p.onPresenceMatch(0L, -84, true, unlock)
        assertFalse(feed(p, 200L, -80, walkAgeMs = 1_000L))
        assertTrue(p.consumeGnssEvent()?.startsWith("doubt fresh link at -80 dBm, walk 1 s old") == true)
        assertTrue(p.doubtActive(200L))
        // the presence route qualifies here (200 ms in the band) and is held although -80 is strong
        assertFalse(feed(p, 400L, -80, walkAgeMs = 1_200L))
        val hold = p.consumeRiseHoldEvent()
        assertTrue("held by the gate: $hold", hold != null && hold.startsWith("held presence at -80 dBm") && hold.endsWith("(gate)"))
        assertTrue(p.consumeGnssEvent()?.startsWith("short 0 own steps since the doubt (fresh link at -80 dBm") == true)
        // a far fix (25 m, accuracy 6) keeps the hold; the note repeats every 5 s
        var t = 600L
        while (t <= 6_600L) { assertFalse(feed(p, t, -80, walkAgeMs = 1_400L, gnssM = 25.0)); t += 200L }
        assertTrue(p.consumeGnssEvent()?.startsWith("far 25 m acc 6 after 0 own steps - holds presence at -80 dBm") == true)
        // a near fix (6 m) releases the doubt and the held route confirms
        assertTrue(feed(p, t, -80, walkAgeMs = 1_400L, gnssM = 6.0))
        assertTrue("the presence route (re)qualifies before the hold confirms: ${p.unlockRoute}", p.unlockRoute.startsWith("presence"))
        assertTrue(p.consumeGnssEvent()?.startsWith("released: near 6 m acc 6 after 0 own steps") == true)
        assertFalse(p.doubtActive(t))
    }

    @Test fun aDoubtExpiresAfterAMinute() {
        val p = policy()
        p.onPresenceMatch(0L, -84, true, unlock)
        assertFalse(feed(p, 200L, -80, walkAgeMs = 1_000L))
        assertTrue(p.doubtActive(200L))
        assertTrue(p.doubtActive(60_000L))
        assertFalse(p.doubtActive(60_300L))
    }

    @Test fun aWalkedRiseOfSixDecibelsOverTheDoubtLevelReleasesTheDoubt() {
        val p = policy()
        p.onPresenceMatch(0L, -86, true, unlock)
        var t = 200L
        while (t <= 2_200L) { assertFalse(feed(p, t, -84, walkAgeMs = 500L)); t += 200L }   // the doubt level: -84
        assertTrue(p.consumeGnssEvent()?.startsWith("doubt fresh link at -84 dBm") == true)
        // -78 from 2.4 s: the presence route qualifies (and is held); three readings 6 dB up over 0.8 s release the doubt
        var at: Long? = null
        while (t <= 4_000L) { if (at == null && feed(p, t, -78, walkAgeMs = 2_000L)) at = t; t += 200L }
        assertEquals(3_200L, at)
        assertTrue("the presence route (re)qualifies before the hold confirms: ${p.unlockRoute}", p.unlockRoute.startsWith("presence"))
        assertTrue(p.consumeGnssEvent()?.startsWith("released: rise -78 dBm over the doubt level -84 after 0 own steps") == true)
    }

    @Test fun twelveOwnStepsSinceTheDoubtReleaseIt() {
        val p = policy()
        p.onPresenceMatch(0L, -84, true, unlock)
        var t = 200L
        while (t <= 1_000L) { assertFalse(feed(p, t, -80, own = 100L, walkAgeMs = 1_000L)); t += 200L }
        assertTrue(p.consumeRiseHoldEvent()?.endsWith("(gate)") == true)
        assertFalse("11 own steps: still short", feed(p, t, -80, own = 111L)); t += 200L
        assertTrue("12 own steps: released, the held route confirms", feed(p, t, -80, own = 112L))
        assertTrue(p.consumeGnssEvent()?.startsWith("released: steps 12 own steps since the doubt") == true)
        assertTrue("the presence route (re)qualifies before the hold confirms: ${p.unlockRoute}", p.unlockRoute.startsWith("presence"))
    }

    @Test fun theAtCarLevelEscapesTheDoubt() {
        val p = policy()
        p.onPresenceMatch(0L, -84, true, unlock)
        assertFalse(feed(p, 200L, -80, walkAgeMs = 1_000L))
        assertTrue(p.consumeGnssEvent()?.startsWith("doubt fresh link") == true)
        assertTrue("-74 is the at-car level: no gate", feed(p, 400L, -74, walkAgeMs = 1_200L))
        assertEquals("presence", p.unlockRoute)
        assertTrue(p.consumeGnssEvent()?.startsWith("released: escape at -74 dBm") == true)
        assertNull(p.consumeRiseHoldEvent())
    }

    @Test fun aFarReadingWithinTheMinuteVetoesTheFreshLinkDoubt() {
        val p = policy()
        var t = farThen(p, 100L)
        p.onLinkEnded()
        t += 4_000L   // a new link 4 s later (no level jump within 3 s of the last reading)
        p.onPresenceMatch(t, -84, true, unlock)
        var at: Long? = null
        repeat(6) { if (at == null && feed(p, t, -80, walkAgeMs = 1_000L)) at = t; t += 200L }
        assertNotNull("the approach was seen from far: no doubt, no hold", at)
        assertEquals("presence", p.unlockRoute)
        assertNull(p.consumeGnssEvent())
        assertFalse(p.doubtActive(t))
    }

    @Test fun aLevelJumpFromFarWithoutOwnStepsIsADoubtThatOwnStepsRelease() {
        val p = policy()
        var t = farThen(p, 100L)
        assertFalse(feed(p, t, -84, own = 100L)); t += 200L
        assertTrue(p.consumeGnssEvent()?.startsWith("doubt jump -98 -> -84 dBm (0 own steps between the readings)") == true)
        // the weak qualification is held anyway; the +2 dB rise does not confirm while the doubt stands
        var at: Long? = null
        repeat(4) { if (at == null && feed(p, t, -84, own = 100L)) at = t; t += 200L }
        assertNull(at)
        assertTrue(p.consumeRiseHoldEvent()?.startsWith("held approach at -84 dBm") == true)
        repeat(3) { assertFalse(feed(p, t, -82, own = 100L)); t += 200L }
        assertTrue(p.consumeGnssEvent()?.startsWith("short 0 own steps since the doubt (jump -98 -> -84 dBm") == true)
        assertTrue(feed(p, t, -82, own = 112L))
        assertEquals("approach_rise", p.unlockRoute)
        assertTrue(p.consumeGnssEvent()?.startsWith("released: steps 12 own steps since the doubt") == true)
    }

    @Test fun aSustainedFarStretchIsADoubtReferenceForTheWalkIn() {
        val p = policy()
        var t = 0L
        while (t <= 17_000L) { assertFalse(feed(p, t, -98, own = 100L)); t += 1_000L }
        assertTrue(p.consumeGnssEvent()?.startsWith("doubt far -98 dBm for 16 s") == true)
        // walking in without a level jump: the weak qualification is held by the doubt, a near fix releases it
        t = walkIn(p, t, 100L)
        var at: Long? = null
        repeat(4) { if (at == null && feed(p, t, -84, own = 100L)) at = t; t += 200L }
        assertNull(at)
        assertTrue(p.consumeRiseHoldEvent()?.endsWith("(gate)") == true)
        assertTrue(feed(p, t, -82, own = 100L, gnssM = 5.0))
        assertTrue(p.consumeGnssEvent()?.startsWith("released: near 5 m acc 6") == true)
        assertEquals("approach_rise", p.unlockRoute)
    }

    @Test fun withoutTheOwnRulesOrWithTheGnssRulesOffThereIsNoDoubtAndNoVeto() {
        val p = ProximityDecisionPolicy(ownRules = false)
        p.onPresenceMatch(0L, -84, true, unlock)
        assertFalse(feed(p, 200L, -80, walkAgeMs = 1_000L, gnssM = 25.0))
        assertTrue(feed(p, 400L, -80, walkAgeMs = 1_200L, gnssM = 25.0))
        assertEquals("presence", p.unlockRoute)
        assertNull(p.consumeGnssEvent())
        assertNull(p.consumeRiseHoldEvent())
        assertFalse(p.doubtActive(400L))
        val q = ProximityDecisionPolicy(ownRules = true, gnssRules = false)
        q.onPresenceMatch(0L, -84, true, unlock)
        assertFalse(feed(q, 200L, -80, walkAgeMs = 1_000L, gnssM = 25.0))
        assertTrue("strong: the 0.4.0b rules alone do not hold it", feed(q, 400L, -80, walkAgeMs = 1_200L, gnssM = 25.0))
        assertNull(q.consumeGnssEvent())
        assertFalse(q.doubtActive(400L))
    }

    // ---- 0.4.0d (study 4g): GNSS as positive approach evidence, the route "approach_gnss" (variant g6-p2) ----
    // -87 is 1 dB below the unlock level: inside the band and not yet far (the far margin is 2 dB), so no other route
    // qualifies there. A good fix set: median 5 m, latest 4.5 m, summed accuracy 5, falling 1.5 m over 3 fixes.
    private fun near(policy: ProximityDecisionPolicy, t: Long, rssi: Int = -87, gnssM: Double = 5.0, latest: Double = 4.5,
                     acc: Double = 5.0, trend: Double? = 1.5, fixes: Int = 3, moving: Boolean = true): Boolean =
        feed(policy, t, rssi, moving = moving, gnssM = gnssM, gnssAccM = acc, gnssLatestM = latest, gnssTrendM = trend, gnssFixes = fixes)

    @Test fun theGnssApproachOpensOnTheSecondNearReadingInsideTheBand() {
        val p = policy()
        var t = walkIn(p, farThen(p, 100L), 100L)
        assertFalse("one near reading is not enough", near(p, t)); t += 200L
        assertNull(p.consumeGnssEvent())
        assertTrue("the second near reading in a row opens", near(p, t))
        assertEquals("approach_gnss", p.unlockRoute)
        val note = p.consumeGnssEvent()
        assertEquals("open approach_gnss at -87 dBm: 5 m acc 5 trend 1.5 after 3 fixes (2 readings)", note)
        assertNull("the rise hold does not apply to the GNSS route", p.consumeRiseHoldEvent())
    }

    @Test fun theStreakRestartsAfterAMissButSurvivesAFarDipInBetween() {
        val p = policy()
        var t = walkIn(p, farThen(p, 100L), 100L)
        assertFalse(near(p, t)); t += 200L
        assertFalse("a reading without the fall breaks the streak", near(p, t, trend = 0.5)); t += 200L
        assertFalse(near(p, t)); t += 200L
        assertTrue(near(p, t)); t += 200L
        val q = policy()
        t = walkIn(q, farThen(q, 100L), 100L)
        assertFalse(near(q, t)); t += 200L
        assertFalse("a far dip in between (the pocket signal around the far margin)", feed(q, t, -98)); t += 200L
        assertTrue("the second near reading opens: the dip did not restart the streak", near(q, t))
        assertEquals("approach_gnss", q.unlockRoute)
    }

    @Test fun theGnssApproachNeedsAccurateFreshFallingFixesWithinSixMetres() {
        fun opens(block: (ProximityDecisionPolicy, Long) -> Boolean): Boolean {
            val p = policy()
            var t = walkIn(p, farThen(p, 100L), 100L)
            var ok = false
            repeat(3) { if (block(p, t)) ok = true; t += 200L }
            return ok
        }
        assertFalse("median 7 m", opens { p, t -> near(p, t, gnssM = 7.0, latest = 4.5) })
        assertFalse("latest 6.5 m", opens { p, t -> near(p, t, gnssM = 5.0, latest = 6.5) })
        assertFalse("summed accuracy 9", opens { p, t -> near(p, t, acc = 9.0) })
        assertFalse("two fixes", opens { p, t -> near(p, t, fixes = 2) })
        assertFalse("a fall of 0.9 m", opens { p, t -> near(p, t, trend = 0.9) })
        assertFalse("no trend at all", opens { p, t -> near(p, t, trend = null) })
        assertFalse("standing", opens { p, t -> near(p, t, moving = false) })
        assertFalse("below the band", opens { p, t -> near(p, t, rssi = -93) })
        assertTrue("the good set", opens { p, t -> near(p, t) })
        assertTrue("6 m is inside", opens { p, t -> near(p, t, gnssM = 6.0, latest = 6.0) })
    }

    @Test fun theGnssApproachIsOffWithoutTheOwnRulesAndAfterAnObservedDeparture() {
        val live = ProximityDecisionPolicy(ownRules = false)
        var t = walkIn(live, farThen(live, 100L), 100L)
        repeat(4) { assertFalse("0.4.0c live rules: the route does not exist", near(live, t)); t += 200L }
        assertNull(live.consumeGnssEvent())
        // strong at the car, then far: an observed departure; the walk back on this session never reopens by GNSS
        val p = policy()
        assertFalse(feed(p, 0L, -70))
        t = farThen(p, 100L, from = 200L)
        t = walkIn(p, t, 100L)
        p.consumeGnssEvent()   // the fresh-link doubt of the first strong reading is 0.4.0c's business, not this test's
        repeat(4) { assertFalse(near(p, t)); t += 200L }
        val ev = p.consumeGnssEvent()
        assertTrue("no GNSS opening after an observed departure: $ev", ev == null || !ev.startsWith("open"))
        assertEquals("none", p.unlockRoute)
    }
}
