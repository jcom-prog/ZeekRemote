package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureCorroborationTest {
    private val anchor = DepartureFix(52.0, 5.0, 4f, 1_000L)
    private fun at(m: Double, t: Long) = DepartureFix(52.0 + m / 111_000.0, 5.0, 4f, t)

    @Test fun withoutStepsTheBleCorroborationIsRequired() {
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(anchor, at(40.0, 20_000L), at(41.0, 22_000L), 0L, 22_100L))
        assertTrue(DepartureSafetyEvidence.confirmsDeparture(anchor, at(40.0, 20_000L), at(41.0, 22_000L), 0L, 22_100L,
            bleCorroborated = true))
    }

    @Test fun withoutStepsTheClearanceIsMuchLarger() {
        // 22 m: enough with steps (4 + 2x8 = 20), not without (12 + 2x8 = 28).
        assertTrue(DepartureSafetyEvidence.confirmsDeparture(anchor, at(22.0, 20_000L), at(22.5, 22_000L), 20L, 22_100L))
        assertFalse(DepartureSafetyEvidence.confirmsDeparture(anchor, at(22.0, 20_000L), at(22.5, 22_000L), 0L, 22_100L,
            bleCorroborated = true))
    }
}

class DepartureJumpTest {
    private val anchor = DepartureFix(52.0, 5.0, 4f, 1_000L)
    private fun at(m: Double, t: Long) = DepartureFix(52.0 + m / 111_000.0, 5.0, 4f, t)

    @Test fun aGnssJumpBesideTheCarNeverProvesADeparture() {
        val w = DepartureObservationWindow(anchor, 10_000L)
        var t = 10_500L
        assertFalse(w.observe(at(1.0, t), 30L, t + 10)); t += 1_000L
        // Jump to 30 m for 6 s (multipath), then back.
        repeat(6) { assertFalse(w.observe(at(30.0, t), 30L, t + 10)); t += 1_000L }
        repeat(5) { assertFalse(w.observe(at(1.5, t), 30L, t + 10)); t += 1_000L }
    }

    @Test fun aRealWalkStillConfirms() {
        val w = DepartureObservationWindow(anchor, 10_000L)
        var t = 10_500L; var d = 2.0; var ok = false
        repeat(20) { ok = ok or w.observe(at(d, t), 30L, t + 10); t += 1_000L; d += 1.4 }
        assertTrue(ok)
    }
}
