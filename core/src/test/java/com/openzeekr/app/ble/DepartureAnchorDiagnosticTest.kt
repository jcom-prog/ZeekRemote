package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepartureAnchorDiagnosticTest {
    private val fix = DepartureFix(51.0, 5.0, 3f, 10_500L)

    @Test fun unavailableAndPoorAccuracyHaveDifferentReasons() {
        assertEquals("no_fix", DepartureAnchorDiagnostic.rejection(null, 10_000L, 11_000L))
        assertEquals("accuracy_over_eight_meters",
            DepartureAnchorDiagnostic.rejection(fix.copy(accuracyM = 20f), 10_000L, 11_000L))
    }

    @Test fun staleAndFutureFixesCannotBeAccepted() {
        assertEquals("fix_predates_unlock",
            DepartureAnchorDiagnostic.rejection(fix.copy(elapsedAtMs = 8_999L), 10_000L, 11_000L))
        assertEquals("future_fix",
            DepartureAnchorDiagnostic.rejection(fix.copy(elapsedAtMs = 11_001L), 10_000L, 11_000L))
    }

    @Test fun originalAgeAndAccuracyBoundariesRemainAccepted() {
        assertNull(DepartureAnchorDiagnostic.rejection(fix.copy(accuracyM = 8f,
            elapsedAtMs = 9_000L), 10_000L, 11_000L))
        assertNull(DepartureAnchorDiagnostic.rejection(fix, 10_000L, 11_000L))
    }
}
