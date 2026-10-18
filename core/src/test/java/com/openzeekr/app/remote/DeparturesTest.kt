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

    /** A plan as the official app makes it: its defaults stored even for switched-off items. */
    private fun ownPlan(id: Int = 1, name: String = "Plan A", sts: Int = 1) = BookingTravelSetting(
        btId = id, name = name, sts = sts, displaySts = 1, bookingType = 1,
        temporaryTime = Departures.PLACEHOLDER_TIME,
        cycleTimes = (1..7).map { CycleTime(it, if (it == 2 || it == 5) 1 else 0, "06:40:00") },
        preHeatSts = 0,
        csSetting = TravelClimateSetting(duration = 15, sts = 1, temp = "23.0"),
        ventiSettings = listOf(TravelSeatSetting(15, 2, 1, 0), TravelSeatSetting(15, 2, 2, 0)),
        heatSettings = listOf(TravelSeatSetting(15, 2, 1, 0), TravelSeatSetting(15, 2, 2, 0), TravelSeatSetting(15, 2, 3, 1), TravelSeatSetting(15, 2, 5, 0)),
        whlSetting = TravelWheelSetting(duration = 15, level = 2, sts = 0),
        fragSetting = TravelFragranceSetting(channel = 1, duration = 15, level = 1, sts = 0),
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
            wheelHeat = true, seatHeat = true, batteryMaintenance = true)
        val p = Departures.newPlan(d, btId = 2, t = t)
        assertEquals(2, p.btId)
        assertEquals(listOf(6, 7), p.cycleTimes.filter { it.sts == 1 }.map { it.day })
        assertEquals(7, p.cycleTimes.size)
        assertTrue(p.cycleTimes.all { it.time == "09:30:00" })
        assertEquals(TravelClimateSetting(15, 1, "21.5"), p.csSetting)
        assertEquals(TravelWheelSetting(15, 2, 1), p.whlSetting)
        assertEquals(1, p.preHeatSts)
        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 0, 5 to 0), p.heatSettings.associate { it.location to it.sts })
        assertEquals(Departures.PLACEHOLDER_TIME, p.temporaryTime)
        assertEquals(d, Departures.draftOf(p))
    }

    @Test fun aNewPlanIsWrittenOverTheFreeSlotAsTheCarReportedIt() {
        // 0.3.14: field 16/10 and 18/10 - a plan built from scratch never reached the car; the slot's own
        // record keeps every block the car sent, a missing one gets the official app's defaults.
        val t = Departures.template(listOf(ownPlan(), emptySlot(2)))
        val d = DepartureDraft("Test", "04:00:00", setOf(7), climate = true, tempC = 21.0)
        val p = Departures.fromSlot(emptySlot(2), d, t)
        assertEquals(2, p.btId)
        assertEquals(Triple(1, 1, 1), Triple(p.sts, p.displaySts, p.bookingType))
        assertEquals(Departures.PLACEHOLDER_TIME, p.temporaryTime)
        assertEquals(listOf(7), p.cycleTimes.filter { it.sts == 1 }.map { it.day })
        assertEquals(TravelClimateSetting(15, 1, "21.0"), p.csSetting)
        assertEquals("the official app's fragrance block, switched off", TravelFragranceSetting(1, 15, 1, 0), p.fragSetting)
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
        val q = Departures.fromSlot(reported, d.copy(seatHeat = true), t)
        assertEquals(TravelClimateSetting(15, 1, "21.0"), q.csSetting)
        assertEquals(listOf(TravelSeatSetting(15, 2, 1, 1), TravelSeatSetting(15, 2, 2, 1)), q.heatSettings)
        assertEquals("the car's own fragrance block, switched off", TravelFragranceSetting(2, 10, 2, 0), q.fragSetting)
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
        assertEquals("seat switch untouched: every seat entry kept as reported", p.heatSettings, e.heatSettings)
        assertEquals("26.0", e.csSetting!!.temp)
        assertEquals(15, e.csSetting!!.duration)
        assertEquals(TravelWheelSetting(15, 2, 1), e.whlSetting)
        assertEquals(setOf(1, 2, 3, 4, 5), e.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet())
        // Switching seat heating on changes only the front seats; the rear seat keeps its own state.
        val s = Departures.applied(p, d.copy(seatHeat = true))
        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 1, 5 to 0), s.heatSettings.associate { it.location to it.sts })
    }

    @Test fun aDeleteLandsOnceTheSlotReadsBackEmptyOrIsGone() {
        assertTrue(Departures.gone(listOf(ownPlan(1), emptySlot(2)), 2))
        assertTrue("slot missing from the read-back", Departures.gone(listOf(ownPlan(1)), 3))
        assertFalse("the plan is still there", Departures.gone(listOf(ownPlan(1), ownPlan(2, "B")), 2))
        assertFalse("switched off is not deleted", Departures.gone(listOf(ownPlan(2, "B", sts = 0)), 2))
    }

    @Test fun aWriteOnlyCountsWhenNameStateDaysAndTimeAllMatch() {
        val want = Departures.newPlan(DepartureDraft("Plan A", "06:40:00", setOf(2, 5)), 2)
        assertTrue(Departures.landed(listOf(ownPlan(1, "Plan A")), want))
        assertFalse("same name, other days", Departures.landed(listOf(ownPlan(1, "Plan A")), want.copy(cycleTimes = want.cycleTimes.map { it.copy(sts = 1) })))
        assertFalse("same name, other time", Departures.landed(listOf(ownPlan(1, "Plan A")), want.copy(cycleTimes = want.cycleTimes.map { it.copy(time = "08:00:00") })))
    }
}
