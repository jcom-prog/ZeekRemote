package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.CycleTime
import com.openzeekr.app.net.model.TravelClimateSetting
import com.openzeekr.app.net.model.TravelSeatSetting
import com.openzeekr.app.net.model.TravelWheelSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.18: a small simulator of the departure plans - the car's five slots, the cloud, ZeekRemote and the official app -
 * with the rules the field diagnoses of 16/10 and 18/10 showed, each test replaying one of those observations:
 *  - the car applies an "edit" within seconds (every edit from 0.3.15 on confirmed in 1-2 read-backs, also with the car
 *    not woken first); it ignored every "start" and "stop" (16/10 and 18/10: still pending after a minute);
 *  - ZeekRemote's list is the car's list (getTravelPlan reads back what the car holds);
 *  - the official app shows its own changes over the car's list: a plan made there showed there but not in the car's
 *    list (18/10);
 *  - the official app shows a seat by its level alone: an off seat stored with III read "III" there (18/10).
 * 0.3.20 (field 19/10): the official app's delete does reach the car. The car switches the plan off and hides it
 * (displaySts 0) and keeps its name, days and blocks; the official app shows only plans that are not hidden, and its
 * switch keeps a plan shown. What 0.3.18 read as "still on the car" (18/10) was such a hidden plan. A "stop" the car took
 * minutes later (18/10, 0.3.15) hid a plan with the state it carried: on and hidden.
 */
class ScheduleSimTest {

    private class Car(val slots: MutableList<BookingTravelSetting>) {
        /** Changes the cloud accepted but the car did not apply yet ("start"/"stop"): the official app shows them anyway. */
        val intent = mutableMapOf<Int, Pair<String, BookingTravelSetting>>()

        fun send(command: String, s: BookingTravelSetting) {
            when (command) {
                "edit" -> { slots[slots.indexOfFirst { it.btId == s.btId }] = s; intent.remove(s.btId) }
                "start", "stop" -> intent[s.btId] = command to s
            }
        }

        /** The car takes what waited (it never did within a minute in the field; it may when it wakes): a new plan, or
         *  a delete as the car does it - the plan hidden, with the state the request carried. */
        fun takesWhatWaited() {
            for ((id, w) in intent.toMap()) {
                val i = slots.indexOfFirst { it.btId == id }
                if (i < 0) continue
                slots[i] = if (w.first == "start") w.second else slots[i].copy(sts = w.second.sts, displaySts = 0)
            }
            intent.clear()
        }

        /** What ZeekRemote reads and shows. */
        fun zeekRemote(): List<BookingTravelSetting> = Departures.visible(slots)

        /** What the official app shows: the plans the car shows, over which its own changes that wait. */
        fun officialApp(): List<BookingTravelSetting> = slots.mapNotNull { s ->
            val w = intent[s.btId]
            when (w?.first) { null -> s; "start" -> w.second; else -> null }
        }.filter { it.displaySts == 1 }

        /** A seat as the official app shows it: I-III by level, else off. */
        fun officialSeats(p: BookingTravelSetting): Map<Int, Int> =
            Departures.HEAT_LOCATIONS.associateWith { loc -> p.heatSettings.firstOrNull { it.location == loc }?.level?.takeIf { it in 1..3 } ?: 0 }
                .filterValues { it > 0 }
    }

