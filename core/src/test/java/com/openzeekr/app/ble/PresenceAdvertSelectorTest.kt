package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenceAdvertSelectorTest {
    @Test
    fun `complete rnd advert beats stronger uuid-only split advert`() {
        val selected = PresenceAdvertSelector.select(
            listOf(
                PresenceAdvertSelector.Candidate("uuid-only", rssi = -61, hasBroadcastRnd = false),
                PresenceAdvertSelector.Candidate("complete", rssi = -66, hasBroadcastRnd = true),
            ),
        )

        assertEquals("complete", selected)
    }

    @Test
    fun `strongest result wins when both have equal completeness`() {
        val selected = PresenceAdvertSelector.select(
            listOf(
                PresenceAdvertSelector.Candidate("weak", rssi = -80, hasBroadcastRnd = true),
                PresenceAdvertSelector.Candidate("strong", rssi = -64, hasBroadcastRnd = true),
            ),
        )

        assertEquals("strong", selected)
    }

    @Test
    fun `empty callback has no candidate`() {
        assertNull(PresenceAdvertSelector.select<String>(emptyList()))
    }
}
