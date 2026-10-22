package com.openzeekr.app.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProximityDiagnosticJournalTest {
    @Test fun capabilityObservationWhitelistsOnlyCategories() {
        val value = "noSense=ADVERTISED peMode=UNKNOWN calibration=NOT_ADVERTISED"
        assertEquals("VEHICLE_CAPABILITIES $value", ProximityDiagnosticJournal.event("dk", "vehicle capabilities observed $value"))
        assertNull(ProximityDiagnosticJournal.event("dk", "vehicle capabilities observed $value VIN=private"))
        assertNull(ProximityDiagnosticJournal.event("dk", "vehicle capabilities observed request_failed token=private"))
        assertNull(ProximityDiagnosticJournal.event("dk", "vehicle capabilities observed noSense=SUPPORTED"))
        assertEquals("VEHICLE_CAPABILITIES binding_rejected", ProximityDiagnosticJournal.event("dk", "vehicle capabilities observed binding_rejected"))
    }

    @Test fun measurementLogKeepsOnlyCountsAndSeconds() {
        assertEquals("STEP_COUNTER +16 in=151,6s updates=1 longest=151,6s",
            ProximityDiagnosticJournal.event("motion", "step counter: steps +16 in 151,6 s (1 update, longest wait 151,6 s)"))
        assertEquals("STEP_DETECTOR +18 in=10.2s updates=18 longest=0.8s",
            ProximityDiagnosticJournal.event("motion", "step detector: steps +18 in 10.2 s (18 updates, longest wait 0.8 s)"))
        assertEquals("STEP_SUMMARY +5 in=12,0s updates=1 longest=12,0s detector=3 counter=none",
            ProximityDiagnosticJournal.event("motion", "steps +5 in 12,0 s (1 update, longest wait 12,0 s); detector=3 counter=none"))
        assertEquals("STEP_SUMMARY +16 in=154,0s updates=4 longest=151,6s detector=14 counter=38",
            ProximityDiagnosticJournal.event("motion", "steps +16 in 154,0 s (4 updates, longest wait 151,6 s); detector=14 counter=38"))
        assertEquals("AR_TRANSITION walking enter", ProximityDiagnosticJournal.event("motion", "AR transition: activity=7 trans=0"))
        assertEquals("AR_TRANSITION still enter", ProximityDiagnosticJournal.event("motion", "AR transition: activity=3 trans=0"))
        assertEquals("AR_TRANSITION other exit", ProximityDiagnosticJournal.event("motion", "AR transition: activity=5 trans=1"))
        assertNull("trailing text", ProximityDiagnosticJournal.event("motion", "AR transition: activity=7 trans=0 at work"))
        assertEquals("MOTION_SOURCE sensors", ProximityDiagnosticJournal.event("motion", "started via trigger sensors"))
        assertEquals("MOTION_SOURCE activity", ProximityDiagnosticJournal.event("motion",
            "started via Activity Recognition + wake-up significant motion + non-wakeup step assist"))
        assertEquals("STEP_COUNTER_SENSOR active", ProximityDiagnosticJournal.event("motion", "step counter active"))
        assertEquals("SCREEN on", ProximityDiagnosticJournal.event("svc", "screen on"))
        assertEquals("SCREEN off", ProximityDiagnosticJournal.event("svc", "screen off"))
        assertNull("trailing text", ProximityDiagnosticJournal.event("motion",
            "step counter: steps +16 in 151,6 s (1 update, longest wait 151,6 s) at home"))
        assertNull("a colon (clock-like value)", ProximityDiagnosticJournal.event("motion",
            "step counter: steps +16 in 151:6 s (1 update, longest wait 151,6 s)"))
        assertNull("a position", ProximityDiagnosticJournal.event("svc", "screen off 12.3456,-65.4321"))
        assertNull("another area", ProximityDiagnosticJournal.event("http",
            "step counter: steps +16 in 151,6 s (1 update, longest wait 151,6 s)"))
    }

    @Test fun theEndOfAnAlarmEpisodeIsRecorded() {
        assertEquals("ALARM_EPISODE_CLOSED presence", ProximityDiagnosticJournal.event("prox",
            "presence hit at the car after an alarm (user back) -> alarm episode closed"))
        assertEquals("ALARM_EPISODE_CLOSED signal", ProximityDiagnosticJournal.event("prox",
            "sustained signal at the car after an alarm (user back) -> alarm episode closed"))
        assertEquals("ALARM_EPISODE_CLOSED link", ProximityDiagnosticJournal.event("prox",
            "link back 42 s, then lost out of range -> alarm episode closed"))
        assertEquals("ALARM_EPISODE_CLOSED strong", ProximityDiagnosticJournal.event("prox",
            "strong signal at the car after an alarm -> user back; alarm silenced"))
        assertNull(ProximityDiagnosticJournal.event("prox", "link back 42 s at home, then lost out of range -> alarm episode closed"))
    }

    @Test fun anAlarmThatKeepsSoundingAfterWeakEvidenceIsRecorded() {
        assertEquals("ALARM_EPISODE_CLOSED signal kept", ProximityDiagnosticJournal.event("prox",
            "sustained signal at the car after an alarm (user back) -> alarm episode closed (a sounding alarm keeps sounding)"))
        assertEquals("ALARM_EPISODE_CLOSED presence kept", ProximityDiagnosticJournal.event("prox",
            "presence hit at the car after an alarm (user back) -> alarm episode closed (a sounding alarm keeps sounding)"))
        assertEquals("ALARM_EPISODE_CLOSED link kept", ProximityDiagnosticJournal.event("prox",
            "link back 42 s, then lost out of range -> alarm episode closed (a sounding alarm keeps sounding)"))
        assertNull(ProximityDiagnosticJournal.event("prox",
            "sustained signal at the car after an alarm (user back) -> alarm episode closed (a sounding alarm at home)"))
    }

    @Test fun vehicleStatusRecordsOnlyWhitelistedRawBits() {
        val line = "vehicle status observed session=42 approach=1 walkAway=0 pe=1 ps=0 central=3"
        assertEquals("VEHICLE_STATUS 42 1 0 1 0 3", ProximityDiagnosticJournal.event("dk", line))
        assertNull(ProximityDiagnosticJournal.event("dk", line + " VIN=private"))
        assertNull(ProximityDiagnosticJournal.event("dk", line.replace("central=3", "central=4")))
        assertNull(ProximityDiagnosticJournal.event("dk", "raw body=private"))
        assertNull(ProximityDiagnosticJournal.event("dk", line.replace("session=42", "session=-1")))
    }
    @Test fun departureObservationReasonsRetainNoPositionData() {
        val outcomes = listOf("accuracy_over_eight_meters", "waiting_for_pair",
            "departure_confirmed", "departure_returning", "departure_clearance_insufficient",
            "departure_fix_stale", "request_timeout")
        for (outcome in outcomes) assertEquals("LOCATION $outcome",
            ProximityDiagnosticJournal.event("prox", "departure location outcome=$outcome"))
        assertNull(ProximityDiagnosticJournal.event("prox",
            "departure location outcome=departure_confirmed latitude=51 longitude=5"))
    }
    @Test fun bleDepartureAndAutomaticLockEventsAreCategorical() {
        assertEquals("BLE_DEPARTURE_CONFIRMED",
            ProximityDiagnosticJournal.event("prox", "ble departure confirmed"))
        assertEquals("AUTO_LOCK_STARTED ble very_deep_gradual -101",
            ProximityDiagnosticJournal.event("prox", "ble departure lock authorized (rssi=-101 kind=very_deep_gradual); sending Lock"))
        assertEquals("UNLOCK_STARTED presence -84", ProximityDiagnosticJournal.event("prox",
            "approach-unlock ARM (rssi=-84 ~10,0m prevZone=NEAR route=presence) — confirmed-unlock loop"))
        assertEquals("UNLOCK_STARTED self_lock_return -58", ProximityDiagnosticJournal.event("prox",
            "approach-unlock ARM (rssi=-58 ~0,9m prevZone=NEAR route=self_lock_return) — confirmed-unlock loop"))
        // 0.3.2: the screen state (phone in use) and the walk-back return are journaled too.
        assertEquals("UNLOCK_STARTED approach -84 screen=true", ProximityDiagnosticJournal.event("prox",
            "approach-unlock ARM (rssi=-84 ~10,0m prevZone=NEAR route=approach screen=true) — confirmed-unlock loop"))
        assertEquals("UNLOCK_STARTED walk_back -83 screen=false", ProximityDiagnosticJournal.event("prox",
            "approach-unlock ARM (rssi=-83 ~9,1m prevZone=FAR route=walk_back screen=false) — confirmed-unlock loop"))
        assertEquals("UNLOCK_STARTED away_return_steps -79 screen=?", ProximityDiagnosticJournal.event("prox",
            "approach-unlock ARM (rssi=-79 ~6,3m prevZone=FAR route=away_return_steps screen=?) — confirmed-unlock loop"))
        assertEquals("AUTO_LOCK_CONFIRMED ble-departure", ProximityDiagnosticJournal.event("prox",
            "auto lock confirmed (ble-departure): the car reports locked"))
        assertEquals("AUTO_LOCK_CONFIRMED verified-link-departure", ProximityDiagnosticJournal.event("prox",
            "auto lock confirmed (verified-link-departure): the car reports locked"))
        assertEquals("LOCK_NOT_EXECUTED", ProximityDiagnosticJournal.event("dk",
            "control 0x02: received, but no locked status within 3000 ms -> NOT_EXECUTED"))
        assertEquals("MANUAL_LOCK_NOT_EXECUTED", ProximityDiagnosticJournal.event("prox",
            "manual Lock not executed: no fresh locked report; alarm posted=true"))
        assertEquals("AUTO_LOCK_CLOUD_VERIFIED", ProximityDiagnosticJournal.event("prox",
            "ble-departure: locked via cloud; a newer cloud report confirms it"))
        assertEquals("CLOUD_LOCK_BLE_BACKED", ProximityDiagnosticJournal.event("prox",
            "ble-departure: cloud Lock backed by a fresh BLE departure proof"))
        assertNull(ProximityDiagnosticJournal.event("prox", "rssi=-98 ema=-97 (verifying departure) motion=MOVING"))
        assertEquals("CAR_LOCKED_CHIME", ProximityDiagnosticJournal.event("prox",
            "car reported locked -> 'Auto gesloten' notification"))
        assertEquals("BLE_DEPARTURE_REVOKED strong_near",
            ProximityDiagnosticJournal.event("prox", "ble departure revoked (strong_near)"))
        assertNull(ProximityDiagnosticJournal.event("prox", "auto lock confirmed (other): the car reports locked"))
        assertNull(ProximityDiagnosticJournal.event("prox", "ble departure revoked (latitude=51)"))
        assertNull("per-sample reasons are not journaled",
            ProximityDiagnosticJournal.event("prox", "ble departure evidence clear_too_short"))
        assertEquals("BLE_DEPARTURE_ROUTE enabled", ProximityDiagnosticJournal.event("prox",
            "ble departure route enabled (steps available)"))
        assertEquals("BLE_DEPARTURE_ROUTE disabled", ProximityDiagnosticJournal.event("prox",
            "ble departure route disabled (uncalibrated preset)"))
        assertEquals("BLE_DEPARTURE_SUSPENDED", ProximityDiagnosticJournal.event("prox",
            "ble departure route suspended for this epoch after unconfirmed Lock"))
        assertEquals("LOCK_ALARM raised DEPARTURE_UNVERIFIED", ProximityDiagnosticJournal.event("prox",
            "lock alarm raised (DEPARTURE_UNVERIFIED)"))
        assertEquals("LOCK_ALARM unavailable LINK_LOST_WHILE_UNLOCKED", ProximityDiagnosticJournal.event("prox",
            "lock alarm unavailable (LINK_LOST_WHILE_UNLOCKED)"))
        assertEquals("LOCK_ALARM suppressed DEPARTURE_UNVERIFIED", ProximityDiagnosticJournal.event("prox",
            "lock alarm suppressed (DEPARTURE_UNVERIFIED)"))
        assertNull(ProximityDiagnosticJournal.event("prox", "lock alarm raised (lat=51)"))
        // Existing manual-lock events keep their meaning.
        assertEquals("LOCK_CONFIRMED", ProximityDiagnosticJournal.event("prox", "lock confirmed (manual) -> departure latched"))
    }

    @Test fun aReturnToTheCarAndAVetoedLockAreJournaledAsCategories() {
        // 0.3.19: a return to the car without a strong reading, and which check kept an automatic Lock back.
        assertEquals("RETURN_TO_CAR -84", ProximityDiagnosticJournal.event("prox",
            "return to the car without a strong reading (rssi=-84) -> steps since the car count from here"))
        assertNull(ProximityDiagnosticJournal.event("prox", "return to the car without a strong reading (rssi=near) -> x"))
        assertEquals("LOCK_VETOED far-no-motion steps_since_return", ProximityDiagnosticJournal.event("prox",
            "far-no-motion: Lock vetoed - only 5 steps since the return to the car (detector=10 counter=12); " +
                "user at the car; NO LOCK SENT; reminderPosted=true"))
        assertEquals("LOCK_VETOED verified-link-departure steps_since_car", ProximityDiagnosticJournal.event("prox",
            "verified-link-departure: Lock vetoed - only 11 steps since the car (detector=10 counter=12); " +
                "user at the car; NO LOCK SENT; reminderPosted=true"))
        assertEquals("LOCK_VETOED ble-departure strong_now", ProximityDiagnosticJournal.event("prox",
            "ble-departure: Lock vetoed - fresh reading -71 dBm at the car; user at the car; NO LOCK SENT; reminderPosted=false"))
        assertEquals("LOCK_VETOED ble-departure lock_range", ProximityDiagnosticJournal.event("prox",
            "ble-departure: Lock vetoed - signal back in the lock range (-80 dBm); user at the car; NO LOCK SENT; reminderPosted=false"))
        assertEquals("LOCK_VETOED walk-away-lock strong_recent", ProximityDiagnosticJournal.event("prox",
            "walk-away-lock: Lock vetoed - strong reading 3.2 s ago; user at the car; NO LOCK SENT; reminderPosted=false"))
        assertEquals("LOCK_VETOED ble-departure walking_back", ProximityDiagnosticJournal.event("prox",
            "ble-departure: cloud Lock vetoed - walking back (-90 -> -84 dBm)"))
    }

    private fun withFile(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("proximity-journal-test").toFile()
        try { test(File(dir, "events.log")) } finally { dir.deleteRecursively() }
    }

    @Test fun eventsAreAppendedAndImmediatelyReadableAcrossWriterRecreation() = withFile { file ->
        ProximityDiagnosticJournal(file).append("12:00", "prox", "departure location outcome=provider_no_fix")
        ProximityDiagnosticJournal(file).append("12:01", "prox", "departure anchor attempt=2 outcome=accepted")
        assertEquals(listOf("12:00 LOCATION provider_no_fix", "12:01 ANCHOR 2 accepted"), file.readLines())
    }

    @Test fun onlyTwoBoundedFilesAreRetained() = withFile { file ->
        val journal = ProximityDiagnosticJournal(file, 80)
        repeat(20) { journal.append("12:00", "motion", "-> MOVING") }
        assertTrue(file.length() in 1L..80L)
        assertTrue(File(file.path + ".previous").length() in 1L..80L)
        assertEquals(2, file.parentFile.listFiles()!!.size)
    }

    @Test fun positionsPayloadsAndUnrecognizedOutcomesAreNotStored() = withFile { file ->
        val journal = ProximityDiagnosticJournal(file)
        journal.append("12:00", "http", "token=secret")
        journal.append("12:00", "ble", "session key=secret")
        journal.append("12:00", "prox", "departure location outcome=latitude_51")
        journal.append("12:00", "prox", "rssi=-80 latitude=51 longitude=5")
        assertFalse(file.exists())
        journal.append("12:01", "prox", "unlock confirmed (secret payload) -> departure watch")
        assertEquals("12:01 UNLOCK_CONFIRMED\n", file.readText())
    }

    @Test fun foregroundAndNearRecoveryReasonsRemainCategorical() = withFile { file ->
        val journal = ProximityDiagnosticJournal(file)
        journal.append("12:00", "svc", "departure location foreground=enabled")
        journal.append("12:01", "prox", "departure near anchor outcome=accuracy_over_eight_meters")
        journal.append("12:02", "prox", "departure near anchor outcome=accepted")
        assertEquals(listOf("12:00 LOCATION_FOREGROUND_ENABLED",
            "12:01 NEAR_ANCHOR accuracy_over_eight_meters", "12:02 NEAR_ANCHOR accepted"), file.readLines())
    }

    @Test fun connectAndReadyTimingIsRetainedWithoutDeviceAddressOrKeyMaterial() = withFile { file ->
        val journal = ProximityDiagnosticJournal(file)
        journal.append("12:00", "ble", "connectGatt PRIVATE_DEVICE_ADDRESS")
        journal.append("12:01", "ble", "DK session READY")
        journal.append("12:02", "ble", "onConnectionStateChange status=133 newState=0")
        assertEquals(listOf("12:00 BLE_CONNECT_STARTED", "12:01 BLE_SESSION_READY",
            "12:02 BLE_STATUS_133"), file.readLines())
        assertFalse(file.readText().contains("PRIVATE_DEVICE_ADDRESS"))
    }

    @Test fun theAlarmWaitAndTheDriveMotionAreJournaledAsCategories() {
        // 0.3.20 (study J).
        assertEquals("LOCK_ALARM_HELD very_deep",
            ProximityDiagnosticJournal.event("prox", "departure alarm held: a very-deep proof is under way (9s); re-checking in 6s"))
        assertEquals("BLE_DEPARTURE_HELD drive_motion",
            ProximityDiagnosticJournal.event("prox", "very-deep proof held: MOVING from the drive only (no step or walk since)"))
    }

    @Test fun cloudLockFailureAndReopenAreJournaledAsCategoriesOnly() {
        assertEquals("CLOUD_LOCK_FAILED IDLE",
            ProximityDiagnosticJournal.event("ctl", "LOCK: cloud request failed (key link IDLE)"))
        assertNull(ProximityDiagnosticJournal.event("ctl", "LOCK: cloud request failed (token=abc)"))
        assertEquals("CAR_REOPENED", ProximityDiagnosticJournal.event("prox",
            "car reported unlocked without our Unlock (key status central=2) -> departure watch re-armed"))
    }

    @Test fun measurementLinesOfTheOwnTrackerAndTheBatteryDayAreCountsOnly() {
        assertEquals("OWN_WINDOW reason=window_over own=12 turn=none secs=118 detector=97 counter=192 ar=MOVING screen=off",
            ProximityDiagnosticJournal.event("motion",
                "own tracker OFF (window over): own steps=12 samples=5900 turn=none secs=118 detector=97 counter=192 ar=MOVING screen=off"))
        assertEquals("OWN_WINDOW reason=service_stop own=3 turn=yes secs=20 detector=5 counter=none ar=STILL screen=on",
            ProximityDiagnosticJournal.event("motion",
                "own tracker OFF (service stop): own steps=3 samples=1000 turn=4s ago secs=20 detector=5 counter=none ar=STILL screen=on"))
        // 0.3.27: a Measurement-mode window (no car) closes with its own reason.
        assertEquals("OWN_WINDOW reason=measurement_over own=40 turn=yes secs=300 detector=38 counter=41 ar=MOVING screen=off",
            ProximityDiagnosticJournal.event("motion",
                "own tracker OFF (measurement over): own steps=40 samples=15000 turn=12s ago secs=300 detector=38 counter=41 ar=MOVING screen=off"))
        assertNull(ProximityDiagnosticJournal.event("motion",
            "own tracker OFF (window over): own steps=12 samples=5900 turn=none secs=118 detector=97 counter=192 ar=MOVING screen=off at home"))
        val day = "mode=pulse fires=3 pulses=2 pulseS=20 pulseMah=0,12 walking=1 headStartS=2,5 usedMah=310 screenOffH=14,2 unpluggedH=15,0 perScreenOffH=21,8"
        assertEquals("BATTERY_DAY $day", ProximityDiagnosticJournal.event("prox", "battery day: $day"))
        val empty = "mode=off fires=0 pulses=0 pulseS=0 pulseMah=0.00 walking=0 headStartS=- usedMah=0 screenOffH=0.0 unpluggedH=0.0 perScreenOffH=-"
        assertEquals("BATTERY_DAY $empty", ProximityDiagnosticJournal.event("prox", "battery day: $empty"))
        assertNull(ProximityDiagnosticJournal.event("prox", "battery day: mode=pulse fires=3 lat=52.0"))
        // 0.3.25: the quarantined implausible fix is a journaled location outcome.
        assertEquals("LOCATION departure_fix_implausible",
            ProximityDiagnosticJournal.event("prox", "departure location outcome=departure_fix_implausible"))
    }

    @Test fun aReturnHeldForWalkingEvidenceIsJournaledAsCategoriesOnly() {
        // 0.3.28: the signal return after a Lock reached the return level without walking evidence.
        assertEquals("RETURN_HELD -82 STILL steps=same", ProximityDiagnosticJournal.event("prox",
            "return after Lock held (rssi=-82 motion=STILL steps=same): no walking evidence -> stays locked"))
        assertNull(ProximityDiagnosticJournal.event("prox",
            "return after Lock held (rssi=-82 motion=STILL steps=same): no walking evidence -> stays locked at home"))
    }
}
