package com.openzeekr.app.ble

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ephemeral positions used only while deciding whether the phone has actually left the car. */
internal data class DepartureFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val elapsedAtMs: Long,
)

/** RSSI and steps alone cannot distinguish a body-shadowed phone at the car from departure. */
internal object DepartureSafetyEvidence {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MAX_ACCURACY_M = 8f
    private const val MAX_FIX_AGE_MS = 5_000L
    private const val MIN_FIX_SEPARATION_MS = 1_500L
    private const val MIN_STEPS = 10L
    private const val REQUIRED_CLEARANCE_M = 4.0
    /** Without a step count the GNSS pair must clear the car by much more (review 0.1.63). */
    private const val CORROBORATED_CLEARANCE_M = 12.0
    private const val RETURN_PROGRESS_M = 2.0

    /** Categorical reasons only; never include a phone or vehicle position in logs. */
    fun diagnostic(
        anchor: DepartureFix?, first: DepartureFix?, second: DepartureFix?,
        stepsSinceUnlock: Long?, nowElapsedMs: Long, bleCorroborated: Boolean = false,
    ): String = when {
        anchor == null -> "location anchor unavailable"
        first == null || second == null -> "fresh location unavailable"
        !valid(anchor) || !valid(first) || !valid(second) -> "location accuracy invalid or insufficient"
        !bleCorroborated && (stepsSinceUnlock == null || stepsSinceUnlock < MIN_STEPS) -> "too few observed steps"
        first.elapsedAtMs <= anchor.elapsedAtMs ||
            second.elapsedAtMs - first.elapsedAtMs < MIN_FIX_SEPARATION_MS ||
            second.elapsedAtMs > nowElapsedMs ||
            nowElapsedMs - second.elapsedAtMs > MAX_FIX_AGE_MS -> "location timing invalid"
        distanceM(anchor, second) < distanceM(anchor, first) - RETURN_PROGRESS_M ->
            "returning toward car"
        else -> "distance lower bound insufficient"
    }

    /**
     * @param bleCorroborated the key link itself shows a sustained clear separation (see
     *  ProximityController.departureBleCorroborated). It replaces the step count, which the S24+
     *  does not deliver with the screen off (field 03/10, simulator): the GNSS pair must still be
     *  clear of the car by twice the reported accuracy, so a stationary GNSS drift alone, or BLE
     *  body shadow alone, never confirms.
     */
    fun confirmsDeparture(
        anchor: DepartureFix?, first: DepartureFix?, second: DepartureFix?,
        stepsSinceUnlock: Long?, nowElapsedMs: Long, bleCorroborated: Boolean = false,
    ): Boolean {
        if (anchor == null || first == null || second == null) return false
        val stepsOk = stepsSinceUnlock != null && stepsSinceUnlock >= MIN_STEPS
        if (!stepsOk && !bleCorroborated) return false
        val clearance = if (stepsOk) REQUIRED_CLEARANCE_M else CORROBORATED_CLEARANCE_M
        if (listOf(anchor, first, second).any { !valid(it) }) return false
        if (first.elapsedAtMs <= anchor.elapsedAtMs ||
            second.elapsedAtMs - first.elapsedAtMs < MIN_FIX_SEPARATION_MS ||
            second.elapsedAtMs > nowElapsedMs ||
            nowElapsedMs - second.elapsedAtMs > MAX_FIX_AGE_MS) return false
        if (distanceM(anchor, second) < distanceM(anchor, first) - RETURN_PROGRESS_M)
            return false // a clearly observed return cancels the command
        return listOf(first, second).all {
            val lowerBoundM = distanceM(anchor, it) - 2.0 * (anchor.accuracyM + it.accuracyM)
            lowerBoundM >= clearance
        }
    }

    internal fun valid(fix: DepartureFix): Boolean =
        fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
            fix.accuracyM > 0f && fix.accuracyM <= MAX_ACCURACY_M && fix.elapsedAtMs > 0L

    /**
     * True when [b] is further from [a] than a walker can move in the time between them, beyond
     * both accuracy radii: a GNSS jump (urban multipath), not a walk.
     */
    fun implausibleJump(a: DepartureFix, b: DepartureFix): Boolean {
        val dtS = (b.elapsedAtMs - a.elapsedAtMs).coerceAtLeast(0L) / 1000.0
        return distanceM(a, b) > MAX_WALK_SPEED_MPS * dtS + a.accuracyM + b.accuracyM
    }

    private const val MAX_WALK_SPEED_MPS = 2.5

    private fun distanceM(a: DepartureFix, b: DepartureFix): Double {
        val latDiff = Math.toRadians(b.latitude - a.latitude)
        val lonDiff = Math.toRadians(b.longitude - a.longitude)
        val h = sin(latDiff / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lonDiff / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}
