package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.11 (study A, field 29/10): home by car, a few metres to the garden gate, standing there, through it (the gate closes
 * between phone and car), on to the back door. Pinned runs of the study (front pocket, world "good"): the cloud Lock runs
 * beside the key Lock the car does not execute (G2b), the key loop hands over to it instead of warning (G2b, stress world),
 * and without a gate the departure alarm waits for the walk route's window instead of sounding seconds before its Lock
 * (G2d). The unit tests run the own rules in SHADOW (core/build.gradle.kts); the seeds were checked in that mode.
 */
class SimBehindTheGateTest {
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
    private fun log(world: SimWorld) = world.logLines.joinToString("\n")

    @Test fun behindTheGateTheCloudLockRunsBesideTheKeyLockTheCarDoesNotExecute() {
        val world = play("calibrated", "G2b", Carry.FRONT_POCKET, "good", -61063980107L)
        val codes = SimOracle.check(world).map { it.code }
        assertTrue("the cloud Lock ran beside the key Lock", "cloud Lock in parallel with the key Lock" in log(world))
        val gate = world.events.first { it.kind == "OBSTACLE" }
        val lock = world.events.firstOrNull { it.kind == "CAR_LOCKED" && it.atMs > gate.atMs }
        assertTrue("locked by the cloud behind the gate: $codes", lock != null && lock.detail == "cloud")
        assertTrue("within 30 s of the gate: ${(lock!!.atMs - gate.atMs) / 1000} s", lock.atMs - gate.atMs <= 30_000L)
        assertTrue("no alarm before the Lock: $codes", world.events.none { it.kind == "ALARM" && it.atMs < lock.atMs })
    }

    @Test fun aVetoHandsOverToThePendingCloudLockWhichJudgesOnItsOwn() {
        // In SHADOW the own counter is not live, so the K1 step veto fires here while the user is 22 m away: 0.4.10 posted
        // the silent "user at the car" notice and the alarm came 40 s later with the lost link; now the key loop hands over
        // and the cloud path, vetoed too, raises the "not locked" warning at once. The car stays open in both (link lost).
        val world = play("stress", "G2b", Carry.FRONT_POCKET, "s24-off", -60602551085L)
        val codes = SimOracle.check(world).map { it.code }
        val log = log(world)
        assertTrue("the key loop handed over", "key loop hands over to the pending cloud Lock (vetoed" in log)
        assertTrue("no warning from the key loop itself", "; user at the car; NO LOCK SENT" !in log)
        assertTrue("the cloud path judged on its own", "cloud Lock vetoed" in log)
        assertTrue("the warning is the cloud path's: $codes", world.events.any { it.kind == "NOTIFY" && it.detail == "AUTO_LOCK_UNCONFIRMED" })
        assertTrue("no safety finding: $codes", codes.none { it in SimOracle.SAFETY })
    }

    @Test fun withoutAGateTheAlarmWaitsForTheWalkProof() {
        val world = play("calibrated", "G2d", Carry.FRONT_POCKET, "good", -61553095193L)
        val codes = SimOracle.check(world).map { it.code }
        assertTrue("the alarm waited for the walk window", "a walk-away proof is under way" in log(world))
        assertTrue("no alarm right before the Lock: $codes", "ALARM_THEN_LOCK" !in codes)
        assertTrue("locked by the app", world.events.any { it.kind == "CAR_LOCKED" && (it.detail == "key" || it.detail == "cloud") })
    }
}
