package com.openzeekr.app.util

/**
 * Wall-clock-scaled time that never jumps (plan H7, 0.3.1). The proximity logic measures intervals
 * on "wall" milliseconds: proof ages, alarm delays, reconnect windows, backoffs. When the phone's
 * clock is set - by hand, or by a network that sends a wrong time abroad - an interval measured
 * across the change becomes hours long or negative: a 6-second "recent strong reading" veto could
 * last an hour, or a 30-second wait end at once. This clock is the wall time of the moment the
 * process started, advanced by the monotonic elapsed-realtime clock (which includes deep sleep and
 * is never set), so intervals stay true. Values remain epoch milliseconds for logs and storage.
 *
 * Not for anything another device checks against real time (the digital-key frame timestamp keeps
 * [System.currentTimeMillis]).
 */
object SteadyClock {
    private val time = SteadyTime({ System.currentTimeMillis() }, { android.os.SystemClock.elapsedRealtime() })

    fun wallMs(): Long = time.wallMs()
}

/** [SteadyClock] with injectable sources (tests). */
internal class SteadyTime(wall: () -> Long, private val elapsed: () -> Long) {
    private val anchorWallMs = wall()
    private val anchorElapsedMs = elapsed()

    fun wallMs(): Long = anchorWallMs + (elapsed() - anchorElapsedMs)
}
