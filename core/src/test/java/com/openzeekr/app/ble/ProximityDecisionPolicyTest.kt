package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityDecisionPolicyTest {
    @Test
    fun capturedDepartureBounceDoesNotUnlockOrRelock() {
        val policy = ProximityDecisionPolicy()
        var now = 0L

        // Captured 0.1.20 sequence: user was close, walked away, then multipath made RSSI rebound.
        // The old FAR->NEAR edge unlocked at the final -84/-82 rebound.
        val lockedTrace = listOf(-64, -68, -70, -72, -73, -76, -77, -76, -75, -76,
            -79, -82, -84, -85, -87, -88, -88, -87, -84, -83, -84, -84, -82)
        lockedTrace.forEach { rssi ->
            assertFalse("must not unlock while departing; rssi=$rssi", policy.shouldUnlock(now, rssi, true, -86))
            now += 200
        }

        // Even if an unlock was externally confirmed, the captured post-unlock bounce must not lock
        // during the 15 s arrival grace, and a STILL sample must cancel a walk-away candidate.
        policy.onUnlockConfirmed(now)
        val postUnlock = listOf(-79, -79, -79, -79, -77, -79, -82, -85, -86, -83,
            -80, -81, -84, -85, -84, -84, -84, -86, -86, -84)
        postUnlock.forEach { rssi ->
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, rssi, true, -82))
            now += 200
        }
        assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
            policy.onUnlockedSample(now, -87, false, -82))
    }

    @Test
    fun qualifiedApproachUnlocksThenRealDepartureLocks() {
        val policy = ProximityDecisionPolicy()
        var now = 0L

        // Establish a real FAR baseline before approaching.
        repeat(14) {
            assertFalse(policy.shouldUnlock(now, -91, true, -86))
            now += 200
        }
        // Sustained approach, comfortably through the threshold margin.
        var unlocked = false
        listOf(-87, -85, -83, -82, -81, -80, -79, -78).forEach { rssi ->
            unlocked = unlocked || policy.shouldUnlock(now, rssi, true, -86)
            now += 200
        }
        assertTrue(unlocked)

        policy.onUnlockConfirmed(now)
        var arrival = ProximityDecisionPolicy.ArmedDecision.NONE
        repeat(9) {
            arrival = policy.onUnlockedSample(now, -68, true, -82)
            now += 200
        }
        assertEquals(ProximityDecisionPolicy.ArmedDecision.ARRIVAL_CONFIRMED, arrival)

        var lock = ProximityDecisionPolicy.ArmedDecision.NONE
        listOf(-83, -83, -84, -84, -85, -85, -86, -86, -87, -87, -88, -88).forEach { rssi ->
            lock = policy.onUnlockedSample(now, rssi, true, -82)
            now += 200
        }
        assertEquals(ProximityDecisionPolicy.ArmedDecision.LOCK, lock)
    }

    @Test
    fun capturedFastApproachUnlocksEarlierWithoutWaitingAtDoor() {
        val policy = ProximityDecisionPolicy()
        var now = 0L

        // 0.1.22 screen-off field trace. Establish the distant baseline, then replay the first clean
        // rise. The decision must be ready at -83 instead of waiting through the later dip/recovery
        // until -82; this preserves unlock distance when the user walks faster in rain.
        repeat(14) {
            assertFalse(policy.shouldUnlock(now, -91, true, -86))
            now += 200
        }
        assertFalse(policy.shouldUnlock(now, -85, true, -86)); now += 200
        assertFalse(policy.shouldUnlock(now, -83, true, -86)); now += 200
        assertTrue(policy.shouldUnlock(now, -83, true, -86))
    }

    @Test
    fun capturedDepartureReboundStillLocksPromptly() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onUnlockConfirmed(now)

        // Confirm that the phone really reached the car first.
        repeat(9) {
            policy.onUnlockedSample(now, -62, true, -82)
            now += 200
        }

        // Extract from 0.1.22-deep-sleep-distance-retest.log. Short recoveries to -80/-81 used to
        // erase the entire walk-away timer, and the final rebound from -93 to -84 prevented the
        // required current-vs-start 3 dB drop. The weakest observed sample now preserves direction.
        var decision = ProximityDecisionPolicy.ArmedDecision.NONE
        listOf(-73, -75, -77, -76, -78, -79, -82, -82, -81, -80, -80, -82,
            -83, -85, -86, -88, -88, -91, -93, -90, -87, -84).forEach { rssi ->
            decision = policy.onUnlockedSample(now, rssi, true, -82)
            now += 200
        }
        assertEquals(ProximityDecisionPolicy.ArmedDecision.LOCK, decision)
    }

    @Test
    fun strongRecoveryCancelsWalkAwayCandidate() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onUnlockConfirmed(now)
        repeat(9) { policy.onUnlockedSample(now, -65, true, -82); now += 200 }

        listOf(-83, -85, -86, -79).forEach { rssi ->
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, rssi, true, -82))
            now += 500
        }
        repeat(6) {
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, -83, true, -82))
            now += 400
        }
    }

    @Test
    fun bodyShadowWhileStillNeverLocks() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onUnlockConfirmed(now)
        repeat(9) { now += 200; policy.onUnlockedSample(now, -65, false, -82) }

        repeat(30) {
            now += 200
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, -90, false, -82))
        }
    }

    @Test
    fun capturedDeepSleepArrivalSurvivesHandshakeDelay() {
        val policy = ProximityDecisionPolicy()
        var now = 0L

        // Captured 2026-09-26 deep-sleep trace: presence connected at the door while motion had
        // already gone STILL. These samples happened before SESSION_READY.
        listOf(-61, -62, -64, -64, -64, -63, -65).forEach { rssi ->
            policy.shouldUnlock(now, rssi, false, -86)
            now += 200
        }
        assertTrue("door arrival must be qualified during the handshake",
            policy.shouldUnlock(now, -68, false, -86))

        // Handshake completes after body shadow weakens RSSI, but before any FAR/departure sample.
        now += 2_000
        assertTrue("qualified arrival must remain consumable when the DK session becomes ready",
            policy.shouldUnlock(now, -84, false, -86))
    }

    @Test
    fun pendingDoorArrivalIsCancelledByFarDeparture() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        repeat(8) {
            policy.shouldUnlock(now, -64, false, -86)
            now += 200
        }
        assertTrue(policy.shouldUnlock(now, -70, false, -86))

        now += 200
        assertFalse("a FAR sample must invalidate pending arrival evidence immediately",
            policy.shouldUnlock(now, -88, true, -86))
        now += 200
        assertFalse(policy.shouldUnlock(now, -84, true, -86))
    }

    @Test
    fun pendingDoorArrivalExpiresBeforeLaterReconnect() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        repeat(8) {
            policy.shouldUnlock(now, -64, false, -86)
            now += 200
        }
        assertTrue(policy.shouldUnlock(now, -70, false, -86))

        now += 5_001
        assertFalse("old arrival evidence must not unlock a later session",
            policy.shouldUnlock(now, -84, false, -86))
    }

    @Test
    fun strongPresenceAtDoorCannotCreateDepartureUnlock() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onPresenceMatch(now, -58, true, -86)
        val departure = listOf(-58, -67, -71, -78, -82, -86, -89, -92, -88, -82)
        departure.forEach { rssi ->
            assertFalse("strong first presence is ambiguous and must not arm; rssi=$rssi",
                policy.shouldUnlock(now, rssi, true, -86))
            now += 200
        }
    }

    @Test
    fun expiredPresenceEpochCannotUnlockOnLaterMotion() {
        val policy = ProximityDecisionPolicy()
        policy.onPresenceMatch(0L, -84, true, -86)
        assertFalse(policy.shouldUnlock(35_001L, -81, true, -86))
        assertFalse(policy.shouldUnlock(35_401L, -78, true, -86))
    }

    @Test
    fun departureLatchBlocksEverySameSessionUnlockAndNeedsFreshPresence() {
        val policy = ProximityDecisionPolicy()
        policy.onDepartureLockStarted()

        // Exact rebound shape that emitted an unwanted CTRL_UNLOCK five seconds after the confirmed
        // lock in 0.1.29 fast test 1. No connected-RSSI sample may rearm the same session.
        var now = 0L
        listOf(-95, -96, -98, -96, -95, -94, -93, -92, -91, -90, -88, -86,
            -86, -85, -84, -84, -84, -87, -89, -91).forEach { rssi ->
            assertFalse("post-lock rebound must stay blocked; rssi=$rssi",
                policy.shouldUnlock(now, rssi, true, -86))
            now += 200
        }

        // A presence hit before the old link ends is still the same departure and cannot rearm.
        policy.onPresenceMatch(now, -90, true, -86)
        assertFalse(policy.shouldUnlock(now + 200, -82, true, -86))

        // Only link end plus a new edge-range hardware presence event creates a new arrival epoch.
        policy.onLinkEnded()
        policy.onPresenceMatch(now + 400, -90, true, -86)
        assertFalse(policy.shouldUnlock(now + 400, -85, true, -86))
        assertFalse(policy.shouldUnlock(now + 600, -82, true, -86))
        assertTrue(policy.shouldUnlock(now + 800, -81, true, -86))
    }
}
