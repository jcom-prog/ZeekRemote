package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySleepPolicyTest {
    private fun still(ms: Long): (Long) -> Boolean = { it <= ms }

    @Test fun phoneLyingInTheCarKeepsTheKeyAwakeUpTo30Minutes() {
        // Field 10/10 16:44-16:47: -46..-49 in the car, slept after 2 min, car no longer found it.
        assertTrue(KeySleepPolicy.atCar(true, -48))
        assertFalse(KeySleepPolicy.maySleep(still(29 * 60_000L), atCar = true, sinceAtCarMs = 0L))
        assertTrue(KeySleepPolicy.maySleep(still(30 * 60_000L), atCar = true, sinceAtCarMs = 0L))
    }

    @Test fun awayFromTheCarTheTwoMinuteSleepIsUnchanged() {
        assertTrue(KeySleepPolicy.atCar(true, -76))  // in a pocket beside the car
        assertFalse(KeySleepPolicy.atCar(true, -82))
        assertFalse(KeySleepPolicy.atCar(false, -48)) // no live session: nothing to keep awake
        assertFalse(KeySleepPolicy.atCar(true, null))
        assertTrue(KeySleepPolicy.maySleep(still(2 * 60_000L), atCar = false, sinceAtCarMs = null))
        assertFalse(KeySleepPolicy.maySleep(still(119_000L), atCar = false, sinceAtCarMs = null))
    }

    @Test fun leavingTheCarRestartsTheTwoMinutesEvenIfMotionStillSaysStill() {
        // Phone taken from the car and carried away; screen-off AR still reports STILL.
        val longStill = still(10 * 60_000L)
        assertFalse(KeySleepPolicy.maySleep(longStill, atCar = false, sinceAtCarMs = 5_000L))
        assertFalse(KeySleepPolicy.maySleep(longStill, atCar = false, sinceAtCarMs = 119_000L))
        assertTrue(KeySleepPolicy.maySleep(longStill, atCar = false, sinceAtCarMs = 120_000L))
    }
}
