package com.openzeekr.app.ble

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockExecutionWatchTest {
    @Test fun aLockedStatusAfterTheReceiptConfirmsTheLock() = runTest {
        val w = LockExecutionWatch(); w.onStatus(1)
        val armed = w.arm()
        val result = async { w.awaitExecuted(armed, 3_000L) }
        delay(400L); w.onStatus(3)
        assertTrue(result.await())
    }

    @Test fun theLockedStatusMayArriveBeforeTheReceipt() = runTest {
        val w = LockExecutionWatch(); w.onStatus(1)
        val armed = w.arm()
        w.onStatus(3)                                // ~20 ms before the 0x0111 (field)
        assertTrue(w.awaitExecuted(armed, 3_000L))
    }

    @Test fun field0510ReceivedButNeverLockedIsNotExecuted() = runTest {
        // 05/10 16:08:55: 0x0111, then status pushes 33 s later still "open".
        val w = LockExecutionWatch(); w.onStatus(1)
        val armed = w.arm()
        val result = async { w.awaitExecuted(armed, 3_000L) }
        delay(3_100L); w.onStatus(1)
        assertFalse(result.await())
        assertTrue(w.reportedOpen)
    }

    @Test fun aCarAlreadyReportedLockedNeedsNoNewPush() = runTest {
        val w = LockExecutionWatch(); w.onStatus(3)
        assertTrue(w.awaitExecuted(w.arm(), 3_000L))
    }

    @Test fun anOldLockedStatusFromABeforeTheUnlockDoesNotCount() = runTest {
        val w = LockExecutionWatch(); w.onStatus(3); w.onStatus(1)   // unlocked since
        assertFalse(w.awaitExecuted(w.arm(), 3_000L))
    }

    @Test fun aNewSessionForgetsTheCarsState() = runTest {
        val w = LockExecutionWatch(); w.onStatus(3)
        w.reset()
        assertFalse(w.awaitExecuted(w.arm(), 3_000L))
        assertFalse(w.reportedOpen)
    }

    @Test fun anotherWaitersLockedStatusDoesNotLeakIntoTheNextLock() = runTest {
        val w = LockExecutionWatch(); w.onStatus(1)
        val first = w.arm()
        assertFalse(w.awaitExecuted(first, 1_000L))
        w.onStatus(2)
        val second = w.arm()
        assertFalse(w.awaitExecuted(second, 1_000L))
    }
}
