package com.openzeekr.app.ble.sim

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the scenario matrix against the real ProximityController and writes a report. Reporting
 * mode (default): never fails, so the report always appears. With -Dsim.strict=true it fails on
 * any finding (use once the app is expected to pass). The regression gate is [SimGateTest].
 *
 * Environment:
 *   SIM_SUITE   calibrated (default) | stress | both - stress samples a different world per run
 *   SIM_SEEDS   seeds per family x carry x motion cell (default 3)
 *   SIM_ONLY    comma-separated family prefixes (e.g. "F28,F37")
 *   SIM_OUT     report directory (default build/sim-report)
 *   SIM_TRACE_EVERY_SEED=1  one trace per seed instead of one per (family, finding, carry, motion)
 */
class WholeAppSimulationTest {
    @Test fun scenarioMatrix() {
        val outDir = File(System.getenv("SIM_OUT") ?: "build/sim-report").apply { mkdirs() }
        val seeds = (System.getenv("SIM_SEEDS") ?: "3").toInt()
        val suites = when (System.getenv("SIM_SUITE") ?: "calibrated") {
            "both" -> listOf("calibrated", "stress"); else -> listOf(System.getenv("SIM_SUITE") ?: "calibrated") }
        val only = System.getenv("SIM_ONLY")?.split(",")
        val traces = SimReport.TraceWriter(outDir, System.getenv("SIM_TRACE_EVERY_SEED") == "1")
        val results = SimRunner.run(suites, seeds, only, traces::accept)
        val report = SimReport.text(results)
        File(outDir, "report.txt").writeText(report)
        File(outDir, "summary.json").writeText(SimReport.summaryJson(results))
        println(report.lineSequence().take(70).joinToString("\n"))
        if (System.getProperty("sim.strict") == "true") assertTrue("simulation findings", results.all { it.findings.isEmpty() })
    }
}

internal data class SimResult(val suite: String, val scenario: Scenario, val carry: Carry, val profile: MotionProfile,
                              val seed: Long, val physics: SimPhysics, val findings: List<SimOracle.Finding>, val world: SimWorld)

internal object SimRunner {
    /** [onRun] sees every run with its full log (traces); the log is dropped afterwards to bound memory. */
    fun run(suites: List<String>, seeds: Int, only: List<String>?, onRun: ((SimResult) -> Unit)? = null): List<SimResult> = suites.flatMap { suite ->
        SimScenarios.matrix(seeds).filter { r -> only == null || only.any { r.first.family.startsWith(it) } }.map { (sc, carry, mp) ->
            val (profile, seed) = mp
            // The world of a stress run depends on the seed only, so two app versions meet the same world.
            val physics = if (suite == "stress") SimPhysics.random(SimRandom(seed xor 0x5EED_0F_57E55L)) else SimPhysics.calibrated()
            val w = SimWorld(seed, profile, carry, sc.startM, sc.gnssAccuracyM, carRefusesKeyLock = sc.carRefusesKeyLock,
                keyEdgeRssi = sc.keyEdgeRssi, physics = physics, cloudDown = sc.cloudDown)
            w.play(sc.legs)
            SimResult(suite, sc, carry, profile, seed, physics, SimOracle.check(w), w).also { r ->
                onRun?.invoke(r)
                w.logLines.clear(); w.logLines.trimToSize()
            }
        }
    }
}

