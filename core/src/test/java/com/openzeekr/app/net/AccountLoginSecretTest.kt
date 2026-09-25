package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountLoginSecretTest {
    @Test
    fun `xchanger-specific secret takes precedence`() {
        assertEquals("xchanger", requireXchangerSignSecret("xchanger"))
    }

    @Test
    fun `blank xchanger secret fails instead of using another key`() {
        assertThrows(IllegalArgumentException::class.java) { requireXchangerSignSecret("") }
        assertThrows(IllegalArgumentException::class.java) { requireXchangerSignSecret("   ") }
    }
}
