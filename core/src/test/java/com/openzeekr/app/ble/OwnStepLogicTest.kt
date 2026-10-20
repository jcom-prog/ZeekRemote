package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Measurement version (doorbraak C): the own step counter and turn detector on synthetic 50 Hz
 * sensor streams - a walk counts about one step per swing, standing noise counts none, and an
 * about-face mid-walk is seen within about two seconds while a gentle path bend is not.
 */
class OwnStepLogicTest {
    private val dt = 20L // 50 Hz

    /** Walk: one 3 m/s² swell per step at [cadence] steps/s over gravity, with a little noise. */
    private fun walk(l: OwnStepLogic, fromMs: Long, ms: Long, cadence: Double, r: Random): Long {
        var t = fromMs
        while (t < fromMs + ms) {
            val phase = 2 * PI * cadence * (t - fromMs) / 1000.0
            val swell = maxOf(0.0, sin(phase)) * 3.0
            l.onAccel(t, 9.81 + swell + r.nextDouble(-0.15, 0.15))
            t += dt
        }
        return t
    }

    private fun stand(l: OwnStepLogic, fromMs: Long, ms: Long, r: Random, jitter: Double = 0.15): Long {
        var t = fromMs
        while (t < fromMs + ms) { l.onAccel(t, 9.81 + r.nextDouble(-jitter, jitter)); t += dt }
        return t
    }

    @Test fun aWalkCountsItsSteps() {
        val l = OwnStepLogic()
        val r = Random(7)
        var t = stand(l, 0, 2_000, r)
        t = walk(l, t, 20_000, 1.8, r) // ~36 swings
        stand(l, t, 2_000, r)
        assertTrue("counted ${l.steps} steps of ~36", l.steps in 30..40)
    }

    @Test fun standingCountsNothing() {
        val l = OwnStepLogic()
        val r = Random(11)
        stand(l, 0, 30_000, r)
        assertEquals(0L, l.steps)
    }

    @Test fun pocketFiddlingCountsAlmostNothing() {
        val l = OwnStepLogic()
        val r = Random(13)
        // Narrow 1-sample spikes (tapping, fiddling): the smoothing damps them below the threshold.
        var t = 0L
        while (t < 20_000) {
            val spike = if (r.nextDouble() < 0.02) 2.5 else 0.0
            l.onAccel(t, 9.81 + spike + r.nextDouble(-0.15, 0.15))
            t += dt
        }
        assertTrue("counted ${l.steps} false steps", l.steps <= 2)
    }

    /** Yaw stream at 50 Hz: hold, then rotate by [rad] over [overMs], then hold. */
    private fun turnYaw(l: OwnStepLogic, fromMs: Long, holdMs: Long, rad: Double, overMs: Long, tailMs: Long) {
        var t = fromMs
        var y = 0.3
        while (t < fromMs + holdMs) { l.onYaw(t, y); t += dt }
        val start = t
        while (t < start + overMs) { l.onYaw(t, y + rad * (t - start) / overMs.toDouble()); t += dt }
        y += rad
        while (t < start + overMs + tailMs) { l.onYaw(t, y); t += dt }
    }

    @Test fun anAboutFaceWhileWalkingIsATurn() {
        val l = OwnStepLogic()
        val r = Random(17)
        var t = walk(l, 0, 6_000, 1.8, r)
        // Keep stepping while the yaw sweeps half a circle in 1.5 s.
        val yawFrom = t
        turnYaw(l, yawFrom, 500, PI, 1_500, 1_000)
        walk(l, t, 3_000, 1.8, r)
        assertNotNull("the about-face was not seen", l.lastTurnAtMs)
        assertTrue("seen late: ${l.lastTurnAtMs!! - yawFrom} ms after the sweep began",
            l.lastTurnAtMs!! <= yawFrom + 500 + 1_500 + 700)
    }

    @Test fun aGentleBendIsNoTurn() {
        val l = OwnStepLogic()
        val r = Random(19)
        val t = walk(l, 0, 6_000, 1.8, r)
        // 60 degrees over 5 s: a path bend, not an about-face.
        turnYaw(l, t, 500, Math.toRadians(60.0), 5_000, 1_000)
        walk(l, t, 6_500, 1.8, r)
        assertNull("a bend counted as a turn", l.lastTurnAtMs)
    }

    @Test fun aSweepWhileStandingIsNoTurn() {
        val l = OwnStepLogic()
        val r = Random(23)
        stand(l, 0, 6_000, r)
        // Turning the body (or the phone) through half a circle without any steps.
        turnYaw(l, 6_000, 500, PI, 1_500, 1_000)
        stand(l, 6_000, 3_000, r)
        assertNull("a stand-still sweep counted as a turn", l.lastTurnAtMs)
    }
}
