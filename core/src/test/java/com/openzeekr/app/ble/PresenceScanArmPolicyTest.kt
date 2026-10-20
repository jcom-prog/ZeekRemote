package com.openzeekr.app.ble

import com.openzeekr.app.ble.PresenceScanArmPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceScanArmPolicyTest {
    @Test fun aRunningScanInTheModeKeepsRunning() {
        (0..4).forEach { assertEquals(Action.KEEP, PresenceScanArmPolicy.decide(armed = true, sameMode = true, freeStarts = it)) }
    }

    @Test fun aModeChangeLeavesOneStartInReserve() {
        assertEquals(Action.RESTART, PresenceScanArmPolicy.decide(armed = true, sameMode = false, freeStarts = 2))
        // Field 20/10: the restart that the ration refused left nothing running - now the running scan stays.
        assertEquals(Action.KEEP, PresenceScanArmPolicy.decide(armed = true, sameMode = false, freeStarts = 1))
        assertEquals(Action.KEEP, PresenceScanArmPolicy.decide(armed = true, sameMode = false, freeStarts = 0))
    }

    @Test fun noScanRunningStartsWithTheLastStartOrWaits() {
        assertEquals(Action.START, PresenceScanArmPolicy.decide(armed = false, sameMode = false, freeStarts = 1))
        assertEquals(Action.DEFER, PresenceScanArmPolicy.decide(armed = false, sameMode = false, freeStarts = 0))
    }

    @Test fun aForegroundScanNextToThePresenceScanNeedsTheReserve() {
        assertTrue(PresenceScanArmPolicy.mayScanAlongside(2))
        assertFalse(PresenceScanArmPolicy.mayScanAlongside(1))
    }
}
