package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountLoginSecretTest {
    @Test
    fun `official app secret is used directly for xchanger signing`() {
        assertEquals("official-app-secret", requireOfficialAppSecret("official-app-secret"))
    }

    @Test
    fun `blank official app secret fails closed`() {
        assertThrows(IllegalArgumentException::class.java) { requireOfficialAppSecret("") }
        assertThrows(IllegalArgumentException::class.java) { requireOfficialAppSecret("   ") }
    }
}
