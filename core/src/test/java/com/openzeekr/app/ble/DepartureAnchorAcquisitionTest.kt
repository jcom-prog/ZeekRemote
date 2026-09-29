package com.openzeekr.app.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepartureAnchorAcquisitionTest {
    private val accurate = DepartureFix(51.0, 5.0, 3f, 10_500L)

    @Test fun poorFirstFixCanRecoverWithinOriginalWindow() = runBlocking {
        var calls = 0
        val fix = DepartureAnchorAcquisition.acquire(10_000L, { 11_000L }, { true },
            { if (++calls == 1) accurate.copy(accuracyM = 20f) else accurate }, {})
        assertEquals(accurate, fix)
        assertEquals(2, calls)
    }

    @Test fun persistentlyPoorFixesNeverBecomeAnAnchorAndStopAfterThreeAttempts() = runBlocking {
        var calls = 0
        assertNull(DepartureAnchorAcquisition.acquire(10_000L, { 11_000L }, { true },
            { calls++; accurate.copy(accuracyM = 20f) }, {}))
        assertEquals(3, calls)
    }

    @Test fun goodFixArrivingAfterOriginalWindowIsNotAcceptedAtDeparture() = runBlocking {
        var now = 11_000L
        assertNull(DepartureAnchorAcquisition.acquire(10_000L, { now }, { true },
            { now = 18_000L; accurate.copy(elapsedAtMs = now) }, {}))
    }

    @Test fun disabledSessionAndStaleFixCannotEstablishReference() = runBlocking {
        assertNull(DepartureAnchorAcquisition.acquire(10_000L, { 11_000L }, { false },
            { throw AssertionError("must not request location after disabling") }, {}))
        assertNull(DepartureAnchorAcquisition.acquire(10_000L, { 11_000L }, { true },
            { accurate.copy(elapsedAtMs = 8_000L) }, {}))
    }
}
