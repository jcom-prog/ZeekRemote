package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.19 (study I): the gate run that locked the car 4 m from the user in six worlds of 0.3.18 - F49, back pocket, a
 * GNSS reference taken in a multipath jump, back at the car between two long stands without a strong reading, and 19
 * steps "since the car" 3 m from it. Played as in the gate (20 learning visits, 15 % flat final approaches, the
 * hand-wobble world); with [com.openzeekr.app.ble.ReturnToCarWatch] the steps count from the return and the Lock is vetoed.
 */
class SimReturnToCarTest {
    @Test fun aBackPocketReturnToTheCarDoesNotLockFourMetresAway() {
        val (scenario, carry, profileAndSeed) = SimScenarios.matrix(3).first { (sc, c, mp) ->
            sc.family.startsWith("F49") && c == Carry.BACK_POCKET && mp.first.name == "s24-steps" && mp.second == SEED
        }
        val world = SimWorld(SEED, profileAndSeed.first, carry, scenario.startM, scenario.gnssAccuracyM,
            physics = SimPhysics(name = "hand-wobble", handWobbleDb = 3.0, approachPlateauP = 0.15))
        world.play(scenario.legs, SimScenarios.learningVisits(SimRandom(SEED xor 0x1EA2_0F_CA1L), 20))
        val log = world.logLines.joinToString("\n")
        assertTrue("the return to the car is seen", "return to the car without a strong reading" in log)
        assertTrue("a Lock is vetoed by the steps since the return", Regex("Lock vetoed - only [0-9]+ steps since the return to the car").containsMatchIn(log))
        val findings = SimOracle.check(world)
        assertTrue("no Lock near the car: ${findings.map { it.code }}", findings.none { it.code == "LOCK_WHILE_NEAR" })
    }

    private companion object {
        const val SEED = -27329551232L
    }
}
