package com.openzeekr.app.ble

/** Retry a failed cloud observation near the car, with a firm request window. */
internal object RelockProbeSchedule {
    const val INTERVAL_MS = 5_000L
    const val WINDOW_MS = 45_000L

    fun expired(firstAtMs: Long, nowMs: Long): Boolean =
        firstAtMs != 0L && nowMs - firstAtMs > WINDOW_MS

    fun due(lastAtMs: Long, nowMs: Long): Boolean =
        lastAtMs == 0L || nowMs - lastAtMs >= INTERVAL_MS
}
