package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountLoginSecretTest {
    @Test
    fun `xchanger-specific secret takes precedence`() {
        assertEquals("xchanger", resolveXchangerSignSecret("xchanger", "prod"))
    }

    @Test
    fun `blank xchanger secret falls back to prod secret`() {
        assertEquals("prod", resolveXchangerSignSecret("", "prod"))
        assertEquals("prod", resolveXchangerSignSecret("   ", "prod"))
    }
}
