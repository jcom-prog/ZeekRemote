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
        assertEquals("AUTO_LOCK_STARTED ble",
            ProximityDiagnosticJournal.event("prox", "ble departure lock authorized (rssi=-90); sending Lock"))
        assertEquals("AUTO_LOCK_CONFIRMED ble-departure", ProximityDiagnosticJournal.event("prox",
            "auto lock confirmed (ble-departure) by BLE receipt"))
        assertEquals("AUTO_LOCK_CONFIRMED verified-link-departure", ProximityDiagnosticJournal.event("prox",
            "auto lock confirmed (verified-link-departure) by BLE receipt"))
        assertEquals("BLE_DEPARTURE_REVOKED strong_near",
            ProximityDiagnosticJournal.event("prox", "ble departure revoked (strong_near)"))
        assertNull(ProximityDiagnosticJournal.event("prox", "auto lock confirmed (other) by BLE receipt"))
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
}
