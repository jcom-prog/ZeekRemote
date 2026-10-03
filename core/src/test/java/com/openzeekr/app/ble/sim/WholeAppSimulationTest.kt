package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the scenario matrix against the real ProximityController and writes a report. Reporting
 * mode (default): never fails, so the report always appears. With -Dsim.strict=true it fails on
 * any finding (use once the app is expected to pass).
 */
class WholeAppSimulationTest {
    @Test fun scenarioMatrix() {
        val outDir = File(System.getenv("SIM_OUT") ?: "build/sim-report").apply { mkdirs() }
        val seeds = (System.getenv("SIM_SEEDS") ?: "3").toInt()
        val only = System.getenv("SIM_ONLY")
        val runs = SimScenarios.matrix(seeds).filter { only == null || it.first.family.startsWith(only) }
        data class Result(val scenario: Scenario, val carry: Carry, val profile: MotionProfile, val seed: Long,
                          val findings: List<SimOracle.Finding>, val world: SimWorld)
        val results = runs.map { (sc, carry, mp) ->
            val (profile, seed) = mp
            val w = SimWorld(seed, profile, carry, sc.startM, sc.gnssAccuracyM)
            w.play(sc.legs)
            Result(sc, carry, profile, seed, SimOracle.check(w), w)
        }
        val sb = StringBuilder()
        sb.appendLine("Whole-app simulation: ${results.size} runs, ${results.count { it.findings.isEmpty() }} without findings")
        sb.appendLine()
        sb.appendLine("Findings by type (runs affected):")
        results.flatMap { r -> r.findings.map { it.code }.distinct() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.forEach { sb.appendLine("  %-30s %4d".format(it.key, it.value)) }
        sb.appendLine()
        sb.appendLine("Per family (runs with findings / runs):")
        results.groupBy { it.scenario.family }.forEach { (fam, rs) ->
            val codes = rs.flatMap { r -> r.findings.map { it.code }.distinct() }.groupingBy { it }.eachCount()
            sb.appendLine("  %-40s %3d/%3d  %s".format(fam, rs.count { it.findings.isNotEmpty() }, rs.size,
                codes.entries.joinToString { "${it.key}=${it.value}" }))
        }
        sb.appendLine()
        sb.appendLine("Per motion profile / carry (runs with findings / runs):")
        results.groupBy { it.profile.name }.forEach { (k, rs) -> sb.appendLine("  motion %-12s %3d/%3d".format(k, rs.count { it.findings.isNotEmpty() }, rs.size)) }
        results.groupBy { it.carry.name }.forEach { (k, rs) -> sb.appendLine("  carry  %-12s %3d/%3d".format(k, rs.count { it.findings.isNotEmpty() }, rs.size)) }
        sb.appendLine()
        // Distances of the approach unlocks and departure Locks that did happen.
        val unlockD = results.flatMap { r -> r.world.events.filter { it.kind == "KEY_UNLOCK_CMD" }.map { it.distanceM } }.sorted()
        val autoLockD = results.flatMap { r ->
            val manual = r.world.events.filter { it.kind == "USER_MANUAL_LOCK" }.map { it.atMs }
            r.world.events.filter { it.kind == "KEY_LOCK_CMD" && manual.none { m -> it.atMs - m in 0..2_000 } }.map { it.distanceM }
        }.sorted()
        fun pct(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * p).toInt()]
        sb.appendLine("Unlock distance m: n=%d p10=%.1f median=%.1f p90=%.1f".format(unlockD.size, pct(unlockD, .1), pct(unlockD, .5), pct(unlockD, .9)))
        sb.appendLine("Auto-Lock distance m: n=%d p10=%.1f median=%.1f p90=%.1f".format(autoLockD.size, pct(autoLockD, .1), pct(autoLockD, .5), pct(autoLockD, .9)))
        sb.appendLine()
        sb.appendLine("Per family: unlock distance on each expected approach / auto-Lock distance (median, p10-p90):")
        results.groupBy { it.scenario.family }.forEach { (fam, rs) ->
            val perApproach = HashMap<Int, MutableList<Double>>()
            rs.forEach { r ->
                val marks = r.world.events.filter { it.kind == "MARK" }
                marks.forEachIndexed { i, m ->
                    if (m.detail != "expect-unlock") return@forEachIndexed
                    val end = marks.getOrNull(i + 1)?.atMs ?: Long.MAX_VALUE
                    r.world.events.firstOrNull { it.kind == "CAR_UNLOCKED" && it.atMs in m.atMs until end }
                        ?.let { perApproach.getOrPut(i) { mutableListOf() } += it.distanceM }
                }
            }
            val locks = rs.flatMap { r -> val manual = r.world.events.filter { it.kind == "USER_MANUAL_LOCK" }.map { it.atMs }
                r.world.events.filter { it.kind == "KEY_LOCK_CMD" && manual.none { m -> it.atMs - m in 0..2_000 } }.map { it.distanceM } }.sorted()
            val ap = perApproach.toSortedMap().values.joinToString("; ") { l -> val s = l.sorted()
                "n=%d %.1f (%.1f-%.1f)".format(s.size, pct(s, .5), pct(s, .1), pct(s, .9)) }
            sb.appendLine("  %-40s unlock[%s]  lock[n=%d %.1f (%.1f-%.1f)]".format(fam, ap, locks.size, pct(locks, .5), pct(locks, .1), pct(locks, .9)))
        }
        sb.appendLine()
        sb.appendLine("Every finding:")
        results.filter { it.findings.isNotEmpty() }.forEach { r ->
            r.findings.forEach { f ->
                sb.appendLine("  %-40s %-13s %-10s seed=%-12d %-28s t=%6.1fs d=%5.1fm %s".format(r.scenario.family,
                    r.carry.name, r.profile.name, r.seed, f.code, f.atMs / 1000.0, f.distanceM, f.detail))
            }
        }
        File(outDir, "report.txt").writeText(sb.toString())
        // One full trace per (family, finding type): the evidence for the analysis.
        val traced = HashSet<String>()
        results.forEach { r -> r.findings.forEach { f ->
            val key = "${r.scenario.family}|${f.code}|${r.carry}|${r.profile.name}"
            if (traced.add(key)) {
                val name = (r.scenario.family + "_" + f.code + "_" + r.carry + "_" + r.profile.name).replace(Regex("[^A-Za-z0-9_+-]"), "_")
                File(outDir, "trace_$name.txt").writeText(buildString {
                    appendLine("${r.scenario.family}: ${r.scenario.description}")
                    appendLine("carry=${r.carry} motion=${r.profile.name} seed=${r.seed} finding=${f.code} at ${f.atMs / 1000.0}s d=${"%.1f".format(f.distanceM)} ${f.detail}")
                    appendLine("--- events"); r.world.events.forEach { appendLine("%8.1f d=%5.1f %s %s".format(it.atMs / 1000.0, it.distanceM, it.kind, it.detail)) }
                    appendLine("--- log"); r.world.logLines.forEach { appendLine(it) }
                })
            }
        } }
        println(sb.toString().lineSequence().take(60).joinToString("\n"))
        if (System.getProperty("sim.strict") == "true") assertTrue("simulation findings", results.all { it.findings.isEmpty() })
    }
}
