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

    @Test fun freeAndWaitCountNothing() {
        val l = ScanStartLimiter()
        assertEquals(4, l.free(0L)); assertEquals(0L, l.waitMs(0L))
        listOf(0L, 1_000L, 2_000L).forEach { l.tryStart(it) }
        assertEquals(1, l.free(2_500L)); assertEquals(0L, l.waitMs(2_500L))
        assertEquals(1, l.free(2_500L))      // asking twice counts nothing
        l.tryStart(3_000L)
        assertEquals(0, l.free(3_000L))
        assertEquals(27_000L, l.waitMs(3_000L))   // the first start leaves the window at 30 s
        assertEquals(1, l.free(30_000L)); assertEquals(0L, l.waitMs(30_000L))
    }
}
