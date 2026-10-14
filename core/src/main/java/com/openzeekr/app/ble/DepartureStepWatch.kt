package com.openzeekr.app.ble

/**
 * Walking and stopping from the step total, for the BLE departure proof (0.3.2, simulation audit C).
 *
 * Activity Recognition on the S24+ with the screen off reports MOVING 4-18 s after the first step of
 * a walk, sometimes not at all for a whole walk, and STILL 3-10 s after a stop. The step total (the hub
 * counter keeps counting while the CPU sleeps) answers both sooner:
 * - [walking]: the total rose within the last [WALKING_MS] - a walk is under way, whatever the motion
 *   state says (simulator s24-off: the walk proof stayed "not moving" for the whole walk away and the
 *   car stayed open until the alarm);
 * - [stopped]: a live count (at least [STOP_MIN_RISES] rises within [RATE_WINDOW_MS]) has not risen for
 *   max([STOP_MIN_MS], 1.5 x its mean interval) - the user stands still while the motion state still
 *   says MOVING (stress F04: the walk proof completed during a stop 5 m from the car).
 * Fed the merged step total on every fresh reading. Pure Kotlin.
 */
internal class DepartureStepWatch {
    private var last: Long? = null
    private var lastRiseAtMs = UNSET_MS
    private val rises = ArrayDeque<Long>()

    fun observe(nowMs: Long, steps: Long?) {
        val s = steps ?: return
        val previous = last
        last = s
        if (previous != null && s > previous) { lastRiseAtMs = nowMs; rises.addLast(nowMs) }
        while (rises.isNotEmpty() && nowMs - rises.first() > RATE_WINDOW_MS) rises.removeFirst()
    }

    fun walking(nowMs: Long): Boolean = lastRiseAtMs != UNSET_MS && nowMs - lastRiseAtMs <= WALKING_MS

    fun stopped(nowMs: Long): Boolean {
        if (rises.size < STOP_MIN_RISES || lastRiseAtMs == UNSET_MS) return false
        val meanIntervalMs = (rises.last() - rises.first()) / (rises.size - 1)
        return nowMs - lastRiseAtMs > maxOf(STOP_MIN_MS, meanIntervalMs * 3 / 2)
    }

    private companion object {
        const val UNSET_MS = Long.MIN_VALUE
        const val WALKING_MS = 5_000L
        const val RATE_WINDOW_MS = 10_000L
        const val STOP_MIN_RISES = 4
        const val STOP_MIN_MS = 2_000L
    }
}
