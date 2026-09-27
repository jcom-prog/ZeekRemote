package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityDecisionPolicyTest {
    @Test
    fun manualLockRejectsMovingOnlyReboundEvenAfterLongFarSeparation() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..8_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -99, true, -86))
        }
        for (time in 8_200L..11_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -85, true, -86))
        }
    }

    @Test
    fun manualLockNeedsQualifiedFarPauseAndNewMotionEdge() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..4_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -98, true, -86))
        }
        assertFalse(policy.shouldUnlock(4_200L, -86, false, -86))
        assertFalse(policy.shouldUnlock(4_800L, -86, true, -86)) // short pause
        assertFalse(policy.shouldUnlock(5_000L, -85, true, -86))
        for (time in 5_200L..7_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -86, false, -86))
        }
        assertFalse(policy.shouldUnlock(7_200L, -86, true, -86))
        assertFalse(policy.shouldUnlock(7_400L, -83, true, -86))
        assertFalse(policy.shouldUnlock(7_600L, -83, true, -86))
        assertTrue(policy.shouldUnlock(7_800L, -83, true, -86))
    }

    @Test
    fun coldStartArrivalIsReadyAtFarThresholdAfterTwoSamples() {
        val policy = ProximityDecisionPolicy()
        policy.onPresenceMatch(0L, -87, true, -86)

        assertFalse(policy.shouldUnlock(100L, -86, true, -86))
        assertTrue("qualified cold arrival must not wait for an extra 4 dB after handshake",
            policy.shouldUnlock(300L, -86, true, -86))
    }

    @Test
    fun coldStartAtFarThresholdStillRequiresMotionAndRisingEvidence() {
        val noMotion = ProximityDecisionPolicy()
        noMotion.onPresenceMatch(0L, -85, false, -86)
        assertFalse(noMotion.shouldUnlock(300L, -86, false, -86))

        val noRise = ProximityDecisionPolicy()
        noRise.onPresenceMatch(0L, -85, true, -86)
        assertFalse(noRise.shouldUnlock(100L, -85, true, -86))
        assertFalse(noRise.shouldUnlock(300L, -85, true, -86))
    }

    @Test
    fun captured0134MidRangeColdArrivalCanRecoverAfterHandshake() {
        val policy = ProximityDecisionPolicy()

        // 0.1.34-aborted-arrival-no-response.log: the only offloaded presence callback arrived at
        // -82 dBm while moving. BLE connected and SESSION_READY followed, then RSSI rose as high as
        // -61, but the old -83 entry cut-off had permanently discarded the arrival epoch.
        policy.onPresenceMatch(0L, -82, true, -86)
        assertFalse(policy.shouldUnlock(100L, -80, true, -86))
        assertTrue("a moving -82 presence followed by a sustained rise must qualify",
            policy.shouldUnlock(300L, -80, true, -86))
    }

    @Test
    fun midRangePresenceStillRequiresMotionAndRisingEvidence() {
        val noMotion = ProximityDecisionPolicy()
        noMotion.onPresenceMatch(0L, -82, false, -86)
        assertFalse(noMotion.shouldUnlock(100L, -80, false, -86))
        assertFalse(noMotion.shouldUnlock(300L, -78, false, -86))

        val noRise = ProximityDecisionPolicy()
        noRise.onPresenceMatch(0L, -82, true, -86)
        assertFalse(noRise.shouldUnlock(100L, -82, true, -86))
        assertFalse(noRise.shouldUnlock(300L, -83, true, -86))
    }

    @Test
    fun strongNearBoundaryCannotCreatePresenceArrival() {
        listOf(-72, -71, -58).forEach { entryRssi ->
            val policy = ProximityDecisionPolicy()
            policy.onPresenceMatch(0L, entryRssi, true, -86)
            assertFalse("strong/ambiguous entry must remain rejected; rssi=$entryRssi",
                policy.shouldUnlock(100L, -70, true, -86))
            assertFalse(policy.shouldUnlock(300L, -68, true, -86))
        }
    }

    @Test
    fun midRangeReturnAfterLinkEndCanCreateFreshArrivalEpoch() {
        val policy = ProximityDecisionPolicy()
        policy.onDepartureLockStarted()
        policy.onLinkEnded()

        policy.onPresenceMatch(0L, -82, true, -86)
        assertFalse(policy.shouldUnlock(100L, -80, true, -86))
        assertTrue(policy.shouldUnlock(300L, -79, true, -86))
    }

    @Test
    fun captured031TurnaroundWithoutOpeningLocksBeforeSessionIsLost() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onUnlockConfirmed(now)

        // Exact EMA sequence from 0.1.31-turnaround-no-lock.log after CTRL_UNLOCK was confirmed.
        // The user never reached the strong-near arrival band and immediately walked away. 0.1.31
        // discarded this evidence during its 15 s grace and later could no longer lock over BLE.
        val turnaround = listOf(
            -83, -85, -83, -80, -79, -81, -82, -80, -80, -79, -79, -80,
            -84, -86, -86, -86, -86, -88, -90, -90, -91, -91,
        )
        var decision = ProximityDecisionPolicy.ArmedDecision.NONE
        turnaround.forEach { rssi ->
            decision = policy.onUnlockedSample(now, rssi, true, -82)
            now += 210
        }

        assertEquals(ProximityDecisionPolicy.ArmedDecision.LOCK, decision)
        assertTrue("lock must be decided while the original DK session is still available", now <= 5_000)
    }

    @Test
    fun shortPostUnlockBodyShadowDoesNotBecomeAbortedArrival() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onUnlockConfirmed(now)

        // More than 8 dB of body-shadow is insufficient without four seconds of observation and a
        // sustained FAR run. This protects a normal user who pauses or changes phone orientation.
        listOf(-75, -78, -84, -85, -87, -80, -79, -86, -88, -81).forEach { rssi ->
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, rssi, true, -82))
            now += 300
        }
    }

    @Test
    fun nearRecoveryResetsAbortedArrivalEvidence() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onUnlockConfirmed(now)

        listOf(-78, -83, -86, -88, -90, -81).forEach { rssi ->
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, rssi, true, -82))
            now += 700
        }
        // The preceding candidate was reset by -81. Remaining FAR samples are too short to lock.
        repeat(4) {
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, -90, true, -82))
            now += 300
        }
    }

    @Test
    fun farRssiWithoutObservedMovementNeverLocksAbortedArrival() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onUnlockConfirmed(now)

        repeat(30) {
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                policy.onUnlockedSample(now, if (it == 0) -78 else -92, false, -82))
            now += 200
        }
    }

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
    fun departureLatchBlocksImmediateSameSessionRebound() {
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

        // A presence hit before the old link ends is still the same departure and cannot rearm
        // unless a separate, stable FAR epoch has first been observed.
        policy.onPresenceMatch(now, -90, true, -86)
        assertFalse(policy.shouldUnlock(now + 200, -82, true, -86))

        // Only link end plus a new edge-range hardware presence event creates a new arrival epoch.
        policy.onLinkEnded()
        policy.onPresenceMatch(now + 400, -90, true, -86)
        assertFalse(policy.shouldUnlock(now + 400, -85, true, -86))
        // 0.1.34: once the old link ended, hardware presence + movement + a rising signal may
        // qualify at the configured Far threshold after two fast samples. The pre-link rebound
        // above remains blocked, so this latency gain does not weaken the departure latch.
        assertTrue(policy.shouldUnlock(now + 600, -82, true, -86))
        assertTrue(policy.shouldUnlock(now + 800, -81, true, -86))
    }

    @Test
    fun captured032QuickReturnRearmsWithoutLinkLoss() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onDepartureLockStarted()

        // The car has just locked. A real departure remains continuously FAR long enough to prove
        // that the previous arrival ended, even though Android keeps the authenticated GATT link.
        // Captured 0.1.32-turnaround-test-1.log: the retained link remained deep FAR after lock.
        listOf(-100, -100, -100, -100, -99, -100, -101, -102, -103, -103,
            -102, -103, -103, -102, -103, -103).forEach { rssi ->
            assertFalse(policy.shouldUnlock(now, rssi, true, -86))
            now += 210
        }

        // Captured 0.1.32-return-no-response.log begins only once the retained connection is already
        // near (-59 dBm). The long gap is real: no link-down or fresh presence callback occurred.
        now += 180_000
        var unlocked = false
        listOf(-59, -59, -59, -61).forEach { rssi ->
            unlocked = unlocked || policy.shouldUnlock(now, rssi, true, -86)
            now += 210
        }
        assertTrue("a proven FAR -> NEAR return must create a new arrival epoch without link loss", unlocked)
    }

    @Test
    fun sameLinkMultipathReboundCannotRearmDepartureLatch() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onDepartureLockStarted()

        // Captured-style post-lock bounce: weak briefly, then rebounds within a few seconds. This is
        // not a new visit and must remain blocked despite crossing the normal unlock threshold.
        listOf(-95, -96, -96, -94, -92, -90, -87, -84, -82, -80).forEach { rssi ->
            assertFalse(policy.shouldUnlock(now, rssi, true, -86))
            now += 250
        }
    }

    @Test
    fun captured0130ArrivalDipDoesNotCancelPendingUnlock() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onPendingUnlockStarted()

        // 0.1.30 final fast test 1: the first command was cancelled while the user was still
        // approaching because a short body-shadow/multipath dip crossed the lock threshold.
        // The rebound immediately afterwards proves this was not a sustained departure.
        listOf(-84, -85, -86, -88, -90, -86).forEach { rssi ->
            assertFalse("short arrival dip must not cancel at rssi=$rssi",
                policy.shouldCancelPendingUnlock(now, rssi, true, -86, -82))
            now += 200
        }
    }

    @Test
    fun sustainedFarMovementCanStillCancelAbandonedUnlock() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onPendingUnlockStarted()

        var cancelled = false
        repeat(8) {
            cancelled = policy.shouldCancelPendingUnlock(now, -91, true, -86, -82)
            now += 200
        }
        assertTrue("a genuinely abandoned approach must eventually cancel", cancelled)
    }
}
