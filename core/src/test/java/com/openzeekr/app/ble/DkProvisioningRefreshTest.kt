package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DkProvisioningRefreshTest {
    @Test
    fun `retry signs the refreshed user and vehicle context`() {
        var context = "old-user" to "old-vin"
        val signer = { userId: String, vin: String -> "$userId|$vin" }

        assertEquals("old-user|old-vin", signWithCurrentDkContext({ context }, signer))
        context = "new-user" to "new-vin"
        assertEquals("new-user|new-vin", signWithCurrentDkContext({ context }, signer))
    }

    @Test
    fun `retry fails closed when refreshed vehicle context is incomplete`() {
        assertThrows(IllegalArgumentException::class.java) {
            signWithCurrentDkContext({ "user" to "" }) { _, _ -> "unreachable" }
        }
    }
}
