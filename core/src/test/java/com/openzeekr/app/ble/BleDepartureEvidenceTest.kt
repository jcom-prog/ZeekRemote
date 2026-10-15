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
        /** A signal that decays from [from] by [dbPerSecond], starting at [fromT]. */
        fun decay(from: Int, fromT: Long, dbPerSecond: Double = 2.5): (Long) -> Int =
            { at -> (from - (at - fromT) * dbPerSecond / 1000.0).toInt().coerceAtLeast(-96) }
    }

    private fun arrivedFeed(): Feed {
        val f = Feed(BleDepartureEvidence(lock))
        f.run(2_000, { -62 }, moving = false) // standing at the door after unlock
        return f
    }

    // ---- perspective 1: decision rules / measurement quality ----

    @Test fun straightDepartureConfirmsOneDeepWindowAfterReachingDeepFar() {
        val f = arrivedFeed()
        val leaveAt = f.t
        // Walk away: strong for 1 s, then steadily weaker (3 dB/s), never recovering.
        val raw = { at: Long -> if (at - leaveAt < 1_000) -66 else f.decay(-74, leaveAt + 1_000, 3.0)(at) }
        f.run(16_000, raw)
        // Deep-far (<= lock - 3 = -85) is first reached ~3.7 s into the decay.
        var deepAt = leaveAt
        while (raw(deepAt) > -85) deepAt += 200
        val confirm = f.firstConfirmAt!!
        assertTrue("confirmed ${confirm - deepAt} ms after deep-far",
            confirm - deepAt in BleDepartureEvidence.MIN_WEAK_MS..BleDepartureEvidence.MIN_WEAK_MS + 400)
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

    @Test fun shieldedWalkingShorterThanTheDeepWindowNeverConfirms() {
        // Field shape (0.1.42, private replay): beside the car, deep-far stretches while
        // walking lasted at most ~5 s before the signal came back above lock − 3 dB.
        val f = arrivedFeed()
        f.run(10 * 60_000, { at -> if ((at / 200) % 30 < 26) -90 else -80 }) // 5.2 s deep, 0.8 s back
        assertNull(f.firstConfirmAt)
    }

    @Test fun sustainedShieldedWalkingBesideCarIsTheMainResidualRisk() {
        // Stated limit: after strong contact, walking with the phone continuously shielded at
        // deep-far for a full deep window is indistinguishable from leaving for BLE + steps.
        // The ONLY guard is the duration; private replay found no near interval that long.
        val f = arrivedFeed()
        val walkAt = f.t
        f.run(BleDepartureEvidence.MIN_WEAK_MS + 2_000, { -90 })
        assertTrue(f.firstConfirmAt != null)
        assertTrue(f.firstConfirmAt!! - walkAt >= BleDepartureEvidence.MIN_WEAK_MS)
    }

    @Test fun weakButNotDeepFarNeverConfirms() {
        val f = arrivedFeed()
        // Weak (below -82) and receding from -62, but never below lock − 3 dB = -85.
        f.run(60_000, { -83 })
        assertNull(f.firstConfirmAt)
        assertEquals("not_deep_far", f.e.reason)
    }

    @Test fun shieldedDecayRightAfterStrongContactIsAKnownResidualRisk() {
        // Documents the stated limit: a walker shielding the phone directly after strong contact,
        // with a signal that decays like a departure, still satisfies this evidence. Replay
        // calibration must bound how often that shape occurs beside the car.
        val f = arrivedFeed()
        val leaveAt = f.t
        f.run(9_000, f.decay(-84, leaveAt, 1.5))
        assertTrue(f.firstConfirmAt != null)
    }

    @Test fun shieldingDropAfterARestartIsAKnownResidualRisk() {
        // Second residual shape (review N3): a restart beside the car captures the baseline at a
        // moderate level (-75, not strong, not weak); a lasting >= 6 dB body-shielding drop to
        // deep-far while walking for >= 6 s then satisfies recession without strong contact.
        // Pinned here so calibration and field replay must look for exactly this shape.
        val f = arrivedFeed()
        f.run(1_000, { -75 }, moving = false)      // restart: standing beside the car
        f.run(2_000, { -75 })                      // starts walking around it: baseline -75
        f.run(9_000, { -87 })                      // phone into a shielded pocket, keeps walking
        assertTrue(f.firstConfirmAt != null)
    }

    @Test fun strongNearBandNeverSitsInsideTheLockThreshold() {
        // "close" preset: lock -74. The strong band must sit at least 8 dB above it (-66), so a
        // sample at -64 restarts the clear period even though it is below the -62 arrival level,
        // and a -70 reading between the bands still resets the weak window (no confirmation).
        val f = Feed(BleDepartureEvidence(-74))
        f.run(1_000, { -62 }, moving = false)
        val leaveAt = f.t
        f.run(4_000, f.decay(-78, leaveAt))
        f.run(200, { -64 })                        // inside lock+8: still at the car
        assertEquals("strong_near", f.e.reason)
        f.run(200, { -70 })
        assertEquals("not_weak", f.e.reason)
        f.run(14_000, f.decay(-78, f.t))
        assertTrue(f.firstConfirmAt!! - f.e.lastStrongAtMs!! >= 6_000L)
    }

    @Test fun smoothedSignalAboveLockThresholdKeepsUnlocked() {
        val f = arrivedFeed()
        f.run(30_000, raw = { -80 }, smoothed = { -79 })
        assertNull(f.firstConfirmAt)
        assertEquals("not_weak", f.e.reason)
    }

    @Test fun tooFewStepsCannotConfirmEvenWhenWeakForLong() {
        val f = arrivedFeed()
        val leaveAt = f.t
        f.run(16_000, f.decay(-80, leaveAt), stepsPerSecond = 0.2) // shuffling, ~3 steps in 16 s
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
        val leaveAt = f.t
        f.run(5_000, f.decay(-78, leaveAt))
        f.run(200, { -68 })
        val strongAt = f.t
        f.run(12_000, f.decay(-78, strongAt))
        assertTrue(f.firstConfirmAt!! - strongAt >= 6_000L)
    }

    // ---- perspective 2: asynchrony / lifecycle / stale data ----

    @Test fun sparseIdleChecksNeverConfirm() {
        val f = arrivedFeed()
        // The 0.1.38 idle-far-lock shape: one weak sample every 10 s for minutes.
        f.run(5 * 60_000, { -92 }, stepMs = 10_000L)
        assertNull(f.firstConfirmAt)
        assertEquals("sampling_gap", f.e.reason)
    }

    @Test fun oneGapRestartsTheWindowOnlyFromTheFirstSampleAfterIt() {
        val f = arrivedFeed()
        val leaveAt = f.t
        f.run(4_000, f.decay(-78, leaveAt))
        f.run(1_500, { -86 }, stepMs = 1_500L) // single 1.5 s observation gap
        val gapAt = f.t
        f.run(12_000, f.decay(-84, gapAt, 1.5))
        assertTrue(f.firstConfirmAt!! - gapAt >= 6_000L)
    }

    @Test fun duplicateOrBackwardTimestampRestartsObservation() {
        for (bad in listOf(0L, -300L)) {
            val f = arrivedFeed()
            val leaveAt = f.t
            f.run(5_800, f.decay(-80, leaveAt))
            assertEquals(BleDepartureEvidence.Decision.NONE,
                f.e.observe(f.t + bad, -90, -90, true, f.steps + 1))
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
        val leaveAt = f.t
        f.run(3_000, f.decay(-80, leaveAt))
        f.run(1_000, { -88 }, moving = false)
        val resumedAt = f.t
        f.run(12_000, f.decay(-84, resumedAt, 1.5))
        assertTrue(f.firstConfirmAt!! - resumedAt >= 6_000L)
    }

    @Test fun eachEpochIsIndependent() {
        val first = arrivedFeed()
        val leaveAt = first.t
        first.run(18_000, first.decay(-78, leaveAt))
        assertTrue(first.firstConfirmAt != null)
        val second = Feed(BleDepartureEvidence(lock)) // new unlock epoch => new object
        second.t = first.t; second.steps = first.steps
        second.run(10_000, { -90 })
        assertNull(second.firstConfirmAt)
    }

    // ---- very deep far alone (0.2.4): no steps, no observed arrival, no GNSS needed ----

    @Test fun walkingVeryDeepFarConfirmsWithoutStepsAfterTwelveSeconds() {
        // Field shape: S24+ screen off, the step detector delivered 2 steps.
        val f = Feed(BleDepartureEvidence(lock))
        f.run(11_800L, raw = { -98 }, stepsAvailable = false)
        assertNull("not before 12 s", f.firstConfirmAt)
        f.run(1_000L, raw = { -98 }, stepsAvailable = false)
        assertTrue(f.firstConfirmAt!! in 12_000L..12_400L)
    }

    @Test fun standingStillVeryDeepAtTheBootNeverConfirms() {
        // Simulator F06: back pocket, body and car between, standing at the boot read ~-97.
        val f = Feed(BleDepartureEvidence(lock))
        f.run(60_000L, raw = { -97 }, moving = false, stepsAvailable = false)
        assertNull(f.firstConfirmAt)
    }

    @Test fun aStopOfMoreThanFiveSecondsRestartsTheVeryDeepWindow() {
        val f = Feed(BleDepartureEvidence(lock))
        repeat(5) {
            f.run(8_000L, raw = { -98 }, stepsAvailable = false)
            f.run(6_000L, raw = { -98 }, moving = false, stepsAvailable = false)
        }
        assertNull(f.firstConfirmAt)
    }

    @Test fun field2043ShortStopsAtTheGateAndTheDoorKeepTheVeryDeepWindow() {
        // Field (0.2.4): locking a gate (~4.5 s) and opening the back door (~1 s)
        // restarted the window; the proof came 12 s after the last stop, too late for the Lock.
        val f = Feed(BleDepartureEvidence(lock))
        f.run(3_000L, raw = { -98 }, stepsAvailable = false)
        f.run(4_600L, raw = { -99 }, moving = false, stepsAvailable = false)   // the gate
        f.run(3_000L, raw = { -100 }, stepsAvailable = false)
        f.run(1_200L, raw = { -101 }, moving = false, stepsAvailable = false)  // the back door
        assertNull("never while stopped", f.firstConfirmAt)
        f.run(2_000L, raw = { -102 }, stepsAvailable = false)
        assertTrue("confirmed ${f.firstConfirmAt}", f.firstConfirmAt!! in 12_000L..12_400L)
    }

    @Test fun neverConfirmedDuringAStopEvenAfterTwelveSeconds() {
        val f = Feed(BleDepartureEvidence(lock))
        f.run(10_000L, raw = { -98 }, stepsAvailable = false)
        f.run(4_000L, raw = { -98 }, moving = false, stepsAvailable = false)
        assertNull(f.firstConfirmAt)
        f.run(400L, raw = { -98 }, stepsAvailable = false)
        assertTrue(f.firstConfirmAt != null)
    }

    @Test fun walkingJustAboveTheVeryDeepLevelWithoutStepsNeverConfirms() {
        val f = Feed(BleDepartureEvidence(lock))
        f.run(60_000L, raw = { -94 }, stepsAvailable = false)
        assertNull(f.firstConfirmAt)
    }

    @Test fun sparseVeryDeepReadingsNeverConfirm() {
        // Unobserved time is no evidence: far-sleep reads 5 s apart, or a 1.2 s cadence.
        val f = Feed(BleDepartureEvidence(lock))
        f.run(60_000L, raw = { -100 }, stepsAvailable = false, stepMs = 1_200L)
        assertNull(f.firstConfirmAt)
    }

    @Test fun oneShallowerReadingRestartsTheVeryDeepWindow() {
        val f = Feed(BleDepartureEvidence(lock))
        var n = 0
        f.run(60_000L, raw = { if (++n % 50 == 0) -90 else -98 }, stepsAvailable = false)  // every 10 s
        assertNull(f.firstConfirmAt)
    }

    @Test fun field1607ShapeConfirmsAboutFortySecondsAfterLeavingTheCar() {
        // Field (0.2.2): in the car, out, a stop at the car, walking away 12 s later
        // (-86..-90), a stop, then -98..-100 walking; 2 steps counted in total.
        val f = Feed(BleDepartureEvidence(lock))
        f.run(8_000L, raw = { -58 }, stepsPerSecond = 0.0)
        f.run(12_000L, raw = { -64 }, stepsPerSecond = 0.0)
        f.run(19_000L, raw = { -88 }, stepsPerSecond = 0.0)
        f.run(1_000L, raw = { -95 }, moving = false, stepsPerSecond = 0.0)
        f.run(20_000L, raw = { -99 }, stepsPerSecond = 0.0)
        // The deep run starts in the 1 s stop (-95), which no longer restarts it.
        val deepStart = 39_000L
        assertTrue("confirmed ${f.firstConfirmAt}", f.firstConfirmAt!! in deepStart + 12_000L..deepStart + 12_400L)
    }

    // ---- 0.2.6: walking away while Activity Recognition hangs on STILL ----

    @Test fun gradualDescentToVeryDeepConfirmsEvenWhenMotionSaysStill() {
        // Synthetic shape of field: screen off, AR reported STILL for the whole walk,
        // no steps; the signal sank ~1 dB/s from the lock level to below -100 and stayed there.
        val f = arrivedFeed()
        val leaveAt = f.t
        val raw = { at: Long -> (-70 - (at - leaveAt) / 1000.0).toInt().coerceAtLeast(-104) }
        f.run(90_000, raw, moving = false, stepsAvailable = false)
        assertTrue("confirmed", f.firstConfirmAt != null)
        assertEquals("very_deep_gradual", f.e.confirmKind)
        // -100 is first reached 30 s after leaving; then 12 s held there.
        assertTrue(f.firstConfirmAt!! - leaveAt >= 30_000 + BleDepartureEvidence.VERY_DEEP_MS)
    }

    @Test fun suddenDropToVeryDeepWhileStillNeverConfirms() {
        // Phone put down / shielded beside the car: from strong straight to -101 with no descent.
        val f = arrivedFeed()
        f.run(10 * 60_000, { -101 }, moving = false, stepsAvailable = false)
        assertNull(f.firstConfirmAt)
    }

    @Test fun gradualDescentThatStopsAboveTheStillDeepLevelNeverConfirms() {
        // Sitting ~25 m away with the phone still: the signal settles at -95, not -100.
        val f = arrivedFeed()
        val leaveAt = f.t
        f.run(10 * 60_000, { at -> (-70 - (at - leaveAt) / 1000.0).toInt().coerceAtLeast(-95) },
            moving = false, stepsAvailable = false)
        assertNull(f.firstConfirmAt)
    }

    // ---- 0.3.1: a proven arrival (steps + rise) as the at-the-car anchor ----

    @Test fun aBackPocketDepartureNeedsAProvenArrivalAndThenRecedesFromIt() {
        // Back pocket: -79 at the door, never a strong-near sample; walking away decays to -96.
        fun departure(e: BleDepartureEvidence): Feed {
            val f = Feed(e)
            f.run(4_000, { -79 }, moving = false)
            val leaveAt = f.t
            f.run(20_000, f.decay(-80, leaveAt, 1.5))
            return f
        }
        val without = departure(BleDepartureEvidence(lock))
        assertNull(without.firstConfirmAt)
        assertEquals("arrival_not_observed", without.e.reason)

        val anchored = BleDepartureEvidence(lock).also { it.arrivalObserved(-79) }
        val f = departure(anchored)
        assertTrue("confirmed only once the signal receded from the car level", f.firstConfirmAt != null)
        assertEquals("walk", anchored.confirmKind)
    }

    @Test fun aProvenArrivalNeverReplacesAStrongSample() {
        val e = BleDepartureEvidence(lock)
        val f = Feed(e)
        f.run(2_000, { -62 }, moving = false)                 // strong at the door
        e.arrivalObserved(-90)                               // a later, weaker anchor changes nothing:
        val leaveAt = f.t                                    // with -90 as reference the walk below
        f.run(25_000, { at -> f.decay(-70, leaveAt, 2.0)(at).coerceAtLeast(-93) })  // could never recede 6 dB
        assertTrue(f.firstConfirmAt != null)
        assertEquals("walk", e.confirmKind)
    }
}
