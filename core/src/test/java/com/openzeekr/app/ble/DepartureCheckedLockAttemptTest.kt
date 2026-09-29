package com.openzeekr.app.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureCheckedLockAttemptTest {
    @Test fun continuedDepartureIsRecheckedForEveryRetry() = runBlocking {
        var checks = 0
        var commands = 0
        repeat(2) {
            assertTrue(DepartureCheckedLockAttempt.send({ true },
                { checks++; true }, { commands++; true }) == true)
        }
        assertEquals(2, checks)
        assertEquals(2, commands)
    }

    @Test fun returnBeforeRetryPreventsSecondCommand() = runBlocking {
        var checks = 0
        var commands = 0
        val departure: suspend () -> Boolean = { ++checks == 1 }
        assertEquals(false, DepartureCheckedLockAttempt.send({ true }, departure,
            { commands++; false }))
        assertNull(DepartureCheckedLockAttempt.send({ true }, departure,
            { commands++; true }))
        assertEquals(2, checks)
        assertEquals(1, commands)
    }

    @Test fun missingEvidenceOrDisablingDuringObservationPreventsCommand() = runBlocking {
        var commands = 0
        assertNull(DepartureCheckedLockAttempt.send({ true }, { false },
            { commands++; true }))
        var enabled = true
        assertNull(DepartureCheckedLockAttempt.send({ enabled },
            { enabled = false; true }, { commands++; true }))
        assertEquals(0, commands)
    }

    @Test fun cancelledObservationDoesNotSendCommand() = runBlocking {
        var commands = 0
        var cancelled = false
        try {
            DepartureCheckedLockAttempt.send({ true },
                { throw CancellationException("test cancellation") }, { commands++; true })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, commands)
    }
}
