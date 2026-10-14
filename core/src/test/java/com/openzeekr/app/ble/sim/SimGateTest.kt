package com.openzeekr.app.ble.sim

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression gate (fase 0, 14/10). Runs both suites on the seeds of the committed baseline and fails
 * when any SAFETY finding occurs in more runs than the baseline allows, and (0.3.2) when a COMFORT
 * finding - a late or missing unlock, a lock that does not come - grows beyond a wider allowance: the
 * late unlock of 14/10 18:31 was a comfort finding that no gate looked at. The owner's targets are
 * printed with the table. Only runs with SIM_GATE=1 (CI step), so ordinary unit-test runs stay fast.
 *
 *   SIM_GATE=1 ./gradlew :core:testDebugUnitTest --tests "com.openzeekr.app.ble.sim.SimGateTest"
 *   SIM_GATE=1 SIM_GATE_WRITE=1 ...   rewrites the baseline after an intended change (ratchet down)
 *   (CI and the baseline use SIM_LEARN_VISITS=20 SIM_PLATEAU_P=0.15: the self-calibration learns as
 *   on the phone, and 15 % of the walks to the car end flat as on 14/10 18:31)
 */
class SimGateTest {
    private val baselineFile = File("src/test/resources/sim-baseline.json")

    @Test fun safetyFindingsDoNotRegress() {
        assumeTrue("set SIM_GATE=1 to run the simulation gate", System.getenv("SIM_GATE") == "1")
        val seeds = SimBaseline.seeds(baselineFile) ?: 5
        val results = SimRunner.run(listOf("calibrated", "stress"), seeds, null)
        val now = SimReport.counts(results)
        val outDir = File("build/sim-gate").apply { mkdirs() }
        File(outDir, "report.txt").writeText(SimReport.text(results))
        File(outDir, "summary.json").writeText(SimReport.summaryJson(results))
        if (System.getenv("SIM_GATE_WRITE") == "1") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(SimBaseline.write(seeds, results))
            println("sim baseline written: ${baselineFile.absolutePath}")
            return
        }
        val base = SimBaseline.read(baselineFile)
        val regressions = mutableListOf<String>()
        val table = StringBuilder("Simulation gate (seeds=$seeds): code  baseline -> now\n")
        for (suite in listOf("calibrated", "stress")) {
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
        println(table)
        File(outDir, "gate.txt").writeText(table.toString())
        if (regressions.isNotEmpty()) fail("Regressions against sim-baseline.json:\n" + regressions.joinToString("\n") +
            "\nSee core/build/sim-gate/report.txt (every finding) and gate.txt.")
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
        return Regex("\"(calibrated|stress)\"\\s*:\\s*\\{([^}]*)\\}").findAll(text).associate { m ->
            m.groupValues[1] to Regex("\"([A-Z_a-z]+)\"\\s*:\\s*(\\d+)").findAll(m.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        }
    }

    fun write(seeds: Int, results: List<SimResult>): String = buildString {
        append("{\n  \"seeds\": ").append(seeds).append(",\n")
        val c = SimReport.counts(results)
        c.entries.forEachIndexed { i, (suite, codes) ->
            append("  \"").append(suite).append("\": {\"runs\": ").append(results.count { it.suite == suite })
            codes.forEach { (k, v) -> append(", \"").append(k).append("\": ").append(v) }
            append("}").append(if (i < c.size - 1) ",\n" else "\n")
        }
        append("}\n")
    }
}
