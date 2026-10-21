package com.openzeekr.app.ui

import android.content.Context
import com.openzeekr.app.net.model.ServiceParameter

/**
 * Remembered climate settings (0.3.23, user request 21/10: the stock app's Klimaat tab remembers the
 * run times). The car doesn't report the A/C setpoint or any run time, so the app keeps the last
 * values the user chose and sends them with every climate command. Stored in a tiny prefs file;
 * reads/writes are guarded so a locked/blocked store never crashes the UI.
 */
private const val CLIMATE_PREFS = "climate_prefs"
private const val KEY_TARGET_TEMP = "target_temp_c"
internal const val KEY_AC_DURATION = "ac_duration_min"
internal const val KEY_SEAT_DURATION = "seat_duration_min"
internal const val KEY_STEER_DURATION = "steer_duration_min"

/** The duration choices of the stock app's Tijdsduur rows (minutes). */
internal val DURATION_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)

/** The A/C temperature range the car accepts (Lo..Hi, 0.5 °C steps). */
internal const val AC_TEMP_MIN = 17.0
internal const val AC_TEMP_MAX = 32.0

internal fun readTargetTemp(ctx: Context): Double = runCatching {
    ctx.getSharedPreferences(CLIMATE_PREFS, Context.MODE_PRIVATE)
        .getFloat(KEY_TARGET_TEMP, 22f).toDouble()
}.getOrDefault(22.0)

internal fun writeTargetTemp(ctx: Context, t: Double) {
    runCatching {
        ctx.getSharedPreferences(CLIMATE_PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_TARGET_TEMP, t.toFloat()).apply()
    }
}

internal fun readDurationMin(ctx: Context, key: String, default: Int = 15): Int = runCatching {
    ctx.getSharedPreferences(CLIMATE_PREFS, Context.MODE_PRIVATE).getInt(key, default)
}.getOrDefault(default)

internal fun writeDurationMin(ctx: Context, key: String, minutes: Int) {
    runCatching {
        ctx.getSharedPreferences(CLIMATE_PREFS, Context.MODE_PRIVATE).edit().putInt(key, minutes).apply()
    }
}

/** WIRE value MUST be dot-decimal ("21.5"): the car rejects a locale comma ("21,5"). */
internal fun formatTempUs(t: Double): String = String.format(java.util.Locale.US, "%.1f", t)

/** The remembered setpoint + A/C run time as overrides for [com.openzeekr.app.remote.Command.AC_ON]
 *  (send() dedups by key, so these replace the catalog's 22.0/15 defaults). */
internal fun savedAcExtras(ctx: Context): List<ServiceParameter> = listOf(
    ServiceParameter("AC.temp", formatTempUs(readTargetTemp(ctx))),
    ServiceParameter("AC.duration", readDurationMin(ctx, KEY_AC_DURATION).toString()),
)
