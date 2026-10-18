package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.CycleTime
import com.openzeekr.app.net.model.TravelClimateSetting
import com.openzeekr.app.net.model.TravelFragranceSetting
import com.openzeekr.app.net.model.TravelSeatSetting
import com.openzeekr.app.net.model.TravelWheelSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DeparturesTest {

    private fun emptySlot(id: Int) = BookingTravelSetting(
        btId = id, name = "BookTravel", sts = 0, displaySts = 0, bookingType = 1,
        temporaryTime = Departures.PLACEHOLDER_TIME, cycleTimes = emptyList(), preHeatSts = 0,
    )

    /** A plan as the official app makes it: its defaults stored even for switched-off items; recurring is type 0
     *  and there is no fragrance block (field 18/10). */
    private fun ownPlan(id: Int = 1, name: String = "Plan A", sts: Int = 1) = BookingTravelSetting(
        btId = id, name = name, sts = sts, displaySts = 1, bookingType = Departures.BOOKING_RECURRING,
        temporaryTime = Departures.PLACEHOLDER_TIME,
        cycleTimes = (1..7).map { CycleTime(it, if (it == 2 || it == 5) 1 else 0, "06:40:00") },
        preHeatSts = 0,
        csSetting = TravelClimateSetting(duration = 15, sts = 1, temp = "23.0"),
        ventiSettings = listOf(TravelSeatSetting(15, 2, 1, 0), TravelSeatSetting(15, 2, 2, 0)),
        heatSettings = listOf(TravelSeatSetting(15, 2, 1, 0), TravelSeatSetting(15, 2, 2, 0), TravelSeatSetting(15, 2, 3, 1), TravelSeatSetting(15, 2, 5, 0)),
        whlSetting = TravelWheelSetting(duration = 15, level = 2, sts = 0),
    )

    @Test fun theCarsUnusedSlotsAreHiddenAndNeverOverwritten() {
        val plans = listOf(ownPlan(1), emptySlot(2), emptySlot(3))
        assertEquals(listOf(1), Departures.visible(plans).map { it.btId })
        assertEquals(2, Departures.freeSlotId(plans))
        assertNull("every slot in use: no slot for a new plan", Departures.freeSlotId(listOf(ownPlan(1), ownPlan(2, "B"))))
        assertEquals("no read-back at all: the first slot", 1, Departures.freeSlotId(emptyList()))
        // A switched-off plan the user named is not an empty slot.
        assertFalse(Departures.isEmptySlot(ownPlan(4, "Holiday", sts = 0).copy(cycleTimes = emptyList())))
    }

    @Test fun aNewPlanCarriesTheEditorChoicesAndTheOfficialAppsDefaults() {
        val t = Departures.template(listOf(emptySlot(2), ownPlan()))
        assertEquals(Departures.Template(15, 15, 2, 15, 2), t)
        val d = DepartureDraft("Sunday trip", "09:30:00", setOf(6, 7), climate = true, tempC = 21.5,
            wheelHeat = true, seats = mapOf(1 to 3, 2 to 3), batteryMaintenance = true)
        val p = Departures.newPlan(d, btId = 2, t = t)
        assertEquals(2, p.btId)
        assertEquals(listOf(6, 7), p.cycleTimes.filter { it.sts == 1 }.map { it.day })
        assertEquals(7, p.cycleTimes.size)
        assertTrue(p.cycleTimes.all { it.time == "09:30:00" })
        assertEquals(TravelClimateSetting(15, 1, "21.5"), p.csSetting)
        assertEquals(TravelWheelSetting(15, 2, 1), p.whlSetting)
        assertEquals(1, p.preHeatSts)
        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 0, 5 to 0), p.heatSettings.associate { it.location to it.sts })
        assertEquals("on at the chosen level, off at the official app's", mapOf(1 to 3, 2 to 3, 3 to 2, 5 to 2),
            p.heatSettings.associate { it.location to it.level })
        assertEquals(Departures.PLACEHOLDER_TIME, p.temporaryTime)
        assertEquals(Departures.BOOKING_RECURRING, p.bookingType)
        assertNull("no fragrance block: none of the car's plans has one", p.fragSetting)
        assertEquals(d, Departures.draftOf(p))
    }

    @Test fun aNewPlanIsWrittenOverTheFreeSlotAsTheCarReportedIt() {
        // 0.3.14: field 16/10 and 18/10 - a plan built from scratch never reached the car; the slot's own
        // record keeps every block the car sent, a missing one gets the official app's defaults.
        val t = Departures.template(listOf(ownPlan(), emptySlot(2)))
        val d = DepartureDraft("Test", "04:00:00", setOf(7), climate = true, tempC = 21.0)
        val p = Departures.fromSlot(emptySlot(2), d, t)
        assertEquals(2, p.btId)
        // 0.3.15 (field 18/10): recurring as the official app's plan (the unused slot reads type 1), and no
        // fragrance block, as none of the car's plans has one.
        assertEquals(Triple(1, 1, Departures.BOOKING_RECURRING), Triple(p.sts, p.displaySts, p.bookingType))
        assertEquals(Departures.PLACEHOLDER_TIME, p.temporaryTime)
        assertEquals(listOf(7), p.cycleTimes.filter { it.sts == 1 }.map { it.day })
        assertEquals(TravelClimateSetting(15, 1, "21.0"), p.csSetting)
        assertNull("no fragrance block", p.fragSetting)
        assertEquals(listOf(1, 2), p.ventiSettings.map { it.location })
        assertTrue(p.ventiSettings.all { it.sts == 0 && it.duration > 0 })
        assertEquals(mapOf(1 to 0, 2 to 0, 3 to 0, 5 to 0), p.heatSettings.associate { it.location to it.sts })
        assertEquals(TravelWheelSetting(15, 2, 0), p.whlSetting)
        assertEquals(d, Departures.draftOf(p))
        // A slot that reports its own blocks keeps them; zero durations and levels get the defaults.
        val reported = emptySlot(3).copy(
            csSetting = TravelClimateSetting(0, 0, "22.0"),
            heatSettings = listOf(TravelSeatSetting(0, 0, 1, 0), TravelSeatSetting(0, 0, 2, 0)),
            fragSetting = TravelFragranceSetting(channel = 2, duration = 10, level = 2, sts = 1),
        )
        val q = Departures.fromSlot(reported, d.copy(seats = mapOf(1 to 2, 2 to 2)), t)
        assertEquals(TravelClimateSetting(15, 1, "21.0"), q.csSetting)
        assertEquals(listOf(TravelSeatSetting(15, 2, 1, 1), TravelSeatSetting(15, 2, 2, 1)), q.heatSettings)
        assertEquals("the car's own fragrance block, switched off", TravelFragranceSetting(2, 10, 2, 0), q.fragSetting)
        // A car whose plans do carry a fragrance block: a new plan gets it too, switched off.
        val withScent = Departures.template(listOf(ownPlan().copy(fragSetting = TravelFragranceSetting(1, 15, 1, 1)), emptySlot(2)))
        assertEquals(TravelFragranceSetting(1, 15, 1, 0), Departures.fromSlot(emptySlot(2), d, withScent).fragSetting)
    }

    @Test fun theTemperatureAlwaysUsesAPointWhateverThePhoneLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "NL"))
            assertEquals("22.0", Departures.temp(22.0))
            assertEquals("24.5", Departures.newPlan(DepartureDraft(days = setOf(1), tempC = 24.5), 1).csSetting!!.temp)
        } finally { Locale.setDefault(before) }
        assertEquals("16.0", Departures.temp(10.0))
        assertEquals("28.0", Departures.temp(35.0))
    }

    @Test fun anEditKeepsWhatTheEditorDoesNotShow() {
        val p = ownPlan()
        val d = Departures.draftOf(p).copy(days = setOf(1, 2, 3, 4, 5), tempC = 26.0, wheelHeat = true)
        val e = Departures.applied(p, d)
        assertEquals(p.btId, e.btId)
        assertEquals(p.fragSetting, e.fragSetting)
        assertEquals(p.ventiSettings, e.ventiSettings)
        assertEquals("seats untouched: every seat entry kept as reported", p.heatSettings, e.heatSettings)
        assertEquals("26.0", e.csSetting!!.temp)
        assertEquals(15, e.csSetting!!.duration)
        assertEquals(TravelWheelSetting(15, 2, 1), e.whlSetting)
        assertEquals(setOf(1, 2, 3, 4, 5), e.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet())
        // Switching the front seats on changes only those; the rear seat keeps its own state and level.
        val s = Departures.applied(p, d.copy(seats = d.seats + mapOf(1 to 3, 2 to 3)))
        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 1, 5 to 0), s.heatSettings.associate { it.location to it.sts })
        assertEquals(mapOf(1 to 3, 2 to 3, 3 to 2, 5 to 2), s.heatSettings.associate { it.location to it.level })
    }

    @Test fun eachSeatIsSetOnItsOwnAsTheOfficialAppDoes() {
        // 0.3.17 (user 18/10): driver, passenger and both rear seats each off or I-III. A free slot lists eight seat
        // entries (field 18/10, "h8"): the editor's four are set, the others stay off, and nothing is added.
        val slot = emptySlot(3).copy(heatSettings = (1..8).map { TravelSeatSetting(15, 1, it, 0) })
        val d = DepartureDraft("Test", "04:00:00", setOf(7), tempC = 21.0, seats = mapOf(1 to 1, 2 to 2, 3 to 3))
        val p = Departures.fromSlot(slot, d)
        assertEquals((1..8).toList(), p.heatSettings.map { it.location })
        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 0, 5 to 0, 6 to 0, 7 to 0, 8 to 0), p.heatSettings.associate { it.location to it.sts })
        assertEquals(listOf(1, 2, 3), p.heatSettings.filter { it.sts == 1 }.map { it.level })
        assertEquals(d, Departures.draftOf(p))
        // An edit changes only the seats the editor changed: driver off, rear right on at II.
        val e = Departures.applied(p, d.copy(seats = mapOf(2 to 2, 3 to 3, 5 to 2)))
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 1, 4 to 0, 5 to 1, 6 to 0, 7 to 0, 8 to 0), e.heatSettings.associate { it.location to it.sts })
        assertEquals("the driver's level stays as it was, switched off", 1, e.heatSettings.first { it.location == 1 }.level)
        assertEquals(2, e.heatSettings.first { it.location == 5 }.level)
        assertEquals(p.heatSettings.filter { it.location !in Departures.HEAT_LOCATIONS }, e.heatSettings.filter { it.location !in Departures.HEAT_LOCATIONS })
    }

    @Test fun theEditorOffersTheSeatsTheCarLists() {
        assertEquals(Departures.HEAT_LOCATIONS, Departures.seatsOf(null))
        assertEquals("no seat entries yet: all four, written when the plan is", Departures.HEAT_LOCATIONS, Departures.seatsOf(emptySlot(2)))
        assertEquals(listOf(1, 2), Departures.seatsOf(emptySlot(2).copy(heatSettings = listOf(TravelSeatSetting(15, 1, 1, 0), TravelSeatSetting(15, 1, 2, 0)))))
        assertEquals("switching on: driver and passenger at III", mapOf(1 to 3, 2 to 3), Departures.seatsOn(Departures.HEAT_LOCATIONS))
        assertEquals(mapOf(3 to 3), Departures.seatsOn(listOf(3, 5)))
        assertEquals(mapOf(2 to 3), Departures.withSeat(mapOf(1 to 3, 2 to 3), 1, 0))
        assertEquals(mapOf(1 to 1, 5 to 3), Departures.withSeat(mapOf(1 to 1), 5, 7))
        assertTrue(Departures.withSeat(mapOf(1 to 3), 1, 0).isEmpty())
        assertEquals(0, DepartureDraft(seats = mapOf(1 to 2)).seatLevel(5))
        assertFalse(DepartureDraft().seatHeat)
    }

    @Test fun aDeleteLandsOnceTheSlotReadsBackEmptyOrIsGone() {
        assertTrue(Departures.gone(listOf(ownPlan(1), emptySlot(2)), 2))
        assertTrue("slot missing from the read-back", Departures.gone(listOf(ownPlan(1)), 3))
        assertFalse("the plan is still there", Departures.gone(listOf(ownPlan(1), ownPlan(2, "B")), 2))
        assertFalse("switched off is not deleted", Departures.gone(listOf(ownPlan(2, "B", sts = 0)), 2))
    }

    @Test fun aDeleteWritesThePlanBackAsAnUnusedSlot() {
        // 0.3.16 (field 18/10): "stop" never reached the car while an edit of the next slot landed within seconds; the
        // slot is written back the way the car reports its free slots, its blocks kept as reported, everything off.
        val p = ownPlan(2, "Plan B").copy(preHeatSts = 1, whlSetting = TravelWheelSetting(15, 2, 1),
            fragSetting = TravelFragranceSetting(1, 15, 1, 1))
        val e = Departures.emptied(p)
        assertEquals(2, e.btId)
        assertEquals(Triple(0, 0, Departures.EMPTY_SLOT_TYPE), Triple(e.sts, e.displaySts, e.bookingType))
        assertEquals(Departures.EMPTY_SLOT_NAME, e.name)
        assertEquals(Departures.PLACEHOLDER_TIME, e.temporaryTime)
        assertEquals(7, e.cycleTimes.size)
        assertTrue("no active day", e.cycleTimes.none { it.sts == 1 })
        assertEquals(0, e.preHeatSts)
        assertEquals(TravelClimateSetting(15, 0, "23.0"), e.csSetting)
        assertTrue(e.heatSettings.all { it.sts == 0 } && e.ventiSettings.all { it.sts == 0 })
        assertEquals(p.heatSettings.map { it.location to it.duration }, e.heatSettings.map { it.location to it.duration })
        assertEquals(TravelWheelSetting(15, 2, 0), e.whlSetting)
        assertEquals(TravelFragranceSetting(1, 15, 1, 0), e.fragSetting)
        assertTrue("hidden like the car's own free slots", Departures.isEmptySlot(e))
        assertTrue("read back like this, the delete has landed", Departures.gone(listOf(ownPlan(1), e), 2))
        // The second try: the type an edit is known to land with; still a free slot to the app.
        val f = Departures.emptied(p, Departures.BOOKING_RECURRING)
        assertEquals(e.copy(bookingType = Departures.BOOKING_RECURRING), f)
        assertTrue(Departures.isEmptySlot(f))
    }

    @Test fun aWriteOnlyCountsWhenNameStateDaysAndTimeAllMatch() {
        val want = Departures.newPlan(DepartureDraft("Plan A", "06:40:00", setOf(2, 5)), 2)
        assertTrue(Departures.landed(listOf(ownPlan(1, "Plan A")), want))
        assertFalse("same name, other days", Departures.landed(listOf(ownPlan(1, "Plan A")), want.copy(cycleTimes = want.cycleTimes.map { it.copy(sts = 1) })))
        assertFalse("same name, other time", Departures.landed(listOf(ownPlan(1, "Plan A")), want.copy(cycleTimes = want.cycleTimes.map { it.copy(time = "08:00:00") })))
    }
}
