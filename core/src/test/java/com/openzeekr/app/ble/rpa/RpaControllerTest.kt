package com.openzeekr.app.ble.rpa

import com.openzeekr.app.ble.DkOpcodes
import com.openzeekr.app.ble.DkSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan K4 (0.3.0): the hold-to-move dead-man switch must end even without a finger-up. */
@OptIn(ExperimentalCoroutinesApi::class)
class RpaControllerTest {
    private class FakeSession(private val scope: TestScope) : DkSession {
        val frames = mutableListOf<Triple<Long, Int, ByteArray>>()
        override val isEstablished = true
        override suspend fun establish() {}
        override suspend fun sendFrame(cmd: Int, payload: ByteArray): Boolean {
            frames += Triple(scope.testScheduler.currentTime, cmd, payload); return true
        }
        override suspend fun ping(timeoutMs: Long) = true
        override fun answerChallenge(randX: Int, randY: Int) = byteArrayOf(0, 0)
        override fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit) {}
        override fun close() {}
    }

    private fun FakeSession.moves() = frames.filter { it.second == DkOpcodes.CMD_A2V_RPA_REQ && it.third[1] == RpaReq.CMD_RSPA_FORWARD }
    private fun FakeSession.releases() = frames.filter { it.second == DkOpcodes.CMD_A2V_RPA_REQ && it.third[1] == RpaReq.CMD_RSPA_BOTTOM_RELEASE }
    private fun FakeSession.rssiFrames() = frames.filter { it.second == DkOpcodes.CMD_A2V_RSSI_SYNC }

    @Test fun aHoldEndsByItselfAfterTheLimit() {
        val scope = TestScope()
        val session = FakeSession(scope)
        val rpa = RpaController(session, scope)
        rpa.holdMove(true)
        scope.advanceTimeBy(RpaController.MAX_HOLD_MS + 5_000L); scope.runCurrent()
        val lastMove = session.moves().maxOf { it.first }
        assertTrue("no heartbeat after the limit (last at $lastMove ms)", lastMove < RpaController.MAX_HOLD_MS)
        assertEquals(1, session.releases().size)
        assertEquals(RpaController.Phase.READY, rpa.state.value.phase)
        assertTrue(rpa.state.value.message.orEmpty().contains("safety limit"))
    }

    @Test fun aReleaseStopsTheRssiStreamTheHoldStarted() {
        val scope = TestScope()
        val session = FakeSession(scope)
        val rpa = RpaController(session, scope, rssi = { -60 })
        rpa.holdMove(true)
        scope.advanceTimeBy(3_000L); scope.runCurrent()
        assertTrue(session.rssiFrames().isNotEmpty())
        rpa.releaseMove()
        scope.runCurrent()
        val after = session.rssiFrames().size
        scope.advanceTimeBy(10_000L); scope.runCurrent()
        assertEquals("no RSSI frames after the release", after, session.rssiFrames().size)
        assertTrue(session.moves().all { it.first <= 3_000L })
    }
}
