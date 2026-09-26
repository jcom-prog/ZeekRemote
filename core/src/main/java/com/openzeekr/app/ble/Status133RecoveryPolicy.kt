package com.openzeekr.app.ble

/** Bounded recovery choice for a status-133 failure during initial DK connection setup. */
internal class Status133RecoveryPolicy {
    enum class Route { RETRY_RECENT_ROUTE, FRESH_SCAN }

    private var directRetryUsed = false

    fun onFailure(hasRecentRoute: Boolean): Route {
        if (hasRecentRoute && !directRetryUsed) {
            directRetryUsed = true
            return Route.RETRY_RECENT_ROUTE
        }
        directRetryUsed = false
        return Route.FRESH_SCAN
    }

    fun onSessionReady() {
        directRetryUsed = false
    }
}
