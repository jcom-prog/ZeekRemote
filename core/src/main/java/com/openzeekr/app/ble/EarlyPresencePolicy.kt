package com.openzeekr.app.ble

/** A motion edge may scan briefly; only confirmed motion may establish a key session. */
internal object EarlyPresencePolicy {
    fun mayPrepare(sleeping: Boolean, moving: Boolean, watchBorrowing: Boolean): Boolean =
        sleeping && moving && !watchBorrowing

    fun mayRetain(sleeping: Boolean, moving: Boolean, deadlineMs: Long, nowMs: Long): Boolean =
        sleeping && moving && deadlineMs > nowMs

    fun mayConnect(motionConfirmed: Boolean, watchBorrowing: Boolean,
                   deadlineMs: Long, nowMs: Long): Boolean =
        motionConfirmed && !watchBorrowing && deadlineMs > nowMs

    fun freshAdvertisement(timestampNs: Long, nowNs: Long, maxAgeMs: Long): Boolean =
        timestampNs > 0 && nowNs >= timestampNs &&
            nowNs - timestampNs <= maxAgeMs * 1_000_000L
}
