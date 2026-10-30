package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.VehicleStatusBean
import com.openzeekr.app.util.Logx
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 0.4.12 (field 30/10): a departure plan with seat heating did not heat, and nothing told whether the car ran the plan at all.
 * Two minutes after each enabled plan's time one vehicle status is read and its seat-heating levels and climate flag go to the
 * private journal as numbers (never a name or a time). [update] takes the plans as the car reports them (every list read and
 * every write); the proximity service's keep-alive round calls [tick], which also refreshes the plans once a day.
 */
object PlanRunWatch {
    /** Enabled plans: slot id with the active entries (day 1 = Monday .. 7 = Sunday, "HH:mm:ss"). */
    @Volatile private var entries: List<Pair<Int, List<Pair<Int, String>>>> = emptyList()
    @Volatile private var updatedAtMs = 0L
    @Volatile private var lastCheckedMs = 0L
    /** The status is read this long after the plan's time (the car's preconditioning runs up to the time). */
    const val CHECK_AFTER_MS = 2 * 60_000L
    /** A plan moment older than this is not checked any more (the service may have slept through the window). */
    const val WINDOW_MS = 30 * 60_000L
    const val REFRESH_MS = 24 * 60 * 60_000L

    fun update(plans: List<BookingTravelSetting>, nowMs: Long = System.currentTimeMillis()) {
        entries = plans.filter { it.sts == 1 && !Departures.isFree(it) }
            .map { p -> p.btId to p.cycleTimes.filter { it.sts == 1 && it.day in 1..7 }.map { it.day to it.time } }
            .filter { it.second.isNotEmpty() }
        updatedAtMs = nowMs
    }

    /** The most recent plan moment between [CHECK_AFTER_MS] and [WINDOW_MS] ago: slot id and the moment (wall ms); null: none. */
    fun due(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Long>? {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var best: Pair<Int, Long>? = null
        for ((slot, times) in entries) for ((day, time) in times) {
            val t = runCatching { LocalTime.parse(time.take(8)) }.getOrNull() ?: continue
            for (back in 0L..1L) {
                val date = now.toLocalDate().minusDays(back)
                if (date.dayOfWeek.value != day) continue
                val at = LocalDateTime.of(date, t).atZone(zone).toInstant().toEpochMilli()
                val age = nowMs - at
                if (age in CHECK_AFTER_MS..WINDOW_MS && (best == null || at > best.second)) best = slot to at
            }
        }
        return best
    }

    /** One status read per plan moment. [refresh] re-reads the plan list (once a day); [status] reads the vehicle status. */
    suspend fun tick(nowMs: Long, refresh: suspend () -> Unit, status: suspend () -> VehicleStatusBean?) {
        if (nowMs - updatedAtMs > REFRESH_MS) { updatedAtMs = nowMs; runCatching { refresh() } }
        val d = due(nowMs) ?: return
        if (d.second == lastCheckedMs) return
        lastCheckedMs = d.second
        val st = runCatching { status() }.getOrNull()
        Logx.d("sched", line(d.first, st))
    }

    /** "plan run check slot=5 status=read seats=3/0/0/0 climate=on": seat levels driver/passenger/rear left/rear right. */
    fun line(slot: Int, st: VehicleStatusBean?): String {
        if (st == null) return "plan run check slot=${slot.coerceIn(0, 99)} status=unavailable"
        fun lvl(v: String?) = (v?.toIntOrNull() ?: 0).coerceIn(0, 9)
        val c = st.additionalVehicleStatus?.climateStatus
        val climate = when (c?.preClimateActive) { true -> "on"; false -> "off"; null -> "?" }
        return "plan run check slot=${slot.coerceIn(0, 99)} status=read seats=${lvl(c?.drvHeatSts)}/${lvl(c?.passHeatingSts)}/" +
            "${lvl(c?.rlHeatingSts)}/${lvl(c?.rrHeatingSts)} climate=$climate"
    }
}