internal object SimReport {
    private fun pct(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * p).toInt()]

    /** Per suite: runs affected per finding code (the gate compares these). */
    fun counts(results: List<SimResult>): Map<String, Map<String, Int>> = results.groupBy { it.suite }.mapValues { (_, rs) ->
        rs.flatMap { r -> r.findings.map { it.code }.distinct() }.groupingBy { it }.eachCount().toSortedMap()
    }

    fun summaryJson(results: List<SimResult>): String = buildString {
        append("{\n")
        val c = counts(results)
        c.entries.forEachIndexed { i, (suite, codes) ->
            val runs = results.count { it.suite == suite }
            append("  \"").append(suite).append("\": {\"runs\": ").append(runs)
            codes.forEach { (k, v) -> append(", \"").append(k).append("\": ").append(v) }
            append("}").append(if (i < c.size - 1) ",\n" else "\n")
        }
        append("}\n")
    }

    fun text(results: List<SimResult>): String {
        val sb = StringBuilder()
        for ((suite, rs) in results.groupBy { it.suite }) {
            sb.appendLine("=== Suite: $suite — ${rs.size} runs, ${rs.count { it.findings.isEmpty() }} without findings, " +
                "${rs.count { r -> r.findings.any { SimOracle.tier(it.code) == "SAFETY" } }} with a SAFETY finding ===")
            sb.appendLine()
            sb.appendLine("Findings by type (runs affected):")
            rs.flatMap { r -> r.findings.map { it.code }.distinct() }.groupingBy { it }.eachCount()
                .entries.sortedWith(compareBy({ SimOracle.tier(it.key) }, { -it.value }))
                .forEach { sb.appendLine("  %-8s %-28s %5d".format(SimOracle.tier(it.key), it.key, it.value)) }
            sb.appendLine()
            sb.appendLine("Per family (runs with findings / runs):")
            rs.groupBy { it.scenario.family }.forEach { (fam, frs) ->
                val codes = frs.flatMap { r -> r.findings.map { it.code }.distinct() }.groupingBy { it }.eachCount()
                sb.appendLine("  %-44s %3d/%3d  %s".format(fam, frs.count { it.findings.isNotEmpty() }, frs.size,
                    codes.entries.joinToString { "${it.key}=${it.value}" }))
            }
            sb.appendLine()
            sb.appendLine("Field incidents (families that reproduce them: runs with SAFETY / COMFORT findings):")
            for (fc in SimScenarios.fieldCases) {
                val frs = rs.filter { r -> fc.families.any { r.scenario.family.startsWith("$it ") } }
                val safety = frs.count { r -> r.findings.any { SimOracle.tier(it.code) == "SAFETY" } }
                val comfort = frs.count { r -> r.findings.any { SimOracle.tier(it.code) == "COMFORT" } }
                sb.appendLine("  %-18s %-48s %s".format(fc.id, fc.what.take(48),
                    if (fc.families.isEmpty()) "not simulated: ${fc.note}"
                    else "%s  runs=%d safety=%d comfort=%d%s".format(fc.families.joinToString("/"), frs.size, safety, comfort,
                        if (fc.note.isNotBlank()) "  (${fc.note})" else "")))
            }
            sb.appendLine()
            sb.appendLine("Per motion profile / carry (runs with findings / runs):")
            rs.groupBy { it.profile.name }.forEach { (k, x) -> sb.appendLine("  motion %-12s %4d/%4d".format(k, x.count { it.findings.isNotEmpty() }, x.size)) }
            rs.groupBy { it.carry.name }.forEach { (k, x) -> sb.appendLine("  carry  %-12s %4d/%4d".format(k, x.count { it.findings.isNotEmpty() }, x.size)) }
            sb.appendLine()
            val unlockD = rs.flatMap { r -> r.world.events.filter { it.kind == "KEY_UNLOCK_CMD" }.map { it.distanceM } }.sorted()
            val autoLockD = rs.flatMap { r ->
                val manual = r.world.events.filter { it.kind == "USER_MANUAL_LOCK" }.map { it.atMs }
                r.world.events.filter { it.kind == "KEY_LOCK_CMD" && manual.none { m -> it.atMs - m in 0..2_000 } }.map { it.distanceM }
            }.sorted()
            sb.appendLine("Unlock distance m: n=%d p10=%.1f median=%.1f p90=%.1f".format(unlockD.size, pct(unlockD, .1), pct(unlockD, .5), pct(unlockD, .9)))
            sb.appendLine("Auto-Lock distance m: n=%d p10=%.1f median=%.1f p90=%.1f".format(autoLockD.size, pct(autoLockD, .1), pct(autoLockD, .5), pct(autoLockD, .9)))
            sb.appendLine("Status-133 connect failures: %d (runs affected %d)".format(
                rs.sumOf { r -> r.world.events.count { it.kind == "STATUS_133" } }, rs.count { r -> r.world.events.any { it.kind == "STATUS_133" } }))
            // Battery: share of time the CPU is held, and GNSS seconds, per hour of simulated time.
            val totalS = rs.sumOf { it.world.samples.size }.toDouble().coerceAtLeast(1.0)
            val awakeS = rs.sumOf { r -> r.world.samples.count { it.cpuAwake } }
            val gnssS = rs.sumOf { r -> r.world.samples.count { it.gnssOn } }
            val idleFar = rs.flatMap { r -> r.world.samples.filter { it.carLocked && it.distanceM >= 30.0 } }
            sb.appendLine("Battery: CPU held %.0f%% of the time (%.0f min/h); GNSS %.1f min/h; while far and locked the CPU is held %.0f%%".format(
                100.0 * awakeS / totalS, 60.0 * awakeS / totalS, 60.0 * gnssS / totalS,
                if (idleFar.isEmpty()) 0.0 else 100.0 * idleFar.count { it.cpuAwake } / idleFar.size))
            sb.appendLine()
            sb.appendLine("Per family: unlock distance on each expected approach / auto-Lock distance / seconds to locked (median, p10-p90):")
            rs.groupBy { it.scenario.family }.forEach { (fam, frs) ->
                val perApproach = HashMap<Int, MutableList<Double>>()
                frs.forEach { r ->
                    val marks = r.world.events.filter { it.kind == "MARK" }
                    marks.forEachIndexed { i, m ->
                        if (m.detail != "expect-unlock") return@forEachIndexed
                        val end = marks.getOrNull(i + 1)?.atMs ?: Long.MAX_VALUE
                        r.world.events.firstOrNull { it.kind == "CAR_UNLOCKED" && it.atMs in m.atMs until end }
                            ?.let { perApproach.getOrPut(i) { mutableListOf() } += it.distanceM }
                    }
                }
                val locks = frs.flatMap { r -> val manual = r.world.events.filter { it.kind == "USER_MANUAL_LOCK" }.map { it.atMs }
                    r.world.events.filter { it.kind == "KEY_LOCK_CMD" && manual.none { m -> it.atMs - m in 0..2_000 } }.map { it.distanceM } }.sorted()
                val lockT = frs.mapNotNull { r ->
                    val m = r.world.events.firstOrNull { it.kind == "MARK" && it.detail == "expect-lock" } ?: return@mapNotNull null
                    r.world.events.firstOrNull { it.kind == "CAR_LOCKED" && it.atMs >= m.atMs }?.let { (it.atMs - m.atMs) / 1000.0 }
                }.sorted()
                val ap = perApproach.toSortedMap().values.joinToString("; ") { l -> val s = l.sorted()
                    "n=%d %.1f (%.1f-%.1f)".format(s.size, pct(s, .5), pct(s, .1), pct(s, .9)) }
                sb.appendLine("  %-44s unlock[%s]  lock[n=%d %.1f (%.1f-%.1f)]".format(fam, ap, locks.size, pct(locks, .5), pct(locks, .1), pct(locks, .9)) +
                    "  lockT[n=%d %.0fs (%.0f-%.0f)]".format(lockT.size, pct(lockT, .5), pct(lockT, .1), pct(lockT, .9)))
            }
            sb.appendLine()
            sb.appendLine("Every finding:")
            rs.filter { it.findings.isNotEmpty() }.forEach { r ->
                r.findings.forEach { f ->
                    sb.appendLine("  %-7s %-44s %-13s %-10s seed=%-12d %-24s t=%6.1fs d=%5.1fm %s%s".format(SimOracle.tier(f.code), r.scenario.family,
                        r.carry.name, r.profile.name, r.seed, f.code, f.atMs / 1000.0, f.distanceM, f.detail,
                        if (r.suite == "stress") "  [" + r.physics.describe() + "]" else ""))
                }
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    /** Writes traces while the runs happen (each run's log is dropped right after). */
    class TraceWriter(private val outDir: File, private val everySeed: Boolean) {
        private val traced = HashSet<String>()
        private val all = System.getenv("SIM_TRACE_ALL") == "1"
        fun accept(r: SimResult) {
            if (all) {
                val name = (r.suite + "_" + r.scenario.family + "_" + r.carry + "_" + r.profile.name + "_" + r.seed).replace(Regex("[^A-Za-z0-9_+-]"), "_")
                File(outDir, "run_$name.txt").writeText(body(r, "findings=${r.findings.joinToString { it.code }}"))
            }
            r.findings.forEach { f ->
                val perSeed = if (everySeed) "_${r.seed}" else ""
                val key = "${r.suite}|${r.scenario.family}|${f.code}|${r.carry}|${r.profile.name}$perSeed"
                if (traced.add(key)) {
                    val name = (r.suite + "_" + r.scenario.family + "_" + f.code + "_" + r.carry + "_" + r.profile.name + perSeed)
                        .replace(Regex("[^A-Za-z0-9_+-]"), "_")
                    File(outDir, "trace_$name.txt").writeText(body(r,
                        "finding=${f.code} (${SimOracle.tier(f.code)}) at ${f.atMs / 1000.0}s d=${"%.1f".format(f.distanceM)} ${f.detail}"))
                }
            }
        }
        private fun body(r: SimResult, head: String) = buildString {
            appendLine("${r.scenario.family}: ${r.scenario.description}")
            appendLine("suite=${r.suite} carry=${r.carry} motion=${r.profile.name} seed=${r.seed} physics=[${r.physics.describe()}]")
            appendLine(head)
            appendLine("--- events"); r.world.events.forEach { appendLine("%8.1f d=%5.1f %s %s".format(it.atMs / 1000.0, it.distanceM, it.kind, it.detail)) }
            appendLine("--- log"); r.world.logLines.forEach { appendLine(it) }
        }
    }
}
