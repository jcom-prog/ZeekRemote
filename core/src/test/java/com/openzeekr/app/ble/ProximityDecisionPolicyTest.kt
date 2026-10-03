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
        // 0.1.63: the return must reach -82 (simulator: -85 noise at 10 m reopened the car).
        assertFalse(policy.shouldUnlock(7_400L, -83, true, -86))
        assertTrue(policy.shouldUnlock(7_600L, -81, true, -86))
    }

    @Test
    fun manualLockAcceptsElapsedStillPauseAcrossFarSleepPollingGap() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..2_600L step 200L) {
            assertFalse(policy.shouldUnlock(time, -89, true, -86))
        }
        assertFalse(policy.shouldUnlock(2_800L, -89, false, -86))
        assertFalse(policy.shouldUnlock(23_500L, -89, true, -86))
        assertFalse(policy.shouldUnlock(23_700L, -83, true, -86))
        assertTrue(policy.shouldUnlock(23_900L, -81, true, -86))
    }

    @Test
    fun manualLockStillPauseSurvivesMildReboundWhileStanding() {
        // Field shape 0.1.55 (02/10 20:29, ~20 m walk): FAR while walking away, a 13 s stop at
        // -85..-88 with one -82 orientation rebound, ~1 s STILL before walking back. The rebound
        // erased the qualified pause and the return to the door never rearmed.
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..6_000L step 200L) assertFalse(policy.shouldUnlock(time, -57, false, -86))
        for (time in 10_000L..21_400L step 200L) assertFalse(policy.shouldUnlock(time, -89, true, -86))
        val still = listOf(-87, -87, -88, -88, -87, -85, -85, -85, -86, -87, -86, -85, -82, -87)
        still.forEachIndexed { i, rssi ->
            assertFalse(policy.shouldUnlock(21_600L + i * 1_000L, rssi, false, -86))
        }
        // Next MOVING edge after ~1 s, still FAR, then the walk back rises to the door.
        assertFalse(policy.shouldUnlock(35_600L, -88, true, -86))
        assertFalse(policy.shouldUnlock(35_800L, -89, true, -86))
        assertFalse(policy.shouldUnlock(37_200L, -86, true, -86))
        assertTrue(policy.shouldUnlock(37_400L, -80, true, -86))
    }

    @Test
    fun manualLockStillPauseIsVoidedByStrongNearWhileStanding() {
        // Back at the car while the motion sensor still says STILL: a later shuffle is not a return.
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..3_000L step 200L) assertFalse(policy.shouldUnlock(time, -90, true, -86))
        for (time in 3_200L..6_000L step 1_000L) assertFalse(policy.shouldUnlock(time, -88, false, -86))
        assertFalse(policy.shouldUnlock(7_000L, -66, false, -86))
        assertFalse(policy.shouldUnlock(7_400L, -66, false, -86))
        for (time in 7_600L..12_000L step 200L) assertFalse(policy.shouldUnlock(time, -64, true, -86))
    }

    @Test
    fun manualLockKeptReboundCannotRearmWhileWalkingFurtherAway() {
        // Review 0.1.56: rebound kept while standing at 8-10 m, then the user walks away while the
        // smoothed signal still lags in the arrival band.
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..6_000L step 200L) assertFalse(policy.shouldUnlock(time, -57, true, -86))
        for (time in 10_000L..13_000L step 200L) assertFalse(policy.shouldUnlock(time, -89, true, -86))
        for (time in 13_200L..15_000L step 200L) assertFalse(policy.shouldUnlock(time, -87, false, -86))
        for (time in 16_000L..20_000L step 200L) assertFalse(policy.shouldUnlock(time, -80, false, -86))
        val away = listOf(-80, -82, -84, -84, -85, -86, -88, -90)
        away.forEachIndexed { i, rssi -> assertFalse(policy.shouldUnlock(20_200L + i * 200L, rssi, true, -86)) }
    }

    @Test
    fun manualLockKeptReboundThenMotionJitterAtTenMetresStaysLocked() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..3_000L step 200L) assertFalse(policy.shouldUnlock(time, -90, true, -86))
        for (time in 3_200L..6_000L step 1_000L) assertFalse(policy.shouldUnlock(time, -88, false, -86))
        for (time in 7_000L..20_000L step 1_000L) assertFalse(policy.shouldUnlock(time, -82, false, -86))
        for (time in 20_200L..20_600L step 200L) assertFalse(policy.shouldUnlock(time, -83, true, -86))
        for (time in 20_800L..35_000L step 1_000L) assertFalse(policy.shouldUnlock(time, -84, false, -86))
    }

    @Test
    fun manualLockKeptReboundThenRealWalkBackStillUnlocks() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        for (time in 0L..3_000L step 200L) assertFalse(policy.shouldUnlock(time, -90, true, -86))
        for (time in 3_200L..6_000L step 1_000L) assertFalse(policy.shouldUnlock(time, -88, false, -86))
        assertFalse(policy.shouldUnlock(7_000L, -81, false, -86))
        assertFalse(policy.shouldUnlock(8_000L, -87, false, -86))
        assertFalse(policy.shouldUnlock(8_200L, -87, true, -86))
        assertFalse(policy.shouldUnlock(8_400L, -84, true, -86))
        assertTrue(policy.shouldUnlock(8_600L, -78, true, -86))
    }

    @Test
    fun manualWeakSleepDoesNotRearmAfterBriefDip() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        assertFalse(policy.shouldUnlock(0L, -60, false, -86))
        assertFalse(policy.shouldUnlock(15_000L, -78, false, -86))
        assertFalse(policy.shouldUnlock(17_000L, -79, true, -86))
        for (time in 17_200L..18_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -68, true, -86))
        }
    }

    @Test
    fun manualWeakSleepDoesNotRearmWhileStillDespiteRssiRebound() {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        assertFalse(policy.shouldUnlock(0L, -60, false, -86))
        assertFalse(policy.shouldUnlock(15_000L, -78, false, -86))
        assertFalse(policy.shouldUnlock(26_000L, -78, false, -86))
        for (time in 26_200L..27_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -68, false, -86))
        }
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
    fun failedFarConnectionThenFreshDoorSessionStillUnlocks() {
        val policy = ProximityDecisionPolicy()
        repeat(20) { index -> assertFalse(policy.shouldUnlock(index * 200L, -105, true, -86)) }
        policy.onLinkEnded()
        policy.onPresenceMatch(4_200L, -62, false, -86)
        assertFalse(policy.shouldUnlock(4_400L, -61, false, -86))
        assertTrue("a dead FAR connection cannot suppress a later ready session at the door",
            policy.shouldUnlock(5_700L, -61, false, -86))
    }

    @Test
    fun freshDoorFallbackStillRejectsObservedDepartureAfterLinkEnd() {
        val policy = ProximityDecisionPolicy()
        assertFalse(policy.shouldUnlock(0L, -60, false, -86))
        assertFalse(policy.shouldUnlock(200L, -95, true, -86))
        policy.onLinkEnded()
        assertFalse(policy.shouldUnlock(2_000L, -61, false, -86))
        assertFalse(policy.shouldUnlock(3_500L, -61, false, -86))
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

class ManualLockDeepFarReturnTest {
    private fun locked() = ProximityDecisionPolicy().apply { onManualLockConfirmed() }

    @Test fun walkToFifteenMetresAndBackUnlocksWhileTheMotionSensorSaysStill() {
        // Field shape 0.1.60 (03/10 16:22, car self-lock): away to ~15 m and back, Activity
        // Recognition STILL nearly the whole way; the motion-edge route voided itself at ~10 m.
        val p = locked()
        var t = 0L
        listOf(-70, -70, -71, -81, -84, -84, -85, -89, -90, -90, -91, -92, -93, -94, -95, -95)
            .forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L }
        t += 5_000L // far-sleep gap
        listOf(-97, -95, -93, -94, -90, -88, -84, -84).forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L }
        assertFalse(p.shouldUnlock(t, -81, false, -86)); t += 500L
        assertFalse(p.shouldUnlock(t, -82, false, -86)); t += 500L
        assertTrue(p.shouldUnlock(t, -81, false, -86))
        assertTrue(p.consumeDeepFarReturnRearm())
    }

    @Test fun twoStrongReadingsAboutOneSecondApartAreAReturnDespiteSamplingJitter() {
        // Field shape 0.1.63 (03/10 19:57, car self-lock): back from ~25 m on the 1 s linked
        // watch, -81 then -82 at ~6 m only 995 ms apart; the strict ">= 1000 ms" refused it, the
        // next reading (-84) restarted the count and the car opened only at the door.
        val p = locked()
        var t = 0L
        listOf(-95, -96, -95, -95, -93, -94, -95, -96, -96, -95, -93)
            .forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L }
        listOf(-89, -94, -91, -88, -85).forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L }
        assertFalse(p.shouldUnlock(t, -81, false, -86)); t += 995L
        assertTrue(p.shouldUnlock(t, -82, false, -86))
        assertTrue(p.consumeDeepFarReturnRearm())
    }

    @Test fun twoStrongReadingsWellUnderASecondApartAreNotYetAReturn() {
        val p = locked()
        var t = 0L
        repeat(8) { assertFalse(p.shouldUnlock(t, -96, false, -86)); t += 1_000L }
        // Activity Recognition still says STILL (the motion-gated route has its own rules).
        assertFalse(p.shouldUnlock(t, -80, false, -86)); t += 200L
        assertFalse(p.shouldUnlock(t, -82, false, -86)); t += 200L
        assertFalse(p.shouldUnlock(t, -84, false, -86))
    }

    @Test fun keyWakingWhileWalkingAwayStillAllowsTheReturn() {
        // Simulator 03/10 (0.1.64): car self-locked, phone asleep at the car; the key wakes on the
        // walk away (presence -94, moving), the arrival epoch lapses, the user comes back later.
        val p = locked()
        p.onLinkEnded()
        var t = 100_000L
        p.onPresenceMatch(t, -94, true, -86); t += 1_000L
        repeat(60) { assertFalse(p.shouldUnlock(t, -96 - it % 4, true, -86)); t += 200L }   // away to 25 m
        repeat(25) { assertFalse(p.shouldUnlock(t, -95, false, -86)); t += 1_000L }         // waiting, epoch lapses
        listOf(-93, -91, -89, -88, -86, -85).forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 500L }
        var opened = false
        listOf(-82, -81, -80, -79, -77).forEach { if (p.shouldUnlock(t, it, true, -86)) opened = true; t += 300L }
        assertTrue(opened)
    }

    @Test fun keyWakingWhileWalkingAwayDoesNotOpenAtTenMetres() {
        val p = locked()
        p.onLinkEnded()
        var t = 100_000L
        p.onPresenceMatch(t, -90, true, -86); t += 1_000L
        repeat(40) { assertFalse(p.shouldUnlock(t, -88, false, -86)); t += 1_000L }   // epoch lapses at ~10 m
        // No clear departure (>= 4 s at <= -92) was ever seen: 10 m noise must not open the car.
        repeat(60) { assertFalse(p.shouldUnlock(t, listOf(-86, -88, -84, -89, -87, -85)[it % 6], it % 2 == 0, -86)); t += 1_000L }
    }

    @Test fun standingAtTenMetresWithShortDeepDipsStaysLocked() {
        val p = locked()
        var t = 0L
        repeat(10) { assertFalse(p.shouldUnlock(t, -68, false, -86)); t += 1_000L }
        // ~10 m, standing and shuffling: -84..-89, single body-shadow dips to -93.
        val tenMetres = listOf(-86, -88, -93, -87, -89, -85, -93, -88, -84, -86, -94, -87, -88, -85)
        repeat(4) { tenMetres.forEachIndexed { i, r -> assertFalse(p.shouldUnlock(t, r, i % 3 == 0, -86)); t += 1_000L } }
        assertFalse(p.consumeDeepFarReturnRearm())
    }

    @Test fun shortDeepDipThenTheCarDoesNotUnlock() {
        val p = locked()
        var t = 0L
        listOf(-93, -95, -94).forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L } // 2 s only
        repeat(10) { assertFalse(p.shouldUnlock(t, -70, false, -86)); t += 500L }
    }

    @Test fun clearlyFarThenBackOnlyToTenMetresStaysLocked() {
        val p = locked()
        var t = 0L
        repeat(8) { assertFalse(p.shouldUnlock(t, -96, false, -86)); t += 1_000L }
        repeat(20) { assertFalse(p.shouldUnlock(t, if (it % 2 == 0) -84 else -85, false, -86)); t += 500L }
    }

    @Test fun oneStrongReadingIsNotAReturn() {
        val p = locked()
        var t = 0L
        repeat(8) { assertFalse(p.shouldUnlock(t, -96, false, -86)); t += 1_000L }
        assertFalse(p.shouldUnlock(t, -78, false, -86)); t += 1_000L
        assertFalse(p.shouldUnlock(t, -90, false, -86)); t += 1_000L
        assertFalse(p.shouldUnlock(t, -79, false, -86)); t += 500L
        assertFalse(p.shouldUnlock(t, -88, false, -86))
    }

    @Test fun isolatedDeepDipsAcrossFarSleepGapsNeverProveADeparture() {
        // Review 0.1.61: at the door or at 10 m, one dip before a far-sleep gap and one after it.
        val p = locked()
        var t = 0L
        repeat(6) {
            assertFalse(p.shouldUnlock(t, -84, false, -86)); t += 1_000L
            assertFalse(p.shouldUnlock(t, -93, false, -86)); t += 30_000L
            assertFalse(p.shouldUnlock(t, -94, false, -86)); t += 1_000L
        }
        repeat(6) { assertFalse(p.shouldUnlock(t, -78, false, -86)); t += 500L }
    }

    @Test fun anAutomaticLockAfterAnEarlierManualLockDoesNotUseTheMotionGatedManualRoutes() {
        val p = locked()
        p.onUnlockConfirmed(0L)          // e.g. "car reopened" on the same link
        p.onDepartureLockStarted()       // then a walk-away auto-lock
        var t = 1_000L
        // Not clearly far (the deep-far return needs <= -92): the manual still/motion routes must not apply.
        repeat(8) { assertFalse(p.shouldUnlock(t, -89, it % 2 == 0, -86)); t += 1_000L }
        repeat(6) { assertFalse(p.shouldUnlock(t, -83, true, -86)); t += 500L }
    }

    @Test fun afterAProvenAutomaticLockTheWalkBackReopens() {
        // Simulator 03/10 "turn back at 9 m": the Lock fires as the user turns around.
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(0L)
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(10_000L)
        var t = 10_200L
        // A post-lock rebound inside the guard never reopens (0.1.29 shape).
        listOf(-90, -86, -84, -80, -80, -84).forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }
        t = 14_000L
        listOf(-88, -87, -86, -84).forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 500L }
        assertFalse(p.shouldUnlock(t, -81, true, -86)); t += 1_000L
        assertTrue(p.shouldUnlock(t, -80, true, -86))
    }

    @Test fun aLockWhileTurningBackStillReopensAtTheCar() {
        // Simulator 03/10 (0.1.64) "turn back at 9 m": the Lock lands at ~7 m on the walk back.
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(0L)
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(10_000L)
        var t = 10_200L
        // The one reading outside the unlock band falls inside the post-lock guard...
        listOf(-88, -86, -85, -86, -85, -83, -85, -83, -83).forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }
        // ...and the walk back to the door must still reopen.
        listOf(-80, -81, -81, -82, -82, -81, -80, -78, -78).forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }
        var opened = false
        listOf(-77, -76, -77, -74, -73, -73, -71).forEach { if (p.shouldUnlock(t, it, true, -86)) opened = true; t += 200L }
        assertTrue(opened)
    }

    @Test fun aLockWhileTurningBackWithoutRecessionNeedsTheCarItself() {
        // No reading outside the unlock band at all: noise at 8-10 m (up to -78) never reopens.
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(0L)
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(10_000L)
        var t = 10_200L
        repeat(60) { i -> assertFalse(p.shouldUnlock(t, listOf(-84, -82, -80, -78, -81, -85)[i % 6], true, -86)); t += 200L }
        var opened = false
        repeat(8) { if (p.shouldUnlock(t, -74, true, -86)) opened = true; t += 200L }
        assertTrue(opened)
    }

    @Test fun afterAProvenAutomaticLockNoiseAtTheLockPointDoesNotReopen() {
        // Review 0.1.63: locked at ~8-10 m (-85..-88); noise up to -80 without first reading
        // clearly outside the unlock band must not reopen the car.
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(0L)
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(10_000L)
        var t = 13_100L
        repeat(40) { i -> assertFalse(p.shouldUnlock(t, listOf(-85, -86, -87, -81, -80, -84)[i % 6], false, -86)); t += 500L }
    }

    @Test fun motionlessApproachNeedsASustainedStrongRiseFromTheLatestFarFloor() {
        val p = ProximityDecisionPolicy()
        var t = 0L
        repeat(6) { assertFalse(p.shouldUnlock(t, -95, false, -86)); t += 1_000L }   // FAR baseline
        repeat(2) { assertFalse(p.shouldUnlock(t, -81, false, -86)); t += 1_000L }   // 2 s only
        assertFalse(p.shouldUnlock(t, -86, false, -86)); t += 1_000L
        repeat(3) { assertFalse(p.shouldUnlock(t, -81, false, -86)); t += 1_000L }
        assertTrue(p.shouldUnlock(t, -81, false, -86))
    }

    @Test fun motionlessApproachIgnoresAnOldFarOutlier() {
        val p = ProximityDecisionPolicy()
        var t = 0L
        repeat(6) { assertFalse(p.shouldUnlock(t, -100, false, -86)); t += 1_000L }  // old, very far
        assertFalse(p.shouldUnlock(t, -85, false, -86)); t += 1_000L                 // leaves FAR
        repeat(4) { assertFalse(p.shouldUnlock(t, -89, false, -86)); t += 1_000L }   // new FAR floor -89
        repeat(6) { assertFalse(p.shouldUnlock(t, -82, false, -86)); t += 1_000L }   // only +7 dB
    }

    @Test fun aProvenAutomaticLockDoesNotReopenAMinuteLaterAtTheCar() {
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(0L)
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(10_000L)
        var t = 11_000L
        repeat(70) { assertFalse(p.shouldUnlock(t, -87, false, -86)); t += 1_000L }
        repeat(6) { assertFalse(p.shouldUnlock(t, -78, false, -86)); t += 500L }
    }

    @Test fun aProvenDepartureExpiresAfterAMinuteBackAtTheCar() {
        val p = locked()
        var t = 0L
        repeat(6) { assertFalse(p.shouldUnlock(t, -96, false, -86)); t += 1_000L }
        // Back at the door but just under the return level for more than a minute.
        repeat(70) { assertFalse(p.shouldUnlock(t, -84, false, -86)); t += 1_000L }
        repeat(6) { assertFalse(p.shouldUnlock(t, -78, false, -86)); t += 500L }
    }

    @Test fun theReturnRequalifiesWithoutMotionAfterTheEvidenceExpired() {
        val p = locked()
        var t = 0L
        repeat(6) { assertFalse(p.shouldUnlock(t, -96, false, -86)); t += 1_000L }
        assertFalse(p.shouldUnlock(t, -80, false, -86)); t += 500L
        assertFalse(p.shouldUnlock(t, -80, false, -86)); t += 500L
        assertTrue(p.shouldUnlock(t, -80, false, -86)); t += 1_000L
        assertFalse(p.shouldUnlock(t, -90, false, -86)); t += 6_000L // evidence gone (FAR + TTL)
        assertTrue(p.shouldUnlock(t, -79, false, -86))
    }
}
