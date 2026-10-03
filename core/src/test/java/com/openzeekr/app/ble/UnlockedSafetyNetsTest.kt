package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockedSafetyNetsTest {
    private fun s(gen: Long, code: Int) = ObservedVehicleStatus(gen, code)

    @Test fun fieldShapeCarRelockedTwoMinutesAfterApproachUnlock() {
        // 03/10: stale 3 just before the Unlock receipt, 1 right after, car relocked 2 min later.
        // The "1" arrives ~20 ms BEFORE the Unlock receipt and must still count.
        val d = CarSelfLockDetector()
        assertEquals(CarSelfLockDetector.Event.NONE, d.observe(s(5, 3), 999_500L))          // before our Unlock: ignored
        assertEquals(CarSelfLockDetector.Event.NONE, d.observe(s(5, 1), 999_980L))
        d.onUnlockConfirmed(1_000_000L)
        assertEquals(CarSelfLockDetector.Event.SELF_LOCK, d.observe(s(5, 3), 1_122_000L))
        assertEquals("only once", CarSelfLockDetector.Event.NONE, d.observe(s(5, 3), 1_123_000L))
    }

    @Test fun staleLockStatusRightAfterUnlockOrWithoutTransitionIsIgnored() {
        val d = CarSelfLockDetector()
        d.onUnlockConfirmed(0L)
        assertEquals("no unlocked status seen yet", CarSelfLockDetector.Event.NONE, d.observe(s(1, 3), 5_000L))
        assertEquals("still no 1 -> 3", CarSelfLockDetector.Event.NONE, d.observe(s(1, 3), 9_000L))
        val e = CarSelfLockDetector()
        e.onUnlockConfirmed(0L)
        assertEquals(CarSelfLockDetector.Event.NONE, e.observe(s(1, 1), 100L))
        assertEquals("within 2 s of the Unlock", CarSelfLockDetector.Event.NONE, e.observe(s(1, 3), 1_500L))
    }

    @Test fun aNewSessionNeedsItsOwnTransition() {
        val d = CarSelfLockDetector()
        d.onUnlockConfirmed(0L)
        assertEquals(CarSelfLockDetector.Event.NONE, d.observe(s(1, 1), 3_000L))
        assertEquals("first status after reconnect", CarSelfLockDetector.Event.NONE, d.observe(s(2, 3), 60_000L))
    }

    @Test fun ourOwnLockIsNotASelfLock() {
        val d = CarSelfLockDetector()
        d.onUnlockConfirmed(0L)
        assertEquals(CarSelfLockDetector.Event.NONE, d.observe(s(1, 1), 3_000L))
        d.onLockedByUs()
        assertEquals(CarSelfLockDetector.Event.NONE, d.observe(s(1, 3), 30_000L))
    }

    @Test fun carReopenedAfterItsSelfLockReArms() {
        val d = CarSelfLockDetector()
        d.observe(s(1, 1), 0L); d.onUnlockConfirmed(10L)
        assertEquals(CarSelfLockDetector.Event.SELF_LOCK, d.observe(s(1, 3), 120_000L))
        assertEquals(CarSelfLockDetector.Event.REOPENED, d.observe(s(1, 1), 150_000L))
        assertEquals("once", CarSelfLockDetector.Event.NONE, d.observe(s(1, 3), 160_000L))
    }

    @Test fun carOpenedWhileTheLinkWasDownReArmsOnTheNextSession() {
        val d = CarSelfLockDetector()
        d.observe(s(1, 1), 0L); d.onUnlockConfirmed(10L)
        assertEquals(CarSelfLockDetector.Event.SELF_LOCK, d.observe(s(1, 3), 120_000L))
        assertEquals(CarSelfLockDetector.Event.REOPENED, d.observe(s(2, 1), 300_000L))
    }

    @Test fun noReopenEventWithoutAPriorSelfLock() {
        val d = CarSelfLockDetector()
        d.observe(s(1, 3), 0L)
        assertEquals(CarSelfLockDetector.Event.NONE, d.observe(s(1, 1), 1_000L))
    }

    @Test fun stationaryFarAlarmsOnceAfterAMinuteOfConsistentlyDeepReadings() {
        val w = StationaryFarWatch()
        val lock = -82
        for (t in 0L..57_000L step 3_000L) assertFalse(w.observe(t, -90, lock))
        assertTrue(w.observe(60_000L, -91, lock))
        assertFalse("once per unlock", w.observe(90_000L, -95, lock))
    }

    @Test fun anyReadingThatIsNotClearlyFarRestartsTheClock() {
        val w = StationaryFarWatch()
        val lock = -82
        for (t in 0L..30_000L step 3_000L) w.observe(t, -90, lock)
        w.observe(33_000L, -85, lock)        // not clearly far: restart
        assertEquals(0L, w.deepFarForMs(33_000L))
        for (t in 36_000L..93_000L step 3_000L) assertFalse(w.observe(t, -90, lock))
        assertTrue(w.observe(96_000L, -90, lock))
    }

    @Test fun oneDipBeforeALinkLossIsNotASeparation() {
        // Review 0.1.60: a single -89 at the car, then the car's security sleep drops the link.
        val w = StationaryFarWatch()
        w.observe(0L, -89, -82)
        assertEquals(0L, w.deepFarForMs(30_000L))
        w.onReadingsStopped()
        assertEquals(0L, w.deepFarForMs(60_000L))
    }

    @Test fun nearTheCarBodyShadowNeverReachesTheDeepBand() {
        // At the door in a pocket the field showed -78..-86 (lock -82): never <= -88.
        val w = StationaryFarWatch()
        for (t in 0L..300_000L step 3_000L) assertFalse(w.observe(t, if (t % 2 == 0L) -86 else -80, -82))
    }

    @Test fun measurementModeIsTimeBounded() {
        assertTrue(MeasurementMode.active(10_000L, 9_999L))
        assertFalse(MeasurementMode.active(10_000L, 10_000L))
        assertEquals(300_000L, MeasurementMode.DURATION_MS)
    }
}
