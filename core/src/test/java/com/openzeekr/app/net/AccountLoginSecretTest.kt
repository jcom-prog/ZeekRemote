package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountLoginSecretTest {
    @Test
    fun `official app secret is used directly for xchanger signing`() {
        assertEquals("official-app-secret", requireOfficialAppSecret("official-app-secret", ""))
    }

    @Test
    fun `blank official app secret fails closed`() {
        assertThrows(IllegalArgumentException::class.java) { requireOfficialAppSecret("", "") }
        assertThrows(IllegalArgumentException::class.java) { requireOfficialAppSecret("   ", "   ") }
    }
}

class FriendlyLoginErrorTest {
    @Test
    fun `9007 explains the region and a server message is preferred`() {
        assertEquals(true, friendlyLoginError("9007", null)?.contains("region"))
        assertEquals(null, friendlyLoginError("9007", "user not exist"))
        assertEquals(null, friendlyLoginError("1234", null))
    }
}
