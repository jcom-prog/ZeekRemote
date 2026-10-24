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

    @Test fun aResumeWithoutAPauseIsNotAHandOver() {
        WearLinkArbiter.reset { 9_000_000L }
        WearLinkArbiter.resume()
        assertEquals(0L, WearLinkArbiter.handOverEndedAtMs)
        assertFalse(WearLinkArbiter.recentHandOver())
    }
}
