package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStartLimiterTest {
    @Test fun fourStartsPerThirtySecondsThenWait() {
        val l = ScanStartLimiter()
        assertEquals(listOf(true, true, true, true, false), (0 until 5).map { l.tryStart(1_000L * it) })
        assertFalse(l.tryStart(29_999L))
        assertTrue(l.tryStart(30_000L))      // the first start left the window
    }
}
