package com.openzeekr.experiments

import org.junit.Assert.*
import org.junit.Test

class RelativeVelocityTrackTest {
    private fun sample(t: Long, bearing: Double = 0.0, speed: Double = 1.0) =
        VelocitySample(t, speed, bearing, 0.05, 3.0)

    @Test fun straightDepartureAccumulatesNetMovement() {
        val track = RelativeVelocityTrack(1)
        var estimate: RelativeVelocityTrack.Estimate? = null
        for (i in 0..8) estimate = track.observe(sample(1000L + i * 1000), 1000L + i * 1000, 1)
        assertEquals(8.0, estimate!!.netM, 1e-6)
        assertTrue(estimate.estimatedClearanceM > 4)
    }

    @Test fun fullCircleCancelsNetMovementEvenWithManySamples() {
        val track = RelativeVelocityTrack(1)
        var estimate: RelativeVelocityTrack.Estimate? = null
        for (i in 0..24) estimate = track.observe(sample(1000L + i * 1000, (i % 24) * 15.0), 1000L + i * 1000, 1)
        assertEquals(0.0, estimate!!.netM, 1e-6)
        assertTrue(estimate.estimatedClearanceM < 0)
    }

    @Test fun returnCancelsAccumulatedDisplacement() {
        val track = RelativeVelocityTrack(1)
        var estimate: RelativeVelocityTrack.Estimate? = null
        for (i in 0..16) estimate = track.observe(sample(1000L + i * 1000, if (i < 8) 0.0 else 180.0), 1000L + i * 1000, 1)
        assertTrue(estimate!!.netM <= 1.01)
    }

    @Test fun missingAndBadMeasurementsNeverBecomeAnEstimate() {
        val bad = listOf(sample(1000).copy(speedMps = null), sample(1000).copy(bearingDegrees = null),
            sample(1000).copy(speedAccuracyMps = null), sample(1000).copy(bearingAccuracyDegrees = null),
            sample(1000).copy(mock = true), sample(1000).copy(speedMps = Double.NaN),
            sample(1000).copy(bearingDegrees = 360.0), sample(1000).copy(speedAccuracyMps = 0.3))
        for (s in bad) {
            val track = RelativeVelocityTrack(1)
            assertNull(track.observe(s, 1000, 1))
            assertNull(track.observe(sample(2000), 2000, 1))
        }
    }

    @Test fun gapsEpochChangesAndOutOfOrderSamplesInvalidateTheTrack() {
        for ((s, epoch, now) in listOf(Triple(sample(3000), 1L, 3000L),
            Triple(sample(2000), 2L, 2000L), Triple(sample(1000), 1L, 1000L),
            Triple(sample(2000), 1L, 4000L), Triple(sample(2000), 1L, 1000L))) {
            val track = RelativeVelocityTrack(1)
            track.observe(sample(1000), 1000, 1)
            assertNull(track.observe(s, now, epoch))
        }
    }

    @Test fun lowQualitySignalsStillCannotDeliverEightMeterClearance() {
        val track = RelativeVelocityTrack(1)
        var estimate: RelativeVelocityTrack.Estimate? = null
        for (i in 0..8) estimate = track.observe(sample(1000L + i * 1000).copy(
            speedAccuracyMps = 0.2, bearingAccuracyDegrees = 10.0), 1000L + i * 1000, 1)
        assertTrue(estimate!!.estimatedClearanceM < 4)
    }

    @Test fun identicalSampleEndpointsCannotRuleOutAnUnobservedLoop() {
        // Both a straight path and a closed loop can have identical velocity at sample instants.
        // This demonstrates why the model is not a standalone physical safety proof.
        val track = RelativeVelocityTrack(1)
        var estimate: RelativeVelocityTrack.Estimate? = null
        for (i in 0..8) estimate = track.observe(sample(1000L + i * 1000), 1000L + i * 1000, 1)
        assertTrue(estimate!!.estimatedClearanceM > 4)
        val actualNetOfClosedLoops = 0.0
        assertNotEquals(actualNetOfClosedLoops, estimate.netM, 1e-6)
    }
}