    // The car as the journal of 18/10 shows it (seats line "1=9-/2=9-/3=9-/5=9-" etc.; 9 = at least 9).
    private val off = 9
    private fun seats(vararg e: Pair<Int, Int>, on: Set<Int> = emptySet()) = e.map { (loc, lvl) -> TravelSeatSetting(15, lvl, loc, if (loc in on) 1 else 0) }
    private fun freeSlot(id: Int, level: Int = off) = BookingTravelSetting(
        btId = id, name = Departures.EMPTY_SLOT_NAME, sts = 0, displaySts = 0, bookingType = Departures.EMPTY_SLOT_TYPE,
        temporaryTime = Departures.PLACEHOLDER_TIME, cycleTimes = (1..7).map { CycleTime(it, 0, "04:00:00") }, preHeatSts = 0,
        csSetting = TravelClimateSetting(15, 0, "22.0"), ventiSettings = (1..8).map { TravelSeatSetting(15, level, it, 0) },
        heatSettings = (1..8).map { TravelSeatSetting(15, level, it, 0) }, whlSetting = TravelWheelSetting(15, level, 0),
    )
    private val ownPlan = BookingTravelSetting(
        btId = 1, name = "Plan A", sts = 1, displaySts = 1, bookingType = Departures.BOOKING_RECURRING,
        temporaryTime = Departures.PLACEHOLDER_TIME, cycleTimes = (1..7).map { CycleTime(it, if (it == 2 || it == 5) 1 else 0, "06:40:00") },
        preHeatSts = 0, csSetting = TravelClimateSetting(15, 1, "21.0"),
        heatSettings = seats(1 to off, 2 to off, 3 to off, 5 to off), whlSetting = TravelWheelSetting(15, 3, 0),
    )
    private fun car() = Car(mutableListOf(ownPlan, freeSlot(2), freeSlot(3), freeSlot(4), freeSlot(5)))
    private val test = DepartureDraft("Test", "04:00:00", setOf(7), tempC = 21.0, seats = mapOf(1 to 1, 2 to 2, 3 to 3))

    @Test fun aPlanMadeHereIsOnTheCarAndTheSameInBothApps() {
        val car = car()
        val t = Departures.template(car.slots)
        assertEquals("the car's own off level", off, t.seatOffLevel)
        val plan = Departures.fromSlot(car.slots.first { it.btId == Departures.freeSlotId(car.slots) }, test, t)
        car.send("edit", plan)
        val here = car.zeekRemote().single { it.name == "Test" }
        val there = car.officialApp().single { it.name == "Test" }
        assertEquals(test.seats, Departures.draftOf(here).seats)
        assertEquals("rear right off in both apps (0.3.17 left III there)", test.seats, car.officialSeats(there))
        assertEquals("every off seat stored the car's way", setOf(off), here.heatSettings.filter { it.sts == 0 }.map { it.level }.toSet())
    }

