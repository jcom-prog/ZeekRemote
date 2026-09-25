package com.openzeekr.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpLogTest {
    @Test
    fun `scrubs sensitive json fields`() {
        val raw = """{"email":"person@example.com","vin":"TESTVIN123","authCode":"abc123","accessToken":"secret-token"}"""
        val scrubbed = HttpLog.scrub(raw)
        listOf("person@example.com", "TESTVIN123", "abc123", "secret-token").forEach {
            assertFalse(scrubbed.contains(it))
        }
        assertTrue(scrubbed.contains("***"))
    }

    @Test
    fun `scrubs bearer and jwt without relying on field name`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature"
        val scrubbed = HttpLog.scrub("Authorization: Bearer opaque.token-value payload=$jwt")
        assertFalse(scrubbed.contains("opaque.token-value"))
        assertFalse(scrubbed.contains(jwt))
    }
}
