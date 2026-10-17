package com.openzeekr.app.ble.sim

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression gate (fase 0, 14/10). Runs both suites on the seeds of the committed baseline and fails
 * when any SAFETY finding occurs in more runs than the baseline allows, and (0.3.2) when a COMFORT
 * finding - a late or missing unlock, a lock that does not come - grows beyond a wider allowance: the
 * late unlock seen in the field was a comfort finding that no gate looked at. The owner's targets are
 * printed with the table. Only runs with SIM_GATE=1 (CI step), so ordinary unit-test runs stay fast.
 *
 *   SIM_GATE=1 ./gradlew :core:testDebugUnitTest --tests "com.openzeekr.app.ble.sim.SimGateTest"
 *   SIM_GATE=1 SIM_GATE_WRITE=1 ...   rewrites the baseline after an intended change (ratchet down)
 *   0.3.9 (user decision 15/10): also the owner's fixed worst cases ([SimRunner.WORST_CASES]: a silent or dead
 *   step counter, poor GPS, poor Activity Recognition, a flat signal at the door), [WORST_SEEDS] seeds each.
 *   (CI and the baseline use SIM_LEARN_VISITS=20 SIM_PLATEAU_P=0.15: the self-calibration learns as
 * on the phone, and 15 % of the walks to the car end flat as in the field)
 *   0.3.13 (user decision 17/10): the walk back of the owner's own field walks ([SimBaseline.RETURN_FAMILIES]) over
 *   all suites together - a suite-wide allowance hid it (study E-B opened F45 later in 12 of 13 worlds and passed).
 */
class SimGateTest {
    private val baselineFile = File("src/test/resources/sim-baseline.json")

    @Test fun safetyFindingsDoNotRegress() {
        assumeTrue("set SIM_GATE=1 to run the simulation gate", System.getenv("SIM_GATE") == "1")
        val seeds = SimBaseline.seeds(baselineFile) ?: 5
        val results = SimRunner.run(MAIN_SUITES, seeds, null) + SimRunner.run(SimRunner.WORST_CASES, WORST_SEEDS, null)
        val now = SimReport.counts(results)
        val outDir = File("build/sim-gate").apply { mkdirs() }
        File(outDir, "report.txt").writeText(SimReport.text(results))
        File(outDir, "summary.json").writeText(SimReport.summaryJson(results))
        val base = SimBaseline.read(baselineFile)
        val regressions = mutableListOf<String>()
        val table = StringBuilder("Simulation gate (seeds=$seeds): code  baseline -> now\n")
        for (suite in MAIN_SUITES + SimRunner.WORST_CASES) {
            val b = base[suite].orEmpty(); val n = now[suite].orEmpty()
            for (code in (b.keys + n.keys).filter { it != "runs" }.toSortedSet()) {
                val bv = b[code] ?: 0; val nv = n[code] ?: 0
                val tier = SimOracle.tier(code)
                table.append("  %-10s %-7s %-26s %4d -> %4d%s\n".format(suite, tier, code, bv, nv,
                    if (nv > bv) "  (+${nv - bv})" else if (nv < bv) "  (${nv - bv})" else ""))
                if (tier == "SAFETY" && nv > bv + tolerance(bv)) regressions += "$suite $code: $bv -> $nv"
                if (tier == "COMFORT" && nv > bv + comfortTolerance(bv)) regressions += "$suite $code (comfort): $bv -> $nv"
            }
            table.append("  targets ($suite):\n")
            SimReport.targets(results.filter { it.suite == suite }).forEach { t ->
                table.append("    %-4s %-48s %-22s goal %s\n".format(if (t.met) "ok" else "MISS", t.name, t.value, t.goal))
            }
        }
        // 0.3.13: the walk back of the owner's own field walks. Its median and mean opening distance may drop at most
        // [RETURN_SLACK_CM]; the walks back that open only after the door or not at all may rise at most 10 %.
        table.append("  walks back after a Lock (all suites; opening distance median/mean, after the door or not at all):\n")
        for (fam in SimBaseline.RETURN_FAMILIES) {
            val key = "return-" + fam.lowercase(); val b = base[key].orEmpty(); val n = SimReport.returnStats(results, fam)
            table.append("    %-4s runs %4d -> %4d  median %5.2f m -> %5.2f m  mean %5.2f m -> %5.2f m  late %4d -> %4d\n".format(fam,
                b["runs"] ?: 0, n.runs, (b["medianCm"] ?: 0) / 100.0, n.medianCm / 100.0, (b["meanCm"] ?: 0) / 100.0, n.meanCm / 100.0,
                b["notBeforeDoor"] ?: 0, n.notBeforeDoor))
            b["medianCm"]?.let { if (n.medianCm < it - RETURN_SLACK_CM) regressions += "$key median opening distance: $it cm -> ${n.medianCm} cm" }
            b["meanCm"]?.let { if (n.meanCm < it - RETURN_SLACK_CM) regressions += "$key mean opening distance: $it cm -> ${n.meanCm} cm" }
            b["notBeforeDoor"]?.let { if (n.notBeforeDoor > it + maxOf(3, it / 10)) regressions += "$key after the door or not at all: $it -> ${n.notBeforeDoor}" }
        }
        println(table)
        File(outDir, "gate.txt").writeText(table.toString())
        // The table above compares with the old baseline; a rewrite replaces it after an intended change.
        if (System.getenv("SIM_GATE_WRITE") == "1") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(SimBaseline.write(seeds, results))
            println("sim baseline written: ${baselineFile.absolutePath}")
            return
        }
        if (regressions.isNotEmpty()) fail("Regressions against sim-baseline.json:\n" + regressions.joinToString("\n") +
            "\nSee core/build/sim-gate/report.txt (every finding) and gate.txt.")
    }

    private companion object {
        val MAIN_SUITES = listOf("calibrated", "stress")
        /** The worst-case suites run fewer seeds, so the gate stays within a CI step. */
        const val WORST_SEEDS = 3
        /** 0.3.13: how far the opening on a field walk back may move towards the door (study E-B: about 1 m). */
        const val RETURN_SLACK_CM = 50
    }

    /** Small noise allowance: a changed decision shifts later random draws in the same run. */
    private fun tolerance(base: Int) = maxOf(1, (base * 0.10).toInt())

    /** Comfort findings are more frequent and noisier: a wider allowance, still a gate. */
    private fun comfortTolerance(base: Int) = maxOf(3, (base * 0.15).toInt())
}

