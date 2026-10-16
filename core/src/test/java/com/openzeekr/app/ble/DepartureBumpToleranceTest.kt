package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.12: one short bump out of the deep-far band keeps the walk-away window of [BleDepartureEvidence].
 * Field, phone in the hand: walking away the signal wobbles about 2 dB at the 1 s scale, with single short
 * bumps - each such bump restarted the 8 s window and the car locked later or not at all.
 */
class DepartureBumpToleranceTest {
    private val lock = -82   // deep-far: <= -85

    /** Walk away at 1.8 steps/s: strong for 1 s after leaving the door, then -90, with bumps to -84 at [bumps] (ms after leaving). */
    private fun confirmAt(bumps: List<LongRange>): Long {
        val e = BleDepartureEvidence(lock)
        var t = 0L
        var steps = 0L
        var stepAcc = 0.0
        repeat(10) { t += 200L; e.observe(t, -62, -62, false, steps) }   // at the door after the unlock
        val leave = t
        while (t < leave + 40_000L) {
            t += 200L
            stepAcc += 1.8 * 0.2
            while (stepAcc >= 1.0) { steps++; stepAcc -= 1.0 }
            val since = t - leave
            val rssi = when {
                since < 1_000L -> -66
                bumps.any { since in it } -> -84
                else -> -90
            }
            if (e.observe(t, rssi, rssi, true, steps) == BleDepartureEvidence.Decision.CONFIRMED) return since
        }
        error("no departure confirmed")
    }

    @Test fun oneShortBumpKeepsTheWindow() {
        val none = confirmAt(emptyList())
        assertEquals("a 1.6 s bump neither restarts the window nor confirms by itself",
            none, confirmAt(listOf(4_000L until 5_600L)))
    }

    @Test fun aLongBumpOrASecondBumpRestartsTheWindow() {
        val long = confirmAt(listOf(4_000L until 7_000L))
        assertTrue("a 3 s bump restarts the 8 s window ($long ms)", long >= 7_000L + BleDepartureEvidence.MIN_WEAK_MS)
        val twice = confirmAt(listOf(3_000L until 4_000L, 6_000L until 7_000L))
        assertTrue("the second bump in one window restarts it ($twice ms)", twice >= 7_000L + BleDepartureEvidence.MIN_WEAK_MS)
    }
}
