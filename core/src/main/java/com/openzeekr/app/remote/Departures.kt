package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.CycleTime
import com.openzeekr.app.net.model.TravelClimateSetting
import com.openzeekr.app.net.model.TravelSeatSetting
import com.openzeekr.app.net.model.TravelWheelSetting
import java.util.Locale

/** What the departure editor shows and changes (Schedule tab). Days: 1 = Monday .. 7 = Sunday. */
data class DepartureDraft(
    val name: String = "Departure",
    val time: String = "08:00:00",
    val days: Set<Int> = emptySet(),
    val climate: Boolean = true,
    val tempC: Double = 22.0,
    val wheelHeat: Boolean = false,
    val seatHeat: Boolean = false,
    val batteryMaintenance: Boolean = false,
)

/**
 * Departure ("booking travel") plans: the rules for showing them and for turning an edit back into a
 * plan the car accepts. The car keeps a fixed number of plan slots. An unused slot reads back as
 * "BookTravel" with no days and the stock placeholder time; the official app hides it, and so do we.
 * A new plan only ever goes into such a free slot, never onto a plan that is in use.
 */
object Departures {
    /** Stock's far-future placeholder for a recurring plan's temporaryTime (the server rejects empty). */
    const val PLACEHOLDER_TIME = "2099:12:12:08:00:00"
    const val EMPTY_SLOT_NAME = "BookTravel"
    const val MIN_TEMP = 16.0
    const val MAX_TEMP = 28.0

    /** Seat-heat locations front left/right, rear left/right; the editor's switch drives the front pair. */
    val HEAT_LOCATIONS = listOf(1, 2, 3, 5)
    val FRONT_SEATS = setOf(1, 2)
    val VENTI_LOCATIONS = listOf(1, 2)

    /**
     * Durations (minutes before departure) and levels for new plans. Taken from a plan the official
     * app made, which stores its defaults even for switched-off items; otherwise 15 minutes, as the
     * official app says ("switches on 15 minutes before departure") and as our climate command sends.
     */
    data class Template(
        val climateMinutes: Int = 15,
        val seatMinutes: Int = 15,
        val seatLevel: Int = 3,
        val wheelMinutes: Int = 15,
        val wheelLevel: Int = 3,
    )

    fun isEmptySlot(p: BookingTravelSetting): Boolean =
        p.sts == 0 && p.cycleTimes.none { it.sts == 1 } &&
            (p.temporaryTime.isBlank() || p.temporaryTime == PLACEHOLDER_TIME) &&
            (p.name.isBlank() || p.name.equals(EMPTY_SLOT_NAME, ignoreCase = true))

    /** The plans the user made; the car's unused slots are left out. */
    fun visible(plans: List<BookingTravelSetting>): List<BookingTravelSetting> = plans.filterNot(::isEmptySlot)

    /** The slot for a new plan: the first unused one (slot 1 when the car reported none at all), or
     *  null when every slot holds a plan. */
    fun freeSlotId(plans: List<BookingTravelSetting>): Int? =
        if (plans.isEmpty()) 1 else plans.firstOrNull(::isEmptySlot)?.btId?.coerceAtLeast(1)

    fun template(plans: List<BookingTravelSetting>): Template {
        val src = visible(plans).firstOrNull { it.csSetting != null } ?: return Template()
        val seat = src.heatSettings.firstOrNull { it.location in FRONT_SEATS }
        val d = Template()
        return Template(
            climateMinutes = src.csSetting?.duration?.takeIf { it > 0 } ?: d.climateMinutes,
            seatMinutes = seat?.duration?.takeIf { it > 0 } ?: d.seatMinutes,
            seatLevel = seat?.level?.takeIf { it in 1..3 } ?: d.seatLevel,
            wheelMinutes = src.whlSetting?.duration?.takeIf { it > 0 } ?: d.wheelMinutes,
            wheelLevel = src.whlSetting?.level?.takeIf { it in 1..3 } ?: d.wheelLevel,
        )
    }

    fun draftOf(p: BookingTravelSetting): DepartureDraft = DepartureDraft(
        name = p.name,
        time = p.cycleTimes.firstOrNull { it.sts == 1 }?.time ?: "08:00:00",
        days = p.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet(),
        climate = p.csSetting?.sts == 1,
        tempC = p.csSetting?.temp?.toDoubleOrNull()?.coerceIn(MIN_TEMP, MAX_TEMP) ?: 22.0,
        wheelHeat = p.whlSetting?.sts == 1,
        seatHeat = p.heatSettings.any { it.location in FRONT_SEATS && it.sts == 1 },
        batteryMaintenance = p.preHeatSts == 1,
    )