/** The committed baseline: per suite, runs affected per finding code (flat JSON). */
internal object SimBaseline {
    fun seeds(f: File): Int? = if (!f.exists()) null else Regex("\"seeds\"\\s*:\\s*(\\d+)").find(f.readText())?.groupValues?.get(1)?.toInt()

    fun read(f: File): Map<String, Map<String, Int>> {
        if (!f.exists()) return emptyMap()
        val text = f.readText()
        return Regex("\"([a-z][a-z0-9-]*)\"\\s*:\\s*\\{([^}]*)\\}").findAll(text).associate { m ->
            m.groupValues[1] to Regex("\"([A-Z_a-z]+)\"\\s*:\\s*(\\d+)").findAll(m.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        }
    }

    /** 0.3.13: the owner's own field walks whose walk back the gate checks on its own (see [SimReport.returnStats]). */
    val RETURN_FAMILIES = listOf("F43", "F45")

    fun write(seeds: Int, results: List<SimResult>): String = buildString {
        append("{\n  \"seeds\": ").append(seeds).append(",\n")
        val suites = SimReport.counts(results).map { (suite, codes) ->
            "  \"$suite\": {\"runs\": ${results.count { it.suite == suite }}" + codes.entries.joinToString("") { (k, v) -> ", \"$k\": $v" } + "}"
        }
        val returns = RETURN_FAMILIES.map { fam -> val r = SimReport.returnStats(results, fam)
            "  \"return-${fam.lowercase()}\": {\"runs\": ${r.runs}, \"medianCm\": ${r.medianCm}, \"meanCm\": ${r.meanCm}, " +
                "\"notBeforeDoor\": ${r.notBeforeDoor}}"
        }
        append((suites + returns).joinToString(",\n")).append("\n}\n")
    }
}
