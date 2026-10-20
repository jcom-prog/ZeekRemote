package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.21 (study K2c): a step counter that lags an ordinary walk away vetoed the Lock ("only N steps since the car") until
 * the user was far beyond 25 m. Calibrated F47 (open, 30 s busy at the car, 15 m into the house), phone in the hand, screen
 * off, played as in the gate (20 learning visits, 15 % flat final approaches): 0.3.20 left the car open until the alarm.
 * Nine counted steps and 8 s of unbroken walking now lift the veto.
 */
class SimWalkBoutTest {
    @Test fun anUnbrokenWalkLiftsTheStepVetoOfALaggingCounter() {
        val (scenario, carry, mp) = SimScenarios.matrix(5).first { (sc, c, mp) ->
            sc.family.startsWith("F47") && c == Carry.HAND && mp.first.name == "s24-off" && mp.second == SEED
        }
        val world = SimWorld(SEED, mp.first, carry, scenario.startM, scenario.gnssAccuracyM,
            physics = SimPhysics.calibrated().copy(approachPlateauP = 0.15))
        world.play(scenario.legs, SimScenarios.learningVisits(SimRandom(SEED xor 0x1EA2_0F_CA1L), 20))
        val log = world.logLines.joinToString("\n")
        assertTrue("the step veto was lifted by the walk", Regex("[0-9]+ steps since the car, walking without a break for").containsMatchIn(log))
        val codes = SimOracle.check(world).map { it.code }
        assertTrue("the car was locked: $codes", "DEPARTURE_NO_LOCK_ALARMED" !in codes && "DEPARTURE_NO_LOCK_SILENT" !in codes)
        assertTrue("no Lock near the car: $codes", "LOCK_WHILE_NEAR" !in codes && "UNWANTED_LOCK_NEAR" !in codes)
    }

    private companion object {
        const val SEED = 11026665128L
    }
}
