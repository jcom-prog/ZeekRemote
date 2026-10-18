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
     * with whether its days, time, on/off state and (0.3.17) climate temperature and seat heating match.
     * Only "same"/"other", never the values.
     */
    fun found(plans: List<BookingTravelSetting>?, want: BookingTravelSetting?): String {
        if (plans == null || want == null) return "-"
        val p = plans.firstOrNull { it.name == want.name && !Departures.isEmptySlot(it) } ?: return "none"
        fun same(b: Boolean) = if (b) "same" else "other"
        val days = p.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet() == want.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet()
        val time = p.cycleTimes.firstOrNull { it.sts == 1 }?.time == want.cycleTimes.firstOrNull { it.sts == 1 }?.time
        // Field 18/10: the plan's temperature read back as sent while the car set another one when it ran;
        // compared as numbers ("28" and "28.0" are the same).
        val temp = p.csSetting?.temp?.toDoubleOrNull() == want.csSetting?.temp?.toDoubleOrNull()
        // Each seat sent reads back in the same state, and at the same level when on.
        val seats = want.heatSettings.filter { it.location in Departures.HEAT_LOCATIONS }.all { w ->
            p.heatSettings.firstOrNull { it.location == w.location }?.let { it.sts == w.sts && (w.sts != 1 || it.level == w.level) } == true
        }
        return "slot${p.btId.coerceIn(0, 99)} name=same days=${same(days)} time=${same(time)} on=${same(p.sts == want.sts)} " +
            "temp=${same(temp)} seats=${same(seats)}"
    }

    /**
     * 0.3.14: how a plan is built, as flags and counts only (never a name, time, day, temperature or id):
     * state, shown, type, battery, the placeholder time, the number of day entries and active days,
     * whether the name is the car's empty-slot name, and per block present / missing / zero duration,
     * e.g. "s1.d1.b1.p0.tp.c7.a2.nu.cs+.v2+.h4+.w+.f+". Compares a plan the car accepted with one we send.
     */
    fun shape(p: BookingTravelSetting): String {
        fun flag(v: Int) = v.coerceIn(0, 9).toString()
        fun block(duration: Int?) = when { duration == null -> "-"; duration > 0 -> "+"; else -> "0" }
        fun seats(l: List<com.openzeekr.app.net.model.TravelSeatSetting>) =
            l.size.coerceIn(0, 9).toString() + if (l.isEmpty()) "" else if (l.all { it.duration > 0 }) "+" else "0"
        val time = when { p.temporaryTime.isBlank() -> "0"; p.temporaryTime == Departures.PLACEHOLDER_TIME -> "p"; else -> "x" }
        val name = if (p.name.isBlank() || p.name.equals(Departures.EMPTY_SLOT_NAME, ignoreCase = true)) "e" else "u"
        return "s${flag(p.sts)}.d${flag(p.displaySts)}.b${flag(p.bookingType)}.p${flag(p.preHeatSts)}.t$time" +
            ".c${p.cycleTimes.size.coerceIn(0, 9)}.a${p.cycleTimes.count { it.sts == 1 }.coerceIn(0, 7)}.n$name" +
            ".cs${block(p.csSetting?.duration)}.v${seats(p.ventiSettings)}.h${seats(p.heatSettings)}" +
            ".w${block(p.whlSetting?.duration)}.f${block(p.fragSetting?.duration)}"
    }

    /** The shape line: [at] is "load" or the write's kind; [sent] the plan written (null on a load). */
    fun shapeLine(at: String, sent: BookingTravelSetting?, plans: List<BookingTravelSetting>?): String =
        "schedule shape at=$at sent=${sent?.let { "${it.btId.coerceIn(0, 99)}:${shape(it)}" } ?: "-"} slots=" +
            (if (plans.isNullOrEmpty()) "none" else plans.take(10).joinToString(",") { "${it.btId.coerceIn(0, 99)}:${shape(it)}" })

    /**
     * 0.3.17: a plan's seat heating, entry by entry as seat code, level and on (+) / off (-), e.g. "1=3+/2=3+/3=2-/5=2-".
     * Seat codes and levels only. Shows which code the car uses for which seat (field test 0.3.17). 0.3.18: the level as
     * the car stores it, up to 999 (field 18/10: an off seat is stored with a level outside I-III).
     */
    fun seats(p: BookingTravelSetting): String =
        p.heatSettings.take(9).joinToString("/") { "${it.location.coerceIn(0, 99)}=${it.level.coerceIn(0, 999)}${if (it.sts == 1) "+" else "-"}" }
            .ifEmpty { "none" }

    /**
     * 0.3.18: one read of the departure list - how many plans, how many needed empty fields ("filled") and how many
     * could not be read at all - or why it failed, as a category. Field 18/10: a read that failed left no trace.
     */
    fun listLine(read: TravelPlanList.Read?, failure: Throwable?): String =
        if (read != null) "schedule list read=ok plans=${read.plans.size.coerceIn(0, 99)} filled=${read.filled.coerceIn(0, 99)} " +
            "unreadable=${read.unreadable.coerceIn(0, 99)}"
        else "schedule list read=failed reason=" + when (failure) {
            is retrofit2.HttpException -> "http"
            is java.io.IOException -> "network"
            is kotlinx.serialization.SerializationException -> "parse"
            is IllegalStateException -> "refused"
            else -> "other"
        }

    /** The seats line, next to the shape line: [at] is "load" or the write's kind; [sent] the plan written. */
    fun seatsLine(at: String, sent: BookingTravelSetting?, plans: List<BookingTravelSetting>?): String =
        "schedule seats at=$at sent=${sent?.let { "${it.btId.coerceIn(0, 99)}:${seats(it)}" } ?: "-"} slots=" +
            (if (plans.isNullOrEmpty()) "none" else plans.take(10).joinToString(",") { "${it.btId.coerceIn(0, 99)}:${seats(it)}" })

    /** The charging plan's read-back: its command and who wrote it last, as categories. */
    fun charge(command: String?, dataSource: String?): String =
        "${when (command) { "start" -> "start"; "stop" -> "stop"; else -> "other" }}/" +
            if (dataSource.equals("APP", ignoreCase = true)) "app" else "other"
}
