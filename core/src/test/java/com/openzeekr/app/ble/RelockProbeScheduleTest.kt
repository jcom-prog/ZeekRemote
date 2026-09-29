package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelockProbeScheduleTest {
    @Test fun transientCloudFailureGetsAnotherAttemptAtFiveSeconds() {
        assertTrue(RelockProbeSchedule.due(0L, 100_000L))
        assertFalse(RelockProbeSchedule.due(100_000L, 104_999L))
        assertTrue(RelockProbeSchedule.due(100_000L, 105_000L))
    }

    @Test fun failedRecoveryStopsAfterBoundedWindow() {
        assertFalse(RelockProbeSchedule.expired(100_000L, 145_000L))
        assertTrue(RelockProbeSchedule.expired(100_000L, 145_001L))
    }

    @Test fun monotonicClockAfterFreshConnectionStartsNewWindow() {
        assertFalse(RelockProbeSchedule.expired(0L, 200_000L))
        assertTrue(RelockProbeSchedule.due(0L, 200_000L))
    }
}
