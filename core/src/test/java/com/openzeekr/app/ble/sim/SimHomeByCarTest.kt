package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.20 (study J, field 16/10 and 19/10): home by car. After the drive the phone held every step and Activity Recognition
 * said MOVING for minutes. Two gate runs of 0.3.19 in the world "steps-after-drive", played as in the gate (20 learning
 * visits, 15 % flat final approaches): the alarm sounded seconds before the very-deep Lock (F52), and the car locked at
 * the boot while the user unloaded it (F51).
 */
class SimHomeByCarTest {
    private fun play(family: String, carry: Carry, motion: String, seed: Long): SimWorld {
        val (scenario, c, mp) = SimScenarios.matrix(5).first { (sc, c, mp) ->
            sc.family.startsWith(family) && c == carry && mp.first.name == motion && mp.second == seed
        }
        val world = SimWorld(seed, SimRunner.profileFor(SUITE, mp.first), c, scenario.startM, scenario.gnssAccuracyM,
            carRefusesKeyLock = scenario.carRefusesKeyLock, keyEdgeRssi = scenario.keyEdgeRssi,
            physics = SimRunner.physicsFor(SUITE, seed).copy(approachPlateauP = 0.15), cloudDown = scenario.cloudDown)
        world.play(scenario.legs, SimScenarios.learningVisits(SimRandom(seed xor 0x1EA2_0F_CA1L), 20))
        return world
    }

    @Test fun homeByCarTheAlarmWaitsForTheVeryDeepLock() {
        val world = play("F52", Carry.HAND, "s24-off", 35377726276L)
        val codes = SimOracle.check(world).map { it.code }
        assertTrue("the alarm waited", "departure alarm held: a very-deep proof is under way" in world.logLines.joinToString("\n"))
        assertTrue("no alarm right before the Lock: $codes", "ALARM_THEN_LOCK" !in codes)
        assertTrue("locked by the app", world.events.any { it.kind == "CAR_LOCKED" && (it.detail == "key" || it.detail == "cloud") })
    }

    @Test fun unloadingTheBootAfterADriveNeverLocksTheCar() {
        val world = play("F51", Carry.BACK_POCKET, "s24-off", 5999855711L)
        val codes = SimOracle.check(world).map { it.code }
        assertTrue("the drive's motion held the proof back", "very-deep proof held: MOVING from the drive only" in world.logLines.joinToString("\n"))
        assertTrue("no Lock at the boot: $codes", "LOCK_WHILE_NEAR" !in codes && "UNWANTED_LOCK_NEAR" !in codes)
    }

    private companion object {
        const val SUITE = "steps-after-drive"
    }
}
