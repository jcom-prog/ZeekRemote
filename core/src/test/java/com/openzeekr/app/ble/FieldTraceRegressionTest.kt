package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mandatory replay gates derived from the physical-car logs for 0.1.24 through 0.1.29. */
class FieldTraceRegressionTest {
    private data class Sample(
        val elapsedMs: Long,
        val rssi: Int,
        val moving: Boolean,
        val presence: Boolean = false,
    )

    @Test
    fun v0124_departureNeverUnlocks() {
        val policy = ProximityDecisionPolicy()
        val decisions = replayLocked(policy, "0.1.24-wrong-direction.csv")
        assertFalse("0.1.24 regression: walking away was classified as arrival", decisions.any { it })
    }

    @Test
    fun v0125_realApproachUnlocksAndPreservesWalkAwayLock() {
        val policy = ProximityDecisionPolicy()
        val trace = trace("0.1.25-arrival-and-walk-away.csv")
        val decisions = trace.map { policy.shouldUnlock(it.elapsedMs, it.rssi, it.moving, UNLOCK_RSSI) }
        assertTrue("captured approach must qualify an unlock", decisions.any { it })

        var now = trace.last().elapsedMs + 200
        policy.onUnlockConfirmed(now)
        repeat(9) { policy.onUnlockedSample(now.also { now += 200 }, -65, true, LOCK_RSSI) }
        var lock = ProximityDecisionPolicy.ArmedDecision.NONE
        listOf(-83, -84, -85, -86, -87, -88, -89, -90, -91, -92, -93, -90).forEach {
            lock = policy.onUnlockedSample(now, it, true, LOCK_RSSI)
            now += 250
        }
        assertEquals(ProximityDecisionPolicy.ArmedDecision.LOCK, lock)
    }

    @Test
    fun v0126_closeStationaryArrivalReachesWireDecision() {
        val policy = ProximityDecisionPolicy()
        val decisions = replayLocked(policy, "0.1.26-close-stationary.csv")
        assertTrue("close stationary arrival was repeatedly rejected before send", decisions.any { it })
    }

    @Test
    fun v0127_transientDipWhileStillDoesNotEraseArrival() {
        val policy = ProximityDecisionPolicy()
        val decisions = replayLocked(policy, "0.1.27-still-dip-then-stronger.csv")
        assertTrue("qualified arrival must survive the captured STILL/RSSI dip", decisions.any { it })
        assertTrue("arrival remains latched while still near", decisions.last())
    }

    @Test
    fun v0128_presenceArrivalQualifiesBeforeDoorRange() {
        val policy = ProximityDecisionPolicy()
        val trace = trace("0.1.28-arrival-late-10s.csv")
        val decisions = replayLocked(policy, trace)
        val first = decisions.indexOfFirst { it }
        assertTrue("0.1.28 regression: arrival must qualify during the handshake", first >= 0)
        assertTrue("arrival must qualify no later than SESSION_READY", trace[first].elapsedMs <= 4_100L)
        assertTrue("arrival must qualify before close-range RSSI", trace[first].rssi <= -82)
    }

    @Test
    fun v0128_status133RecoveryPreservesArrivalAndNeverWaitsForDepartureMotion() {
        val policy = ProximityDecisionPolicy()
        val trace = trace("0.1.28-status133-arrival.csv")
        val decisions = replayLocked(policy, trace)
        val first = decisions.indexOfFirst { it }
        assertTrue("status-133 recovery must preserve the original arrival", first >= 0)
        assertFalse("unlock must be qualified while stationary at the car, not after walking away",
            trace[first].moving)
        assertTrue("unlock evidence must exist before the departure edge",
            trace[first].elapsedMs < 54_843L)
    }

    @Test
    fun v0129PostLockReboundCannotStartAnotherUnlock() {
        val policy = ProximityDecisionPolicy()
        policy.onDepartureLockStarted()
        val decisions = replayLocked(policy, "0.1.29-post-lock-rebound.csv")
        assertFalse("same-session RSSI rebound emitted a second unlock", decisions.any { it })
    }

    @Test
    fun v0129CleanDepartureRemainsLocked() {
        val policy = ProximityDecisionPolicy()
        policy.onDepartureLockStarted()
        val decisions = replayLocked(policy, "0.1.29-clean-post-lock.csv")
        assertFalse("clean departure may never rearm arrival", decisions.any { it })
    }

    private fun replayLocked(policy: ProximityDecisionPolicy, name: String): List<Boolean> =
        replayLocked(policy, trace(name))

    private fun replayLocked(policy: ProximityDecisionPolicy, samples: List<Sample>): List<Boolean> =
        samples.map {
            if (it.presence) policy.onPresenceMatch(it.elapsedMs, it.rssi, it.moving, UNLOCK_RSSI)
            policy.shouldUnlock(it.elapsedMs, it.rssi, it.moving, UNLOCK_RSSI)
        }

    private fun trace(name: String): List<Sample> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/proximity-traces/$name")) { name }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { row ->
                    val value = row.split(',')
                    Sample(
                        elapsedMs = value[0].toLong(),
                        rssi = value[1].toInt(),
                        moving = value[2].toBooleanStrict(),
                        presence = value.getOrNull(3)?.toBooleanStrict() ?: false,
                    )
                }.toList()
        }
    }

    private companion object {
        const val UNLOCK_RSSI = -86
        const val LOCK_RSSI = -82
    }
}
