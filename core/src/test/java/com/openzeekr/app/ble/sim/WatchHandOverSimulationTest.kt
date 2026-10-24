package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W2 (24/10): the watch companion's hand-overs on the phone side - [SimScenarios.watchFamilies] in three worlds, two
 * seeds, front pocket, the gate's learning period. One world at a time: WearLinkArbiter is process-global (as in the
 * app), so these never run in parallel with each other. Writes build/sim-watch/report.txt (events per run).
 *
 * What must hold: a watch Lock is never undone on the walk to the door (W-1, W-3) and never doubled; the car is never left
 * open and silent after a watch Unlock, with or without the watch's resume (W-2, W-4); the approach after a remote Unlock
 * from inside is a normal arrival (W-5). 0.4.3: a hand-over while the phone's own Lock loop runs stands the loop down - no
 * Lock of the phone behind the watch's Unlock, no second Lock (W-6, W-7); an aborted hand-over leaves no trace (W-8); a
 * hand-over that outruns the fail-safe still delivers the watch's Unlock to the engine (W-9).
 */
class WatchHandOverSimulationTest {
    private class Run(val sc: Scenario, val world: String, val seed: Long, val findings: List<SimOracle.Finding>,
                      val events: List<SimWorld.Event>, val log: String) {
        val codes get() = findings.map { it.code }.toSet()
        fun after(kind: String, marker: String): List<SimWorld.Event> {
            val at = events.firstOrNull { it.kind == marker }?.atMs ?: return emptyList()
            return events.filter { it.kind == kind && it.atMs > at }
        }
        fun alarmsAfter(marker: String) = after("ALARM", marker)
        val name get() = "${sc.family.substringBefore(' ')} $world seed=$seed"
    }

    private fun play(sc: Scenario, suite: String, seed: Long): Run {
        val physics = SimRunner.physicsFor(suite, seed)
        val profile = SimRunner.profileFor(suite, MotionProfile.S24_STEPS_AWAKE)
        val w = SimWorld(seed, profile, Carry.FRONT_POCKET, sc.startM, sc.gnssAccuracyM, physics = physics)
        w.play(sc.legs, SimScenarios.learningVisits(SimRandom(seed xor 0x1EA2_0F_CA1L), 20, carry = null))
        return Run(sc, suite, seed, SimOracle.check(w), w.events.toList(), w.logLines.joinToString("\n"))
    }

