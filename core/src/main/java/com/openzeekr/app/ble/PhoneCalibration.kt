package com.openzeekr.app.ble

import kotlin.math.roundToInt

/**
 * Self-calibration per phone (0.3.1, plan chapter 5.1). Every RSSI threshold in the proximity logic
 * was tuned on one phone: the owner's S24+ in a pocket (the simulator's calibrated world). Another
 * phone, case or firmware hears the car several dB better or worse (stress test: +4 dB opened the
 * car from > 15 m five times as often, -4 dB doubled the late openings). This class learns how THIS
 * phone hears the car from moments whose distance is known without any RSSI, and maps its readings
 * onto the reference phone, so every existing rule keeps working on the scale it was tuned for.
 *
 * The known moments come from the step counter (it counts with the screen off):
 *  - **arrival**: a walk of at least [MIN_BOUT_STEPS] steps ends at the car. Proven by the signal:
 *    it rose by at least [MIN_RISE_DB] over the last [APPROACH_STEPS] steps, as only the last
 *    metres before the car do (a stop at 5 m to talk rises about half as much). Learned: the level
 *    [APPROACH_STEPS] steps before the stop (about 5-6 m, the distance an approach should open at)
 *    and the level at the car itself;
 *  - **departure**: after an arrival, a walk away; learned: the level [DEPART_STEPS] steps out
 *    (about 17 m), or "link already lost" when the phone no longer heard the car there.
 *
 * Only the approach level sets the correction ([offsetDb]): the median of the last [KEEP] samples
 * against the reference phone's level ([REF_APPROACH_DB]), after at least [MIN_SAMPLES] arrivals.
 * The correction only ever makes the app MORE careful with opening (simulator 14/10): for a phone
 * that hears the car better, its readings are lowered, so it opens at the reference distance
 * instead of from 15 m+. A phone that seems to hear worse is not raised: one decibel more already
 * doubled the openings from more than 15 m with the phone in the hand, and the learned value of
 * the reference phone itself scatters by 1-2 dB (the pocket changes from walk to walk). So: only
 * [MIN_APPLIED_DB] dB or more counts, reduced by [DEAD_BAND_DB] and shrunk while few arrivals are
 * known, at most [MAX_OFFSET_DB]. [learnedOffsetDb] shows the unrestricted value. The car and far
 * levels are shown to the user and kept for a later step; they change no decision yet.
 * No positions, no identifiers. Pure Kotlin.
 */
