package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class PhoneCalibrationTest {
    private class Memory : PhoneCalibrationStore {
        var saved: PhoneCalibration.Profile? = null
        override fun load() = saved
        override fun save(profile: PhoneCalibration.Profile) { saved = profile }
    }

    /** Synthetic phone: -71 dBm at 1 m, 14.7 dB per decade, plus [offsetDb]; 0.72 m per step. */
    private class Walker(val cal: PhoneCalibration, val offsetDb: Double = 0.0) {
        var t = 1_000_000L; var steps = 100L; var distanceM = 30.0
        private var stepAcc = 0.0
        fun rssi(d: Double = distanceM) = (-71.0 - 14.7 * log10(maxOf(d, 0.7)) + offsetDb).toInt()
        /** Walk to [toM] at 1.3 m/s, one reading every 500 ms. */
        fun walkTo(toM: Double) {
            val dir = if (toM < distanceM) -1 else 1
            while ((dir < 0 && distanceM > toM) || (dir > 0 && distanceM < toM)) {
                t += 500; distanceM = if (dir < 0) maxOf(toM, distanceM - 0.65) else minOf(toM, distanceM + 0.65)
                stepAcc += 0.65 / 0.72
                while (stepAcc >= 1.0) { stepAcc -= 1.0; steps++ }
                cal.observe(t, rssi(), steps)
            }
        }
        fun stand(ms: Long) { val end = t + ms; while (t < end) { t += 500; cal.observe(t, rssi(), steps) } }
        fun visit() { distanceM = 30.0; walkTo(1.0); stand(20_000); walkTo(30.0); stand(20_000) }
    }

    @Test fun anArrivalTeachesTheLevelEightStepsBeforeTheCar() {
        val cal = PhoneCalibration(Memory())
        val arrivals = mutableListOf<PhoneCalibration.Arrival>()
        cal.onArrival = { arrivals += it }
        val w = Walker(cal)
        w.walkTo(1.0); w.stand(20_000)
        assertEquals(1, cal.profile.approach.size)
        // Eight steps of 0.72 m before a stop at 1 m: about 6.8 m, where this phone reads about -83.
        assertEquals(w.rssi(6.8).toDouble(), cal.profile.approach.single(), 1.5)
        assertEquals(-71.0, cal.profile.atCar.single(), 1.5)
        assertEquals(1, arrivals.size)
    }

    @Test fun aStopShortOfTheCarOrAShortWalkTeachesNothing() {
        val cal = PhoneCalibration(Memory())
        val w = Walker(cal)
        w.walkTo(12.0); w.stand(20_000)          // waiting at 12 m: the signal hardly rose
        assertTrue(cal.profile.approach.isEmpty())
        w.walkTo(8.0); w.stand(20_000)           // a walk of a few steps
        assertTrue(cal.profile.approach.isEmpty())
    }

    @Test fun aPhoneThatHearsBetterIsCorrectedDownAndOneThatHearsWorseIsLeftAlone() {
        // The synthetic walker carries the phone like the reference user only on average, so compare
        // phones against the same walker with a 0 dB phone.
        fun learned(offsetDb: Double, visits: Int): PhoneCalibration =
            PhoneCalibration(Memory()).also { cal -> val w = Walker(cal, offsetDb); repeat(visits) { w.visit() } }
        val reference = learned(0.0, 30).learnedOffsetDb!!

        val early = learned(5.0, 4)
        assertEquals(0, early.offsetDb)          // still learning
        assertNull(early.learnedOffsetDb)

        val better = learned(5.0, 30)
        assertEquals(5.0, better.learnedOffsetDb!! - reference, 1.0)
        assertTrue("corrected down, by less than learned (dead band, shrink)",
            better.offsetDb in 1..better.learnedOffsetDb!!.toInt())
        assertEquals(-80 - better.offsetDb, better.normalize(-80))

        val worse = learned(-5.0, 30)
        assertEquals(-5.0, worse.learnedOffsetDb!! - reference, 1.0)
        assertTrue(worse.learnedOffsetDb!! < 0.0)
        assertEquals(0, worse.offsetDb)            // never raised: that would open from further away
        assertEquals(-80, worse.normalize(-80))
    }

    @Test fun departuresAreLearnedAndALostLinkCounts() {
        val cal = PhoneCalibration(Memory())
        val w = Walker(cal)
        w.walkTo(1.0); w.stand(20_000); w.walkTo(30.0)
        // 25 steps out is about 18 m: -89.5 for this phone.
        assertEquals(w.rssi(18.0 + 1.0).toDouble(), cal.profile.far.single()!!, 2.0)
        w.walkTo(1.0); w.stand(20_000); w.walkTo(10.0)
        cal.onLinkLost(w.t)
        assertEquals(listOf(true, false), cal.profile.far.map { it != null })
    }

    @Test fun theProfileSurvivesARestartAndAReset() {
        val memory = Memory()
        val cal = PhoneCalibration(memory)
        val w = Walker(cal, offsetDb = 6.0)
        repeat(12) { w.visit() }
        val again = PhoneCalibration(memory)
        assertEquals(cal.profile, again.profile)
        assertEquals(cal.offsetDb, again.offsetDb)
        val decoded = PhoneCalibrationStore.decode(PhoneCalibrationStore.encode(cal.profile))!!
        assertEquals(cal.profile.approach.size, decoded.approach.size)
        cal.profile.approach.zip(decoded.approach).forEach { (x, y) -> assertEquals(x, y, 0.051) }
        assertEquals(cal.profile.far.map { it == null }, decoded.far.map { it == null })
        again.reset()
        assertEquals(0, again.offsetDb)
        assertEquals(PhoneCalibration.Profile(), memory.saved)
    }

    // ---- 0.3.2: diagnose lines for the step counter ----

    @Test fun aShortWalkSaysHowItsStepsArrived() {
        val cal = PhoneCalibration(Memory())
        val notes = mutableListOf<String>()
        cal.onDecision = { notes += it }
        val w = Walker(cal)
        w.walkTo(25.0); w.stand(20_000)          // about 7 steps, then a stop far from the car
        assertTrue(notes.toString(), notes.any { it.startsWith("stop after ") && it.contains(" updates in ") })
    }

    /** Synthetic walk from 30 m to the car while the step count stays at 100, then standing there. */
    private fun frozenWalk(cal: PhoneCalibration, w: Walker, afterStop: List<Long>): Long {
        var t = w.t
        var d = 30.0
        while (d > 1.0) { t += 500; d = maxOf(1.0, d - 0.65); cal.observe(t, w.rssi(d), 100L) }
        afterStop.forEach { steps -> t += 500; cal.observe(t, w.rssi(1.0), steps) }
        return t
    }

    @Test fun anArrivalWhoseStepsComeInOneLateBatchIsReported() {
        // The count does not move during the walk and the whole walk arrives 5 s after the stop. One
        // long silence also stretches the stop time (the learner adapts it to slow deliveries).
        val cal = PhoneCalibration(Memory())
        val notes = mutableListOf<String>()
        cal.onDecision = { notes += it }
        frozenWalk(cal, Walker(cal), List(10) { 100L } + List(120) { 140L })
        assertTrue(cal.profile.approach.isEmpty())
        assertTrue(notes.toString(), notes.any { it == "stop after 40 steps: too few step updates (2 updates in 27.5 s)" ||
            it.startsWith("stop after 40 steps: too few step updates (2 updates in 27,5 s)") })
    }

    @Test fun anArrivalWithAlmostNoStepsIsReportedFromTheRise() {
        val cal = PhoneCalibration(Memory())
        val notes = mutableListOf<String>()
        cal.onDecision = { notes += it }
        frozenWalk(cal, Walker(cal), List(10) { 100L } + List(120) { 102L })
        assertTrue(notes.toString(), notes.any { it.startsWith("stop: signal rose") && it.endsWith("only 2 steps were counted") })
    }

    @Test fun aFadeAtTheEndOfAWalkAwayIsNoArrival() {
        // Stress F37: walking away to 30 m, a fade lifts the level 9 dB at the stop; the rise looks
        // like the last metres to the car, but -84 is no level at the car.
        val cal = PhoneCalibration(Memory())
        val notes = mutableListOf<String>()
        cal.onDecision = { notes += it }
        val w = Walker(cal)
        w.distanceM = 1.0
        w.walkTo(30.0)
        var t = w.t
        repeat(40) { t += 500; cal.observe(t, w.rssi(30.0) + 9, w.steps) }
        assertTrue(cal.profile.approach.isEmpty())
        assertTrue(notes.toString(), notes.any { it.startsWith("stop: level") })
    }
}
