package com.openzeekr.app.ble

import com.openzeekr.app.ble.LockAlertPolicy.Event
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockAlertPolicyTest {
    @Test fun expectedButUnconfirmedLockIsAudible() {
        for (e in listOf(Event.AUTO_LOCK_UNCONFIRMED, Event.LINK_LOST_WHILE_UNLOCKED,
                Event.DEPARTURE_UNVERIFIED))
            assertTrue("$e must sound", LockAlertPolicy.audible(e))
    }

    @Test fun userAtTheCarOrOperatingTheAppStaysSilent() {
        // Stationary link loss: security sleep, Watch hand-over or a phone set down beside the car.
        for (e in listOf(Event.LINK_LOST_STATIONARY, Event.PROXIMITY_RECOVERED_BEFORE_LOCK, Event.MANUAL_CLOUD_LOCK_UNVERIFIED,
                Event.LOCATION_REFERENCE_UNAVAILABLE, Event.DEPARTURE_CANDIDATE_UNVERIFIED))
            assertFalse("$e must not sound", LockAlertPolicy.audible(e))
    }

    @Test fun lockTheCarAcknowledgedOrTheCloudFreshlyConfirmedDoesNotSound() {
        // A BLE receipt plus a lagging cloud is the normal successful case; no alarm fatigue.
        assertFalse(LockAlertPolicy.audible(Event.AUTO_LOCK_STATE_UNVERIFIED))
        assertFalse(LockAlertPolicy.audible(Event.AUTO_LOCK_CLOUD_CONFIRMED))
    }

    @Test fun missingGnssReferenceIsNotNewsWhileTheBleRouteCanLock() {
        assertFalse(LockAlertPolicy.shouldPost(Event.LOCATION_REFERENCE_UNAVAILABLE, bleDepartureRouteActive = true))
        assertTrue(LockAlertPolicy.shouldPost(Event.LOCATION_REFERENCE_UNAVAILABLE, bleDepartureRouteActive = false))
    }

    @Test fun everyOtherEventIsAlwaysPosted() {
        for (e in Event.values()) if (e != Event.LOCATION_REFERENCE_UNAVAILABLE) {
            assertTrue(LockAlertPolicy.shouldPost(e, true))
            assertTrue(LockAlertPolicy.shouldPost(e, false))
        }
    }
}
