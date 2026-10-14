package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureStepWatchTest {
    /** A walk with the total handed over every [everyMs] (2 steps each), from [fromMs] to [toMs]. */
    private fun walk(watch: DepartureStepWatch, fromMs: Long, toMs: Long, everyMs: Long = 1_000L, start: Long = 100L): Long {
        var steps = start
        for (t in fromMs..toMs step everyMs) { watch.observe(t, steps); steps += 2 }
        return steps - 2
    }

    @Test fun aRisingTotalIsWalkingForFiveSeconds() {
        val watch = DepartureStepWatch()
        val last = walk(watch, 0L, 6_000L)
        assertTrue(watch.walking(6_000L))
        watch.observe(11_000L, last)
        assertTrue(watch.walking(11_000L))
        watch.observe(11_200L, last)
        assertFalse("no rise for 5.2 s", watch.walking(11_200L))
    }

    @Test fun aLiveCountThatStopsIsAStopAfterTwoSeconds() {
        val watch = DepartureStepWatch()
        val last = walk(watch, 0L, 8_000L)
        watch.observe(9_900L, last)
        assertFalse(watch.stopped(9_900L))
        watch.observe(10_100L, last)
        assertTrue(watch.stopped(10_100L))
    }

    @Test fun slowDeliveriesStretchTheStop() {
        // A count handed over every 2 s: 1.5 x 2 s = 3 s before "stopped".
        val watch = DepartureStepWatch()
        val last = walk(watch, 0L, 10_000L, everyMs = 2_000L)
        watch.observe(12_900L, last)
        assertFalse(watch.stopped(12_900L))
        watch.observe(13_100L, last)
        assertTrue(watch.stopped(13_100L))
    }

    @Test fun aSparseCountNeverClaimsAStop() {
        // Every 3 s: four rises fill the 10 s window only at the last one, so a stop is never seen.
        val watch = DepartureStepWatch()
        val last = walk(watch, 0L, 12_000L, everyMs = 3_000L)
        assertFalse((12_500L..30_000L step 500L).any { watch.observe(it, last); watch.stopped(it) })
    }

    @Test fun noStopWithoutALiveCount() {
        val none = DepartureStepWatch()
        (0L..20_000L step 500L).forEach { none.observe(it, null) }
        assertFalse(none.walking(20_000L)); assertFalse(none.stopped(20_000L))
        val frozen = DepartureStepWatch()
        (0L..20_000L step 500L).forEach { frozen.observe(it, 50L) }
        assertFalse("a count that never moved is no walk", frozen.walking(20_000L))
        assertFalse(frozen.stopped(20_000L))
        val few = DepartureStepWatch()
        walk(few, 0L, 3_000L)   // rises at 1, 2, 3 s: three, fewer than four
        few.observe(9_000L, 106L)
        assertFalse("three rises are not a live count", few.stopped(9_000L))
    }

    @Test fun aStopLongAgoIsForgotten() {
        // The rises leave the 10 s window: the rule stops applying (the motion state has caught up by then).
        val watch = DepartureStepWatch()
        val last = walk(watch, 0L, 8_000L)
        watch.observe(12_000L, last)
        assertTrue(watch.stopped(12_000L))
        watch.observe(17_500L, last)
        assertFalse(watch.stopped(17_500L))
    }
}
