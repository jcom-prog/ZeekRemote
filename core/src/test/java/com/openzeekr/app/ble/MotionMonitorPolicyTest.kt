package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionMonitorPolicyTest {
    @Test
    fun `non wake-up step detector is never accepted as secured-key wake source`() {
        assertFalse(MotionMonitor.isWakeCapableStepDetector(isWakeUpSensor = false))
    }

    @Test
    fun `wake-up step detector remains an accepted wake source`() {
        assertTrue(MotionMonitor.isWakeCapableStepDetector(isWakeUpSensor = true))
    }
}
