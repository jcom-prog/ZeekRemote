package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class DepartureSamplingContinuityTest {
    private fun arrived(): ProximityDecisionPolicy = ProximityDecisionPolicy().apply {
        onUnlockConfirmed(0)
        for (t in 0L..1_600L step 200L) onUnlockedSample(t, -65, false, -82)
    }

    @Test fun sparseIdleChecksCannotPretendToObserveContinuousDeparture() {
        val p = arrived()
        assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
            p.onUnlockedSample(5_000, -84, true, -82))
        assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
            p.onUnlockedSample(8_000, -92, true, -82))
    }

    @Test fun continuousDepartureStillQualifiesAfterAnObservationGap() {
        val p = arrived()
        var result = ProximityDecisionPolicy.ArmedDecision.NONE
        for (t in 5_000L..7_200L step 200L) {
            result = p.onUnlockedSample(t, if (t == 5_000L) -84 else -88, true, -82)
        }
        assertEquals(ProximityDecisionPolicy.ArmedDecision.LOCK, result)
    }

    @Test fun recoveredNearSignalCancelsBodyShadowCandidate() {
        val p = arrived()
        p.onUnlockedSample(5_000, -84, true, -82)
        p.onUnlockedSample(5_200, -90, true, -82)
        p.onUnlockedSample(5_400, -65, true, -82)
        assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
            p.onUnlockedSample(5_600, -92, true, -82))
    }

    @Test fun duplicateOrBackwardClockCannotConsumeAnEarlierDepartureWindow() {
        for (at in listOf(7_000L, 6_000L)) {
            val p = arrived()
            for (t in 5_000L..7_000L step 200L)
                p.onUnlockedSample(t, -84, true, -82)
            assertEquals(ProximityDecisionPolicy.ArmedDecision.NONE,
                p.onUnlockedSample(at, -92, true, -82))
        }
    }
}
