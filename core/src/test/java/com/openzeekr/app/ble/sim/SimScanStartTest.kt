package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.21 (field 20/10): the car did not open on the walk out to it. Two connects at the edge of range ended in status 133;
 * the presence scan the recovery re-armed was stopped for a mode change that the scan-start ration (four per 30 s, Android
 * allows five) then refused, and for 24 s nothing listened for the car. The world "edge-133" (every connect below -90
 * fails) replays it, played as in the gate (20 learning visits, 15 % flat final approaches).
 */
class SimScanStartTest {
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

    /** The longest stretch (s) of the first walk up to the door in which the key was awake and nothing could find the car. */
    private fun blindWalkS(world: SimWorld): Int {
        val mark = world.events.first { it.kind == "MARK" && it.detail == "expect-unlock" }.atMs
        val door = world.samples.firstOrNull { it.atMs >= mark && it.distanceM <= 1.05 }?.atMs ?: Long.MAX_VALUE
        var run = 0; var best = 0
        for (s in world.samples.filter { it.atMs in mark..door }) {
            if (s.moving && s.carLocked && !s.keyAsleep && !s.listening) { run++; best = maxOf(best, run) } else run = 0
        }
        return best
    }

    @Test fun aRefusedRestartLeavesTheWalkUpWithoutAScan() {
        val world = play("F07", Carry.HAND, "good", -44903568788L)
        val log = world.logLines.joinToString("\n")
        assertTrue("connects at the edge ended in status 133", world.events.count { it.kind == "STATUS_133" } >= 2)
        assertTrue("a running scan was stopped for a restart that was refused",
            Regex("presence scan DISARMED\n.*presence scan arm deferred").containsMatchIn(log))
        val blind = blindWalkS(world)
        assertTrue("nothing listened for $blind s of the walk up", blind >= 15)
    }

    private companion object {
        const val SUITE = "edge-133"
    }
}
