package com.openzeekr.app.ble

import org.junit.Assert.*
import org.junit.Test

class DepartureObservationWindowTest {
    private val car = DepartureFix(51.0, 5.0, 3f, 1_000L)
    private fun far(time: Long, accuracy: Float = 3f) = DepartureFix(51.0003, 5.0, accuracy, time)
    private fun near(time: Long) = car.copy(elapsedAtMs = time)
    private fun metres(distanceM: Double, time: Long, accuracy: Float = 4f) =
        DepartureFix(car.latitude + distanceM / 111_000.0, car.longitude, accuracy, time)
    private fun known(distanceM: Double, time: Long, steps: Long?) =
        DepartureFixMemory(car, 0L).also { it.learn(metres(distanceM, time), steps) }

    @Test fun poorFirstFixDoesNotTerminateLaterValidDeparture() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(20_000L, 20f), 26, 20_000L))
        assertEquals("accuracy_over_eight_meters", window.outcome)
        assertFalse(window.observe(far(21_000L), 26, 21_000L))
        assertTrue(window.observe(far(23_000L), 26, 23_000L))
    }
    @Test fun continuouslyInaccurateFixesNeverAuthorizeLock() {
        val window = DepartureObservationWindow(car, 20_000L)
        for (time in 20_000L..44_000L step 1_000L)
            assertFalse(window.observe(far(time, 20f), 26, time))
        assertFalse(window.observe(far(45_000L), 26, 45_000L))
        assertEquals("observation_expired", window.outcome)
    }
    @Test fun circlingNearCarWithManyStepsDoesNotAuthorizeLock() {
        val window = DepartureObservationWindow(car, 20_000L)
        for (time in 20_000L..44_000L step 2_000L)
            assertFalse(window.observe(near(time), 100, time))
        assertEquals("departure_clearance_insufficient", window.outcome)
    }
    @Test fun singleFarFixAndReturnDoNotAuthorizeLock() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(20_000L), 26, 20_000L))
        assertFalse(window.observe(near(22_000L), 26, 22_000L))
        // 33 m back in 2 s is faster than a walk: since 0.1.63 a position jump (quarantined).
        assertEquals("departure_position_jump", window.outcome)
    }
    @Test fun duplicateFutureCachedAndReversedTimeCannotBuildPair() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(19_999L), 26, 20_000L))
        assertFalse(window.observe(far(21_000L), 26, 20_000L))
        assertFalse(window.observe(far(20_000L), 26, 20_000L))
        assertFalse(window.observe(far(20_000L), 26, 20_500L))
        assertFalse(window.observe(far(19_000L), 26, 20_500L))
    }
    @Test fun largeGapStartsNewPairInsteadOfUsingOldPosition() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(20_000L), 26, 20_000L))
        assertFalse(window.observe(far(30_000L), 26, 30_000L))
        assertTrue(window.observe(far(32_000L), 26, 32_000L))
    }
    @Test fun badFixBetweenGoodFixesResetsPair() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(20_000L), 26, 20_000L))
        assertFalse(window.observe(null, 26, 21_000L))
        assertFalse(window.observe(far(22_000L), 26, 22_000L))
        assertTrue(window.observe(far(24_000L), 26, 24_000L))
    }
    @Test fun noStepsAndOneSecondPairCannotAuthorizeLock() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(20_000L), null, 20_000L))
        assertFalse(window.observe(far(21_000L), 26, 21_000L))
        assertFalse(window.observe(far(22_000L), 5, 22_000L))
        assertEquals("departure_steps_insufficient", window.outcome)
        assertTrue(window.observe(far(24_000L), 26, 24_000L))
    }
    @Test fun aJumpAlreadyActiveAtTheFirstFixOfAMotionlessPhoneIsQuarantined() {
        // Simulator F49 (22/10): the phone lay still beside the car - no motion and no step since the previous window
        // last saw it 4 m from the car, 18 steps from the car - and the next window's first fix read 24 m from it: a
        // jump that straddled two windows; its pair locked the car at 4 m. Nothing moved: quarantine, no confirmation.
        val memory = known(4.0, 15_000L, 18)
        val window = DepartureObservationWindow(car, 20_000L, memory)
        assertFalse(window.observe(metres(24.0, 20_000L), 18, 20_000L, lastMotionAtMs = 14_000L))
        assertEquals("departure_fix_implausible", window.outcome)
        assertFalse(window.observe(metres(24.5, 21_500L), 18, 21_500L, lastMotionAtMs = 14_000L))
        assertEquals("departure_position_jump", window.outcome)
        // The jump ends during the quarantine; after it the fixes sit where the phone is: nothing may confirm.
        for (time in 31_000L..44_000L step 2_000L)
            assertFalse(window.observe(metres(4.5, time), 18, time, lastMotionAtMs = 14_000L))
        assertEquals("departure_clearance_insufficient", window.outcome)
    }
    @Test fun aWalkingPhoneIsHeldToWalkingSpeedFromTheLastKnownPosition() {
        // The phone has moved since the previous window saw it 5 m from the car: 20 m further 7 s later is a walk,
        // and the pair confirms the departure; 45 m further in 7 s is not.
        val window = DepartureObservationWindow(car, 20_000L, known(5.0, 13_000L, 6))
        assertFalse(window.observe(metres(25.0, 20_000L), 14, 20_000L, lastMotionAtMs = 20_000L))
        assertEquals("waiting_for_pair", window.outcome)
        assertTrue(window.observe(metres(27.5, 22_000L), 16, 22_000L, lastMotionAtMs = 22_000L))
        val jumped = DepartureObservationWindow(car, 20_000L, known(5.0, 13_000L, 6))
        assertFalse(jumped.observe(metres(50.0, 20_000L), 14, 20_000L, lastMotionAtMs = 20_000L))
        assertEquals("departure_fix_implausible", jumped.outcome)
    }
    @Test fun aStepOrAMotionSinceTheKnownPositionLiftsTheStandstillBound() {
        // Steps advanced (18 -> 24) since the known position: the walking-speed bound applies, not the standstill.
        val stepped = DepartureObservationWindow(car, 20_000L, known(4.0, 15_000L, 18))
        assertFalse(stepped.observe(metres(16.0, 20_000L), 24, 20_000L, lastMotionAtMs = 14_000L))
        assertEquals("waiting_for_pair", stepped.outcome)
        // Motion reported after it with the count unchanged (a counter that holds its steps): the same.
        val moved = DepartureObservationWindow(car, 20_000L, known(4.0, 15_000L, 18))
        assertFalse(moved.observe(metres(16.0, 20_000L), 18, 20_000L, lastMotionAtMs = 16_000L))
        assertEquals("waiting_for_pair", moved.outcome)
        // Without a step source the standstill cannot be asserted either.
        val noSteps = DepartureObservationWindow(car, 20_000L, DepartureFixMemory(car, null))
        assertFalse(noSteps.observe(metres(16.0, 20_000L), null, 20_000L, lastMotionAtMs = 5_000L))
        assertEquals("waiting_for_pair", noSteps.outcome)
    }
    @Test fun theStandstillBoundIsAskedOncePerWindow() {
        // After its quarantine the window goes on with the pair: a motion stack that misses a walk (S24+ screen off,
        // count frozen) costs one delay, never the proof - and a real jump has ended by then.
        val window = DepartureObservationWindow(car, 20_000L, known(4.0, 15_000L, 18))
        assertFalse(window.observe(metres(24.0, 20_000L), 18, 20_000L, lastMotionAtMs = 14_000L))
        assertEquals("departure_fix_implausible", window.outcome)
        assertFalse(window.observe(metres(26.0, 31_000L), 18, 31_000L, lastMotionAtMs = 14_000L))
        assertEquals("waiting_for_pair", window.outcome)
        assertTrue(window.observe(metres(28.0, 33_000L), 18, 33_000L, lastMotionAtMs = 14_000L))
    }
    @Test fun withoutAMemoryTheAnchorIsTheKnownPosition() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(metres(80.0, 20_000L), 26, 20_000L))
        assertEquals("departure_fix_implausible", window.outcome)
    }
    @Test fun theWindowTeachesTheMemoryItsLastFixOutsideAJump() {
        val memory = DepartureFixMemory(car, 0L)
        val window = DepartureObservationWindow(car, 20_000L, memory)
        assertFalse(window.observe(metres(3.0, 20_000L), 2, 20_000L))
        assertFalse(window.observe(metres(30.0, 21_000L), 2, 21_000L))
        assertEquals("departure_position_jump", window.outcome)
        assertEquals(3.0, DepartureSafetyEvidence.distanceM(car, memory.lastKnown), 0.1)
        assertEquals(2L, memory.stepsAtLastKnown)
    }
    @Test fun staleDeliveredFixDoesNotAuthorizeLock() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(20_000L), 26, 22_000L))
        assertEquals("departure_fix_stale", window.outcome)
    }
}
