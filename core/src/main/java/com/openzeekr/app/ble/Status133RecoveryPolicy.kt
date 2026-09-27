package com.openzeekr.app.ble

/** Bounded recovery choice for a status-133 failure during initial DK connection setup. */
internal class Status133RecoveryPolicy {
    enum class Route { RETRY_RECENT_ROUTE, FRESH_SCAN }

    private var directRetryUsed = false
    private var consecutiveFailures = 0
    private var weakBackoffUntilMs = 0L

    @Synchronized fun onFailure(hasRecentRoute: Boolean, nowMs: Long = 0L): Route {
        consecutiveFailures++
        if (consecutiveFailures >= WEAK_BACKOFF_AFTER_FAILURES && nowMs > 0L) {
            weakBackoffUntilMs = nowMs + WEAK_BACKOFF_MS
        }
        if (hasRecentRoute && !directRetryUsed) {
            directRetryUsed = true
            return Route.RETRY_RECENT_ROUTE
        }
        directRetryUsed = false
        return Route.FRESH_SCAN
    }

    /** After repeated failures, wait for a stronger fresh advert; never defer a door-range hit. */
    @Synchronized fun shouldDeferWeakPresence(rssi: Int, nowMs: Long): Boolean {
        if (weakBackoffUntilMs == 0L) return false
        if (nowMs >= weakBackoffUntilMs) {
            weakBackoffUntilMs = 0L
            consecutiveFailures = 0
            return false
        }
        return rssi < WEAK_PRESENCE_LIMIT_RSSI
    }

    @Synchronized fun onSessionReady() {
        directRetryUsed = false
        consecutiveFailures = 0
        weakBackoffUntilMs = 0L
    }

    private companion object {
        const val WEAK_BACKOFF_AFTER_FAILURES = 4
        const val WEAK_PRESENCE_LIMIT_RSSI = -90
        const val WEAK_BACKOFF_MS = 10_000L
    }
}