internal class PhoneCalibration(
    private val store: PhoneCalibrationStore = PhoneCalibrationStore.None,
    private val refApproachDb: Double = REF_APPROACH_DB,
) {
    /** A proven arrival at the car, reported when it is recognised (up to ~15 s after the stop). */
    data class Arrival(val atMs: Long, val stepsAtCar: Long, val carLevelDb: Double)

    data class Profile(
        val approach: List<Double> = emptyList(),
        val atCar: List<Double> = emptyList(),
        /** null = the link was already lost at the far point (the phone heard nothing there). */
        val far: List<Double?> = emptyList(),
    )

    @Volatile var profile: Profile = runCatching { store.load() }.getOrNull() ?: Profile()
        private set

    /** dB this phone hears the car better (+) or worse (-) than the reference phone (null while learning). */
    @Volatile var learnedOffsetDb: Double? = learnedFor(profile)
        private set

    /** The correction applied to readings: only ever lowers them (see the class comment). */
    @Volatile var offsetDb: Int = appliedFor(profile)
        private set

    /** Raw reading of this phone -> the reference phone's scale. */
    fun normalize(rawRssi: Int): Int = rawRssi - offsetDb

    /** Called when a proven arrival is recognised (the departure logic may use it as its anchor). */
    var onArrival: ((Arrival) -> Unit)? = null

    /** Categorical outcome of each examined stop or departure, for the log (no positions). */
    var onDecision: ((String) -> Unit)? = null
    private fun note(what: String) { onDecision?.invoke(what) }

    private data class Reading(val atMs: Long, val rssi: Int)
    private data class StepMark(val atMs: Long, val steps: Long)
    private val readings = ArrayDeque<Reading>()
    private val stepMarks = ArrayDeque<StepMark>()
    // The latest arrival and the departure that may follow it.
    private var arrivalSteps: Long? = null
    private var arrivalAtMs = 0L
    private var departBaseSteps: Long? = null
    private var departDone = true
    private var lastHandledStopAtMs = 0L

    /** One fresh connected reading (raw, before [normalize]); [steps] is the cumulative step count. */
    fun observe(nowMs: Long, rawRssi: Int, steps: Long?) {
        readings.addLast(Reading(nowMs, rawRssi))
        while (readings.isNotEmpty() && nowMs - readings.first().atMs > BUFFER_MS) readings.removeFirst()
        if (steps == null) return
        val last = stepMarks.lastOrNull()
        if (last == null || steps != last.steps) {
            if (last != null && steps < last.steps) { stepMarks.clear(); departDone = true; arrivalSteps = null }
            // A new walk after a pause: a departure counts from where this walk began.
            else if (last != null && !departDone && arrivalSteps != null && nowMs - last.atMs >= stopMs())
                departBaseSteps = last.steps
            stepMarks.addLast(StepMark(nowMs, steps))
        }
        while (stepMarks.size > 2 && nowMs - stepMarks[1].atMs > BUFFER_MS) stepMarks.removeFirst()
        checkDeparture(nowMs, steps)
        checkArrival(nowMs, steps)
    }

    /** The link is gone: a departure that has not reached its far point yet ends as "not heard". */
    fun onLinkLost(nowMs: Long) {
        val base = departBaseSteps
        if (!departDone && base != null && nowMs - arrivalAtMs < MAX_DEPART_MS) {
            val latest = stepMarks.lastOrNull()?.steps ?: base
            // Only once really leaving; losing the link beside the car proves nothing.
            if (latest - base >= MIN_LOST_STEPS) { record(far = null); note("departure learned: link lost before the far point") }
        }
        departDone = true
        readings.clear()
    }

    fun reset() {
        profile = Profile(); offsetDb = 0; learnedOffsetDb = null
        runCatching { store.save(profile) }
        readings.clear(); stepMarks.clear(); arrivalSteps = null; departDone = true
    }

    /**
     * How long the count must stand still to call it a stop: some phones hand over their step count
     * only every few seconds while walking, so a pause must be clearly longer than that rhythm.
     */
    private fun stopMs(): Long {
        val gaps = stepMarks.toList().takeLast(10).zipWithNext { a, b -> b.atMs - a.atMs }.sorted()
        val typical = if (gaps.isEmpty()) 0L else gaps[gaps.size / 2]
        return maxOf(STOP_MS, typical * 8 / 5)
    }

    private fun checkArrival(nowMs: Long, steps: Long) {
        val lastChange = stepMarks.lastOrNull() ?: return
        if (lastChange.atMs == lastHandledStopAtMs) return
        val stopMs = stopMs()
        if (nowMs - lastChange.atMs < stopMs) return
        lastHandledStopAtMs = lastChange.atMs
        // The bout that ended here: back to the latest pause.
        var boutStart = stepMarks.size - 1
        while (boutStart > 0 && stepMarks[boutStart].atMs - stepMarks[boutStart - 1].atMs < stopMs) boutStart--
        val bout = stepMarks.toList().subList(boutStart, stepMarks.size)
        val boutSteps = steps - bout.first().steps
        if (bout.size < 3 || boutSteps < MIN_BOUT_STEPS) { if (boutSteps >= 4) note("stop after $boutSteps steps: walk too short"); return }
        // Cadence from the bout without its last interval (that one contains the stop itself).
        val ratePerMs = (bout[bout.size - 2].steps - bout.first().steps).toDouble() /
            (bout[bout.size - 2].atMs - bout.first().atMs).coerceAtLeast(1L)
        if (ratePerMs <= 0.0) { note("stop: no cadence"); return }
        val prev = bout[bout.size - 2]
        val stopAtMs = prev.atMs + ((steps - prev.steps) / ratePerMs).toLong().coerceAtMost(lastChange.atMs - prev.atMs)
        val approachAtMs = timeAtSteps(bout, steps - APPROACH_STEPS, ratePerMs, stopAtMs)
            ?: run { note("stop: no step history"); return }
        val approach = meanAround(approachAtMs) ?: run { note("stop: no reading $APPROACH_STEPS steps before"); return }
        val atCar = meanBetween(stopAtMs + 500L, stopAtMs + 3_500L) ?: run { note("stop: no reading after the stop"); return }
        if (atCar - approach < MIN_RISE_DB) { note("stop: rise %.0f dB, not at the car".format(atCar - approach)); return }
        arrivalSteps = steps; arrivalAtMs = stopAtMs
        departBaseSteps = null; departDone = false
        record(approach = approach, atCar = atCar)
        note("arrival learned: approach %.0f car %.0f".format(approach, atCar))
        onArrival?.invoke(Arrival(stopAtMs, steps, atCar))
    }

    private fun checkDeparture(nowMs: Long, steps: Long) {
        if (departDone) return
        val atCar = arrivalSteps ?: return
        if (nowMs - arrivalAtMs > MAX_DEPART_MS) { departDone = true; return }
        // The walk away starts with the first steps after the arrival (or after a later pause).
        val base = departBaseSteps ?: if (steps > atCar) atCar.also { departBaseSteps = it } else return
        if (steps - base < DEPART_STEPS) return
        departDone = true
        val bout = stepMarks.toList()
        val ratePerMs = bout.zipWithNext().lastOrNull { (a, b) -> b.steps > a.steps }
            ?.let { (a, b) -> (b.steps - a.steps).toDouble() / (b.atMs - a.atMs).coerceAtLeast(1L) } ?: return
        val farAtMs = timeAtSteps(bout, base + DEPART_STEPS, ratePerMs, nowMs) ?: return
        val far = meanAround(farAtMs) ?: run { note("departure: no reading $DEPART_STEPS steps out"); return }
        // Not a departure if the signal did not fall (a walk around the car).
        val car = profile.atCar.lastOrNull() ?: return
        if (car - far < MIN_FALL_DB) { note("departure: signal did not fall"); return }
        record(far = far)
        note("departure learned: far %.0f".format(far))
    }

    /** When the count passed [target], from step marks assuming a steady cadence; null if unknown. */
    private fun timeAtSteps(marks: List<StepMark>, target: Long, ratePerMs: Double, notAfterMs: Long): Long? {
        if (marks.isEmpty() || target < marks.first().steps) return null
        for (i in marks.indices.reversed()) {
            val m = marks[i]
            if (m.steps <= target) {
                val next = marks.getOrNull(i + 1)
                val t = m.atMs + ((target - m.steps) / ratePerMs).toLong()
                return if (next != null) minOf(t, next.atMs, notAfterMs) else minOf(t, notAfterMs)
            }
        }
        return null
    }

    private fun meanAround(atMs: Long) = meanBetween(atMs - WINDOW_HALF_MS, atMs + WINDOW_HALF_MS)

    private fun meanBetween(fromMs: Long, toMs: Long): Double? {
        val r = readings.filter { it.atMs in fromMs..toMs }
        return if (r.size < MIN_READINGS) null else r.map { it.rssi }.average()
    }

    private fun record(approach: Double? = null, atCar: Double? = null, far: Double? = Double.NaN) {
        val p = profile
        val next = Profile(
            approach = if (approach != null) (p.approach + approach).takeLast(KEEP) else p.approach,
            atCar = if (atCar != null) (p.atCar + atCar).takeLast(KEEP) else p.atCar,
            far = if (far == null || !far.isNaN()) (p.far + far).takeLast(KEEP) else p.far,
        )
        profile = next
        learnedOffsetDb = learnedFor(next)
        offsetDb = appliedFor(next)
        runCatching { store.save(next) }
    }

    private fun learnedFor(p: Profile): Double? {
        if (p.approach.size < MIN_SAMPLES) return null
        val sorted = p.approach.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        return median - refApproachDb
    }

    private fun appliedFor(p: Profile): Int {
        val learned = learnedFor(p) ?: return 0
        if (learned < MIN_APPLIED_DB) return 0
        // Few arrivals: trust the median less (n / (n + SHRINK_SAMPLES)).
        val n = p.approach.size.toDouble()
        return ((learned - DEAD_BAND_DB) * n / (n + SHRINK_SAMPLES)).roundToInt().coerceIn(0, MAX_OFFSET_DB)
    }

    /** One line for the log and the settings card; levels only, no positions. */
    fun summary(): String {
        val p = profile
        fun med(l: List<Double>) = l.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        return "arrivals=${p.approach.size}/$MIN_SAMPLES offset=${offsetDb}dB" +
            " learned=${learnedOffsetDb?.let { "%+.1f".format(it) } ?: "-"}" +
            " approach=${med(p.approach)?.let { "%.0f".format(it) } ?: "-"}" +
            " atCar=${med(p.atCar)?.let { "%.0f".format(it) } ?: "-"}" +
            " far=${p.far.size}(${p.far.count { it == null }} lost)"
    }

    companion object {
        /** Approach level of the reference phone (S24+, pocket mix) [APPROACH_STEPS] before the car. */
        const val REF_APPROACH_DB = -84.7
        const val APPROACH_STEPS = 8L
        const val DEPART_STEPS = 25L
        const val MIN_BOUT_STEPS = 16L
        const val MIN_RISE_DB = 7.0
        const val MIN_FALL_DB = 8.0
        const val MIN_LOST_STEPS = 10L
        const val MIN_SAMPLES = 5
        const val KEEP = 30
        const val MIN_APPLIED_DB = 2.0
        const val DEAD_BAND_DB = 1.0
        const val SHRINK_SAMPLES = 4.0
        const val MAX_OFFSET_DB = 6
        const val STOP_MS = 6_000L
        const val BUFFER_MS = 90_000L
        const val MAX_DEPART_MS = 10 * 60_000L
        const val WINDOW_HALF_MS = 1_500L
        const val MIN_READINGS = 2
    }
}

