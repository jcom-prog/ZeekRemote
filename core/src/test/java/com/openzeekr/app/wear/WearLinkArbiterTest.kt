package com.openzeekr.app.wear

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W2: the hand-over window (R2), the fail-safe deadline and the reset of the process-global arbiter. */
class WearLinkArbiterTest {
    @After fun tearDown() { WearLinkArbiter.reset() }

    @Test fun aHandOverIsRecentWhileItRunsAndForSixtySecondsAfterItEnds() {
        var t = 1_000_000L
        WearLinkArbiter.reset { t }
        assertFalse(WearLinkArbiter.recentHandOver())
        WearLinkArbiter.suspendForWatch()
        assertTrue(WearLinkArbiter.linkSuspended.value)
        assertTrue(WearLinkArbiter.recentHandOver())
        t += 10_000L; WearLinkArbiter.resume()
        assertFalse(WearLinkArbiter.linkSuspended.value)
        assertEquals(t, WearLinkArbiter.handOverEndedAtMs)
        t += 59_000L; assertTrue(WearLinkArbiter.recentHandOver())
        t += 2_000L; assertFalse(WearLinkArbiter.recentHandOver())
    }

    @Test fun theFailSafeExpiresAfterFortyFiveSecondsAndTheReclaimCountsAsAnEnd() {
        var t = 5_000_000L
        WearLinkArbiter.reset { t }
        WearLinkArbiter.suspendForWatch()
        t += WearLinkArbiter.AUTO_RESUME_MS - 1L; assertFalse(WearLinkArbiter.expired())
        t += 1L; assertTrue(WearLinkArbiter.expired())
        WearLinkArbiter.resume()
        assertFalse(WearLinkArbiter.expired())
        assertEquals(t, WearLinkArbiter.handOverEndedAtMs)
    }

    @Test fun handOverIdsBindReportsAndResumesToTheirHandOver() {
        var t = 2_000_000L
        WearLinkArbiter.reset { t }
        val first = WearLinkArbiter.suspendForWatch()
        assertEquals(1L, first)
        assertEquals(first, WearLinkArbiter.suspendForWatch())          // a repeated PAUSE keeps the running hand-over
        assertTrue(WearLinkArbiter.acceptsReport(first))
        assertFalse(WearLinkArbiter.acceptsReport(first + 1))
        assertFalse(WearLinkArbiter.acceptsReport(0L))
        assertFalse(WearLinkArbiter.resume(first + 1))                   // a stale resume is ignored
        assertTrue(WearLinkArbiter.linkSuspended.value)
        t += 10_000L; assertTrue(WearLinkArbiter.resume(first))
        assertFalse(WearLinkArbiter.linkSuspended.value)
        t += 59_000L; assertTrue(WearLinkArbiter.acceptsReport(first))   // a report after the end still counts for 60 s
        t += 2_000L; assertFalse(WearLinkArbiter.acceptsReport(first))
        val second = WearLinkArbiter.suspendForWatch()
        assertEquals(2L, second)
        assertFalse(WearLinkArbiter.acceptsReport(first))
        assertTrue(WearLinkArbiter.resume(null))                         // an older watch without an id
    }

    @Test fun theLeaseCountsFromTheAck() {
        var t = 3_000_000L
        WearLinkArbiter.reset { t }
        WearLinkArbiter.suspendForWatch()
        t += 4_000L; WearLinkArbiter.markAcked()
        t += WearLinkArbiter.AUTO_RESUME_MS - 1L; assertFalse(WearLinkArbiter.expired())
        t += 1L; assertTrue(WearLinkArbiter.expired())
    }

    @Test fun aResumeWithoutAPauseIsNotAHandOver() {
        WearLinkArbiter.reset { 9_000_000L }
        WearLinkArbiter.resume()
        assertEquals(0L, WearLinkArbiter.handOverEndedAtMs)
        assertFalse(WearLinkArbiter.recentHandOver())
    }
}
