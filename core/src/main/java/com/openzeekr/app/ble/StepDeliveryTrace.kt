package com.openzeekr.app.ble

/**
 * Diagnose (0.3.2): when the step count actually reaches the app while the car is heard.
 *
 * Field 14/10 18:31-18:33 (S24+, front trouser pocket): the self-calibration did not learn the
 * arrival and counted "11 steps" for a ~20 s walk away. The count is the larger of the step detector
 * and the sensor hub's step counter (flushed at most once a second); if the hub hands over its
 * steps late or in batches, a walk looks like a stop followed by a jump. This trace shows that
 * pattern without logging every step: at most one line per [minIntervalMs] while the count
 * changes, with how many updates arrived in that time and the longest wait between two of them.
 * Pure Kotlin; no positions, no identifiers.
 */
internal class StepDeliveryTrace(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var lastSteps: Long? = null
    private var lastChangeAtMs = 0L
    private var lineSteps = 0L
    private var lineStartMs = 0L
    private var updates = 0
    private var longestWaitMs = 0L

    /** One observation of the cumulative count; returns a log line when one is due, else null. */
    fun observe(nowMs: Long, steps: Long?): String? {
        if (steps == null) return null
        val previous = lastSteps
        if (previous == null || steps < previous) {
            // First observation, or the count restarted: a new baseline, nothing to report.
            lastSteps = steps; lastChangeAtMs = nowMs
            lineSteps = steps; lineStartMs = nowMs; updates = 0; longestWaitMs = 0L
            return null
        }
        if (steps == previous) return null
        updates++
        longestWaitMs = maxOf(longestWaitMs, nowMs - lastChangeAtMs)
        lastSteps = steps; lastChangeAtMs = nowMs
        if (nowMs - lineStartMs < minIntervalMs) return null
        val line = "steps +%d in %.1f s (%d update%s, longest wait %.1f s)".format(
            steps - lineSteps, (nowMs - lineStartMs) / 1000.0, updates, if (updates == 1) "" else "s",
            longestWaitMs / 1000.0)
        lineSteps = steps; lineStartMs = nowMs; updates = 0; longestWaitMs = 0L
        return line
    }

    private companion object { const val MIN_INTERVAL_MS = 3_000L }
}
