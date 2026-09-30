package com.openzeekr.app.ble

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
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

    @Test fun lateProviderCompletionReportsWindowExpiryInsteadOfAccuracyFailure() = runBlocking {
        var now = 11_000L
        val outcomes = mutableListOf<String>()
        assertNull(DepartureAnchorAcquisition.acquire(10_000L, { now }, { true },
            { now = 18_000L; accurate }, {}, { _, reason -> outcomes += reason }))
        assertEquals(listOf("window_expired_during_request"), outcomes)
    }

    @Test fun diagnosticsKeepRetryOrderAndAcceptedReference() = runBlocking {
        var calls = 0
        val outcomes = mutableListOf<Pair<Int, String>>()
        assertEquals(accurate, DepartureAnchorAcquisition.acquire(10_000L,
            { 11_000L }, { true }, { if (++calls == 1) null else accurate }, {},
            { attempt, reason -> outcomes += attempt to reason }))
        assertEquals(listOf(1 to "no_fix", 2 to "accepted"), outcomes)
    }

    @Test(expected = CancellationException::class)
    fun cancelledRequestIsNotRetriedOrReportedAsMissingLocation() = runBlocking {
        DepartureAnchorAcquisition.acquire(10_000L, { 11_000L }, { true },
            { throw CancellationException("test cancellation") },
            { throw AssertionError("must not retry cancelled request") },
            { _, _ -> throw AssertionError("must not relabel cancellation") })
        Unit
    }
}
