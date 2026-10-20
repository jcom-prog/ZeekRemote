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
 *   SIM_SUITE   calibrated (default) | stress | both | worst | all - stress samples a different world per
 *               run; worst = the owner's fixed worst cases (0.3.9, [SimRunner.WORST_CASES])
 *   SIM_SEEDS   seeds per family x carry x motion cell (default 3)
 *   SIM_ONLY    comma-separated family prefixes (e.g. "F28,F37")
 *   SIM_OUT     report directory (default build/sim-report)
 *   SIM_TRACE_EVERY_SEED=1  one trace per seed instead of one per (family, finding, carry, motion)
 *   SIM_LEARN_VISITS  0.3.1: ordinary visits in the same world before each run (self-calibration
 *                     learning period; the phone's carry varies per visit). Default 0.
 *   SIM_SELF_CAL=0 / SIM_ARRIVAL_ANCHOR=0 / SIM_H2=0  switch a phase-2 rule off (comparison runs)
 *   SIM_APPROACH  unbroken (0.3.1) | two_in_a_row | two_of_three - walking-approach confirmation (0.3.2)
 *   SIM_KEEP_LEARN_LOG=1  keep the learning period in events and traces (debugging)
 *   SIM_TRACE_CODES=A,B   traces only for these finding codes; SIM_TRACE_SAMPLES=1 adds the 1 s samples
 * SIM_PLATEAU_P chance per walk up to the car of a flat final approach (field; default 0)
 *   SIM_KEYED=0    sequential noise streams instead of the time-keyed noise (default keyed)
 */
class WholeAppSimulationTest {
    @Test fun scenarioMatrix() {
        val outDir = File(System.getenv("SIM_OUT") ?: "build/sim-report").apply { mkdirs() }
        val seeds = (System.getenv("SIM_SEEDS") ?: "3").toInt()
        val suites = when (System.getenv("SIM_SUITE") ?: "calibrated") {
            "both" -> listOf("calibrated", "stress")
            "worst" -> SimRunner.WORST_CASES
            "all" -> listOf("calibrated", "stress") + SimRunner.WORST_CASES
            else -> listOf(System.getenv("SIM_SUITE") ?: "calibrated") }
        val only = System.getenv("SIM_ONLY")?.split(",")
        val traces = SimReport.TraceWriter(outDir, System.getenv("SIM_TRACE_EVERY_SEED") == "1")
        val results = SimRunner.run(suites, seeds, only, traces::accept)
        val report = SimReport.text(results)
        File(outDir, "report.txt").writeText(report)
        File(outDir, "summary.json").writeText(SimReport.summaryJson(results))
        // One line per run for analysis (synthetic worlds only; no field data).
        File(outDir, "runs.csv").writeText(buildString {
            appendLine("suite,family,carry,motion,seed,phoneOffsetDb,slope,learnedOffsetDb,arrivals,codes,learnedRawDb")
            results.forEach { r ->
                appendLine(listOf(r.suite, r.scenario.family.substringBefore(' '), r.carry, r.profile.name, r.seed,
                    "%.1f".format(java.util.Locale.ROOT, r.physics.phoneOffsetDb), "%.1f".format(java.util.Locale.ROOT, r.physics.pathSlope),
                    r.world.learnedOffsetDb ?: "", r.world.learnedProfile?.approach?.size ?: 0,
                    r.findings.map { it.code }.distinct().joinToString("|"),
                    r.world.learnedRawOffsetDb?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "").joinToString(","))
            }
        })
        println(report.lineSequence().take(70).joinToString("\n"))
        if (System.getProperty("sim.strict") == "true") assertTrue("simulation findings", results.all { it.findings.isEmpty() })
    }
}

internal data class SimResult(val suite: String, val scenario: Scenario, val carry: Carry, val profile: MotionProfile,
                              val seed: Long, val physics: SimPhysics, val findings: List<SimOracle.Finding>, val world: SimWorld)

internal object SimRunner {
    val LEARN_VISITS = (System.getenv("SIM_LEARN_VISITS") ?: "0").toInt()
    /** 0.3.9 (user decision 15/10): the uncertain phone behaviour as fixed worst cases, each on the calibrated world.
     *  0.3.11 (user decision 16/10): two more from the measurement walk of 16/10.
     *  0.3.12 (user decision 16/10): quick STILL, the hand wobble, and the field of 16/10 in one world.
     *  0.3.13 (user decision 17/10): the strong spot also while walking.
     *  Quick walk 18/10 (user decision): late steps up to 18 s, already after 5 s of standing (steps-late).
     *  0.3.19 (user decision 18/10): steps that stall in the middle of a walk, and a link that holds deeper indoors.
     *  0.3.21 (user decision 20/10): connects at the edge of range that end in status 133. */
    val WORST_CASES = listOf("steps-silent", "steps-dead", "gps-poor", "ar-poor", "signal-flat", "steps-late", "strong-spot", "ar-quick-still",
        "hand-wobble", "field-like", "strong-spot-walk", "steps-stall", "link-holds", "steps-after-drive", "edge-133")

    /** The world of a run. A stress world depends on the seed only, so two app versions meet the same world. */
    fun physicsFor(suite: String, seed: Long): SimPhysics = when (suite) {
        "stress" -> SimPhysics.random(SimRandom(seed xor 0x5EED_0F_57E55L))
        // Field 15/10: the counter was silent for 151.6 s while walking.
        "steps-silent" -> SimPhysics(name = suite, hubFreezeMs = 180_000L)
        "steps-dead" -> SimPhysics(name = suite, hubNone = true)
        // Field 15/10: 16 of 18 fixes at the car were worse than 8 m.
        "gps-poor" -> SimPhysics(name = suite, gnssAccMedianM = 12.0, gnssAccSpread = 0.35, gnssTtffMs = 15_000L)
        // The signal stays flat over the last metres to the door in most walks (field: phone in a pocket).
        "signal-flat" -> SimPhysics(name = suite, approachPlateauP = 0.6)
        // Field 16/10 (S24+, front pocket): after standing, counter and detector delivered a walk's first steps
        // 8-14 s late, all at once; quick walk 18/10: later still, after a short stop at the car.
        "steps-late" -> SimPhysics(name = suite, lateStepsMinMs = 8_000L, lateStepsMaxMs = 18_000L)
        // Field 16/10: standing at the front door (~25 m) read -86/-87 in the front pocket - the unlock threshold.
        "strong-spot" -> SimPhysics(name = suite, spotDb = 7.0)
        // Field 16/10: walking from 30 to 25 m read -81..-87 in the front pocket too (an upper bound: not every visit).
        "strong-spot-walk" -> SimPhysics(name = suite, spotDb = 7.0, spotWhileWalking = true)
        // Field 16/10, phone in the hand: walking wobbles 1.9-2.4 dB at the 1 s scale (sim 3 dB -> 2.2).
        "hand-wobble" -> SimPhysics(name = suite, handWobbleDb = 3.0)
        // Field 18/10: the step stream stalled for 10-18 s in the middle of a short walk.
        "steps-stall" -> SimPhysics(name = suite, midStallMinMs = 10_000L, midStallMaxMs = 18_000L)
        // Field 18/10: the link held for minutes indoors at about -100..-105.
        "link-holds" -> SimPhysics(name = suite, dropRssi = -107.0)
        // Study J (field 16/10-19/10): after a drive the steps came only after 12-25 s of walking, at once, or not at all,
        // and Activity Recognition said MOVING for minutes.
        "steps-after-drive" -> SimPhysics(name = suite, afterDriveReleaseMinMs = 12_000L, afterDriveReleaseMaxMs = 25_000L, dropRssi = -107.0)
        // Field 20/10 (phone in a pocket): the presence scan heard the car indoors at about -100; two connects at an advert
        // of about -90 ended in status 133, one a few dB stronger connected. Here every connect below -90 fails.
        "edge-133" -> SimPhysics(name = suite, presenceFloorRssi = -100.0, edge133Rssi = -90.0)
        // The field of 16/10 together: late steps after standing, quick STILL (profileFor), poor GPS as in
        // "gps-poor", and the hand wobble.
        "field-like" -> SimPhysics(name = suite, lateStepsMinMs = 8_000L, lateStepsMaxMs = 14_000L,
            gnssAccMedianM = 12.0, gnssAccSpread = 0.35, gnssTtffMs = 15_000L, handWobbleDb = 3.0)
        else -> SimPhysics.calibrated()
    }

    /** "ar-poor": Activity Recognition reports a walk up to 30 s late and misses 40 % of the walks. */
    fun profileFor(suite: String, p: MotionProfile): MotionProfile = when (suite) {
        "ar-poor" -> p.copy(name = p.name + "-arpoor", onLagMaxMs = maxOf(p.onLagMaxMs, 30_000L), missBoutP = maxOf(p.missBoutP, 0.4))
        // Field 16/10: Activity Recognition said STILL 1-5 s after a stop.
        "ar-quick-still", "field-like" -> p.copy(name = p.name + "-arquick", offLagMinMs = 1_000L, offLagMaxMs = 3_000L)
        else -> p
    }

    /** [onRun] sees every run with its full log (traces); the log is dropped afterwards to bound memory. */
    /** Study (doorbraak D): worlds are independent; SIM_PARALLEL workers run them, results stay in order. */
    private val PARALLEL = (System.getenv("SIM_PARALLEL") ?: "1").toInt()

    private fun runOne(suite: String, sc: Scenario, carry: Carry, mp: Pair<MotionProfile, Long>): SimResult {
        val (baseProfile, seed) = mp
        val profile = profileFor(suite, baseProfile)
        val physics = physicsFor(suite, seed)
        val w = SimWorld(seed, profile, carry, sc.startM, sc.gnssAccuracyM, carRefusesKeyLock = sc.carRefusesKeyLock,
            keyEdgeRssi = sc.keyEdgeRssi, physics = physics, cloudDown = sc.cloudDown)
        // 0.3.1: SIM_LEARN_VISITS ordinary visits in the same world first (self-calibration).
        val learning = if (LEARN_VISITS > 0) SimScenarios.learningVisits(SimRandom(seed xor 0x1EA2_0F_CA1L), LEARN_VISITS)
            else emptyList()
        w.play(sc.legs, learning)
        return SimResult(suite, sc, carry, profile, seed, physics, SimOracle.check(w), w)
    }

    fun run(suites: List<String>, seeds: Int, only: List<String>?, onRun: ((SimResult) -> Unit)? = null): List<SimResult> {
        val jobs = suites.flatMap { suite ->
            SimScenarios.matrix(seeds).filter { r -> only == null || only.any { r.first.family.startsWith(it) } }
                .map { suite to it }
        }
        fun collect(r: SimResult): SimResult {
            onRun?.invoke(r)
            r.world.logLines.clear(); r.world.logLines.trimToSize()
            return r
        }
        if (PARALLEL <= 1) return jobs.map { (suite, j) -> collect(runOne(suite, j.first, j.second, j.third)) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try {
            // Bounded look-ahead: at most PARALLEL + 2 finished worlds wait with their logs in memory.
            val ahead = PARALLEL + 2
            val futures = arrayOfNulls<java.util.concurrent.Future<SimResult>>(jobs.size)
            var submitted = 0
            fun submitUpTo(n: Int) {
                while (submitted < minOf(n, jobs.size)) {
                    val (suite, j) = jobs[submitted]
                    futures[submitted] = pool.submit(java.util.concurrent.Callable { runOne(suite, j.first, j.second, j.third) })
                    submitted++
                }
            }
            submitUpTo(ahead)
            return jobs.indices.map { i ->
                val r = futures[i]!!.get()
                futures[i] = null
                submitUpTo(i + 1 + ahead)
                collect(r)
            }
        } finally {
            pool.shutdown()
        }
    }
}

internal object SimReport {
    private fun pct(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * p).toInt()]

    /**
     * 0.3.13: the walk back of [family] (its last expected approach) in every run where the car was still locked when it
     * began: the median and mean distance at which the car opened, in cm (a walk back that never opened counts as 0 m),
     * and the runs that opened only after the door or not at all, over all suites in [results].
     */
    fun returnStats(results: List<SimResult>, family: String): ReturnStats {
        val dists = mutableListOf<Double>(); var late = 0
        results.filter { it.scenario.family.substringBefore(' ') == family }.forEach { r ->
            val marks = r.world.events.filter { it.kind == "MARK" }
            val from = marks.lastOrNull { it.detail == "expect-unlock" }?.atMs ?: return@forEach
            val before = r.world.events.lastOrNull { (it.kind == "CAR_UNLOCKED" || it.kind == "CAR_LOCKED") && it.atMs < from }
            if (before?.kind == "CAR_UNLOCKED") return@forEach
            val end = marks.firstOrNull { it.atMs > from }?.atMs ?: Long.MAX_VALUE
            dists += r.world.events.firstOrNull { it.kind == "CAR_UNLOCKED" && it.atMs in from until end }?.distanceM ?: 0.0
            if (r.findings.any { it.atMs >= from && (it.code == "APPROACH_UNLOCK_AFTER_DOOR" || it.code == "APPROACH_NO_UNLOCK") }) late++
        }
        dists.sort()
        return ReturnStats(dists.size, if (dists.isEmpty()) 0 else Math.round(dists[(dists.size - 1) / 2] * 100).toInt(),
            if (dists.isEmpty()) 0 else Math.round(dists.average() * 100).toInt(), late)
    }
    data class ReturnStats(val runs: Int, val medianCm: Int, val meanCm: Int, val notBeforeDoor: Int)

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

    /**
     * Absolute targets (0.3.2): what the owner should notice, not only "better than the last version".
     * Field: the comparison-only view hid that about 1 in 9 front-pocket approaches opened late.
     */
    data class Target(val name: String, val value: String, val goal: String, val met: Boolean)

    /**
     * The owner's goals (0.3.9, user decision 15/10): open before the door in >= 98 % and lock by itself at
     * 10-25 m in >= 98 % (front pocket), never open from more than 20 m, never lock at the car or while
     * walking back, never an open car with the user away and nobody told.
     */
    fun targets(rs: List<SimResult>): List<Target> {
        fun has(r: SimResult, vararg codes: String) = r.findings.any { it.code in codes }
        fun expects(r: SimResult, what: String) = r.world.events.any { it.kind == "MARK" && it.detail == what }
        fun share(n: Int, d: Int) = if (d == 0) "-" else "%d/%d (%.1f%%)".format(n, d, 100.0 * n / d)
        fun pctOk(n: Int, d: Int, goal: Double) = d == 0 || 100.0 * n / d >= goal
        val pocket = rs.filter { it.carry != Carry.HAND }
        val hand = rs.filter { it.carry == Carry.HAND }
        val front = rs.filter { it.carry == Carry.FRONT_POCKET }
        val approaches = front.filter { expects(it, "expect-unlock") }
        val onTime = approaches.count { !has(it, "APPROACH_UNLOCK_LATE", "APPROACH_UNLOCK_AFTER_DOOR", "APPROACH_NO_UNLOCK") }
        val departures = front.filter { expects(it, "expect-lock") }
        val lockedInBand = departures.count { lockedBetween(it, 10.0, 25.0) }
        // 0.3.12: the same lock target for the phone in the hand.
        val handDepartures = hand.filter { expects(it, "expect-lock") }
        val handLockedInBand = handDepartures.count { lockedBetween(it, 10.0, 25.0) }
        fun farUnlocks(set: List<SimResult>) = set.count { r -> r.findings.any { it.code == "UNLOCK_WHILE_FAR" && it.distanceM > 20.0 } }
        val farPocket = farUnlocks(pocket)
        val farHand = farUnlocks(hand)
        val unwanted = rs.count { has(it, "UNWANTED_UNLOCK", "UNLOCK_WHILE_LOITERING") }
        val lockNear = rs.count { has(it, "LOCK_WHILE_NEAR", "LOCK_WHILE_RETURNING", "UNWANTED_LOCK_NEAR") }
        // 0.3.10: an alarm silenced too early while the car is open and the user away is silent too.
        val silent = rs.count { has(it, "DEPARTURE_NO_LOCK_SILENT", "OPEN_FAR_NO_ALARM", "GONE_BUT_UNLOCKED", "ALARM_SILENCED_AWAY") }
        return listOf(
            Target("never opens from > 20 m, phone in a pocket", share(farPocket, pocket.size), "0", farPocket == 0),
            Target("never opens from > 20 m, phone in the hand", share(farHand, hand.size), "0", farHand == 0),
            Target("never opens where the car should stay locked", share(unwanted, rs.size), "0", unwanted == 0),
            Target("never locks near or while walking back", share(lockNear, rs.size), "0", lockNear == 0),
            Target("never silent while open and away", share(silent, rs.size), "0", silent == 0),
            Target("front pocket: approach opens before the door", share(onTime, approaches.size), ">= 98%", pctOk(onTime, approaches.size, 98.0)),
            Target("front pocket: locks by itself at 10-25 m", share(lockedInBand, departures.size), ">= 98%", pctOk(lockedInBand, departures.size, 98.0)),
            Target("hand: locks by itself at 10-25 m", share(handLockedInBand, handDepartures.size), ">= 98%",
                pctOk(handLockedInBand, handDepartures.size, 98.0)),
        )
    }

    /** The app's own Lock (not the car's self-lock) after the departure mark, between [from] and [to] metres. */
    private fun lockedBetween(r: SimResult, from: Double, to: Double): Boolean {
        val mark = r.world.events.firstOrNull { it.kind == "MARK" && it.detail == "expect-lock" } ?: return false
        val lock = r.world.events.firstOrNull { it.kind == "CAR_LOCKED" && it.atMs >= mark.atMs } ?: return false
        return lock.detail != "self-lock" && lock.distanceM in from..to
    }

    fun text(results: List<SimResult>): String {
        val sb = StringBuilder()
        for ((suite, rs) in results.groupBy { it.suite }) {
            sb.appendLine("=== Suite: $suite — ${rs.size} runs, ${rs.count { it.findings.isEmpty() }} without findings, " +
                "${rs.count { r -> r.findings.any { SimOracle.tier(it.code) == "SAFETY" } }} with a SAFETY finding ===")
            sb.appendLine()
            sb.appendLine("Targets (what the owner notices):")
            targets(rs).forEach { t -> sb.appendLine("  %-4s %-48s %-22s goal %s".format(if (t.met) "ok" else "MISS", t.name, t.value, t.goal)) }
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
            val learned = rs.mapNotNull { it.world.learnedOffsetDb }.sorted()
            if (learned.isNotEmpty()) {
                val approach = rs.mapNotNull { r -> r.world.learnedProfile?.approach?.takeIf { it.size >= 5 }?.sorted()?.let { it[it.size / 2] } }.sorted()
                val truth = rs.filter { it.world.learnedOffsetDb != null }.map { it.physics.phoneOffsetDb }
                val err = rs.filter { it.world.learnedOffsetDb != null && (it.world.learnedProfile?.approach?.size ?: 0) >= 5 }
                    .mapNotNull { r -> r.world.learnedRawOffsetDb?.let { it - r.physics.phoneOffsetDb } }.sorted()
                sb.appendLine(("Self-calibration: %d runs, %d with >= 5 arrivals; offset dB p10=%d median=%d p90=%d; " +
                    "approach level median p10=%.1f median=%.1f p90=%.1f; error vs true phone offset p10=%.1f median=%.1f p90=%.1f (true median %.1f)").format(
                    learned.size, approach.size, learned[(learned.size - 1) / 10], learned[(learned.size - 1) / 2], learned[(learned.size - 1) * 9 / 10],
                    pct(approach, .1), pct(approach, .5), pct(approach, .9), pct(err, .1), pct(err, .5), pct(err, .9), pct(truth.sorted(), .5)))
            }
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
            val gnssWindows = rs.map { it.world.controller.maxGnssWindowsInAnEpoch }.sorted()
            sb.appendLine("GNSS windows in one visit (25 s each): median=%d p90=%d max=%d".format(
                gnssWindows[(gnssWindows.size - 1) / 2], gnssWindows[(gnssWindows.size - 1) * 9 / 10], gnssWindows.last()))
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
        /** SIM_TRACE_CODES=A,B: only these finding codes produce traces (default all). */
        private val codes = System.getenv("SIM_TRACE_CODES")?.split(",")?.toSet()
        fun accept(r: SimResult) {
            if (all) {
                val name = (r.suite + "_" + r.scenario.family + "_" + r.carry + "_" + r.profile.name + "_" + r.seed).replace(Regex("[^A-Za-z0-9_+-]"), "_")
                File(outDir, "run_$name.txt").writeText(body(r, "findings=${r.findings.joinToString { it.code }}"))
            }
            r.findings.filter { codes == null || it.code in codes }.forEach { f ->
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
            if (r.world.learnedOffsetDb != null) appendLine("learned: ${r.world.learnedSummary}")
            appendLine(head)
            appendLine("--- events"); r.world.events.forEach { appendLine("%8.1f d=%5.1f %s %s".format(it.atMs / 1000.0, it.distanceM, it.kind, it.detail)) }
            appendLine("--- log"); r.world.logLines.forEach { appendLine(it) }
            if (System.getenv("SIM_TRACE_SAMPLES") == "1") {
                appendLine("--- samples (t d locked walking appOpen link cpu gnss trueRssi motion steps)")
                r.world.samples.forEach { s -> appendLine("%8.1f d=%5.1f %s %s %s %s %s %s %6.1f %s %s".format(s.atMs / 1000.0, s.distanceM,
                    if (s.carLocked) "L" else "U", if (s.moving) "mov" else "---", if (s.appBelievesUnlocked) "open" else "lckd",
                    if (s.linkUp) "link" else "----", if (s.cpuAwake) "cpu" else "zzz", if (s.gnssOn) "gnss" else "----",
                    s.trueRssi, s.motionState, s.steps ?: "-")) }
            }
        }
    }
}
