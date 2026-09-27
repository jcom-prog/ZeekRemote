package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class Status133RecoveryPolicyTest {
    @Test
    fun repeatedWeakFailuresPauseOnlyWeakScansAndRecoverAtStrongPresence() {
        val policy = Status133RecoveryPolicy()
        assertEquals(Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE, policy.onFailure(true, 1_000))
        assertEquals(Status133RecoveryPolicy.Route.FRESH_SCAN, policy.onFailure(true, 1_300))
        assertEquals(false, policy.shouldDeferWeakPresence(-97, 1_500))
        assertEquals(Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE, policy.onFailure(true, 2_000))
        assertEquals(Status133RecoveryPolicy.Route.FRESH_SCAN, policy.onFailure(true, 2_300))
        assertEquals(true, policy.shouldDeferWeakPresence(-97, 2_500))
        assertEquals(false, policy.shouldDeferWeakPresence(-80, 2_500))
        policy.onSessionReady()
        assertEquals(false, policy.shouldDeferWeakPresence(-97, 2_600))
    }

    @Test
    fun weakPresenceBackoffExpiresSoAttenuatedPhonesCanRetry() {
        val policy = Status133RecoveryPolicy()
        repeat(4) { policy.onFailure(true, 1_000L + it * 100L) }
        assertEquals(true, policy.shouldDeferWeakPresence(-96, 2_000L))
        assertEquals(false, policy.shouldDeferWeakPresence(-96, 11_301L))
    }

    @Test
    fun isolated133KeepsEarlierSuccessfulFastRetryRoute() {
        val policy = Status133RecoveryPolicy()
        assertEquals(Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE, policy.onFailure(true, 1_000L))
        assertEquals(false, policy.shouldDeferWeakPresence(-95, 1_100L))
        policy.onSessionReady()
        assertEquals(Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE, policy.onFailure(true, 2_000L))
        assertEquals(false, policy.shouldDeferWeakPresence(-95, 2_100L))
    }
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
