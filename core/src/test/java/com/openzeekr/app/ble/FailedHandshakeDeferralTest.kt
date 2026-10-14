package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedHandshakeDeferralTest {
    @Test fun aCarWhoseHandshakeFailedWaitsButIsNeverRefused() {
        val d = FailedHandshakeDeferral()
        d.onFailure("AA", 1_000L)
        // A new scan at 2 s: the failed address waits 3 s, any other address goes first.
        assertTrue(d.deferNow("AA", 2_500L, scanStartedAtMs = 2_000L))
        assertFalse(d.deferNow("BB", 2_500L, scanStartedAtMs = 2_000L))
        // No other car answered: after 3 s into the scan it is tried after all.
        assertFalse(d.deferNow("AA", 5_100L, scanStartedAtMs = 2_000L))
    }

    @Test fun successOrTenMinutesClearIt() {
        val d = FailedHandshakeDeferral()
        d.onFailure("AA", 0L)
        d.onSuccess("AA")
        assertFalse(d.deferNow("AA", 100L, scanStartedAtMs = 100L))
        d.onFailure("BB", 0L)
        assertFalse(d.deferNow("BB", FailedHandshakeDeferral.REMEMBER_MS, scanStartedAtMs = FailedHandshakeDeferral.REMEMBER_MS))
    }
}