    @Test fun theSeatTestOf18OctoberReplayed() {
        // First: slot 2 as an earlier ZeekRemote delete left it (every level 3), the car's own free slots at 9.
        val car = Car(mutableListOf(ownPlan, freeSlot(2, level = 3), freeSlot(4), freeSlot(5)))
        val t = Departures.template(car.slots)
        // 0.3.17 sent "1=1+/2=2+/3=3+/4=3-/5=3-/6=3-/7=3-/8=3-": the official app read rear right III.
        val v17 = Departures.fromSlot(car.slots[1], test, Departures.Template())
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3, 5 to 3), car.officialSeats(v17))
        // 0.3.18: rear right off in the official app too.
        val v18 = Departures.fromSlot(car.slots[1], test, t)
        car.send("edit", v18)
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3), car.officialSeats(car.officialApp().single { it.name == "Test" }))
        // Then: driver off, rear right II. 0.3.17 kept the driver's I with the switch off, so the official app read I.
        val edit = test.copy(seats = mapOf(2 to 2, 3 to 3, 5 to 2))
        val e17 = Departures.applied(v17, edit, Departures.Template())
        assertEquals(1, car.officialSeats(e17)[1])
        val e18 = Departures.applied(car.slots[1], edit, t)
        car.send("edit", e18)
        assertEquals(mapOf(2 to 2, 3 to 3, 5 to 2), car.officialSeats(car.officialApp().single { it.name == "Test" }))
        assertEquals(edit.seats, Departures.draftOf(car.zeekRemote().single { it.name == "Test" }).seats)
        // Last: deleted here: a free slot again, the car's way, in both apps.
        car.send("edit", Departures.emptied(car.slots[1], t = t))
        assertTrue(car.zeekRemote().none { it.name == "Test" } && car.officialApp().none { it.name == "Test" })
        assertEquals(setOf(off), car.slots[1].heatSettings.map { it.level }.toSet())
    }

    @Test fun aDeleteInTheOfficialAppIsGoneHereToo() {
        // 19/10: made in the official app, then deleted there. The car held it off and hidden with its name, days and
        // blocks, and 0.3.19 showed it here as switched off.
        val car = car()
        val made = Departures.fromSlot(car.slots[1], test.copy(name = "Other app"), Departures.template(car.slots))
        car.send("start", made)
        car.takesWhatWaited()
        assertTrue(car.zeekRemote().any { it.name == "Other app" } && car.officialApp().any { it.name == "Other app" })
        car.send("stop", made.copy(sts = 0))
        assertTrue("gone there at once", car.officialApp().none { it.name == "Other app" })
        car.takesWhatWaited()
        val held = car.slots.single { it.btId == made.btId }
        assertEquals("the car keeps it, off and hidden", Triple("Other app", 0, 0), Triple(held.name, held.sts, held.displaySts))
        assertTrue("gone here too", car.zeekRemote().none { it.name == "Other app" })
        assertEquals("its place is free again", 4, car.slots.count(Departures::isFree))
        // Switched off in the official app, a plan stays in both lists (19/10).
        car.send("edit", ownPlan.copy(sts = 0))
        assertTrue(car.zeekRemote().any { it.name == "Plan A" } && car.officialApp().any { it.name == "Plan A" })
    }

    @Test fun aNewPlanGoesIntoTheSlotOfADeletedOneOnlyWhenNoUnusedSlotIsLeft() {
        // A plan the official app made and deleted (its seats stay on in the car, 19/10), next to unused slots: a new
        // plan takes an unused slot. With every other slot in use it takes the deleted plan's slot, with nothing of it.
        val deleted = ownPlan.copy(btId = 2, name = "Plan B", sts = 0, displaySts = 0,
            heatSettings = seats(1 to 3, 2 to 3, 3 to 3, 5 to 3, on = setOf(1, 2, 3, 5)))
        val withFree = Car(mutableListOf(ownPlan, deleted, freeSlot(3), freeSlot(4), freeSlot(5)))
        assertEquals(3, Departures.freeSlotId(withFree.slots))
        val full = Car(mutableListOf(ownPlan, deleted, ownPlan.copy(btId = 3, name = "Plan C"), ownPlan.copy(btId = 4, name = "Plan D"),
            ownPlan.copy(btId = 5, name = "Plan E")))
        assertEquals(2, Departures.freeSlotId(full.slots))
        val t = Departures.template(full.slots)
        full.send("edit", Departures.fromSlot(full.slots[1], test, t))
        val here = full.zeekRemote().single { it.btId == 2 }
        assertEquals(here, full.officialApp().single { it.btId == 2 })
        assertEquals("name, days, time and settings all from the editor", test, Departures.draftOf(here))
        assertEquals("rear right off in the official app too", test.seats, full.officialSeats(here))
    }

    @Test fun aPlanHiddenWhileOnStaysShownUntilSwitchedOffOrDeletedHere() {
        // 18/10: a delete the car took minutes later hid a plan that was on. The car may still run it: shown here, not in
        // the official app. Switched off here it is an ordinary plan in both apps again; deleted here it is gone.
        val car = car()
        car.send("stop", ownPlan)
        car.takesWhatWaited()
        assertEquals(1 to 0, car.slots[0].sts to car.slots[0].displaySts)
        assertTrue(car.zeekRemote().any { it.name == "Plan A" })
        assertTrue(car.officialApp().none { it.name == "Plan A" })
        val t = Departures.template(car.slots)
        car.send("edit", Departures.switched(car.slots[0], false, t))
        assertEquals(0, car.zeekRemote().single { it.name == "Plan A" }.sts)
        assertEquals(0, car.officialApp().single { it.name == "Plan A" }.sts)
        car.send("edit", Departures.emptied(car.slots[0], t = t))
        assertFalse(car.zeekRemote().any { it.name == "Plan A" } || car.officialApp().any { it.name == "Plan A" })
    }

    @Test fun aPlanMadeInTheOfficialAppShowsHereOnceTheCarHasIt() {
        // 18/10: shown in the official app (also after a restart), not in the car's list - so not here either.
        val car = car()
        val made = Departures.fromSlot(car.slots[1], test.copy(name = "Other app"), Departures.template(car.slots))
        car.send("start", made)
        assertTrue(car.officialApp().any { it.name == "Other app" })
        assertFalse(car.zeekRemote().any { it.name == "Other app" })
        // Once the car takes it, the next read shows it here (0.3.18 reads on showing the tab, on coming back and every 30 s).
        car.takesWhatWaited()
        assertTrue(car.zeekRemote().any { it.name == "Other app" })
    }
}
