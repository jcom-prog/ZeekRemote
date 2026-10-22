package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.12: the returns after a Lock that open from the signal need a walk back. Simulator worst case
 * "strong spot" (standing 18-32 m out the signal is 7 dB stronger): the car reopened from more than
 * 20 m while the user stood there, and a peak while walking away reopened it at 33 m.
 */
class FarReturnGuardTest {
    private fun locked() = ProximityDecisionPolicy().apply { onManualLockConfirmed() }

    /** A proven clear departure: 8 s at -96 (beyond the deep-far level of -92), the last at 7 s. */
    private fun ProximityDecisionPolicy.provenFar(): Long {
        var t = 0L
        repeat(8) { assertFalse(shouldUnlock(t, -96, false, -86)); t += 1_000L }
        return t
    }

    @Test fun theSignalReturnWaitsWhileALiveStepCountShowsTheUserStanding() {
        val p = locked()
        var t = p.provenFar() + 4_000L
        // Standing where the signal reads -80 (the return level), the step count stopped.
        repeat(30) { assertFalse(p.shouldUnlock(t, -80, false, -86, walkingConfirmed = false, stepsTotal = 100L)); t += 1_000L }
        // The count advances again, at a live cadence (0.3.28): the same level is the walk back.
        var opened = false
        repeat(4) { if (p.shouldUnlock(t, -80, false, -86, walkingConfirmed = true, stepsTotal = 103L + 3L * it)) opened = true; t += 500L }
        assertTrue(opened)
    }

    @Test fun theFarProofDoesNotAgeWhileTheStepCountShowsTheUserStanding() {
        // Ninety seconds standing out there (the proof alone lapses after a minute), then the walk back.
        val p = locked()
        var t = p.provenFar()
        repeat(90) { assertFalse(p.shouldUnlock(t, -88, false, -86, walkingConfirmed = false, stepsTotal = 100L)); t += 1_000L }
        var opened = false
        repeat(4) { if (p.shouldUnlock(t, -80, false, -86, walkingConfirmed = true, stepsTotal = 103L + 3L * it)) opened = true; t += 500L }
        assertTrue(opened)
    }

    @Test fun theSignalReturnWaitsWhileTheStepCountHasStalledForMoreThanFourAndAHalfSeconds() {
        // The stall gate's own threshold follows the hub's packet interval (5-9 s): in between, a
        // stall longer than 4.5 s already means standing (simulator F29: the user stood 1 s after STILL).
        fun opens(stallAgeMs: Long): Boolean {
            val p = locked()
            val t = p.provenFar() + 8_000L
            // The count advanced at a live cadence until stallAgeMs ago (0.3.28: that is the walking evidence; the
            // motion sensor says nothing).
            p.shouldUnlock(t - stallAgeMs - 400L, -88, false, -86, stepsTotal = 100L)
            p.shouldUnlock(t - stallAgeMs - 200L, -88, false, -86, stepsTotal = 103L)
            p.shouldUnlock(t - stallAgeMs, -88, false, -86, stepsTotal = 106L)
            return (0 until 10).any { p.shouldUnlock(t + 500L * it, -80, false, -86, stepStallAgeMs = stallAgeMs, stepsTotal = 106L) }
        }
        assertTrue("3 s between step packets still opens", opens(3_000L))
        assertFalse("6 s without a step does not", opens(6_000L))
    }

    @Test fun aJumpOutOfTheDeepFarLevelIsAPeakNotAReturn() {
        // Simulator F20: +15 dB for 1.5 s at 33 m while walking away, 1.4 s after a -94 reading.
        val p = locked()
        var t = p.provenFar() - 600L                       // 400 ms after the last deep-far reading
        listOf(-80, -80, -81, -80, -80).forEach { assertFalse(p.shouldUnlock(t, it, false, -86)); t += 500L }
        assertFalse(p.shouldUnlock(t, -95, false, -86)); t += 1_000L
        // A real walk back (the counted total advancing, 0.3.28) rises for seconds out of the deep-far level and opens.
        var steps = 100L
        listOf(-91, -88, -85).forEach { steps += 2L; assertFalse(p.shouldUnlock(t, it, false, -86, stepsTotal = steps)); t += 1_000L }
        var opened = false
        listOf(-81, -80, -79, -78).forEach { steps += 1L; if (p.shouldUnlock(t, it, false, -86, stepsTotal = steps)) opened = true; t += 500L }
        assertTrue(opened)
    }

    @Test fun aBackPocketJumpingToTheAtCarLevelIsTheArrival() {
        // A back pocket stays deep behind the body all the way and jumps at the car itself (-73..-77); the counted
        // total shows the walk (0.3.28: no level is exempt from walking evidence).
        val p = locked()
        var t = p.provenFar() - 500L
        var steps = 100L
        listOf(-76, -76, -75, -75).forEach { steps += 3L; assertFalse(p.shouldUnlock(t, it, false, -86, stepsTotal = steps)); t += 500L }
        assertTrue(p.shouldUnlock(t, -75, false, -86, stepsTotal = steps))
    }

