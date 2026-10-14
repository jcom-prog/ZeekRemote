package com.openzeekr.app.ble

/**
 * Android allows an app five BLE scan starts per 30 s; the sixth is refused silently, and for the
 * rest of the window every scan "runs" without a single result (battery point, plan 0.3.1). Motion
 * edges, status-133 recovery and keep-alive retries can come in bursts, so starts are rationed here
 * with one start of margin: [MAX_STARTS] per [WINDOW_MS]. A refused start is retried by its caller's
 * own loop. Pure Kotlin.
 */
internal class ScanStartLimiter {
    private val starts = ArrayDeque<Long>()

    /** True (and counted) when a scan may start now. */
    @Synchronized fun tryStart(nowMs: Long): Boolean {
        while (starts.isNotEmpty() && (nowMs - starts.first() >= WINDOW_MS || nowMs < starts.first())) starts.removeFirst()
        if (starts.size >= MAX_STARTS) return false
        starts.addLast(nowMs)
        return true
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val MAX_STARTS = 4
    }
}
