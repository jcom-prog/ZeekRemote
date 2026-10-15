package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepDeliveryTraceTest {
    /** Readings every 200 ms; [stepsAt] gives the cumulative count the app sees at each moment. */
    private fun lines(durationMs: Long, stepsAt: (Long) -> Long): List<String> {
        val trace = StepDeliveryTrace()
        return (0L..durationMs step 200L).mapNotNull { t -> trace.observe(t, stepsAt(t)) }
    }

    @Test fun aSteadyWalkGivesALineEveryFewSecondsWithShortWaits() {
        // Two steps handed over every second for 12 s.
        val out = lines(12_000L) { t -> 100L + 2L * (t / 1_000L) }
        assertEquals(4, out.size)
        assertTrue(out[0], out[0].startsWith("steps +6 in 3"))
        assertTrue(out[0], out[0].contains("(3 updates, longest wait 1"))
    }

    @Test fun aBatchAfterALongSilenceShowsAsOneUpdateWithALongWait() {
        // Nothing for 9 s while walking, then the whole walk at once.
        val out = lines(12_000L) { t -> if (t < 9_000L) 100L else 125L }
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("steps +25 in 9"))
        assertTrue(out[0], out[0].contains("(1 update, longest wait 9"))
    }

    @Test fun theMeasurementLogWritesAtMostOneLinePerTenSecondsPerSensor() {
        // 0.3.9: a counter that hands over steps every second while walking for a minute.
        val trace = StepDeliveryTrace(10_000L)
        val out = (0L..60_000L step 1_000L).mapNotNull { t -> trace.observe(t, 100L + 2L * (t / 1_000L)) }
        assertEquals(6, out.size)
        assertTrue(out[0], out[0].startsWith("steps +20 in 10"))
        assertTrue(out[0], out[0].contains("(10 updates, longest wait 1"))
    }

    @Test fun noStepsOrAStandingPhoneLogNothingAndARestartIsANewBaseline() {
        assertTrue(lines(30_000L) { 100L }.isEmpty())
        val trace = StepDeliveryTrace()
        assertNull(trace.observe(0L, 500L))
        assertNull(trace.observe(4_000L, 3L))       // the count restarted (process or sensor restart)
        assertNull(trace.observe(5_000L, null))     // no step sensor reading
        val line = trace.observe(8_000L, 13L)
        assertTrue(line!!, line.startsWith("steps +10 in 4"))
    }
}