    @Test fun theWatchHandOversLeaveThePhoneInTheRightState() {
        val runs = mutableListOf<Run>()
        val report = StringBuilder()
        for (sc in SimScenarios.watchFamilies) for (world in WORLDS) for (seed in SEEDS) {
            val r = play(sc, world, seed)
            runs += r
            report.appendLine("== ${r.name}: findings ${r.findings.map { it.code }.ifEmpty { listOf("none") }}")
            r.events.filter { it.kind in SHOWN }.forEach { e ->
                report.appendLine("   %8.1f s %5.1f m %s %s".format(java.util.Locale.ROOT, e.atMs / 1000.0, e.distanceM, e.kind, e.detail))
            }
            // The phone's log from 5 s before the hand-over to 150 s after it (what the engine decided and why).
            val t0 = (r.events.firstOrNull { it.kind == "WATCH_PAUSE" }?.atMs ?: 0L) / 1000.0
            r.log.lineSequence().filter { l -> l.trim().substringBefore(' ').toDoubleOrNull()?.let { it >= t0 - 5.0 && it <= t0 + 150.0 } == true }
                .take(200).forEach { report.appendLine("   | $it") }
        }
        File("build/sim-watch").mkdirs()
        File("build/sim-watch/report.txt").writeText(report.toString())
        println(report.toString().lineSequence().take(400).joinToString("\n"))

        val problems = mutableListOf<String>()
        for (r in runs) {
            val f = r.sc.family.substringBefore(' ')
            // Every hand-over pauses the phone and (unless the watch died) resumes it.
            if (r.events.none { it.kind == "WATCH_PAUSE" }) problems += "${r.name}: no WATCH_PAUSE"
            val resumed = r.events.any { it.kind == "WATCH_RESUME" }
            when (f) {
                "W-1" -> {
                    if ("UNWANTED_UNLOCK" in r.codes || "UNLOCK_WHILE_FAR" in r.codes || "UNLOCK_WHILE_LOITERING" in r.codes)
                        problems += "${r.name}: the watch Lock was undone (${r.codes})"
                    if (r.after("KEY_UNLOCK_CMD", "WATCH_LOCK").isNotEmpty()) problems += "${r.name}: a key Unlock after the watch Lock"
                    if (r.alarmsAfter("WATCH_LOCK").isNotEmpty()) problems += "${r.name}: an alarm after the watch Lock"
                    if ("lock confirmed (watch Lock) -> departure latched" !in r.log) problems += "${r.name}: the watch Lock did not reach the engine"
                }
                "W-3" -> {
                    if (r.codes.any { it in setOf("LOCK_WHILE_NEAR", "DEPARTURE_NO_LOCK_SILENT", "GONE_BUT_UNLOCKED", "FALSE_ALARM_CAR_LOCKED") })
                        problems += "${r.name}: ${r.codes}"
                    if (r.after("KEY_LOCK_CMD", "WATCH_LOCK").isNotEmpty() || r.after("CLOUD_LOCK_CMD", "WATCH_LOCK").isNotEmpty())
                        problems += "${r.name}: a second Lock after the watch Lock"
                    if (r.alarmsAfter("WATCH_LOCK").isNotEmpty()) problems += "${r.name}: an alarm after the watch Lock"
                }
                "W-2", "W-4" -> {
                    if (r.codes.any { it in setOf("DEPARTURE_NO_LOCK_SILENT", "GONE_BUT_UNLOCKED", "OPEN_FAR_NO_ALARM", "LOCK_UNLOCK_THRASH") })
                        problems += "${r.name}: ${r.codes}"
                    if ("unlock confirmed (watch Unlock)" !in r.log) problems += "${r.name}: the watch Unlock did not reach the engine"
                    if (f == "W-4") {
                        if (resumed) problems += "${r.name}: W-4 must not resume"
                        if ("watch link-borrow expired" !in r.log) problems += "${r.name}: the fail-safe did not reclaim the link"
                    } else if (!resumed) problems += "${r.name}: no WATCH_RESUME"
                }
                "W-5" -> {
                    if (r.codes.any { it in setOf("APPROACH_NO_UNLOCK", "BACK_AT_CAR_BUT_LOCKED", "FALSE_ALARM_CAR_LOCKED", "UNWANTED_UNLOCK") })
                        problems += "${r.name}: ${r.codes}"
                    if (r.after("KEY_LOCK_CMD", "WATCH_UNLOCK").any { it.distanceM > 20.0 }) problems += "${r.name}: the phone locked the car from inside"
                }
                // 0.4.3 (T1): a hand-over mid-Lock-loop - the phone never locks behind the watch's Unlock, never doubles its Lock.
                "W-6" -> {
                    if (r.after("KEY_LOCK_CMD", "WATCH_UNLOCK").isNotEmpty() || r.after("CLOUD_LOCK_CMD", "WATCH_UNLOCK").isNotEmpty())
                        problems += "${r.name}: the phone locked the car after the watch Unlock"
                    if (r.alarmsAfter("WATCH_UNLOCK").isNotEmpty()) problems += "${r.name}: an alarm after the watch Unlock"
                    if (r.codes.any { it in setOf("BACK_AT_CAR_BUT_LOCKED", "LOCK_WHILE_NEAR", "LOCK_UNLOCK_THRASH", "APPROACH_NO_UNLOCK") })
                        problems += "${r.name}: ${r.codes}"
                    if (r.events.any { it.kind == "WATCH_TAP" && it.detail != "no loop" } && "watch: link stood down" !in r.log)
                        problems += "${r.name}: the engine did not stand down for the hand-over"
                }
                "W-7" -> {
                    if (r.after("KEY_LOCK_CMD", "WATCH_LOCK").isNotEmpty() || r.after("CLOUD_LOCK_CMD", "WATCH_LOCK").isNotEmpty())
                        problems += "${r.name}: a second Lock after the watch Lock"
                    if (r.alarmsAfter("WATCH_LOCK").isNotEmpty()) problems += "${r.name}: an alarm after the watch Lock"
                    if (r.codes.any { it in setOf("GONE_BUT_UNLOCKED", "DEPARTURE_NO_LOCK_SILENT", "LOCK_UNLOCK_THRASH", "FALSE_ALARM_CAR_LOCKED") })
                        problems += "${r.name}: ${r.codes}"
                }
                // 0.4.3 (H5/T5): an aborted hand-over leaves no trace but the pause and the resume.
                "W-8" -> {
                    if (r.alarmsAfter("WATCH_PAUSE").isNotEmpty()) problems += "${r.name}: an alarm after an aborted hand-over"
                    if (r.after("KEY_LOCK_CMD", "WATCH_PAUSE").isNotEmpty() || r.after("CLOUD_LOCK_CMD", "WATCH_PAUSE").isNotEmpty() ||
                        r.after("KEY_UNLOCK_CMD", "WATCH_PAUSE").isNotEmpty()) problems += "${r.name}: the phone acted after an aborted hand-over"
                    if (!resumed) problems += "${r.name}: no WATCH_RESUME"
                }
                // 0.4.3 (T6): the fail-safe took the link back before the watch's Unlock - the car is never left open and silent.
                "W-9" -> {
                    if (r.codes.any { it in setOf("DEPARTURE_NO_LOCK_SILENT", "GONE_BUT_UNLOCKED", "OPEN_FAR_NO_ALARM", "LOCK_UNLOCK_THRASH") })
                        problems += "${r.name}: ${r.codes}"
                    if ("watch link-borrow expired" !in r.log) problems += "${r.name}: the fail-safe did not reclaim the link"
                    if ("unlock confirmed (watch Unlock)" !in r.log) problems += "${r.name}: the late watch Unlock did not reach the engine"
                }
            }
        }
        // 0.4.3: the mid-loop taps must have landed in a running loop in at least one run per family, or they prove nothing.
        for (f in listOf("W-6", "W-7")) {
            val taps = runs.filter { it.sc.family.startsWith(f) }
            val inLoop = taps.count { r -> r.events.any { it.kind == "WATCH_TAP" && it.detail != "no loop" } }
            println("$f: the tap landed in a running loop in $inLoop of ${taps.size} runs")
            if (inLoop == 0) problems += "$f: no run tapped the watch while a loop ran"
        }
        // Informational (decided with the owner): does the phone alarm after a remote Unlock from inside (W-5)?
        val w5Alarms = runs.filter { it.sc.family.startsWith("W-5") }.count { it.alarmsAfter("WATCH_UNLOCK").isNotEmpty() }
        println("W-5: alarm after the remote Unlock in $w5Alarms of ${runs.count { it.sc.family.startsWith("W-5") }} runs")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        val WORLDS = listOf("calibrated", "steps-late", "field-like")
        val SEEDS = listOf(11L, 23L)
        val SHOWN = setOf("MARK", "CAR_UNLOCKED", "CAR_LOCKED", "DOOR_OPENED", "WATCH_PAUSE", "WATCH_TAP", "WATCH_LOCK", "WATCH_UNLOCK", "WATCH_RESUME",
            "KEY_LOCK_CMD", "KEY_UNLOCK_CMD", "CLOUD_LOCK_CMD", "ALARM", "ALARM_STOP", "SECURITY_SLEEP", "LINK_DOWN", "APP_KILLED")
    }
}