/** Where the learned profile survives a restart (app: SharedPreferences; simulator: memory). */
internal interface PhoneCalibrationStore {
    fun load(): PhoneCalibration.Profile?
    fun save(profile: PhoneCalibration.Profile)

    object None : PhoneCalibrationStore {
        override fun load(): PhoneCalibration.Profile? = null
        override fun save(profile: PhoneCalibration.Profile) {}
    }

    companion object {
        /** "a=-84.1,-85.0;c=-75.2;f=-96.0,L" - L marks "link already lost". */
        fun encode(p: PhoneCalibration.Profile): String =
            "a=" + p.approach.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) } +
                ";c=" + p.atCar.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) } +
                ";f=" + p.far.joinToString(",") { it?.let { v -> "%.1f".format(java.util.Locale.ROOT, v) } ?: "L" }

        fun decode(s: String): PhoneCalibration.Profile? = runCatching {
            val parts = s.split(";").associate { it.substringBefore("=") to it.substringAfter("=", "") }
            fun list(k: String) = parts[k].orEmpty().split(",").filter { it.isNotBlank() }
            PhoneCalibration.Profile(
                approach = list("a").map { it.toDouble() },
                atCar = list("c").map { it.toDouble() },
                far = list("f").map { if (it == "L") null else it.toDouble() },
            )
        }.getOrNull()
    }
}

internal class SharedPrefsPhoneCalibrationStore(context: android.content.Context) : PhoneCalibrationStore {
    private val prefs = context.getSharedPreferences("phone_calibration", android.content.Context.MODE_PRIVATE)
    override fun load(): PhoneCalibration.Profile? = prefs.getString("profile", null)?.let { PhoneCalibrationStore.decode(it) }
    override fun save(profile: PhoneCalibration.Profile) {
        prefs.edit().putString("profile", PhoneCalibrationStore.encode(profile)).apply()
    }
}
