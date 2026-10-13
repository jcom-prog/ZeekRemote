package com.openzeekr.app.ble

import com.openzeekr.app.ble.WeakConnectGate.PresenceAction.CONNECT
import com.openzeekr.app.ble.WeakConnectGate.PresenceAction.FOREGROUND_SCAN
import com.openzeekr.app.ble.WeakConnectGate.PresenceAction.IGNORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakConnectGateTest {
    @Test fun `edge-of-range adverts never start a connection (field 13-10 shape)`() {
        // Phone at home, car outside: presence hits at -98..-100, every connect ended in 133.
        listOf(-100, -99, -98, -97, -96, -95).forEach {
            assertTrue("$it", WeakConnectGate.tooWeak(it))
            assertEquals(IGNORE, WeakConnectGate.onPresence(it, moving = false))
        }
    }

    @Test fun `a weak hit while walking hands over to the foreground scan`() {
        assertEquals(FOREGROUND_SCAN, WeakConnectGate.onPresence(-98, moving = true))
    }

    @Test fun `in range and door-range adverts connect at once, moving or not`() {
        listOf(-94, -90, -84, -78, -60).forEach {
            assertFalse("$it", WeakConnectGate.tooWeak(it))
            assertEquals(CONNECT, WeakConnectGate.onPresence(it, moving = false))
            assertEquals(CONNECT, WeakConnectGate.onPresence(it, moving = true))
        }
    }
}
