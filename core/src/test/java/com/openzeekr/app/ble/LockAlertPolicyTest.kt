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

    @Test fun fieldShapeWalkedAwayThenStoodStillWhileTheLinkDroppedIsAudible() {
        // 0.1.53 part B: candidate 18:47:42, no strong reading after, link lost 18:49:24 while STILL.
        val candidate = 1_000_000L; val lastStrong = candidate - 25_000L
        val suspected = LockAlertPolicy.departureSuspected(candidate, lastStrong)
        assertTrue(suspected)
        assertTrue(LockAlertPolicy.audible(LockAlertPolicy.linkLossEvent(movedDuringLoss = false, departureSuspected = suspected)))
        assertTrue(LockAlertPolicy.departureAlarmDue(suspected, stillUnlocked = true, latestRssi = -98, lockThreshold = -74))
    }

    @Test fun returningToTheCarClearsTheSuspectedDeparture() {
        val candidate = 1_000_000L
        assertFalse(LockAlertPolicy.departureSuspected(candidate, lastStrongNearAtMs = candidate + 3_000L))
        assertFalse(LockAlertPolicy.departureSuspected(0L, 0L))
        // A stationary loss without any walk-away candidate stays silent.
        assertFalse(LockAlertPolicy.audible(LockAlertPolicy.linkLossEvent(false, false)))
    }

    @Test fun departureAlarmIsNotDueNearTheCarOrAfterALock() {
        assertFalse(LockAlertPolicy.departureAlarmDue(true, stillUnlocked = true, latestRssi = -70, lockThreshold = -82))
        assertFalse(LockAlertPolicy.departureAlarmDue(true, stillUnlocked = false, latestRssi = null, lockThreshold = -82))
        assertFalse(LockAlertPolicy.departureAlarmDue(false, stillUnlocked = true, latestRssi = null, lockThreshold = -82))
        assertTrue("link down counts as away", LockAlertPolicy.departureAlarmDue(true, true, null, -82))
    }

    @Test fun oneAlarmPerEpisode() {
        // 0.1.54 part B: shadow-path alarm 19:29:19.6, watchdog alarm 19:29:41.9 -> second suppressed.
        val first = 1_000_000L
        assertTrue(LockAlertPolicy.alarmShouldSound(0L, first))
        assertFalse(LockAlertPolicy.alarmShouldSound(first, first + 22_000L))
        assertTrue(LockAlertPolicy.alarmShouldSound(first, first + LockAlertPolicy.ALARM_EPISODE_MS))
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
