package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class Status133RecoveryPolicyTest {
    @Test
    fun firstFailureRetriesRecentPresenceRouteThenFallsBackToFreshScan() {
        val policy = Status133RecoveryPolicy()
        assertEquals(Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE, policy.onFailure(true))
        assertEquals(Status133RecoveryPolicy.Route.FRESH_SCAN, policy.onFailure(true))
    }

    @Test
    fun missingRouteAlwaysUsesFreshScan() {
        val policy = Status133RecoveryPolicy()
        assertEquals(Status133RecoveryPolicy.Route.FRESH_SCAN, policy.onFailure(false))
    }

    @Test
    fun successfulSessionResetsTheOneRetryBudget() {
        val policy = Status133RecoveryPolicy()
        assertEquals(Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE, policy.onFailure(true))
        policy.onSessionReady()
        assertEquals(Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE, policy.onFailure(true))
    }
}
