package com.openzeekr.app.ble

import com.openzeekr.app.net.model.RemoteControlResponse
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.VehicleControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLockTest {
    @Test fun everyWarningThatAdvisesALockOffersTheButton() {
        for (e in LockAlertPolicy.Event.values()) {
            assertEquals(e.name, e != LockAlertPolicy.Event.AUTO_LOCK_CLOUD_CONFIRMED, LockAlertPolicy.offersLock(e))
        }
    }

    @Test fun everyAudibleAlarmOffersTheButton() {
        LockAlertPolicy.Event.values().filter { LockAlertPolicy.audible(it) }
            .forEach { assertTrue(it.name, LockAlertPolicy.offersLock(it)) }
    }

    @Test fun carCloudOrNothing() {
        assertEquals(NotificationLockOutcome.CONFIRMED_BY_CAR, NotificationLockOutcome.of(
            CallResult.Ok(RemoteControlResponse(status = VehicleControl.BLE_OK_STATUS))))
        assertEquals(NotificationLockOutcome.ACCEPTED_BY_CLOUD, NotificationLockOutcome.of(
            CallResult.Ok(RemoteControlResponse(status = null))))
        assertEquals(NotificationLockOutcome.FAILED, NotificationLockOutcome.of(CallResult.Err("HTTP 401")))
        assertEquals(NotificationLockOutcome.FAILED, NotificationLockOutcome.of(null))
        assertFalse(NotificationLockOutcome.of(null) == NotificationLockOutcome.ACCEPTED_BY_CLOUD)
    }
}

class NotificationLockGuardTest {
    @Test fun oneAtATimeAndNeverDeadForGood() {
        NotificationLockGuard.finish()
        assertTrue(NotificationLockGuard.tryStart(1_000L))
        assertFalse(NotificationLockGuard.tryStart(2_000L))
        assertTrue(NotificationLockGuard.active(2_000L))
        // A send that never reports back releases the button after the cap.
        assertFalse(NotificationLockGuard.active(1_000L + NotificationLockGuard.MAX_IN_FLIGHT_MS))
        assertTrue(NotificationLockGuard.tryStart(1_000L + NotificationLockGuard.MAX_IN_FLIGHT_MS))
        NotificationLockGuard.finish()
        assertFalse(NotificationLockGuard.active(5_000L))
        assertTrue(NotificationLockGuard.tryStart(5_000L))
        NotificationLockGuard.finish()
    }
}
