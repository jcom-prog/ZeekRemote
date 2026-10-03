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
        assertFalse(d.observe(s(5, 3), 999_500L))          // before our Unlock: ignored
        assertFalse(d.observe(s(5, 1), 999_980L))
        d.onUnlockConfirmed(1_000_000L)
        assertTrue(d.observe(s(5, 3), 1_122_000L))
        assertFalse("only once", d.observe(s(5, 3), 1_123_000L))
    }

    @Test fun staleLockStatusRightAfterUnlockOrWithoutTransitionIsIgnored() {
        val d = CarSelfLockDetector()
        d.onUnlockConfirmed(0L)
        assertFalse("no unlocked status seen yet", d.observe(s(1, 3), 5_000L))
        assertFalse("still no 1 -> 3", d.observe(s(1, 3), 9_000L))
        val e = CarSelfLockDetector()
        e.onUnlockConfirmed(0L)
        assertFalse(e.observe(s(1, 1), 100L))
        assertFalse("within 2 s of the Unlock", e.observe(s(1, 3), 1_500L))
    }

    @Test fun aNewSessionNeedsItsOwnTransition() {
        val d = CarSelfLockDetector()
        d.onUnlockConfirmed(0L)
        assertFalse(d.observe(s(1, 1), 3_000L))
        assertFalse("first status after reconnect", d.observe(s(2, 3), 60_000L))
    }

    @Test fun ourOwnLockIsNotASelfLock() {
        val d = CarSelfLockDetector()
        d.onUnlockConfirmed(0L)
        assertFalse(d.observe(s(1, 1), 3_000L))
        d.onLockedByUs()
        assertFalse(d.observe(s(1, 3), 30_000L))
    }

    @Test fun stationaryFarAlarmsOnceAfterAMinuteClearlyBeyondTheLockThreshold() {
        val w = StationaryFarWatch()
        val lock = -82
        assertFalse(w.observe(0L, -89, lock))
        assertFalse(w.observe(59_000L, -90, lock))
        assertTrue(w.observe(60_000L, -91, lock))
        assertFalse("once per unlock", w.observe(90_000L, -95, lock))
    }

    @Test fun aReadingInsideTheLockThresholdRestartsAndTheBandBetweenHolds() {
        val w = StationaryFarWatch()
        val lock = -82
        w.observe(0L, -90, lock)
        w.observe(30_000L, -85, lock)        // between lock-6 and lock: keeps the clock
        assertEquals(30_000L, w.deepFarForMs(30_000L))
        w.observe(40_000L, -80, lock)        // back inside: restart
        assertEquals(0L, w.deepFarForMs(40_000L))
        assertFalse(w.observe(95_000L, -90, lock))
        assertTrue(w.observe(155_000L, -90, lock))
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
