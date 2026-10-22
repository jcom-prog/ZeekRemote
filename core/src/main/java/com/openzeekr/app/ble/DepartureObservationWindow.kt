package com.openzeekr.app.ble

/**
 * 0.3.25: the position this visit last knew for certain - the departure anchor, refreshed at every strong reading at
 * the car, then the latest fix of a proof window that was neither rejected nor inside a jump - and the step count
 * then. Shared by the proof windows of one visit, so a jump that straddles two windows is seen by the second one.
 */
internal class DepartureFixMemory(anchor: DepartureFix, stepsAtAnchor: Long?) {
    var lastKnown: DepartureFix = anchor
        private set
    var stepsAtLastKnown: Long? = stepsAtAnchor
        private set

    fun learn(fix: DepartureFix, steps: Long?) { lastKnown = fix; stepsAtLastKnown = steps }
}

/** A bad early fix must not finish departure acquisition. This window authorizes only the
 * existing conservative two-position evidence; neither elapsed time nor bad GPS is a fallback.
 */
internal class DepartureObservationWindow(
    private val anchor: DepartureFix,
    private val startedAtMs: Long,
    /** 0.3.25: what this visit's earlier windows learnt (null: nothing beyond the anchor). */
    private val memory: DepartureFixMemory? = null,
) {
    private var first: DepartureFix? = null
    private var lastValid: DepartureFix? = null
    private var quarantineUntilMs = 0L
    private var continuityChecked = false
    var outcome: String = "waiting_for_pair"
        private set

    fun observe(fix: DepartureFix?, steps: Long?, nowMs: Long, bleCorroborated: Boolean = false,
                /** 0.3.25: when the phone was last known to be moving (fix clock); Long.MAX_VALUE = it may be moving now. */
                lastMotionAtMs: Long = Long.MAX_VALUE): Boolean {
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
        // 0.3.25 (simulator F49, 22/10): the window's first fix has no earlier fix of its own to be caught against, so
        // a jump that began before it, or straddled two windows, passed: the phone lay still beside the car, the next
        // window opened inside a jump and its pair locked the car at 4 m. The first fix is held to the last position
        // this visit knew for certain (the anchor, a strong reading at the car, or the previous window's last fix
        // outside any jump): at walking speed - and to no displacement at all while the phone has reported neither
        // motion nor a step since then, because a motionless phone has not left that position. Asked once per window:
        // the quarantine outlasts a jump, and a motion stack that misses a walk (S24+, screen off) may cost one delay,
        // never the proof.
        if (before == null && !continuityChecked) {
            continuityChecked = true
            val known = memory?.lastKnown ?: anchor
            val motionless = lastMotionAtMs <= known.elapsedAtMs && steps != null && steps == memory?.stepsAtLastKnown
            if (DepartureSafetyEvidence.implausibleDisplacement(known, current, motionless)) {
                quarantineUntilMs = nowMs + JUMP_QUARANTINE_MS
                first = null
                outcome = "departure_fix_implausible"
                return false
            }
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
        memory?.learn(current, steps)
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
