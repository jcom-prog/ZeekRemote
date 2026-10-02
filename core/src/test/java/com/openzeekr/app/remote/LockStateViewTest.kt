package com.openzeekr.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockStateViewTest {
    private val t = 1_790_000_000_000L

    @Test fun staleCloudLockedDoesNotHideANewerKeyUnlock() {
        // Field shape 0.1.52: proximity Unlock confirmed by the car, cloud still reports an older Lock.
        val view = LockView.resolve(cloudLocked = true, cloudUpdatedAtMs = t - 600_000L,
            local = LocalLockEvent(locked = false, atMs = t))
        assertEquals(LockView.UNLOCKED, view)
        assertTrue("a tap must Lock, not Unlock", view.tapLocks)
    }

    @Test fun newerCloudReportOverridesAnOlderKeyReceipt() {
        // e.g. the car relocked itself after an unused unlock; only the cloud saw it.
        val view = LockView.resolve(cloudLocked = true, cloudUpdatedAtMs = t + 60_000L,
            local = LocalLockEvent(locked = false, atMs = t))
        assertEquals(LockView.LOCKED, view)
        assertFalse(view.tapLocks)
    }

    @Test fun cloudWithinTheSkewMarginDoesNotOverrideTheKey() {
        val view = LockView.resolve(cloudLocked = true,
            cloudUpdatedAtMs = t + LockView.CLOUD_OVERRIDE_MARGIN_MS,
            local = LocalLockEvent(locked = false, atMs = t))
        assertEquals(LockView.UNLOCKED, view)
    }

    @Test fun keyReceiptWinsWhenCloudHasNoTime() {
        assertEquals(LockView.LOCKED, LockView.resolve(false, null, LocalLockEvent(true, t)))
    }

    @Test fun cloudAloneIsUsedWithoutKeyEvidence() {
        assertEquals(LockView.LOCKED, LockView.resolve(true, t, null))
        assertEquals(LockView.UNLOCKED, LockView.resolve(false, t, null))
    }

    @Test fun unknownStateTapsLockNeverUnlock() {
        val view = LockView.resolve(null, null, null)
        assertEquals(LockView.UNKNOWN, view)
        assertTrue(view.tapLocks)
        // Unknown cloud value with key evidence uses the key.
        assertEquals(LockView.UNLOCKED, LockView.resolve(null, t, LocalLockEvent(false, t)))
    }

    @Test fun cloudCodesMapStrictly() {
        assertEquals(true, LockView.cloudLocked("1"))
        assertEquals(false, LockView.cloudLocked("0"))
        assertEquals(null, LockView.cloudLocked(null))
        assertEquals(null, LockView.cloudLocked(""))
        assertEquals(null, LockView.cloudLocked("2"))
    }
}
