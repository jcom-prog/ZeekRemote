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
        prune(nowMs)
        if (starts.size >= MAX_STARTS) return false
        starts.addLast(nowMs)
        return true
    }

    /** 0.3.21: the starts still allowed now (nothing counted). */
    @Synchronized fun free(nowMs: Long): Int { prune(nowMs); return MAX_STARTS - starts.size }

    /** 0.3.21: how long until a start is allowed again; 0 = now. */
    @Synchronized fun waitMs(nowMs: Long): Long {
        prune(nowMs)
        return if (starts.size < MAX_STARTS) 0L else starts.first() + WINDOW_MS - nowMs
    }

    private fun prune(nowMs: Long) {
        while (starts.isNotEmpty() && (nowMs - starts.first() >= WINDOW_MS || nowMs < starts.first())) starts.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val MAX_STARTS = 4
    }
}
