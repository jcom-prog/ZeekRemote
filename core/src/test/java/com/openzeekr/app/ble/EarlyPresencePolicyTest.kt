package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyPresencePolicyTest {
    @Test fun `cold approach can prepare early but cannot connect until motion is confirmed`() {
        assertTrue(EarlyPresencePolicy.mayPrepare(sleeping = true, moving = true, watchBorrowing = false))
        assertTrue(EarlyPresencePolicy.mayRetain(sleeping = true, moving = true, deadlineMs = 2500, nowMs = 1000))
        assertFalse(EarlyPresencePolicy.mayConnect(motionConfirmed = false, watchBorrowing = false,
            deadlineMs = 2500, nowMs = 1000))
        assertTrue(EarlyPresencePolicy.mayConnect(motionConfirmed = true, watchBorrowing = false,
            deadlineMs = 2500, nowMs = 1700))
    }

    @Test fun `false motion and expired advertisement cannot wake or connect the key`() {
        assertFalse(EarlyPresencePolicy.mayRetain(sleeping = true, moving = false, deadlineMs = 2500, nowMs = 1200))
        assertFalse(EarlyPresencePolicy.mayConnect(motionConfirmed = true, watchBorrowing = false,
            deadlineMs = 2500, nowMs = 2500))
        assertFalse(EarlyPresencePolicy.freshAdvertisement(timestampNs = 1_000_000_000,
            nowNs = 5_000_000_000, maxAgeMs = 2500))
        assertTrue(EarlyPresencePolicy.freshAdvertisement(timestampNs = 1_000_000_000,
            nowNs = 2_000_000_000, maxAgeMs = 2500))
        assertFalse(EarlyPresencePolicy.mayPrepare(sleeping = false, moving = true, watchBorrowing = false))
    }

    @Test fun `watch borrowing prevents early connection without changing existing return guard`() {
        assertFalse(EarlyPresencePolicy.mayPrepare(sleeping = true, moving = true, watchBorrowing = true))
        assertFalse(EarlyPresencePolicy.mayConnect(motionConfirmed = true, watchBorrowing = true,
            deadlineMs = 2500, nowMs = 1700))
    }
}
