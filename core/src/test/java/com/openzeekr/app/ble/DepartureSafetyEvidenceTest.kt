package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureSafetyEvidenceTest {
    private val car = DepartureFix(51.0000, 5.0000, 3f, 10_000L)

    @Test fun bodyShadowAndWalkingAroundCarNeverAuthorizeLock() {
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(car,
            DepartureFix(51.00001, 5.00001, 3f, 20_000L),
            DepartureFix(51.00002, 5.00000, 3f, 22_000L),
            25L, 22_100L))
    }

    @Test fun twoFreshAccurateFixesFarAwayAuthorizeDeparture() {
        assertTrue(DepartureSafetyEvidence.confirmsDeparture(car,
            DepartureFix(51.00030, 5.00000, 3f, 20_000L),
            DepartureFix(51.00034, 5.00000, 3f, 22_000L),
            35L, 22_100L))
    }

    @Test fun badAccuracyStaleOrMissingLocationCannotAuthorizeLock() {
        val far = DepartureFix(51.00030, 5.00000, 3f, 20_000L)
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(car, far,
            DepartureFix(51.00034, 5.00000, 20f, 22_000L), 35L, 22_100L))
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(car, far,
            DepartureFix(51.00034, 5.00000, 3f, 22_000L), 35L, 30_000L))
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(null, far, far, 35L, 22_100L))
    }

    @Test fun returningToCarBeforeSecondFixCancelsDeparture() {
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(car,
            DepartureFix(51.00030, 5.00000, 3f, 20_000L),
            DepartureFix(51.00001, 5.00000, 3f, 22_000L),
            35L, 22_100L))
    }

    @Test fun shortDepartureOnlyWorksWithVeryAccurateFixes() {
        assertTrue(DepartureSafetyEvidence.confirmsDeparture(
            DepartureFix(51.0000, 5.0000, 1f, 10_000L),
            DepartureFix(51.00009, 5.0000, 1f, 20_000L),
            DepartureFix(51.00012, 5.0000, 1f, 22_000L),
            12L, 22_100L))
    }

    @Test fun farAwayButApproachingCarCannotTriggerLock() {
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(car,
            DepartureFix(51.00034, 5.0000, 3f, 20_000L),
            DepartureFix(51.00030, 5.0000, 3f, 22_000L),
            35L, 22_100L))
    }
}
