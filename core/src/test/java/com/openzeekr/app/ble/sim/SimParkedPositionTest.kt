package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.12 (study D, field 29/10): home by car. The position remembered at the parking was wiped 0.18 s later by the drive's
 * lingering IN_VEHICLE flag, which only Android steps or an Activity Recognition transition clear (nine of 21 parkings in
 * the field). Pinned run of the study (calibrated world, front pocket, scenario G3: parked, 25 m inside for three minutes,
 * back to the car): the flag is recognised as older than the parking, the position stays, and nothing wipes it while the
 * user is inside. The unit tests run the own rules in SHADOW (core/build.gradle.kts); the seed was checked in that mode
 * under the 0.4.12 simulator model (the drive flag lingers 20-450 s after every drive).
 */
class SimParkedPositionTest {
    private fun play(suite: String, family: String, carry: Carry, motion: String, seed: Long): SimWorld {
        val (scenario, c, mp) = SimScenarios.matrix(8, Carry.values().toList()).first { (sc, c, mp) ->
            sc.family.startsWith(family) && c == carry && mp.first.name == motion && mp.second == seed
        }
        val world = SimWorld(seed, SimRunner.profileFor(suite, mp.first), c, scenario.startM, scenario.gnssAccuracyM,
            carRefusesKeyLock = scenario.carRefusesKeyLock, keyEdgeRssi = scenario.keyEdgeRssi,
            physics = SimRunner.physicsFor(suite, seed).copy(approachPlateauP = 0.15), cloudDown = scenario.cloudDown)
        world.play(scenario.legs, SimScenarios.learningVisits(SimRandom(seed xor 0x1EA2_0F_CA1L), 20, carry = null))
        return world
    }

    @Test fun theDriveFlagThatLingersAfterTheParkingDoesNotWipeTheCarPosition() {
        val world = play("calibrated", "G3", Carry.FRONT_POCKET, "good", -12879890103L)
        val codes = SimOracle.check(world).map { it.code }
        val lines = world.logLines
        val remembered = lines.indexOfFirst { "car position remembered" in it }
        assertTrue("the position was remembered at the parking", remembered >= 0)
        val kept = lines.indexOfFirst { "car position kept (the drive flag is older than the parking)" in it }
        assertTrue("the lingering drive flag was recognised as older than the parking", kept > remembered)
        assertTrue("nothing wiped the position afterwards", lines.drop(remembered).none { "car position forgotten" in it })
        assertTrue("the user came back to an open car", world.events.count { it.kind == "CAR_UNLOCKED" } >= 2)
        assertTrue("no safety finding: $codes", codes.none { it in SimOracle.SAFETY })
    }
}
