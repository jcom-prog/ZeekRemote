package com.openzeekr.app.net


import org.junit.Assert.assertEquals
import org.junit.Test


class OfficialHfSigningTest {
    @Test
    fun `baked official secret wins over stale imported value`() {
        assertEquals("official", requireOfficialAppSecret("official", "stale"))
    }

    @Test
    fun `configured official secret is used by clean builds`() {
        assertEquals("configured", requireOfficialAppSecret("", "configured"))
    }

    @Test
    fun `matches independently calculated stock SignUtil vector`() {
        val signature = officialHfSign(
            signSecret = "test-secret",
            url = "https://api-zk.ecloudeu.com/auth/account/session/secure?identity_type=zeekr",
            method = "POST",
            body = "{\"authCode\":\"sample-code\"}",
            nonce = "12345678-1234-1234-1234-123456789abc",
            sigVersion = "1.0",
            timestamp = "1790366400000",
            accept = "application/json;responseformat=3",
        )


        assertEquals("J7fuipFEIRNJdFtXd+JWSUHMpkc=", signature)
    }
}
