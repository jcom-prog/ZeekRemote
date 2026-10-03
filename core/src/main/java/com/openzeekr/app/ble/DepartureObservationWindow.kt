package com.openzeekr.app.ble

/** A bad early fix must not finish departure acquisition. This window authorizes only the
 * existing conservative two-position evidence; neither elapsed time nor bad GPS is a fallback.
 */
internal class DepartureObservationWindow(
    private val anchor: DepartureFix,
    private val startedAtMs: Long,
) {
    private var first: DepartureFix? = null
    private var lastValid: DepartureFix? = null
    private var quarantineUntilMs = 0L
    var outcome: String = "waiting_for_pair"
        private set

    fun observe(fix: DepartureFix?, steps: Long?, nowMs: Long, bleCorroborated: Boolean = false): Boolean {
        if (nowMs < startedAtMs || nowMs - startedAtMs >= WINDOW_MS) {
            outcome = "observation_expired"
            first = null
            return false
        }
        val rejected = DepartureAnchorDiagnostic.rejection(fix, startedAtMs, nowMs)
        if (rejected != null) {
            outcome = rejected
            first = null
            return false
        }
        val current = requireNotNull(fix)
        // A position that jumps faster than anyone walks (multipath) voids the pair and blocks
        // confirmation for a while, both when the jump starts and when it ends (review 0.1.63,
        // simulator with GNSS drift: a 10-25 m jump beside the car otherwise "proved" a departure).
        val before = lastValid
        lastValid = current
        if (before != null && DepartureSafetyEvidence.implausibleJump(before, current)) {
            quarantineUntilMs = nowMs + JUMP_QUARANTINE_MS
            first = null
        }
        if (nowMs < quarantineUntilMs) {
            outcome = "departure_position_jump"
            first = null
            return false
        }
        if (current.elapsedAtMs < startedAtMs || nowMs - current.elapsedAtMs > 1_500L) {
            outcome = "departure_fix_stale"
            first = null
            return false
        }
        val previous = first
        if (previous == null) {
            first = current
            outcome = "waiting_for_pair"
            return false
        }
        if (current.elapsedAtMs <= previous.elapsedAtMs) {
            outcome = "departure_time_not_advancing"
            return false
        }
        if (current.elapsedAtMs - previous.elapsedAtMs > 5_000L) {
            first = current
            outcome = "waiting_for_pair"
            return false
        }
        if (current.elapsedAtMs - previous.elapsedAtMs < 1_500L) {
            outcome = "waiting_for_pair"
            return false
        }
        val confirmed = DepartureSafetyEvidence.confirmsDeparture(anchor, previous, current, steps, nowMs, bleCorroborated)
        outcome = if (confirmed) "departure_confirmed" else when (
            DepartureSafetyEvidence.diagnostic(anchor, previous, current, steps, nowMs, bleCorroborated)
        ) {
            "too few observed steps" -> "departure_steps_insufficient"
            "returning toward car" -> "departure_returning"
            "location timing invalid" -> "departure_timing_invalid"
            "location accuracy invalid or insufficient" -> "departure_accuracy_invalid"
            else -> "departure_clearance_insufficient"
        }
        first = current
        return confirmed
    }

    companion object {
        const val WINDOW_MS = 25_000L
        const val JUMP_QUARANTINE_MS = 10_000L
    }
}
