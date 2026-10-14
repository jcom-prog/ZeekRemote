package com.openzeekr.app.ble

/**
 * "The walk has stopped", from the step counter (0.3.2). Activity Recognition reports STILL 3-10 s
 * after a stop on the S24+ with the screen off; a live step count stops advancing within ~1 s
 * (the hub counter is flushed at most once a second). Simulator F13/F31/F14: the walking approach
 * route opened the car 3-8 s after the user stopped at 8-12 m, because the motion state still said
 * MOVING.
 *
 * Fail-safe: the gate only vetoes once the count was seen advancing in this walk (at least twice);
 * no step sensor, a count that never moves (warm-up, no delivery) or a fresh Activity Recognition
 * MOVING edge after the stall leave it open. The stall threshold adapts to the delivery rhythm:
 * at least [minStallMs], and 1.6 x the largest of the last four intervals between advances (a
 * late-observed advance under CPU sleep makes the next interval look short). Pure Kotlin.
 */
internal class StepStallGate(private val minStallMs: Long = MIN_STALL_MS) {
    private var lastSteps: Long? = null
    private var lastChangeAtMs = 0L
    private var advances = 0
    private val intervals = ArrayDeque<Long>()
    private var lastMovingEdgeAtMs = Long.MIN_VALUE

    /** Activity Recognition reported a new walk (lifts a stall). */
    fun onMovingEdge(nowMs: Long) { lastMovingEdgeAtMs = nowMs }

    /** Milliseconds since a live step count last advanced (0 while advancing, unknown or not live). */
    var stalledForMs = 0L
        private set

    /** One observation of the cumulative count; returns false only for a live count that stopped advancing. */
    fun walking(nowMs: Long, steps: Long?): Boolean {
        stalledForMs = 0L
        val s = steps ?: return true
        val last = lastSteps
        if (last == null || s < last) {
            lastSteps = s; lastChangeAtMs = nowMs; advances = 0; intervals.clear()
            return true
        }
        if (s > last) {
            if (nowMs - lastChangeAtMs > NEW_WALK_GAP_MS) { advances = 0; intervals.clear() }
            else if (advances > 0) { intervals.addLast(nowMs - lastChangeAtMs); while (intervals.size > 8) intervals.removeFirst() }
            advances++
            lastSteps = s; lastChangeAtMs = nowMs
            return true
        }
        if (advances < 2 || lastMovingEdgeAtMs > lastChangeAtMs) return true
        stalledForMs = nowMs - lastChangeAtMs
        val typical = intervals.toList().takeLast(4).maxOrNull() ?: 0L
        return stalledForMs <= maxOf(minStallMs, typical * 8 / 5)
    }

    private companion object {
        const val MIN_STALL_MS = 2_500L
        /** A pause this long starts a new walk: its rhythm is learned afresh. */
        const val NEW_WALK_GAP_MS = 30_000L
    }
}
