package com.openzeekr.app.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProximityDiagnosticJournalTest {
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
}
