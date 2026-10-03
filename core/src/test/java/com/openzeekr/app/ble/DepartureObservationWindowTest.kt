package com.openzeekr.app.ble

import org.junit.Assert.*
import org.junit.Test

class DepartureObservationWindowTest {
    private val car = DepartureFix(51.0, 5.0, 3f, 10_000L)
    private fun far(time: Long, accuracy: Float = 3f) = DepartureFix(51.0003, 5.0, accuracy, time)
    private fun near(time: Long) = car.copy(elapsedAtMs = time)

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
    @Test fun staleDeliveredFixDoesNotAuthorizeLock() {
        val window = DepartureObservationWindow(car, 20_000L)
        assertFalse(window.observe(far(20_000L), 26, 22_000L))
        assertEquals("departure_fix_stale", window.outcome)
    }
}
