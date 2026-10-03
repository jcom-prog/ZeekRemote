package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloginPolicyTest {
    private val min = 60_000L

    @Test fun firstAutomaticAttemptIsAllowedThenAtMostOncePerTenMinutes() {
        assertTrue(ReloginPolicy.autoAllowed(1_000_000L, 0L, 0))
        assertFalse(ReloginPolicy.autoAllowed(1_000_000L + 9 * min, 1_000_000L, 1))
        assertTrue(ReloginPolicy.autoAllowed(1_000_000L + 10 * min, 1_000_000L, 1))
    }

    @Test fun threeFailuresInARowStopAutomaticAttempts() {
        assertTrue(ReloginPolicy.autoAllowed(10_000_000L, 1_000L, ReloginPolicy.MAX_AUTO_FAILURES - 1))
        assertFalse(ReloginPolicy.autoAllowed(10_000_000L, 1_000L, ReloginPolicy.MAX_AUTO_FAILURES))
        assertFalse("stays stopped hours later", ReloginPolicy.autoAllowed(100_000_000L, 1_000L, 3))
    }

    @Test fun aClockSetBackDoesNotBlockRenewalForever() {
        assertTrue(ReloginPolicy.autoAllowed(1_000L, 5_000_000L, 0))
    }

    @Test fun renewsADayAheadOfAKnownExpiryOnly() {
        val exp = 2_000_000_000_000L
        assertFalse(ReloginPolicy.expiresSoon(exp - 2 * ReloginPolicy.RENEW_BEFORE_EXPIRY_MS, exp))
        assertTrue(ReloginPolicy.expiresSoon(exp - ReloginPolicy.RENEW_BEFORE_EXPIRY_MS + 1, exp))
        assertTrue(ReloginPolicy.expiresSoon(exp + 1, exp))
        assertFalse("unknown expiry", ReloginPolicy.expiresSoon(exp, 0L))
    }

    @Test fun onlyTokenExpiredTriggersAndNotSignedInElsewhere() {
        assertTrue(ReloginPolicy.isTokenExpired("""{"code":"079012","msg":"Token expired"}"""))
        assertFalse(ReloginPolicy.isTokenExpired("""{"code":"079021","msg":"logged in elsewhere"}"""))
        assertFalse(ReloginPolicy.isTokenExpired(null))
    }

    @Test fun jwtExpiryIsSecondsAndImplausibleValuesAreIgnored() {
        assertEquals(1_790_000_000_000L, ReloginPolicy.expiryMs("1790000000"))
        assertNull(ReloginPolicy.expiryMs("1790000000000"))
        assertNull(ReloginPolicy.expiryMs("abc"))
        assertNull(ReloginPolicy.expiryMs(null))
    }

    @Test fun onlyTheCodeFieldCountsNotDigitsElsewhere() {
        assertTrue(ReloginPolicy.isTokenExpired("""{"success":false, "code" : "079012"}"""))
        assertFalse(ReloginPolicy.isTokenExpired("""{"code":"000000","data":{"id":"10790123","ts":1079012}}"""))
        assertFalse(ReloginPolicy.isTokenExpired("""{"code":"0790123"}"""))
    }

    @Test fun offlineFailuresAreNotCountedTowardTheStop() {
        assertTrue(ReloginPolicy.isNetworkFailure(java.net.UnknownHostException("no dns")))
        assertTrue(ReloginPolicy.isNetworkFailure(IllegalStateException("wrap", java.net.SocketTimeoutException())))
        assertFalse(ReloginPolicy.isNetworkFailure(IllegalStateException("request failed (9300 ) @ login")))
        assertFalse(ReloginPolicy.isNetworkFailure(null))
    }
}
