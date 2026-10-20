package com.openzeekr.app.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import java.util.Calendar

/**
 * Measurement version (battery plan, owner approval 20/10): the Android glue around [BatteryLedger].
 * Reads the phone's fuel gauge (remaining charge in µAh, momentary current in µA, charger, screen)
 * ONLY when [sample] is called - and it is called solely at moments the CPU is already awake, so the
 * measurement itself costs next to nothing. Writes the ledger's one privacy-safe line per day into
 * the BLE-gated log (counts, mAh and seconds only; the field data stays in the private journal).
 *
 * Known limitation (first field round): the running day's books live in memory; a process restart
 * starts the day's books afresh, and the summary says so through lower unplugged hours.
 */
class BatteryMeter(context: Context) {
    private val appCtx = context.applicationContext
    private val battery = appCtx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val power = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val ledger = BatteryLedger()
    @Volatile private var lastSampleElapsedMs = 0L

    /** Momentary current in µA (magnitude; vendors disagree on the discharge sign), null without a meter. */
    fun currentUa(): Double? = battery
        ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        ?.takeIf { it != Long.MIN_VALUE && it != 0L }
        ?.let { kotlin.math.abs(it.toDouble()) }

    /** True when today is a pulse day (even day of the year): the early-wake pulse may run. */
    fun pulseDayNow(): Boolean = ledger.pulseDay(Calendar.getInstance().get(Calendar.DAY_OF_YEAR))

    /**
     * One fuel-gauge sample; [reason] only bounds the rate (at most one sample per 60 s per reason-
     * independent clock, except day boundaries handled inside the ledger). Call it from moments the
     * CPU is already awake: service (re)start, screen edges the service sees, each keep-alive round.
     */
    fun sample(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleElapsedMs < MIN_SAMPLE_GAP_MS) return
        lastSampleElapsedMs = now
        val charge = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 && it != Long.MIN_VALUE }
        val plugged = runCatching {
            (appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }.getOrDefault(false)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val closed = ledger.sample(day,
            BatteryLedger.Sample(now, charge, plugged, power?.isInteractive == true))
        closed?.let { Logx.d("prox", it) }
    }

    fun onSignificantFire() = ledger.onSignificantFire()

    /** See [BatteryLedger.onPulse]; the tracker reports its pulse here when it ends. */
    fun onPulse(durationMs: Long, meanUa: Double, baselineUa: Double, walking: Boolean, headStartS: Double?) =
        ledger.onPulse(durationMs, meanUa, baselineUa, walking, headStartS)

    /** The running day's line (diagnostics; the day's own line logs itself at the day boundary). */
    fun summaryNow(): String = ledger.summary()

    private companion object {
        const val MIN_SAMPLE_GAP_MS = 60_000L
    }
}
