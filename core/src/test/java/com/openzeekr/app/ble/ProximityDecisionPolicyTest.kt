package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // The 4 s at -98 above is a proven clear departure, so one -82 reading still suffices.
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
        assertFalse(policy.shouldUnlock(23_900L, -81, true, -86))
        assertFalse(policy.shouldUnlock(24_100L, -79, true, -86))
        assertTrue(policy.shouldUnlock(24_300L, -77, true, -86))
    }

    @Test
    fun manualLockStillPauseSurvivesMildReboundWhileStanding() {
        // Field shape 0.1.55 (~20 m walk): FAR while walking away, a 13 s stop at
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
        assertFalse(policy.shouldUnlock(37_400L, -80, true, -86))
        assertTrue(policy.shouldUnlock(37_600L, -78, true, -86))
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
        assertFalse(policy.shouldUnlock(8_600L, -78, true, -86))
        assertTrue(policy.shouldUnlock(8_800L, -76, true, -86))
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

        // 0.2.6: noise just over the Far threshold right after the connect no longer opens (field
        // test, ~25 m away); two readings 4 dB inside it do, without any further wait.
        assertFalse(policy.shouldUnlock(100L, -86, true, -86))
        assertFalse(policy.shouldUnlock(300L, -86, true, -86))
        assertFalse(policy.shouldUnlock(500L, -82, true, -86))
        assertTrue("qualified cold arrival must not wait for an extra 4 dB after handshake",
            policy.shouldUnlock(700L, -82, true, -86))
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

        // Field incident 0.1.34 (aborted arrival): the only offloaded presence callback arrived at
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

        // Synthetic series with the course of field incident 0.1.31 after CTRL_UNLOCK was confirmed.
        // The user never reached the strong-near arrival band and immediately walked away. 0.1.31
        // discarded this evidence during its 15 s grace and later could no longer lock over BLE.
        val turnaround = listOf(
            -83, -85, -82, -80, -79, -81, -82, -82, -80, -80, -79, -80,
            -83, -86, -86, -88, -88, -88, -89, -90, -91, -91,
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

        // Synthetic series with the course of field incident 0.1.22. Short recoveries to -80/-81 used to
        // erase the entire walk-away timer, and the final rebound from -93 to -84 prevented the
        // required current-vs-start 3 dB drop. The weakest observed sample now preserves direction.
        var decision = ProximityDecisionPolicy.ArmedDecision.NONE
        listOf(-73, -74, -75, -77, -79, -80, -82, -82, -80, -80, -80, -83,
            -83, -84, -85, -88, -91, -91, -93, -90, -87, -84).forEach { rssi ->
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
        // 0.2.6: two readings 4 dB inside the Far threshold (-82 and better).
        assertFalse(policy.shouldUnlock(now + 600, -82, true, -86))
        assertTrue(policy.shouldUnlock(now + 800, -81, true, -86))
    }

    @Test
    fun captured032QuickReturnRearmsWithoutLinkLoss() {
        val policy = ProximityDecisionPolicy()
        var now = 0L
        policy.onDepartureLockStarted()

        // The car has just locked. A real departure remains continuously FAR long enough to prove
        // that the previous arrival ended, even though Android keeps the authenticated GATT link.
        // Field incident 0.1.32 (synthetic series): the retained link remained deep FAR after lock.
        listOf(-100, -99, -99, -100, -101, -101, -101, -102, -101, -102,
            -102, -103, -102, -104, -103, -103).forEach { rssi ->
            assertFalse(policy.shouldUnlock(now, rssi, true, -86))
            now += 210
        }

        // The 0.1.32 return (no response) begins only once the retained connection is already
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
        // Field shape 0.1.60 (car self-lock): away to ~15 m and back, Activity
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
        // Field shape 0.1.63 (car self-lock): back from ~25 m on the 1 s linked
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

    @Test fun manualLockThenTurningRoundAtTenMetresStaysLocked() {
        // Field shape 0.1.64: manual Lock at the door, walk to ~10 m with the
        // body between phone and car (down to -97 for ~2 s), stand (STILL), turn round: -82/-81.
        val p = locked()
        var t = 0L
        repeat(60) { assertFalse(p.shouldUnlock(t, -60 - it % 15, true, -86)); t += 200L }      // at the car
        listOf(-81, -85, -87, -83, -84, -88, -88, -87, -88, -86, -89, -90, -92, -93, -93, -96, -96, -96, -97,
            -97, -97, -97, -96, -91, -88, -87, -88, -87, -86, -84, -84, -87, -85, -85, -86, -84, -87, -86, -86,
            -83, -83, -85, -88, -89, -90, -91, -91, -91, -91, -90, -91)
            .forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }
        listOf(-93, -90, -90).forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L } // STILL
        listOf(-86, -82, -81, -81, -81, -83, -82, -84, -83, -82, -81)
            .forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }               // turning at 10 m
    }

    @Test fun manualLockWalkToTenMetresAndBackToTheDoorOpensAtTheCar() {
        // Field shape 0.1.65: Lock, 50 s at the car, walk to ~10 m (-98 behind
        // the body), turning round there (Activity Recognition: 0.3 s STILL), back to the door.
        val p = locked()
        var t = 0L
        listOf(-62, -66, -70, -75, -80, -78, -64, -68, -72, -76, -81, -79, -70, -66, -82, -84, -88, -90)
            .forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L }      // at the car, STILL
        listOf(-98, -98, -96, -97, -97, -97, -97, -96, -96, -92, -90, -88, -88, -89, -86, -85, -85, -83, -84,
            -85, -86, -84, -85, -86, -86, -84, -86, -86, -86, -88, -88, -87, -85, -87, -87, -86, -85, -86, -86)
            .forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }          // to 10 m, turning
        assertFalse(p.shouldUnlock(t, -86, false, -86)); t += 300L                          // 0.3 s STILL
        listOf(-80, -80, -78, -83, -84, -83, -87, -89, -88, -89, -86, -82, -84, -80, -81, -84, -81, -79,
            -75, -74, -77, -76, -78, -81, -79)
            .forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }          // walking back
        var openedAt = -1
        listOf(-72, -67, -63, -62, -62).forEachIndexed { i, r -> if (p.shouldUnlock(t, r, true, -86) && openedAt < 0) openedAt = i; t += 200L }
        assertTrue("back at the door after a manual Lock must open", openedAt in 0..2)
    }

    @Test fun manualLockBackAtTheCarWhileTheSensorSaysStillOpensAfterALongerRun() {
        // Activity Recognition can miss the short walk back: STILL all the way to the door.
        val p = locked()
        var t = 0L
        repeat(20) { assertFalse(p.shouldUnlock(t, -66, false, -86)); t += 1_000L }
        listOf(-90, -93, -96, -98, -97, -95, -92, -90, -88, -87, -89, -88)
            .forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 1_000L }
        listOf(-84, -80, -76, -71, -70, -70).forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 200L }
        assertTrue(p.shouldUnlock(t, -69, false, -86))
    }

    @Test fun manualLockStandingAtTheCarWithAShortBodyShadowStaysLocked() {
        // At the car the pocketed phone can read -84..-88 for a moment; no clear walk away: stays locked.
        val p = locked()
        var t = 0L
        repeat(3) {
            listOf(-62, -70, -84, -86, -87, -88, -87, -86, -86, -87, -88, -86, -86, -70, -64, -62, -61)
                .forEach { r -> assertFalse(p.shouldUnlock(t, r, true, -86)); t += 200L }
        }
    }

    @Test fun manualLockShortWalkThenBackToTheCarStillOpens() {
        // The motion-gated route still opens once the user is clearly back at the car.
        val p = locked()
        var t = 0L
        repeat(15) { assertFalse(p.shouldUnlock(t, -88 - it % 3, true, -86)); t += 200L }
        repeat(3) { assertFalse(p.shouldUnlock(t, -90, false, -86)); t += 1_000L }
        var opened = false
        listOf(-86, -84, -82, -80, -78, -76, -74, -72, -70).forEach { if (p.shouldUnlock(t, it, true, -86)) opened = true; t += 300L }
        assertTrue(opened)
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

    // ---- 0.3.2: unlock-safety rules from the simulation audit ----

    @Test fun aPresenceEntryAlreadyAtTheBandNeedsTheDoorLevelWhileTheStepsShowStanding() {
        // A door ~25 m away (simulator F24, field): the entry reads -84, readings of -82
        // used to open the car. Standing (live step count stopped): -78 needed.
        val standing = ProximityDecisionPolicy()
        standing.onPresenceMatch(0L, -84, true, -86)
        assertFalse((0 until 10).any { standing.shouldUnlock(200L * (it + 1), -82, true, -86, walkingConfirmed = false) })
        assertTrue((10 until 14).any { standing.shouldUnlock(200L * (it + 1), -78, true, -86, walkingConfirmed = false) })
        // Walking up (field 0.1.28 / 0.1.34 arrivals) or no live step count: -82 as before.
        val walking = ProximityDecisionPolicy()
        walking.onPresenceMatch(0L, -84, true, -86)
        assertTrue((0 until 4).any { walking.shouldUnlock(200L * (it + 1), -82, true, -86) })
    }

    @Test fun aStoppedStepCountPausesTheWalkingApproachRoute() {
        // Simulator F13/F31: the walk stops at 10 m, Activity Recognition still says MOVING.
        val stopped = ProximityDecisionPolicy()
        var t = 0L
        repeat(15) { stopped.shouldUnlock(t, -93, true, -86); t += 200L }
        assertFalse((0 until 10).any { stopped.shouldUnlock(t + 200L * it, -84, true, -86, walkingConfirmed = false) })
        val walking = ProximityDecisionPolicy()
        t = 0L
        repeat(15) { walking.shouldUnlock(t, -93, true, -86); t += 200L }
        assertTrue((0 until 10).any { walking.shouldUnlock(t + 200L * it, -84, true, -86, walkingConfirmed = true) })
    }

    @Test fun theStillRouteClosesTenSecondsAfterTheStepsStopped() {
        // Simulator F14: walked up with AR silent, then waited at 8 m; the old rise kept the route open.
        fun opens(stallAgeMs: Long): Boolean {
            val p = ProximityDecisionPolicy()
            var t = 0L
            repeat(15) { p.shouldUnlock(t, -95, false, -86); t += 200L }
            return (0 until 25).any { p.shouldUnlock(t + 200L * it, -80, false, -86, stepStallAgeMs = stallAgeMs) }
        }
        assertTrue("a stop at the door still opens", opens(3_000L))
        assertFalse("a wait half a minute later does not", opens(30_000L))
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
        // 0.3.12: the walk back rises out of the deep-far level for 3 s (a jump straight to -80 is a peak).
        assertFalse(p.shouldUnlock(t, -88, false, -86)); t += 1_000L
        assertFalse(p.shouldUnlock(t, -84, false, -86)); t += 1_000L
        assertFalse(p.shouldUnlock(t, -80, false, -86)); t += 500L
        assertFalse(p.shouldUnlock(t, -80, false, -86)); t += 500L
        assertTrue(p.shouldUnlock(t, -80, false, -86)); t += 1_000L
        assertFalse(p.shouldUnlock(t, -90, false, -86)); t += 6_000L // evidence gone (FAR + TTL)
        assertTrue(p.shouldUnlock(t, -79, false, -86))
    }

    /**
     * Field shape (0.2.1): BLE departure Lock confirmed at t=800 ms,
     * walking on indoors (-92..-97 for ~9 s, then about -89), a ~1.5 s peak while moving (smoothed
     * -86..-81 from ~15 m) crossed the old arrival edge and reopened the car behind the user.
     * Smoothed readings, 200 ms apart, as (rssi, moving); a far-sleep gap and a 1 s STILL as in the field.
     */
    private fun home2206AfterAutoLock(): List<Triple<Long, Int, Boolean>> {
        val out = mutableListOf<Triple<Long, Int, Boolean>>()
        var t = 800L
        fun add(vararg rssi: Int, moving: Boolean = true) = rssi.forEach { out += Triple(t, it, moving); t += 200L }
        add(-92, -90, -89, -88, -88, -88, -87, -88, -89, -89, -91, -91, -90, -89, -88, -86, -85, -85)
        t += 5_000L                                                       // far-sleep
        add(-90, -92, -92, -92, -92, -93, -94, -94, -93, -93, -94, -94, -94, -93, -94)
        repeat(30) { add(listOf(-96, -97, -97, -96, -95)[it % 5]) }
        add(-93, -91, -89, -89, -90, -90, -91, -91, -89, -89, -89, -89, -89, -88, -89)
        add(-89, moving = false); t += 800L                               // a 1 s STILL
        add(-89, -89, -89, -88, -88, -88, -89, -90, -90, -90, -90, -88)
        add(-86, -85, -84, -83, -84, -81, -81, -82, -83, -85, -88)        // the peak
        add(-89, -92, -94, -92, -90, -90, -92, -93, -93, -94, -95, -96, -96, -96, -97, -97, -98, -98, -99)
        return out
    }

    @Test fun field2206APeakWhileWalkingAwayAfterAnAutomaticLockDoesNotReopen() {
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(-450_000L)          // 21:58 "car reopened" -> departure watch
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(800L)
        home2206AfterAutoLock().forEach { (t, rssi, moving) ->
            assertFalse("reopened at $t ms ($rssi)", p.shouldUnlock(t, rssi, moving, -86))
        }
    }

    @Test fun field2206AfterTheAutomaticLockARealWalkBackStillReopensBeforeTheDoor() {
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(-450_000L)
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(800L)
        home2206AfterAutoLock().forEach { (t, rssi, moving) -> p.shouldUnlock(t, rssi, moving, -86) }
        var t = home2206AfterAutoLock().last().first + 200L
        var openedAt = Int.MIN_VALUE
        // Walking back outdoors: about 1.5 dB per 200 ms reading, to the door at -62.
        listOf(-97, -95, -93, -91, -89, -87, -85, -83, -82, -81, -80, -79, -77, -75, -72, -68, -65, -62).forEach {
            if (openedAt == Int.MIN_VALUE && p.shouldUnlock(t, it, true, -86)) openedAt = it
            t += 200L
        }
        // The deep-far return: about a second at >= -82, i.e. well before the door (-62).
        assertTrue("opened only at $openedAt", openedAt in -82..-73)
    }

    @Test fun afterAnAutomaticLockAPeakFromTenMetresNeverReopensButTheCarItselfDoes() {
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(0L)
        p.onDepartureLockStarted(); p.onAutomaticLockProvenDeparture(1_000L)
        var t = 1_200L
        // Walking away clearly out of the unlock band.
        repeat(20) { assertFalse(p.shouldUnlock(t, -93, true, -86)); t += 200L }
        // Repeated short peaks to -80 while moving (indoors, a door or window, ~10-15 m).
        repeat(5) {
            listOf(-86, -84, -81, -80, -83, -88, -90, -90, -90, -90).forEach { assertFalse(p.shouldUnlock(t, it, true, -86)); t += 200L }
        }
        // Two readings at the car reopen.
        assertFalse(p.shouldUnlock(t, -70, true, -86)); t += 200L
        assertTrue(p.shouldUnlock(t, -69, true, -86))
    }

    // ---- 0.2.6: the car locked itself soon after our approach unlock ----

    private fun selfLockedAt(rssi: Int): ProximityDecisionPolicy {
        val policy = ProximityDecisionPolicy()
        policy.onManualLockConfirmed()
        policy.armSelfLockReopen(0L, rssi)
        return policy
    }

    @Test
    fun selfLockAtTheGateReopensOnceWhenWalkingUpToTheDoor() {
        // Synthetic shape of field: unlocked, the car relocked while the user
        // was busy at the gate ~4 m away (back pocket, ~-84), then walked to the door (~-76).
        val policy = selfLockedAt(-84)
        for (time in 200L..60_000L step 200L) {
            val rssi = if ((time / 200) % 3 == 0L) -81 else -85 // fidgeting at the gate
            assertFalse("gate $time", policy.shouldUnlock(time, rssi, true, -86))
        }
        assertFalse(policy.shouldUnlock(60_200L, -77, true, -86))
        assertTrue(policy.shouldUnlock(60_400L, -76, true, -86))
        assertEquals("self_lock_return", policy.unlockRoute)
    }

    @Test
    fun selfLockWhileSittingInTheCarStaysLocked() {
        val policy = selfLockedAt(-48)
        for (time in 200L..120_000L step 200L) {
            val rssi = -50 + ((time / 200) % 5).toInt() // -50..-46
            assertFalse("seat $time", policy.shouldUnlock(time, rssi, (time / 1_000) % 2 == 0L, -86))
        }
    }

    @Test
    fun selfLockReopenNeedsMotion() {
        val policy = selfLockedAt(-84)
        for (time in 200L..20_000L step 200L) {
            assertFalse(policy.shouldUnlock(time, -74, false, -86))
        }
    }

    // ---- plan H2 (0.3.1): back from a walk-away Lock with the phone in a back pocket ----

    private fun lockedAndGoneFar(stepsAtLoss: Long?): ProximityDecisionPolicy = ProximityDecisionPolicy().apply {
        onDepartureLockStarted()                      // automatic Lock on the way out
        onLinkEnded()                                 // the link dropped ...
        onFarLinkLossWhileLocked(stepsAtLoss)         // ... clearly far from the car
    }

    @Test fun backPocketReturnAfterAFarLinkLossOpensWhenTheStepsShowTheWalkBack() {
        // At the door in a back pocket: -79, never strong-near; the motion sensor still says STILL.
        val withSteps = lockedAndGoneFar(stepsAtLoss = 100L)
        assertTrue((0 until 8).any { i -> withSteps.shouldUnlock(60_000L + i * 1_000L, -79, false, -86, 140L) })
        // 0.3.0 behaviour: without the step counter this return never opened the car.
        val withoutSteps = lockedAndGoneFar(stepsAtLoss = null)
        assertFalse((0 until 8).any { i -> withoutSteps.shouldUnlock(60_000L + i * 1_000L, -79, false, -86, null) })
    }

    @Test fun theStepReturnNeedsTheWalkAndADoorLevelSignal() {
        // A few steps at the edge of range (no walk back), door-level readings: stays locked.
        val shuffled = lockedAndGoneFar(stepsAtLoss = 100L)
        assertFalse((0 until 8).any { i -> shuffled.shouldUnlock(60_000L + i * 1_000L, -79, false, -86, 106L) })
        // Walked, but the signal is a 10 m signal (-88): stays locked.
        val tenMetres = lockedAndGoneFar(stepsAtLoss = 100L)
        assertFalse((0 until 8).any { i -> tenMetres.shouldUnlock(60_000L + i * 1_000L, -88, false, -86, 140L) })
    }

    // ---- 0.3.2: a walking approach that sits at the edge of the approach band ----

    /** Walks in from a FAR baseline (-93 for 3 s) and returns the index of the reading that qualified. */
    private fun approachQualifiesAt(
        mode: ProximityDecisionPolicy.ApproachConfirm, readings: List<Int>, moving: Boolean = true,
    ): Int? {
        val policy = ProximityDecisionPolicy(mode)
        var now = 0L
        repeat(15) { assertFalse(policy.shouldUnlock(now, -93, true, -86)); now += 200 }
        readings.forEachIndexed { i, rssi ->
            if (policy.shouldUnlock(now, rssi, moving, -86)) return i
            now += 200
        }
        return null
    }

    @Test fun anApproachAtTheBandEdgeOpensOnTwoReadingsInsteadOfThreeInARow() {
        // Synthetic: the smoothed signal touches the band (-85) between 1 dB dips, then rises late.
        val walk = listOf(-86, -85, -86, -85, -86, -87, -84, -84, -80)
        assertEquals(8, approachQualifiesAt(ProximityDecisionPolicy.ApproachConfirm.UNBROKEN, walk))
        assertEquals(7, approachQualifiesAt(ProximityDecisionPolicy.ApproachConfirm.TWO_IN_A_ROW, walk))
        assertEquals(3, approachQualifiesAt(ProximityDecisionPolicy.ApproachConfirm.TWO_OF_THREE, walk))
    }

    @Test fun singleBandEdgeReadingsAndFarReadingsInBetweenNeverOpen() {
        // One hit at a time, and a FAR reading (-88) between two hits: no mode opens.
        val noise = listOf(-86, -85, -87, -86, -87, -85, -88, -85, -86, -86, -87, -85, -87, -88, -85, -88)
        ProximityDecisionPolicy.ApproachConfirm.values().forEach { mode ->
            assertNull(mode.name, approachQualifiesAt(mode, noise))
        }
    }

    @Test fun theTwoReadingApproachStillNeedsTheWalk() {
        // Standing at 10 m with the signal at the band: the walking route never applies.
        val standing = List(20) { -84 }
        assertNull(approachQualifiesAt(ProximityDecisionPolicy.ApproachConfirm.TWO_OF_THREE, standing, moving = false))
        assertNull(approachQualifiesAt(ProximityDecisionPolicy.ApproachConfirm.TWO_IN_A_ROW, standing, moving = false))
    }

    @Test fun twoHitsMustBeSeparateReadings() {
        val policy = ProximityDecisionPolicy(ProximityDecisionPolicy.ApproachConfirm.TWO_OF_THREE)
        var now = 0L
        repeat(15) { policy.shouldUnlock(now, -93, true, -86); now += 200 }
        assertFalse(policy.shouldUnlock(now, -85, true, -86))
        assertFalse(policy.shouldUnlock(now + 50, -85, true, -86))   // the same moment seen twice
        assertTrue(policy.shouldUnlock(now + 250, -85, true, -86))
    }

    // ---- 0.3.2: walk-back return after a Lock (simulation audit B) ----

    /**
     * An automatic Lock 20 steps from the car (stepsAtCar 1 000), on to 60 steps out and straight back,
     * one step and one smoothed reading every 500 ms; the level falls 0.3 dB per step from [atCarDb].
     * The step total misses the first [warmUp] steps of the walk (a hub counter). Returns "steps from
     * the car, route" of the first unlock on the way back, or null.
     */
    private fun walkBackOpensAt(atCarDb: Double, withSteps: Boolean = true, warmUp: Long = 10L): String? {
        val p = ProximityDecisionPolicy()
        p.onUnlockConfirmed(0L)
        p.onDepartureLockStarted(stepsAtCar = 1_000L); p.onAutomaticLockProvenDeparture(10_000L)
        var t = 14_000L
        for (walked in 20L..120L) {
            val pos = if (walked <= 60L) walked else 120L - walked
            val rssi = (atCarDb - 0.3 * pos).toInt()
            val counted = 1_000L + (walked - warmUp).coerceAtLeast(0L)
            if (p.shouldUnlock(t, rssi, true, -86, if (withSteps) counted else null, stepsRising = withSteps))
                return "${if (walked > 60L) pos else -pos} ${p.unlockRoute}"
            t += 500L
        }
        return null
    }

    @Test fun aWalkBackOpensAtTheApproachLevelOnceMostOfTheWayIsWalked() {
        // A front pocket: -85 from 17 steps out, -82 from 7 steps out (where the deep return opens).
        assertEquals("9 walk_back", walkBackOpensAt(-80.0))
    }

    @Test fun aStrongPhoneAtTheApproachLevelFarOutWaitsForTheDeepReturn() {
        // -85 already 37 steps out on the way back (simulator stress: strong phones opened at 16-29 m).
        // The odometry holds the walk-back return; the deep return opens as before.
        assertEquals("27 deep_return", walkBackOpensAt(-74.0))
    }

    @Test fun aCounterThatMissedTheStartOfTheWalkDoesNotOpenHalfWayBack() {
        // Simulator stress F29: a hand-held strong phone back from 35 m to 20 m; the counter had missed
        // the first steps of the walk, so 21 steps back looked like 66 % of 32 steps out.
        assertEquals("27 deep_return", walkBackOpensAt(-74.0, warmUp = 14L))
    }

    @Test fun withoutCountedStepsOnlyTheDeepReturnOpens() {
        assertEquals("7 deep_return", walkBackOpensAt(-80.0, withSteps = false))
    }
}
