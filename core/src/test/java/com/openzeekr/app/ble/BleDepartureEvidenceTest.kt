package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic contract tests for [BleDepartureEvidence]. These are decision-rule checks, not
 * field evidence: real calibration requires private replay of recorded near-car intervals.
 * Lock threshold -82 dBm is the "far" preset used in the 7GT field tests.
 */
class BleDepartureEvidenceTest {
    private val lock = -82

    private class Feed(val e: BleDepartureEvidence) {
        var t = 0L
        var steps = 0L
        var firstConfirmAt: Long? = null
        /** One fresh sample every [stepMs]; [stepsPerSecond] while moving. */
        fun run(durationMs: Long, raw: (Long) -> Int, smoothed: (Long) -> Int = raw,
                moving: Boolean = true, stepsPerSecond: Double = 1.8, stepMs: Long = 200L,
                stepsAvailable: Boolean = true) {
            val end = t + durationMs
            var stepAcc = 0.0
            while (t < end) {
                t += stepMs
                if (moving) { stepAcc += stepsPerSecond * stepMs / 1000.0
                    while (stepAcc >= 1.0) { steps++; stepAcc -= 1.0 } }
                val d = e.observe(t, raw(t), smoothed(t), moving, if (stepsAvailable) steps else null)
                if (d == BleDepartureEvidence.Decision.CONFIRMED && firstConfirmAt == null)
                    firstConfirmAt = t
            }
        }
    }

    private fun arrivedFeed(): Feed {
        val f = Feed(BleDepartureEvidence(lock))
        f.run(2_000, { -62 }, moving = false) // standing at the door after unlock
        return f
    }

    // ---- perspective 1: decision rules / measurement quality ----

    @Test fun straightDepartureConfirmsFiveSecondsAfterLastStrongSample() {
        val f = arrivedFeed()
        val leaveAt = f.t
        // Walk away: strong for 1 s, then steadily weaker, never recovering.
        f.run(10_000, { at -> val s = (at - leaveAt) / 1000.0
            if (s < 1.0) -66 else (-74 - (s - 1.0) * 3).toInt().coerceAtLeast(-96) })
        val confirm = f.firstConfirmAt!!
        val sinceLastStrong = confirm - f.e.lastStrongAtMs!!
        assertTrue("confirmed $sinceLastStrong ms after last strong", sinceLastStrong in 5_000L..5_400L)
    }

    @Test fun standingStillBesideCarWhileShieldedNeverConfirms() {
        val f = arrivedFeed()
        f.run(10 * 60_000, { -91 }, moving = false)
        assertNull(f.firstConfirmAt)
        assertEquals("not_moving", f.e.reason)
    }

    @Test fun walkingAroundCarWithPeriodicStrongSamplesNeverConfirms() {
        val f = arrivedFeed()
        // Mostly shielded (-85..-91) while cleaning, one strong sample every 4 s.
        f.run(10 * 60_000, { at -> if ((at / 200) % 20 == 0L) -66 else -85 - ((at / 200) % 7).toInt() })
        assertNull(f.firstConfirmAt)
    }

    @Test fun longShieldedWalkBesideCarIsAKnownResidualRisk() {
        // Documents the limit stated in the class: >= 5 s of continuous shielding while walking,
        // with no strong sample, is indistinguishable from leaving. Calibration must bound it.
        val f = arrivedFeed()
        f.run(6_000, { -88 })
        assertTrue(f.firstConfirmAt != null)
    }

    @Test fun smoothedSignalAboveLockThresholdKeepsUnlocked() {
        val f = arrivedFeed()
        f.run(30_000, raw = { -80 }, smoothed = { -79 })
        assertNull(f.firstConfirmAt)
        assertEquals("not_weak", f.e.reason)
    }

    @Test fun tooFewStepsCannotConfirmEvenWhenWeakForLong() {
        val f = arrivedFeed()
        f.run(30_000, { -90 }, stepsPerSecond = 0.2) // shuffling, ~6 steps in 30 s
        assertNull(f.firstConfirmAt)
        assertEquals("steps_too_few", f.e.reason)
    }

    @Test fun noObservedArrivalGivesNoDepartureBaseline() {
        val f = Feed(BleDepartureEvidence(lock))
        f.run(30_000, { -90 })
        assertNull(f.firstConfirmAt)
        assertEquals("arrival_not_observed", f.e.reason)
    }

    @Test fun missingStepSourceDisablesTheRoute() {
        val f = arrivedFeed()
        f.run(30_000, { -92 }, stepsAvailable = false)
        assertNull(f.firstConfirmAt)
        assertEquals("steps_unavailable", f.e.reason)
    }

    @Test fun recoveredStrongSampleRestartsTheFullClearPeriod() {
        val f = arrivedFeed()
        f.run(4_600, { -90 })
        f.run(200, { -68 })
        val strongAt = f.t
        f.run(10_000, { -90 })
        assertTrue(f.firstConfirmAt!! - strongAt >= 5_000L)
    }

    // ---- perspective 2: asynchrony / lifecycle / stale data ----

    @Test fun sparseIdleSamplesNeverConfirm() {
        val f = arrivedFeed()
        // The 0.1.38 idle-far-lock shape: one weak sample every 10 s for minutes.
        f.run(5 * 60_000, { -92 }, stepMs = 10_000L)
        assertNull(f.firstConfirmAt)
        assertEquals("sampling_gap", f.e.reason)
    }

    @Test fun oneGapRestartsTheWindowOnlyFromTheFirstSampleAfterIt() {
        val f = arrivedFeed()
        f.run(4_000, { -90 })
        f.run(1_500, { -90 }, stepMs = 1_500L) // single gap
        val gapAt = f.t
        f.run(10_000, { -90 })
        assertTrue(f.firstConfirmAt!! - gapAt >= 5_000L)
    }

    @Test fun duplicateOrBackwardTimestampRestartsObservation() {
        for (bad in listOf(0L, -300L)) {
            val f = arrivedFeed()
            f.run(4_800, { -90 })
            val at = f.t + bad
            assertEquals(BleDepartureEvidence.Decision.NONE,
                f.e.observe(at, -90, -90, true, f.steps + 1))
            assertEquals("time_not_advancing", f.e.reason)
            f.run(1_000, { -90 })
            assertNull("old window must not be reused", f.firstConfirmAt)
        }
    }

    @Test fun stepCounterResetCannotInflateWalkedSteps() {
        val f = arrivedFeed()
        f.run(2_000, { -66 }) // walking beside the car: strong samples set a non-zero baseline
        f.run(3_000, { -90 })
        assertEquals(BleDepartureEvidence.Decision.NONE, f.e.observe(f.t + 200, -90, -90, true, 0L))
        assertEquals("steps_restarted", f.e.reason)
    }

    @Test fun stoppingBeforeConfirmationCancelsAndNeedsANewWalk() {
        val f = arrivedFeed()
        f.run(3_000, { -90 })
        f.run(1_000, { -90 }, moving = false)
        val resumedAt = f.t
        f.run(10_000, { -90 })
        assertTrue(f.firstConfirmAt!! - resumedAt >= 5_000L)
    }

    @Test fun eachEpochIsIndependent() {
        val first = arrivedFeed()
        first.run(8_000, { -90 })
        assertTrue(first.firstConfirmAt != null)
        val second = Feed(BleDepartureEvidence(lock)) // new unlock epoch => new object
        second.t = first.t; second.steps = first.steps
        second.run(8_000, { -90 })
        assertNull(second.firstConfirmAt)
    }
}
