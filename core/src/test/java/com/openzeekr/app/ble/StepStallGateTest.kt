package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepStallGateTest {
    /** A walk with the count handed over every [everyMs] (2 steps each), until [stopAtMs]. */
    private fun walk(gate: StepStallGate, stopAtMs: Long, everyMs: Long = 1_000L) {
        var steps = 100L
        for (t in 0L..stopAtMs step everyMs) { gate.walking(t, steps); steps += 2 }
    }

    @Test fun aLiveCountThatStopsAdvancingEndsTheWalkAfterTwoAndAHalfSeconds() {
        val gate = StepStallGate()
        walk(gate, 10_000L)
        val last = 120L
        assertTrue(gate.walking(12_400L, last))
        assertFalse(gate.walking(12_600L, last))
        assertEquals(2_600L, gate.stalledForMs)
    }

    @Test fun slowDeliveriesStretchTheStall() {
        // A hub counter handed over every 3 s: 1.6 x 3 s = 4.8 s before "stopped".
        val gate = StepStallGate()
        walk(gate, 12_000L, everyMs = 3_000L)
        val last = 108L
        assertTrue(gate.walking(16_500L, last))
        assertFalse(gate.walking(17_000L, last))
    }

    @Test fun neverVetoesWithoutALiveCountOrAfterANewMovingEdge() {
        val none = StepStallGate()
        assertTrue((0L..20_000L step 500L).all { none.walking(it, null) })
        val frozen = StepStallGate()
        assertTrue("a count that never moved is not live", (0L..20_000L step 500L).all { frozen.walking(it, 50L) })
        val once = StepStallGate()
        once.walking(0L, 50L); once.walking(1_000L, 52L)
        assertTrue("one advance is not enough", once.walking(9_000L, 52L))
        val edge = StepStallGate()
        walk(edge, 10_000L)
        assertFalse(edge.walking(14_000L, 120L))
        edge.onMovingEdge(14_500L)
        assertTrue("Activity Recognition reported a new walk", edge.walking(15_000L, 120L))
    }
}
