package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mandatory replay gates derived from the physical-car logs for 0.1.24 through 0.1.27. */
class FieldTraceRegressionTest {
    private data class Sample(val elapsedMs: Long, val rssi: Int, val moving: Boolean)

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

    private fun replayLocked(policy: ProximityDecisionPolicy, name: String): List<Boolean> =
        trace(name).map { policy.shouldUnlock(it.elapsedMs, it.rssi, it.moving, UNLOCK_RSSI) }

    private fun trace(name: String): List<Sample> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/proximity-traces/$name")) { name }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { row ->
                    val value = row.split(',')
                    Sample(value[0].toLong(), value[1].toInt(), value[2].toBooleanStrict())
                }.toList()
        }
    }

    private companion object {
        const val UNLOCK_RSSI = -86
        const val LOCK_RSSI = -82
    }
}
