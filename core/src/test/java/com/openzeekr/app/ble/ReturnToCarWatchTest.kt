package com.openzeekr.app.ble

import com.openzeekr.app.ble.ReturnToCarWatch.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnToCarWatchTest {
    private val lock = -82

    /** A reading every 200 ms from [fromMs] to [toMs], the smoothed signal equal to the reading. */
    private fun feed(watch: ReturnToCarWatch, fromMs: Long, toMs: Long, rssi: Int, armed: Boolean = true): List<Mark> =
        (fromMs..toMs step 200L).map { watch.observe(it, rssi, rssi.toDouble(), lock, armed) }

    @Test fun aDepartureNeverMarksAReturn() {
        val watch = ReturnToCarWatch()
        val marks = feed(watch, 0L, 10_000L, -70) + feed(watch, 10_200L, 40_000L, -95)
        assertTrue(marks.all { it == Mark.NONE })
        assertEquals(ReturnToCarWatch.UNSET, watch.returnedAtMs)
    }

    @Test fun aNearReadingAfterADeepSpellMarksAReturn() {
        // Simulator F49: 30 s at 3.5 m with the body between phone and car, then back at the car in a back pocket.
        val watch = ReturnToCarWatch()
        feed(watch, 0L, 30_000L, -94)
        assertEquals(Mark.RETURN, watch.observe(30_200L, -83, -84.0, lock, true))
        assertEquals(30_200L, watch.returnedAtMs)
        // The near readings right after it keep moving the mark ...
        assertEquals(Mark.STREAK, watch.observe(31_000L, -84, -83.0, lock, true))
        assertEquals(31_000L, watch.returnedAtMs)
        // ... a near reading later on, without a new deep spell, is no new return.
        assertEquals(Mark.NONE, watch.observe(36_000L, -84, -84.0, lock, true))
        assertEquals(31_000L, watch.returnedAtMs)
    }

    @Test fun aSpellNeedsFiveSecondsOfConsecutiveDeepReadings() {
        val watch = ReturnToCarWatch()
        feed(watch, 0L, 4_600L, -95)
        assertEquals("4.6 s is no spell", Mark.NONE, watch.observe(4_800L, -83, -83.0, lock, true))
        feed(watch, 5_000L, 8_000L, -95)
        watch.observe(8_200L, -86, -90.0, lock, true)
        feed(watch, 8_400L, 12_000L, -95)
        assertEquals("a reading above the deep level restarts the spell", Mark.NONE, watch.observe(12_200L, -83, -83.0, lock, true))
        feed(watch, 12_400L, 17_400L, -95)
        assertEquals(Mark.RETURN, watch.observe(17_600L, -83, -83.0, lock, true))
    }

    @Test fun theSmoothedSignalMustBeNearToo() {
        val watch = ReturnToCarWatch()
        feed(watch, 0L, 6_000L, -95)
        assertEquals("one strong fade", Mark.NONE, watch.observe(6_200L, -83, -88.0, lock, true))
        assertEquals(Mark.RETURN, watch.observe(6_400L, -83, -84.0, lock, true))
    }

    @Test fun theLevelsFollowTheLockThreshold() {
        // Lock threshold -82: deep at -88 or weaker, near at -84 or stronger.
        val watch = ReturnToCarWatch()
        feed(watch, 0L, 5_000L, -88)
        assertEquals(Mark.NONE, watch.observe(5_200L, -85, -85.0, lock, true))
        assertEquals(Mark.RETURN, watch.observe(5_400L, -84, -84.0, lock, true))
    }

    @Test fun noMarkWhileTheCarIsNotKnownOpen() {
        val watch = ReturnToCarWatch()
        feed(watch, 0L, 6_000L, -95, armed = false)
        assertEquals(Mark.NONE, watch.observe(6_200L, -83, -83.0, lock, false))
        assertEquals("the spell still counts once the car is open", Mark.RETURN, watch.observe(6_400L, -83, -83.0, lock, true))
    }

    @Test fun resetForgetsTheSpellAndTheMark() {
        val watch = ReturnToCarWatch()
        feed(watch, 0L, 6_000L, -95)
        assertEquals(Mark.RETURN, watch.observe(6_200L, -83, -83.0, lock, true))
        watch.reset()
        assertEquals(ReturnToCarWatch.UNSET, watch.returnedAtMs)
        assertEquals(Mark.NONE, watch.observe(6_400L, -83, -83.0, lock, true))
    }
}
