package com.openzeekr.app.ble

import kotlinx.coroutines.CompletableDeferred

/** One callback belongs to one connection and one outstanding request. No cached RSSI fallback. */
internal class PendingRssiRead {
    data class Sample(val rssi: Int, val elapsedAtMs: Long) {
        fun valueAt(nowMs: Long): Int? =
            if (elapsedAtMs <= nowMs && nowMs - elapsedAtMs <= 1_000L) rssi else null
    }
    class Request internal constructor(val connection: Any) {
        val reply = CompletableDeferred<Sample?>()
    }
    private var connection: Any? = null
    private var pending: Request? = null

    @Synchronized fun begin(current: Any): Request? {
        if (connection !== current) {
            val abandoned = pending
            connection = current
            return Request(current).also {
                pending = it
                abandoned?.reply?.complete(null)
            }
        }
        // Keep a timed-out/cancelled read until its callback drains or the connection resets.
        // Otherwise its late callback could be mistaken for the next request on the same GATT.
        if (pending != null) return null
        return Request(current).also { pending = it }
    }

    @Synchronized fun response(current: Any, value: Int?, elapsedAtMs: Long) {
        if (connection !== current) return
        val request = pending ?: return
        pending = null
        request.reply.complete(value?.let { Sample(it, elapsedAtMs) })
    }

    @Synchronized fun rejected(request: Request) {
        if (pending !== request) return
        pending = null
        request.reply.complete(null)
    }

    @Synchronized fun clear() {
        val abandoned = pending
        pending = null
        connection = null
        abandoned?.reply?.complete(null)
    }
}
