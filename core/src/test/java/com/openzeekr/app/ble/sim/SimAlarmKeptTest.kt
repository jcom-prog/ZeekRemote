package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.29: the alarm after a failed walk-away Lock used to fall silent on the signal alone. Calibrated F54 (auto-Lock,
 * back to 10 m, 20 s turned away, then facing the car at a favourable spot), phone in the front pocket, S24 screen off,
 * played as in the gate (20 learning visits, 15 % flat final approaches): the Lock failed, the alarm sounded, and 5 s at
 * the -82 level 10 m out silenced it with the car open, 87 s early. Now it keeps sounding until a walk, the Lock, the
 * user's tap or its own timeout.
 */
class SimAlarmKeptTest {
    @Test fun theAlarmIsNotSilencedByTheSignalAloneTenMetresOut() {
        val (scenario, carry, mp) = SimScenarios.matrix(5, listOf(Carry.FRONT_POCKET)).first { (sc, c, mp) ->
            sc.family.startsWith("F54") && c == Carry.FRONT_POCKET && mp.first.name == "s24-off" && mp.second == SEED
        }
        val world = SimWorld(SEED, mp.first, carry, scenario.startM, scenario.gnssAccuracyM,
            physics = SimPhysics.calibrated().copy(approachPlateauP = 0.15))
        world.play(scenario.legs, SimScenarios.learningVisits(SimRandom(SEED xor 0x1EA2_0F_CA1L), 20))
        val log = world.logLines.joinToString("\n")
        val codes = SimOracle.check(world).map { it.code }
        assertTrue("an alarm sounded in this run: $codes", world.events.any { it.kind == "ALARM" })
        assertTrue("the alarm was kept for lack of walking evidence", "alarm kept: signal at the car" in log)
        assertTrue("no alarm silenced away from the car: $codes", "ALARM_SILENCED_AWAY" !in codes)
    }

    private companion object {
        const val SEED = 50146455050L
    }
}
