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
}
