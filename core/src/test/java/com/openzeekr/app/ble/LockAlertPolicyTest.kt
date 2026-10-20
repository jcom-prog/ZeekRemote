package com.openzeekr.app.ble

import com.openzeekr.app.ble.LockAlertPolicy.Event
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockAlertPolicyTest {
    @Test fun aLockTheCarDidNotExecuteSoundsAndOffersLockAgain() {
        // Field: the tile said "Locked", the car stayed open.
        assertTrue(LockAlertPolicy.audible(Event.LOCK_NOT_EXECUTED))
        assertTrue(LockAlertPolicy.offersLock(Event.LOCK_NOT_EXECUTED))
    }

    @Test fun expectedButUnconfirmedLockIsAudible() {
        for (e in listOf(Event.AUTO_LOCK_UNCONFIRMED, Event.LINK_LOST_WHILE_UNLOCKED,
                Event.DEPARTURE_UNVERIFIED, Event.LOCK_NOT_EXECUTED))
            assertTrue("$e must sound", LockAlertPolicy.audible(e))
    }

    @Test fun userAtTheCarOrOperatingTheAppStaysSilent() {
        // Stationary link loss: security sleep, Watch hand-over or a phone set down beside the car.
        for (e in listOf(Event.LINK_LOST_STATIONARY, Event.PROXIMITY_RECOVERED_BEFORE_LOCK, Event.MANUAL_CLOUD_LOCK_UNVERIFIED,
                Event.LOCATION_REFERENCE_UNAVAILABLE, Event.DEPARTURE_CANDIDATE_UNVERIFIED))
            assertFalse("$e must not sound", LockAlertPolicy.audible(e))
    }

    @Test fun theLockOfferedWhileTheDepartureProofRunsIsSilentAndOffersLock() {
        // 0.3.21 (field 20/10): offered at once instead of 25 s later; the alarm still follows only when due.
        assertFalse(LockAlertPolicy.audible(Event.DEPARTURE_PROOF_PENDING))
        assertTrue(LockAlertPolicy.offersLock(Event.DEPARTURE_PROOF_PENDING))
        assertFalse(LockAlertPolicy.resolvesAlarm(Event.DEPARTURE_PROOF_PENDING))
        assertTrue(LockAlertPolicy.shouldPost(Event.DEPARTURE_PROOF_PENDING, bleDepartureRouteActive = true))
    }

    @Test fun lockTheCarAcknowledgedOrTheCloudFreshlyConfirmedDoesNotSound() {
        // A BLE receipt plus a lagging cloud is the normal successful case; no alarm fatigue.
        assertFalse(LockAlertPolicy.audible(Event.AUTO_LOCK_STATE_UNVERIFIED))
        assertFalse(LockAlertPolicy.audible(Event.AUTO_LOCK_CLOUD_CONFIRMED))
    }

    @Test fun fieldShapeWalkedAwayThenStoodStillWhileTheLinkDroppedIsAudible() {
        // 0.1.53 part B: candidate, no strong reading after, link lost ~100 s later while STILL.
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

    @Test fun oneAlarmPerSituationAndAReminderAtMostEveryFiveMinutes() {
        // 0.1.54 part B: shadow-path alarm, watchdog alarm 22 s later -> second suppressed.
        val first = 1_000_000L
        assertTrue(LockAlertPolicy.alarmShouldSound(0L, first))
        assertFalse(LockAlertPolicy.alarmShouldSound(first, first + 22_000L))
        // Field (incident #20): ~12 alarms 33 s apart about the same open car. A timed-out or
        // dismissed alarm no longer lets the next event sound at once (plan K7); a reminder does.
        assertFalse(LockAlertPolicy.alarmShouldSound(first, first + LockAlertPolicy.ALARM_EPISODE_MS))
        assertFalse(LockAlertPolicy.alarmShouldSound(first, first + 22_000L, alarmStillShowing = false))
        assertTrue(LockAlertPolicy.alarmShouldSound(first, first + LockAlertPolicy.ALARM_REMINDER_MS))
    }

    @Test fun onlyAResolvedSituationStopsAnAlarm() {
        assertTrue(LockAlertPolicy.resolvesAlarm(Event.AUTO_LOCK_CLOUD_CONFIRMED))
        // 0.3.10: a withheld Lock is no proof the user is back (simulator, silent step counter: "only 0
        // steps since the car" at 20-80 m silenced the alarm about the open car).
        assertFalse(LockAlertPolicy.resolvesAlarm(Event.PROXIMITY_RECOVERED_BEFORE_LOCK))
        assertFalse(LockAlertPolicy.resolvesAlarm(Event.LOCATION_REFERENCE_UNAVAILABLE))
        assertFalse(LockAlertPolicy.resolvesAlarm(Event.DEPARTURE_CANDIDATE_UNVERIFIED))
        assertFalse(LockAlertPolicy.resolvesAlarm(Event.LINK_LOST_STATIONARY))
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

    @Test fun theDepartureAlarmIsHeldOnlyWhileTheStepCounterShowsAShortWalk() {
        assertTrue(LockAlertPolicy.departureWalkedFarEnough(null, null))     // no step source
        assertTrue(LockAlertPolicy.departureWalkedFarEnough(0L, 0L))         // counter delivers nothing
        assertFalse(LockAlertPolicy.departureWalkedFarEnough(6L, 30L))       // loading the boot
        assertTrue(LockAlertPolicy.departureWalkedFarEnough(20L, 40L))       // walked away
    }
}
