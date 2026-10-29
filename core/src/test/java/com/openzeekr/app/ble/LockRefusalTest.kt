package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.10 (G2): the refusal record is raw codes only; the reason names the tailgate first; the port never blocks. */
class LockRefusalTest {
    @Test fun theRecordKeepsTheRawCodes() {
        assertEquals("doors=0000 bonnet=0 tailgate=1 port=10",
            LockRefusal.openings(listOf("0", "0", "0", "0"), "0", "1", listOf("1", "0")))
        assertEquals("doors=0-00 bonnet=? tailgate=9 port=--",
            LockRefusal.openings(listOf("0", null, "0", "0"), "open", "12", listOf(null, "")))
    }

    @Test fun theReasonNamesTheTailgateFirstAndNeverThePort() {
        assertEquals("the tailgate", LockRefusal.blocker("doors=1000 bonnet=1 tailgate=1 port=1"))
        assertEquals("a door", LockRefusal.blocker("doors=0010 bonnet=0 tailgate=0 port=11"))
        assertEquals("the bonnet", LockRefusal.blocker("doors=0000 bonnet=2 tailgate=0 port=00"))
        assertNull(LockRefusal.blocker("doors=0000 bonnet=0 tailgate=0 port=11"))
        assertNull(LockRefusal.blocker(null))
        assertNull(LockRefusal.blocker("status unavailable"))
    }

    @Test fun allClosedIgnoresThePortAndMissingFields() {
        assertTrue(LockRefusal.allClosed("doors=0000 bonnet=0 tailgate=0 port=11"))
        assertTrue(LockRefusal.allClosed("doors=00-0 bonnet=- tailgate=0 port=--"))
        assertFalse(LockRefusal.allClosed("doors=0000 bonnet=0 tailgate=1 port=00"))
        assertFalse(LockRefusal.allClosed(null))
    }

    @Test fun theNoticeTextsAreExact() {
        assertEquals("The car reports the tailgate open. Close it; ZeekRemote sends the Lock again when the car reports it closed.",
            LockRefusal.noticeText("the tailgate", true))
        assertEquals("The car reports no open door, bonnet or tailgate. ZeekRemote sends the Lock again shortly.",
            LockRefusal.noticeText(null, true))
        assertEquals("The car's status could not be read. Lock manually and check the car.", LockRefusal.noticeText(null, false))
    }
}
