package com.openzeekr.app.ble

import org.junit.Assert.*
import org.junit.Test

class VehicleObservationSessionTest {
    private fun body(bit: Int = 0) = ByteArray(20).also { it[18] = bit.toByte() }
    @Test fun noObservationBeforeAuthentication() {
        val s = VehicleObservationSession()
        assertNull(s.capture())
        assertNull(s.changedStatus(null, body()))
    }
    @Test fun resetDuringDecryptionRejectsOldObservation() {
        val s = VehicleObservationSession(); s.authenticated()
        val old = s.capture()
        s.reset()
        assertNull(s.changedStatus(old, body()))
        s.authenticated()
        assertNull(s.changedStatus(old, body()))
        assertNotNull(s.changedStatus(s.capture(), body()))
    }
    @Test fun repeatedAuthenticationInvalidatesPriorScope() {
        val s = VehicleObservationSession(); s.authenticated()
        val old = s.capture(); s.authenticated()
        assertNull(s.changedStatus(old, body()))
    }
    @Test fun repeatedStatusIsBoundedButChangesAndNewSessionsAreObserved() {
        val s = VehicleObservationSession(); s.authenticated()
        assertNotNull(s.changedStatus(s.capture(), body()))
        assertNull(s.changedStatus(s.capture(), body()))
        assertNotNull(s.changedStatus(s.capture(), body(64)))
        s.reset(); s.authenticated()
        assertNotNull(s.changedStatus(s.capture(), body(64)))
    }
    @Test fun malformedOrStaleDataCannotConsumeCurrentTransition() {
        val s = VehicleObservationSession(); s.authenticated()
        assertNull(s.changedStatus(s.capture(), ByteArray(19)))
        assertNull(s.changedStatus(-1, body()))
        assertEquals("vehicle status observed session=${s.capture()} approach=0 walkAway=0 pe=0 ps=0 central=0",
            s.changedStatus(s.capture(), body()))
    }
    @Test fun differentSessionObjectsNeverShareAnObservationIdentity() {
        val a = VehicleObservationSession(); val b = VehicleObservationSession()
        a.authenticated(); b.authenticated()
        assertNotEquals(a.capture(), b.capture())
        assertNull(b.changedStatus(a.capture(), body()))
    }

    // ---- 0.2.7: the status the car pushes during the handshake ----

    @Test fun statusPushedDuringTheHandshakeIsAppliedToTheNewSession() {
        // Field 10/10 16:51:34: 0x0121 arrived after the session keys, before "established".
        val s = VehicleObservationSession()
        val pushed = body(64)
        assertNull("held, not applied yet", s.offer(s.capture(), pushed))
        val early = s.authenticated()
        assertArrayEquals(pushed, early)
        assertNotNull(s.changedStatus(s.capture(), early!!))
    }

    @Test fun heldStatusIsDroppedOnResetOrNewHandshake() {
        val s = VehicleObservationSession()
        s.offer(null, body()); s.reset()
        assertNull(s.authenticated())
        s.reset(); s.offer(null, body()); s.handshakeStarted()
        assertNull(s.authenticated())
    }

    @Test fun handshakeFrameLandingJustAfterAuthenticationIsAppliedOnceAndNeverOvertakesNewer() {
        val s = VehicleObservationSession()
        assertNull(s.authenticated())
        val gen = s.capture()
        // Captured before authentication, offered after it: still the newest -> apply now.
        assertEquals(gen, s.offer(null, body()))
        // A normal frame afterwards, then another late handshake frame: the late one is stale.
        assertEquals(gen, s.offer(gen, body(64)))
        assertNull(s.offer(null, body()))
    }
}
