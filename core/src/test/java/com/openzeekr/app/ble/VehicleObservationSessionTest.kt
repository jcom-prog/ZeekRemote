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
}
