package com.openzeekr.app.util

/**
 * Measurement version (battery plan, owner approval 20/10): the day book of the battery measurement.
 * Pure Kotlin - the Android glue feeds it fuel-gauge samples at moments the CPU is already awake and
 * asks for one privacy-safe summary line per local day. It decides nothing.
 *
 * What it answers, per day:
 *  - how much charge the phone used outside charging (mAh), and per screen-off hour (the usage-
 *    independent rate the ON/OFF day comparison needs);
 *  - how often the first-movement wake fired and what the early-wake pulses cost by the current
 *    meter (measurement A); the ON/OFF day difference is measurement B;
 *  - how many pulses saw real walking, and the median head start on the old motion signal.
 *
 * Day mode alternates automatically: on an even day of the year the early-wake pulse MAY run
 * ("pulse day"), on an odd day it must not. Alternating days cancel the owner's day-to-day rhythm
 * out of the comparison. The glue asks [pulseDay]; this class only books what actually happened.
 */
class BatteryLedger {
    data class Sample(
        val elapsedMs: Long,
        /** Remaining charge in microampere-hours (the fuel gauge's "litre counter"); null: no gauge. */
        val chargeUah: Long?,
        val plugged: Boolean,
        val screenOn: Boolean,
    )

    private var day = Int.MIN_VALUE
    private var prev: Sample? = null
    // The running day's books.
    private var usedUah = 0.0
    private var unpluggedMs = 0L
    private var screenOffMs = 0L
    private var fires = 0
    private var pulses = 0
    private var pulseMs = 0L
    private var pulseUah = 0.0
    private var walkingPulses = 0
    private val headStartsS = ArrayList<Double>()
    private var pulseDayMode = false

    /** True when the early-wake pulse may run on [dayOfYear] (even days; the glue passes today's). */
    fun pulseDay(dayOfYear: Int): Boolean = dayOfYear % 2 == 0

    /**
     * One fuel-gauge sample. [dayOfYear] keys the local day; the first sample of a new day closes
     * the old one and returns its summary line (null otherwise). Charge spent counts only between
     * two consecutive UNPLUGGED samples (charging and gauge jumps upward never count), screen-off
     * time from the earlier sample's screen state.
     */
    fun sample(dayOfYear: Int, s: Sample): String? {
        var closed: String? = null
        if (dayOfYear != day) {
            if (day != Int.MIN_VALUE) closed = summary()
            reset()
            day = dayOfYear
            pulseDayMode = pulseDay(dayOfYear)
            prev = null
        }
        val p = prev
        if (p != null && s.elapsedMs > p.elapsedMs) {
            val dt = s.elapsedMs - p.elapsedMs
            if (!p.plugged && !s.plugged) {
                unpluggedMs += dt
                if (!p.screenOn) screenOffMs += dt
                val pc = p.chargeUah
                val sc = s.chargeUah
                if (pc != null && sc != null && sc < pc) usedUah += (pc - sc).toDouble()
            }
        }
        prev = s
        return closed
    }

    /** The first-movement sensor fired (counted on pulse and non-pulse days alike). */
    fun onSignificantFire() { fires++ }

    /**
     * One finished early-wake pulse: its duration, the mean current while it ran and the idle
     * current around it (measurement A books the difference), whether it confirmed real walking,
     * and - when the old motion signal arrived later - the head start in seconds.
     */
    fun onPulse(durationMs: Long, meanUa: Double, baselineUa: Double, walking: Boolean, headStartS: Double?) {
        pulses++
        pulseMs += durationMs
        pulseUah += ((meanUa - baselineUa).coerceAtLeast(0.0)) * durationMs / 3_600_000.0
        if (walking) walkingPulses++
        headStartS?.let(headStartsS::add)
    }

    /** The day's one privacy-safe line: counts, milliamp-hours and seconds only. */
    fun summary(): String {
        val offH = screenOffMs / 3_600_000.0
        val perOffH = if (offH > 0.05) usedUah / 1000.0 / offH else Double.NaN
        val head = headStartsS.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        return "battery day: mode=${if (pulseDayMode) "pulse" else "off"} fires=$fires pulses=$pulses" +
            " pulseS=${pulseMs / 1000} pulseMah=${"%.2f".format(pulseUah / 1000.0)}" +
            " walking=$walkingPulses headStartS=${head?.let { "%.1f".format(it) } ?: "-"}" +
            " usedMah=${"%.0f".format(usedUah / 1000.0)} screenOffH=${"%.1f".format(offH)}" +
            " unpluggedH=${"%.1f".format(unpluggedMs / 3_600_000.0)}" +
            " perScreenOffH=${if (perOffH.isNaN()) "-" else "%.1f".format(perOffH)}"
    }

    private fun reset() {
        usedUah = 0.0; unpluggedMs = 0L; screenOffMs = 0L
        fires = 0; pulses = 0; pulseMs = 0L; pulseUah = 0.0
        walkingPulses = 0; headStartsS.clear()
    }
}