    @Test fun aManualReturnWhileTheStepCountShowsStandingNeedsEightStepsSinceTheMotionBegan() {
        // The counter often says "stopped" at the car just before the signal gets there: a walk of
        // eight steps or more still opens. Turning round at 10 m with two steps does not.
        fun opens(walkedSteps: Long): Boolean {
            val p = locked()
            for (time in 0L..2_600L step 200L) p.shouldUnlock(time, -89, true, -86)
            p.shouldUnlock(2_800L, -89, false, -86)
            p.shouldUnlock(23_500L, -89, true, -86, walkingConfirmed = true, stepsTotal = 100L)   // the motion edge
            p.shouldUnlock(23_700L, -84, true, -86, walkingConfirmed = true, stepsTotal = 100L + walkedSteps)
            return listOf(-79, -77, -76).withIndex().any { (i, rssi) ->
                p.shouldUnlock(23_900L + 200L * i, rssi, true, -86, walkingConfirmed = false, stepsTotal = 100L + walkedSteps)
            }
        }
        assertTrue("10 steps back to the car", opens(10L))
        assertFalse("2 steps and standing", opens(2L))
    }

    @Test fun theFarProofKeptWhileStandingLapsesAfterAboutThreeMinutes() {
        // Standing keeps the proof for at most two minutes after the latest deep-far reading, then it
        // lapses in the usual minute: a phone lying still for long must not keep it.
        fun opensAfterStanding(seconds: Int): Boolean {
            val p = locked()
            var t = p.provenFar()
            repeat(seconds) { assertFalse(p.shouldUnlock(t, -88, false, -86, walkingConfirmed = false, stepsTotal = 100L)); t += 1_000L }
            return (0 until 4).any { p.shouldUnlock(t + 500L * it, -80, false, -86, walkingConfirmed = true, stepsTotal = 103L + 3L * it) }
        }
        assertTrue("2.5 min standing, then the walk back", opensAfterStanding(150))
        assertFalse("4 min standing", opensAfterStanding(240))
    }

    @Test fun walkingOutToAStrongSpotIsNoWalkBack() {
        // Simulator F03/F16: after a pause the user walked on to 25 m and stopped there; the stop read 7 dB
        // stronger (the spot) while the motion sensor still said "walking". The steps out there counted as
        // the walk back and the car reopened from 25 m. The walk back counts from the turn.
        val p = locked()
        for (time in 0L..2_600L step 200L) p.shouldUnlock(time, -89, true, -86)
        p.shouldUnlock(2_800L, -89, false, -86)
        var t = 23_500L
        var steps = 100L
        listOf(-89, -91, -93, -94, -95).forEach {
            assertFalse(p.shouldUnlock(t, it, true, -86, walkingConfirmed = true, stepsTotal = steps)); t += 1_000L; steps += 3L
        }
        // Standing at the spot: the count stops, the motion sensor still says "walking", the signal jumps.
        listOf(-81, -80, -79, -79).forEach {
            assertFalse(p.shouldUnlock(t, it, true, -86, walkingConfirmed = false, stepsTotal = steps)); t += 1_000L
        }
    }

    @Test fun stepsCountedBeforeTheMotionShowedMakeNoWalkBack() {
        // Simulator, stress F12: walking back from 25 m the step packets came late and in pieces; counted
        // from the turn they made 9 steps, the motion sensor still said "walking" during a stop at 10 m and
        // the car opened there. The walk back counts from the later of the turn and the motion start.
        fun opens(stepsSinceMotion: Long): Boolean {
            val p = locked()
            for (time in 0L..2_600L step 200L) p.shouldUnlock(time, -89, true, -86)
            p.shouldUnlock(2_800L, -95, false, -86, walkingConfirmed = false, stepsTotal = 100L)   // the turn
            p.shouldUnlock(20_000L, -91, false, -86, walkingConfirmed = true, stepsTotal = 103L)   // late steps, STILL
            p.shouldUnlock(20_500L, -90, true, -86, walkingConfirmed = true, stepsTotal = 103L)    // the motion edge
            p.shouldUnlock(21_500L, -86, true, -86, walkingConfirmed = true, stepsTotal = 103L + stepsSinceMotion)
            return listOf(-80, -79, -79).withIndex().any { (i, rssi) ->
                p.shouldUnlock(22_500L + 500L * i, rssi, true, -86, walkingConfirmed = false, stepsTotal = 103L + stepsSinceMotion)
            }
        }
        assertTrue("8 steps since the motion began", opens(8L))
        assertFalse("7 steps since the motion began, 10 since the turn", opens(7L))
    }

    @Test fun standingByTheCountHoldsUntilTheCountAdvances() {
        // Before: standing lasted one reading - on the next, the motion sensor's "walking" began a new
        // motion and one strong reading after a proven departure opened the car with the count at rest.
        val p = locked()
        var t = p.provenFar() + 1_000L
        assertFalse(p.shouldUnlock(t, -90, true, -86, walkingConfirmed = true, stepsTotal = 100L)); t += 1_000L
        assertFalse(p.shouldUnlock(t, -88, true, -86, walkingConfirmed = true, stepsTotal = 103L)); t += 3_000L
        // The count stops after 3 steps; the motion sensor still says "walking".
        assertFalse(p.shouldUnlock(t, -86, true, -86, walkingConfirmed = false, stepsTotal = 103L)); t += 1_000L
        listOf(-81, -80, -80).forEach {
            assertFalse(p.shouldUnlock(t, it, true, -86, walkingConfirmed = false, stepsTotal = 103L)); t += 1_000L
        }
        // The count advances again: the same level is the walk back.
        var opened = false
        listOf(-80, -79).forEach { if (p.shouldUnlock(t, it, true, -86, walkingConfirmed = true, stepsTotal = 106L)) opened = true; t += 1_000L }
        assertTrue(opened)
    }
}
