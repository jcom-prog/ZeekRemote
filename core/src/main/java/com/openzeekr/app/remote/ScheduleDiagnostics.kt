package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting

/**
 * One privacy-safe diagnostic line per schedule write (0.3.8, user decision 15/10), so "Deel diagnose"
 * shows what the cloud answered and what the car's read-back holds. Only categories and slot numbers:
 * never a plan name, a time, a temperature, a day or an id. The journal accepts the line only in
 * exactly this shape (see ProximityDiagnosticJournal).
 */
internal object ScheduleDiagnostics {

    /** The line for one write; [plans] is the last read-back (null when the write never got that far). */
    fun line(
        kind: ScheduleWrites.Kind,
        slot: Int?,
        sent: String,
        session: String,
        result: String,
        polls: Int,
        readback: String,
        found: String,
    ): String = "schedule write kind=${kind.name.lowercase()} slot=${slot?.takeIf { it in 0..99 } ?: "-"} " +
        "sent=$sent session=$session result=$result polls=${polls.coerceIn(0, 999)} readback=$readback found=$found"

    /** What the cloud did with the request: accepted, or refused with its code (letters/digits only). */
    fun refused(code: String?): String = "refused code=" + (code.orEmpty().filter { it.isLetterOrDigit() || it in "_.-" }
        .take(16).ifEmpty { "none" })

    /** Per slot: number, on/off, used/empty and how many days are active, e.g. "1:off/used/4,2:off/empty/0". */
    fun slots(plans: List<BookingTravelSetting>?): String {
        if (plans.isNullOrEmpty()) return "none"
        return plans.take(10).joinToString(",") { p ->
            val days = p.cycleTimes.count { it.sts == 1 }.coerceIn(0, 7)
            "${p.btId.coerceIn(0, 99)}:${if (p.sts == 1) "on" else "off"}/${if (Departures.isEmptySlot(p)) "empty" else "used"}/$days"
        }
    }

    /**
     * Where the plan that was sent ([want]) shows up in the read-back: the slot holding the same name,
     * with whether its days, time and on/off state match. Only "same"/"other", never the values.
     */
    fun found(plans: List<BookingTravelSetting>?, want: BookingTravelSetting?): String {
        if (plans == null || want == null) return "-"
        val p = plans.firstOrNull { it.name == want.name && !Departures.isEmptySlot(it) } ?: return "none"
        fun same(b: Boolean) = if (b) "same" else "other"
        val days = p.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet() == want.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet()
        val time = p.cycleTimes.firstOrNull { it.sts == 1 }?.time == want.cycleTimes.firstOrNull { it.sts == 1 }?.time
        return "slot${p.btId.coerceIn(0, 99)} name=same days=${same(days)} time=${same(time)} on=${same(p.sts == want.sts)}"
    }

    /** The charging plan's read-back: its command and who wrote it last, as categories. */
    fun charge(command: String?, dataSource: String?): String =
        "${when (command) { "start" -> "start"; "stop" -> "stop"; else -> "other" }}/" +
            if (dataSource.equals("APP", ignoreCase = true)) "app" else "other"
}
