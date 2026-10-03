package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedApproachWatchTest {
    @Test fun pocketedPhoneAtTheCarKeepsTheApproachObservable() {
        // 02/10 19:55: -80..-82 dBm at the car, AR still reporting STILL, car locked, link READY.
        assertTrue(LinkedApproachWatch.holdAwake(sessionReady = true, unlocked = false, wakesOnSteps = false, smoothedRssi = -82))
        // The safety read at 19:54:58 (-94/-98 smoothed) already sits inside / at the edge of the band.
        assertTrue(LinkedApproachWatch.holdAwake(true, false, false, -96))
    }

    @Test fun farAwayLinkedKeyStillSleepsButRechecksSooner() {
        assertFalse(LinkedApproachWatch.holdAwake(true, false, false, -104))
        assertTrue(LinkedApproachWatch.applies(true, false, false))
    }

    @Test fun holdingTheCpuIsBoundedAndNeedsAStepAssist() {
        // Review 0.1.55 F1: motion can stay UNKNOWN (no security sleep) -> own cap per linked session.
        assertTrue(LinkedApproachWatch.holdAwake(true, false, false, -85, hasStepAssist = true, watchingForMs = 0L))
        assertFalse(LinkedApproachWatch.holdAwake(true, false, false, -85, hasStepAssist = true,
            watchingForMs = LinkedApproachWatch.WATCH_MAX_MS))
        // F2: nothing could report MOVING while awake -> do not hold the CPU for nothing.
        assertFalse(LinkedApproachWatch.holdAwake(true, false, false, -85, hasStepAssist = false))
    }

    @Test fun noChangeWhenUnlockedUnlinkedOrStepsCanWakeTheCpu() {
        assertFalse(LinkedApproachWatch.applies(sessionReady = true, unlocked = true, wakesOnSteps = false))
        assertFalse(LinkedApproachWatch.applies(sessionReady = false, unlocked = false, wakesOnSteps = false))
        assertFalse(LinkedApproachWatch.applies(sessionReady = true, unlocked = false, wakesOnSteps = true))
        assertFalse(LinkedApproachWatch.holdAwake(true, true, false, -70))
    }

    @Test fun theWatchClockCountsOnlyWhileLinkedAndLocked() {
        // 03/10 17:05:56 (0.1.61): STILL at the unlocked car must not start the clock.
        assertEquals(0L, LinkedApproachWatch.watchStart(0L, 1_000L, sessionReady = true, unlocked = true, moving = false))
        // Car locked (self-lock 17:07:53): the clock starts now ...
        assertEquals(5_000L, LinkedApproachWatch.watchStart(0L, 5_000L, true, false, false))
        assertEquals(5_000L, LinkedApproachWatch.watchStart(5_000L, 9_000L, true, false, false))
        // ... and stops on MOVING, an unlock or a link that is not READY.
        assertEquals(0L, LinkedApproachWatch.watchStart(5_000L, 9_000L, true, false, moving = true))
        assertEquals(0L, LinkedApproachWatch.watchStart(5_000L, 9_000L, true, unlocked = true, moving = false))
        assertEquals(0L, LinkedApproachWatch.watchStart(5_000L, 9_000L, sessionReady = false, unlocked = false, moving = false))
    }
}
