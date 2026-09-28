package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticLockGateTest {
    @Test fun `default mode never actuates even after repeated verified departure candidates`() {
        val gate = AutomaticLockGate()
        repeat(3) { assertEquals(AutomaticLockGate.Action.WARN_MANUAL_LOCK, gate.onVerifiedDeparture()) }
    }

    @Test fun `actuation requires an explicit and separately reviewed mode change`() {
        val gate = AutomaticLockGate(AutomaticLockGate.Mode.ACTUATE)
        assertEquals(AutomaticLockGate.Action.LOCK, gate.onVerifiedDeparture())
    }
}
