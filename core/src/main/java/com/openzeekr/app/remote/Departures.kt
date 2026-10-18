package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.CycleTime
import com.openzeekr.app.net.model.TravelClimateSetting
import com.openzeekr.app.net.model.TravelFragranceSetting
import com.openzeekr.app.net.model.TravelSeatSetting
import com.openzeekr.app.net.model.TravelWheelSetting
import java.util.Locale

/**
 * What the departure editor shows and changes (Schedule tab). Days: 1 = Monday .. 7 = Sunday. [seats]: the seat
 * heating per seat location ([Departures.HEAT_LOCATIONS]) that is on, with its level 1-3 (I-III); a seat that is
 * not listed is off.
 */
data class DepartureDraft(
    val name: String = "Departure",
    val time: String = "08:00:00",
    val days: Set<Int> = emptySet(),
    val climate: Boolean = true,
    val tempC: Double = 22.0,
    val wheelHeat: Boolean = false,
    val seats: Map<Int, Int> = emptyMap(),
    val batteryMaintenance: Boolean = false,
) {
    /** The level of the seat at [location]: 0 = off, 1-3 = I-III. */
    fun seatLevel(location: Int): Int = seats[location]?.coerceIn(0, 3) ?: 0

    val seatHeat: Boolean get() = seats.values.any { it > 0 }
}

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
    /** The car's type of a plan that repeats on the days in cycleTimes. Field 18/10: the official app's plan reads
     *  back 0 and an unused slot 1; every Create that sent 1 (0.3.10-0.3.14) never reached the car. */
    const val BOOKING_RECURRING = 0
    /** The type an unused slot reads back with (field 18/10: every free slot). */
    const val EMPTY_SLOT_TYPE = 1
    const val MIN_TEMP = 16.0
    const val MAX_TEMP = 28.0

    /** Seat-heat locations in a plan as the official app writes them (upstream notes from its code; the field test of
     *  0.3.17 checks them): driver (front left), passenger (front right), rear left, rear right. The editor sets each
     *  one off or to level I-III, as the official app does; other entries the car lists stay off. */
    const val SEAT_DRIVER = 1
    const val SEAT_PASSENGER = 2
    const val SEAT_REAR_LEFT = 3
    const val SEAT_REAR_RIGHT = 5
    val HEAT_LOCATIONS = listOf(SEAT_DRIVER, SEAT_PASSENGER, SEAT_REAR_LEFT, SEAT_REAR_RIGHT)
    val FRONT_SEATS = setOf(SEAT_DRIVER, SEAT_PASSENGER)
    /** Switching seat heating on in the editor: driver and passenger at III, the rear seats off. */
    val SEATS_ON = mapOf(SEAT_DRIVER to 3, SEAT_PASSENGER to 3)
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
        /** The fragrance block of a plan the car holds, switched off; none when its plans have none (field 18/10). */
        val fragrance: TravelFragranceSetting? = null,
        /** 0.3.18: the level the car stores with a seat that is off ([seatOffLevel]); null when the car shows none. */
        val seatOffLevel: Int? = null,
    )

    /**
     * 0.3.18: how the car itself stores a seat that is off. Field 18/10: in its free slots and in the official app's
     * plans an off seat has a level outside I-III, and the official app shows a seat by its level alone - a seat
     * ZeekRemote had switched off but that kept I-III showed there as on. The most common level of the off entries
     * the car lists, or null when it lists none.
     */
    fun seatOffLevel(plans: List<BookingTravelSetting>): Int? =
        plans.flatMap { it.heatSettings }.filter { it.sts != 1 && it.level !in 1..3 }
            .groupingBy { it.level }.eachCount().maxByOrNull { it.value }?.key

    /** The level for [e] switched off: its own when the car already stores it off its way, else the car's off level,
     *  else (the car shows none) as before 0.3.18. */
    private fun offLevel(e: TravelSeatSetting, t: Template): Int =
        e.level.takeIf { it > 0 && it !in 1..3 } ?: t.seatOffLevel ?: e.level.takeIf { it in 1..3 } ?: t.seatLevel

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
        val off = seatOffLevel(plans)
        val src = visible(plans).firstOrNull { it.csSetting != null } ?: return Template(seatOffLevel = off)
        val seat = src.heatSettings.firstOrNull { it.location in FRONT_SEATS }
        val d = Template()
        return Template(
            climateMinutes = src.csSetting?.duration?.takeIf { it > 0 } ?: d.climateMinutes,
            seatMinutes = seat?.duration?.takeIf { it > 0 } ?: d.seatMinutes,
            seatLevel = seat?.level?.takeIf { it in 1..3 } ?: d.seatLevel,
            wheelMinutes = src.whlSetting?.duration?.takeIf { it > 0 } ?: d.wheelMinutes,
            wheelLevel = src.whlSetting?.level?.takeIf { it in 1..3 } ?: d.wheelLevel,
            fragrance = src.fragSetting?.copy(sts = 0),
            seatOffLevel = off,
        )
    }

    fun draftOf(p: BookingTravelSetting): DepartureDraft = DepartureDraft(
        name = p.name,
        time = p.cycleTimes.firstOrNull { it.sts == 1 }?.time ?: "08:00:00",
        days = p.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet(),
        climate = p.csSetting?.sts == 1,
        tempC = p.csSetting?.temp?.toDoubleOrNull()?.coerceIn(MIN_TEMP, MAX_TEMP) ?: 22.0,
        wheelHeat = p.whlSetting?.sts == 1,
        seats = HEAT_LOCATIONS.mapNotNull { loc ->
            p.heatSettings.firstOrNull { it.location == loc && it.sts == 1 }?.let { loc to it.level.coerceIn(1, 3) }
        }.toMap(),
        batteryMaintenance = p.preHeatSts == 1,
    )

    /** The seats the editor can set for [p]: those of [HEAT_LOCATIONS] the car lists in it (all of them for a plan
     *  without seat entries, which gets them when written). An entry is never added to what the car reported. */
    fun seatsOf(p: BookingTravelSetting?): List<Int> =
        if (p == null || p.heatSettings.isEmpty()) HEAT_LOCATIONS
        else HEAT_LOCATIONS.filter { loc -> p.heatSettings.any { it.location == loc } }

    /** [seats] with the seat at [location] set to [level] (0 = off); only the seats that are on stay listed. */
    fun withSeat(seats: Map<Int, Int>, location: Int, level: Int): Map<Int, Int> =
        (seats + (location to level.coerceIn(0, 3))).filterValues { it > 0 }

    /** Seat heating switched on in the editor for the [available] seats: [SEATS_ON], else the first seat at III. */
    fun seatsOn(available: List<Int>): Map<Int, Int> =
        SEATS_ON.filterKeys { it in available }.ifEmpty { available.take(1).associateWith { 3 } }

    /** A new recurring plan for slot [btId]. Every block the car validates is present (disabled where off). */
    fun newPlan(d: DepartureDraft, btId: Int, t: Template = Template()): BookingTravelSetting = BookingTravelSetting(
        btId = btId.coerceAtLeast(1),
        name = d.name.trim().ifBlank { "Departure" },
        sts = 1,
        displaySts = 1,
        bookingType = BOOKING_RECURRING,
        temporaryTime = PLACEHOLDER_TIME,
        cycleTimes = week(d),
        preHeatSts = if (d.batteryMaintenance) 1 else 0,
        csSetting = TravelClimateSetting(duration = t.climateMinutes, sts = if (d.climate) 1 else 0, temp = temp(d.tempC)),
        ventiSettings = VENTI_LOCATIONS.map { TravelSeatSetting(duration = t.seatMinutes, level = 1, location = it, sts = 0) },
        heatSettings = HEAT_LOCATIONS.map {
            val level = d.seatLevel(it)
            TravelSeatSetting(duration = t.seatMinutes, level = if (level > 0) level else t.seatOffLevel ?: t.seatLevel,
                location = it, sts = if (level > 0) 1 else 0)
        },
        whlSetting = TravelWheelSetting(duration = t.wheelMinutes, level = t.wheelLevel, sts = if (d.wheelHeat) 1 else 0),
        fragSetting = t.fragrance,
    )

    /**
     * 0.3.14: a new plan written over the free [slot] as the car reported it. Field 16/10 and 18/10: a plan
     * built from scratch never reached the car, as "start" nor as "edit", while an edit that sent a plan back
     * as the car had reported it was applied within seconds. So every block the car sent for the slot is kept;
     * a missing block, or one with a zero duration or level, gets the official app's defaults; the plan is
     * switched on, shown and recurring, with the editor's name, days, time and settings. Ventilation and fragrance
     * stay off (the editor does not show them). 0.3.15 (field 18/10, 0.3.14): recurring is [BOOKING_RECURRING] as in
     * the official app's plan, and a fragrance block only when the car has one. 0.3.17: each seat of
     * [HEAT_LOCATIONS] as the editor set it; any other seat entry the slot lists stays off. 0.3.18: what stays off keeps
     * the level the car stores it with (see [seatOffLevel]); only a zero level gets a default.
     */
    fun fromSlot(slot: BookingTravelSetting, d: DepartureDraft, t: Template = Template()): BookingTravelSetting {
        val base = sanitizedForWrite(slot, t)
        val cs = base.csSetting!!
        val whl = base.whlSetting!!
        return base.copy(
            name = d.name.trim().ifBlank { "Departure" },
            sts = 1,
            displaySts = 1,
            bookingType = BOOKING_RECURRING,
            temporaryTime = PLACEHOLDER_TIME,
            cycleTimes = week(d),
            preHeatSts = if (d.batteryMaintenance) 1 else 0,
            csSetting = cs.copy(duration = cs.duration.takeIf { it > 0 } ?: t.climateMinutes,
                sts = if (d.climate) 1 else 0, temp = temp(d.tempC)),
            ventiSettings = base.ventiSettings.map {
                it.copy(duration = it.duration.takeIf { m -> m > 0 } ?: t.seatMinutes, level = it.level.takeIf { l -> l > 0 } ?: 1, sts = 0)
            },
            heatSettings = base.heatSettings.map {
                val level = if (it.location in HEAT_LOCATIONS) d.seatLevel(it.location) else 0
                it.copy(duration = it.duration.takeIf { m -> m > 0 } ?: t.seatMinutes,
                    level = if (level > 0) level else offLevel(it, t), sts = if (level > 0) 1 else 0)
            },
            whlSetting = whl.copy(duration = whl.duration.takeIf { it > 0 } ?: t.wheelMinutes,
                level = if (d.wheelHeat) whl.level?.takeIf { it in 1..3 } ?: t.wheelLevel else whl.level?.takeIf { it > 0 } ?: t.wheelLevel,
                sts = if (d.wheelHeat) 1 else 0),
            fragSetting = (base.fragSetting ?: t.fragrance)?.copy(sts = 0),
        )
    }

    /** [p] changed as the editor says. What the editor does not show (fragrance, ventilation, durations, the slot)
     *  and every seat the editor left on as it was are kept exactly as the car reported them. 0.3.18: a seat that is
     *  off is written as the car stores it (see [seatOffLevel]), also one that was already off but kept I-III. */
    fun applied(p: BookingTravelSetting, d: DepartureDraft, t: Template = Template()): BookingTravelSetting {
        val base = sanitizedForWrite(p, t)
        val before = draftOf(p)
        return base.copy(
            name = d.name.trim().ifBlank { p.name.ifBlank { "Departure" } },
            cycleTimes = week(d),
            preHeatSts = if (d.batteryMaintenance) 1 else 0,
            csSetting = base.csSetting!!.copy(sts = if (d.climate) 1 else 0, temp = temp(d.tempC)),
            heatSettings = base.heatSettings.map {
                val level = d.seatLevel(it.location)
                when {
                    it.location !in HEAT_LOCATIONS -> it
                    level == 0 -> it.copy(sts = 0, level = offLevel(it, t))
                    level == before.seatLevel(it.location) -> it
                    else -> it.copy(sts = 1, level = level)
                }
            },
            whlSetting = base.whlSetting!!.let { w ->
                w.copy(sts = if (d.wheelHeat) 1 else 0, level = if (d.wheelHeat) w.level?.takeIf { it in 1..3 } ?: t.wheelLevel else w.level)
            },
        )
    }

    /** A plan read back from the car, completed so the write-side validation accepts it again: only
     *  MISSING blocks are filled in; values the car reported are kept. */
    fun sanitizedForWrite(p: BookingTravelSetting, t: Template = Template()): BookingTravelSetting = p.copy(
        btId = p.btId.coerceAtLeast(1),
        temporaryTime = p.temporaryTime.ifBlank { PLACEHOLDER_TIME },
        csSetting = p.csSetting ?: TravelClimateSetting(duration = t.climateMinutes, sts = 0, temp = temp(22.0)),
        ventiSettings = p.ventiSettings.ifEmpty { VENTI_LOCATIONS.map { TravelSeatSetting(t.seatMinutes, 1, it, 0) } },
        heatSettings = p.heatSettings.ifEmpty { HEAT_LOCATIONS.map { TravelSeatSetting(t.seatMinutes, t.seatOffLevel ?: t.seatLevel, it, 0) } },
        whlSetting = p.whlSetting ?: TravelWheelSetting(duration = t.wheelMinutes, level = t.wheelLevel, sts = 0),
    )

    /**
     * 0.3.16: the plan in [p]'s slot written back as an unused slot, the way the car reports its free slots (field
     * 18/10): off, hidden, the empty-slot name, no active day, the placeholder time and every item switched off; the
     * blocks stay as reported. [bookingType] is the free slots' type, or [BOOKING_RECURRING] for the second try (an
     * edit with that type is known to land, see Repositories.deleteDeparture). 0.3.18: the seats as the car stores
     * them off (see [seatOffLevel]).
     */
    fun emptied(p: BookingTravelSetting, bookingType: Int = EMPTY_SLOT_TYPE, t: Template = Template()): BookingTravelSetting {
        val base = sanitizedForWrite(p, t)
        return base.copy(
            name = EMPTY_SLOT_NAME,
            sts = 0,
            displaySts = 0,
            bookingType = bookingType,
            temporaryTime = PLACEHOLDER_TIME,
            cycleTimes = base.cycleTimes.map { it.copy(sts = 0) },
            preHeatSts = 0,
            csSetting = base.csSetting!!.copy(sts = 0),
            ventiSettings = base.ventiSettings.map { it.copy(sts = 0) },
            heatSettings = base.heatSettings.map { it.copy(sts = 0, level = offLevel(it, t)) },
            whlSetting = base.whlSetting!!.copy(sts = 0),
            fragSetting = base.fragSetting?.copy(sts = 0),
        )
    }

    /** True when the read-back no longer holds a plan in slot [btId] (the slot reads back empty or is
     *  missing): the delete landed. */
    fun gone(plans: List<BookingTravelSetting>, btId: Int): Boolean =
        plans.none { it.btId == btId && !isEmptySlot(it) }

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