    /** A new recurring plan for slot [btId]. Every block the car validates is present (disabled where off). */
    fun newPlan(d: DepartureDraft, btId: Int, t: Template = Template()): BookingTravelSetting = BookingTravelSetting(
        btId = btId.coerceAtLeast(1),
        name = d.name.trim().ifBlank { "Departure" },
        sts = 1,
        displaySts = 1,
        bookingType = 1,  // recurring: the days in cycleTimes
        temporaryTime = PLACEHOLDER_TIME,
        cycleTimes = week(d),
        preHeatSts = if (d.batteryMaintenance) 1 else 0,
        csSetting = TravelClimateSetting(duration = t.climateMinutes, sts = if (d.climate) 1 else 0, temp = temp(d.tempC)),
        ventiSettings = VENTI_LOCATIONS.map { TravelSeatSetting(duration = t.seatMinutes, level = 1, location = it, sts = 0) },
        heatSettings = HEAT_LOCATIONS.map {
            TravelSeatSetting(duration = t.seatMinutes, level = t.seatLevel, location = it, sts = if (d.seatHeat && it in FRONT_SEATS) 1 else 0)
        },
        whlSetting = TravelWheelSetting(duration = t.wheelMinutes, level = t.wheelLevel, sts = if (d.wheelHeat) 1 else 0),
    )

    /** [p] changed as the editor says. What the editor does not show (fragrance, ventilation, rear seats,
     *  levels, durations, the slot) is kept exactly as the car reported it. */
    fun applied(p: BookingTravelSetting, d: DepartureDraft, t: Template = Template()): BookingTravelSetting {
        val base = sanitizedForWrite(p, t)
        val seatChanged = d.seatHeat != draftOf(p).seatHeat
        return base.copy(
            name = d.name.trim().ifBlank { p.name.ifBlank { "Departure" } },
            cycleTimes = week(d),
            preHeatSts = if (d.batteryMaintenance) 1 else 0,
            csSetting = base.csSetting!!.copy(sts = if (d.climate) 1 else 0, temp = temp(d.tempC)),
            heatSettings = if (!seatChanged) base.heatSettings
            else base.heatSettings.map { if (it.location in FRONT_SEATS) it.copy(sts = if (d.seatHeat) 1 else 0) else it },
            whlSetting = base.whlSetting!!.copy(sts = if (d.wheelHeat) 1 else 0),
        )
    }

    /** A plan read back from the car, completed so the write-side validation accepts it again: only
     *  MISSING blocks are filled in; values the car reported are kept. */
    fun sanitizedForWrite(p: BookingTravelSetting, t: Template = Template()): BookingTravelSetting = p.copy(
        btId = p.btId.coerceAtLeast(1),
        temporaryTime = p.temporaryTime.ifBlank { PLACEHOLDER_TIME },
        csSetting = p.csSetting ?: TravelClimateSetting(duration = t.climateMinutes, sts = 0, temp = temp(22.0)),
        ventiSettings = p.ventiSettings.ifEmpty { VENTI_LOCATIONS.map { TravelSeatSetting(t.seatMinutes, 1, it, 0) } },
        heatSettings = p.heatSettings.ifEmpty { HEAT_LOCATIONS.map { TravelSeatSetting(t.seatMinutes, t.seatLevel, it, 0) } },
        whlSetting = p.whlSetting ?: TravelWheelSetting(duration = t.wheelMinutes, level = t.wheelLevel, sts = 0),
    )

    /** True when the car's read-back holds [want] (same name, state, days and time): the write landed. */
    fun landed(plans: List<BookingTravelSetting>, want: BookingTravelSetting): Boolean = plans.any {
        it.name == want.name && it.sts == want.sts && activeDays(it) == activeDays(want) &&
            it.cycleTimes.firstOrNull { c -> c.sts == 1 }?.time == want.cycleTimes.firstOrNull { c -> c.sts == 1 }?.time
    }

    /** The temperature as the car expects it: one decimal with a point, whatever the phone's language. */
    fun temp(c: Double): String = String.format(Locale.ROOT, "%.1f", c.coerceIn(MIN_TEMP, MAX_TEMP))

    private fun activeDays(p: BookingTravelSetting) = p.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet()

    private fun week(d: DepartureDraft) = (1..7).map { CycleTime(day = it, sts = if (it in d.days) 1 else 0, time = d.time) }
}
