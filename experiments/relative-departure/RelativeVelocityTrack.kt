package com.openzeekr.experiments

import kotlin.math.*

/** Feasibility model only. Not connected to an Android service or a Lock command.
 * Accuracy values are estimates, not physical bounds. Inter-sample motion is unobserved.
 * A positive result MUST NOT be used as vehicle-departure authorization by itself.
 */
data class VelocitySample(
    val elapsedMs: Long,
    val speedMps: Double?,
    val bearingDegrees: Double?,
    val speedAccuracyMps: Double?,
    val bearingAccuracyDegrees: Double?,
    val mock: Boolean = false,
)

class RelativeVelocityTrack(private val epoch: Long) {
    data class Estimate(val eastM: Double, val northM: Double, val estimatedErrorM: Double) {
        val netM: Double get() = hypot(eastM, northM)
        val estimatedClearanceM: Double get() = netM - estimatedErrorM
    }

    private var previous: VelocitySample? = null
    private var east = 0.0
    private var north = 0.0
    private var estimatedError = 0.0
    var invalidReason: String? = null
        private set

    fun observe(sample: VelocitySample, nowMs: Long, currentEpoch: Long): Estimate? {
        if (invalidReason != null) return null
        fun reject(reason: String): Estimate? { invalidReason = reason; return null }
        if (currentEpoch != epoch) return reject("epoch_changed")
        if (sample.mock) return reject("mock")
        val speed = sample.speedMps ?: return reject("speed_missing")
        val bearing = sample.bearingDegrees ?: return reject("bearing_missing")
        val speedAccuracy = sample.speedAccuracyMps ?: return reject("speed_accuracy_missing")
        val bearingAccuracy = sample.bearingAccuracyDegrees ?: return reject("bearing_accuracy_missing")
        if (!listOf(speed, bearing, speedAccuracy, bearingAccuracy).all { it.isFinite() })
            return reject("non_finite")
        if (speed !in 0.0..3.5 || bearing !in 0.0..<360.0 ||
            speedAccuracy !in 0.0..0.2 || bearingAccuracy !in 0.0..10.0)
            return reject("quality_or_range")
        if (sample.elapsedMs <= 0 || sample.elapsedMs > nowMs || nowMs - sample.elapsedMs > 1500)
            return reject("stale_or_future")
        val last = previous
        if (last != null) {
            val dtMs = sample.elapsedMs - last.elapsedMs
            if (dtMs <= 0 || dtMs > 1500) return reject("observation_gap_or_order")
            val dt = dtMs / 1000.0
            fun vector(s: VelocitySample): Pair<Double, Double> {
                val angle = Math.toRadians(s.bearingDegrees!!)
                return s.speedMps!! * sin(angle) to s.speedMps * cos(angle)
            }
            fun estimatedVelocityError(s: VelocitySample): Double {
                val speedError = 2 * s.speedAccuracyMps!!
                val angle = min(PI, Math.toRadians(2 * s.bearingAccuracyDegrees!!))
                return speedError + 2 * (s.speedMps!! + speedError) * sin(angle / 2)
            }
            val (oldEast, oldNorth) = vector(last)
            val (newEast, newNorth) = vector(sample)
            east += (oldEast + newEast) * 0.5 * dt
            north += (oldNorth + newNorth) * 0.5 * dt
            estimatedError += (estimatedVelocityError(last) + estimatedVelocityError(sample)) * 0.5 * dt
        }
        previous = sample
        return Estimate(east, north, estimatedError)
    }
}
