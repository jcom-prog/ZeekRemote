package com.openzeekr.app.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DkLockControllerTest {

    @Test
    fun confirmedManualLockNotifiesLockCallbackOnly() = runBlocking {
        val session = FakeSession(mutableListOf(ControlResult.CONFIRMED))
        var unlocks = 0
        var locks = 0
        val controller = DkLockController(
            session = session,
            onUnlockConfirmed = { unlocks++ },
            onLockConfirmed = { locks++ },
        )

        assertTrue(controller.lock())
        assertEquals(0, unlocks)
        assertEquals(1, locks)
    }

    @Test
    fun failedManualLockDoesNotLatchDeparture() = runBlocking {
        val session = FakeSession(mutableListOf(ControlResult.REJECTED))
        var locks = 0
        val controller = DkLockController(session = session, onLockConfirmed = { locks++ })

        assertFalse(controller.lock())
        assertEquals(0, locks)
    }

    @Test
    fun refreshedConfirmedLockNotifiesExactlyOnce() = runBlocking {
        val session = FakeSession(
            mutableListOf(ControlResult.NO_RESPONSE, ControlResult.CONFIRMED),
        )
        var refreshes = 0
        var locks = 0
        val controller = DkLockController(
            session = session,
            refresh = { refreshes++; true },
            onLockConfirmed = { locks++ },
        )

        assertTrue(controller.lock())
        assertEquals(1, refreshes)
        assertEquals(1, locks)
    }

    private class FakeSession(
        private val results: MutableList<ControlResult>,
    ) : DkSession {
        override val isEstablished = true
        override suspend fun establish() = Unit
        override suspend fun sendFrame(cmd: Int, payload: ByteArray) = true
        override suspend fun control(ctrl: Byte, timeoutMs: Long): ControlResult = results.removeAt(0)
        override suspend fun ping(timeoutMs: Long) = true
        override fun answerChallenge(randX: Int, randY: Int) = byteArrayOf()
        override fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit) = Unit
        override fun close() = Unit
    }
}
