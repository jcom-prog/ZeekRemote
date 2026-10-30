package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.CycleTime
import com.openzeekr.app.util.ProximityDiagnosticJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleDiagnosticsTest {

    private val used = BookingTravelSetting(btId = 1, name = "Plan A", sts = 1, displaySts = 1, bookingType = 1,
        temporaryTime = Departures.PLACEHOLDER_TIME, preHeatSts = 0, cycleTimes = (1..7).map { CycleTime(it, if (it == 2 || it == 5) 1 else 0, "06:40:00") })
    private val empty = BookingTravelSetting(btId = 2, name = Departures.EMPTY_SLOT_NAME, sts = 0, displaySts = 0, bookingType = 1,
        temporaryTime = Departures.PLACEHOLDER_TIME, cycleTimes = emptyList(), preHeatSts = 0)

    @Test fun theLineNamesSlotsAndCategoriesOnly() {
        val want = Departures.newPlan(DepartureDraft("Plan A", "07:00:00", setOf(2, 5)), btId = 1)
        val line = ScheduleDiagnostics.line(ScheduleWrites.Kind.EDIT, 1, "accepted", "yes", "pending", 20,
            ScheduleDiagnostics.slots(listOf(used, empty)), ScheduleDiagnostics.found(listOf(used, empty), want))
        assertEquals("schedule write kind=edit slot=1 sent=accepted session=yes result=pending polls=20 " +
            "readback=1:on/used/2,2:off/empty/0 found=slot1 name=same days=same time=other on=same temp=other seats=other", line)
        for (privateValue in listOf("Plan A", "06:40", "07:00", Departures.PLACEHOLDER_TIME)) assertFalse(privateValue, line.contains(privateValue))
        assertEquals("SCHEDULE_WRITE " + line.removePrefix("schedule write "), ProximityDiagnosticJournal.event("sched", line))
    }

    @Test fun aPlanTheOfficialAppDeletedShowsAsHidden() {
        // 0.3.20 (field 19/10): off and hidden, its days kept.
        val deleted = used.copy(btId = 3, sts = 0, displaySts = 0)
        val readback = ScheduleDiagnostics.slots(listOf(used, empty, deleted))
        assertEquals("1:on/used/2,2:off/empty/0,3:off/hidden/2", readback)
        val line = "schedule write kind=create slot=2 sent=accepted session=yes result=confirmed polls=2 readback=$readback found=none"
        assertEquals("SCHEDULE_WRITE " + line.removePrefix("schedule write "), ProximityDiagnosticJournal.event("sched", line))
        // A plan sent with the name a deleted one still carries is found in its own slot.
        val sent = used.copy(btId = 4)
        assertEquals("slot4 name=same days=same time=same on=same temp=same seats=same", ScheduleDiagnostics.found(listOf(deleted, sent), sent))
    }

    @Test fun theJournalRefusesAnythingThatCouldCarryAPrivateValue() {
        val base = "schedule write kind=create slot=2 sent=accepted session=no result=pending polls=20 readback=none found=none"
        assertEquals("SCHEDULE_WRITE kind=create slot=2 sent=accepted session=no result=pending polls=20 readback=none found=none",
            ProximityDiagnosticJournal.event("sched", base))
        assertNull("a name in the read-back", ProximityDiagnosticJournal.event("sched", base.replace("readback=none", "readback=Test")))
        assertNull("a time", ProximityDiagnosticJournal.event("sched", base.replace("readback=none", "readback=1:on/used/2 06:40")))
        assertNull("a free-text refusal", ProximityDiagnosticJournal.event("sched", base.replace("sent=accepted", "sent=refused code=car offline")))
        assertNull("trailing data", ProximityDiagnosticJournal.event("sched", "$base VIN=private"))
        assertNull("another area", ProximityDiagnosticJournal.event("http", base))
        val found = base.replace("found=none", "found=slot2 name=same days=same time=same on=same temp=same seats=other")
        assertEquals("SCHEDULE_WRITE " + found.removePrefix("schedule write "), ProximityDiagnosticJournal.event("sched", found))
        assertNull("a temperature value", ProximityDiagnosticJournal.event("sched", found.replace("temp=same", "temp=21.0")))
    }

    @Test fun theTemperatureAndTheSeatsAreComparedNotShown() {
        // 0.3.17 (field 18/10): the plan's temperature read back as sent, and each seat with its level.
        val sent = Departures.fromSlot(empty, DepartureDraft("Test", "04:00:00", setOf(7), tempC = 21.0, seats = mapOf(1 to 2)))
        val asSent = sent.copy(btId = 2, csSetting = sent.csSetting!!.copy(temp = "21"))
        assertEquals("slot2 name=same days=same time=same on=same temp=same seats=same", ScheduleDiagnostics.found(listOf(used, asSent), sent))
        val other = asSent.copy(csSetting = sent.csSetting!!.copy(temp = "16.0"),
            heatSettings = sent.heatSettings.map { if (it.location == 1) it.copy(level = 3) else it })
        assertEquals("slot2 name=same days=same time=same on=same temp=other seats=other", ScheduleDiagnostics.found(listOf(used, other), sent))
    }

    @Test fun theSeatsLineShowsSeatCodesAndLevelsOnly() {
        // 0.3.17: which code the car uses for which seat, entry by entry - never a name, time, day or temperature.
        val slot = empty.copy(heatSettings = (1..8).map { com.openzeekr.app.net.model.TravelSeatSetting(15, 1, it, 0) })
        val sent = Departures.fromSlot(slot, DepartureDraft("Test", "04:00:00", setOf(7), tempC = 21.0, seats = mapOf(1 to 1, 2 to 2, 3 to 3)))
        val line = ScheduleDiagnostics.seatsLine("create", sent, listOf(used, slot))
        // 0.4.12: each entry ends with its duration in minutes (the slot's entries carry 15).
        assertEquals("schedule seats at=create sent=2:1=1+15/2=2+15/3=3+15/4=1-15/5=1-15/6=1-15/7=1-15/8=1-15 " +
            "slots=1:none,2:1=1-15/2=1-15/3=1-15/4=1-15/5=1-15/6=1-15/7=1-15/8=1-15", line)
        for (privateValue in listOf("Plan A", "Test", "06:40", "04:00", "21.0", Departures.PLACEHOLDER_TIME))
            assertFalse(privateValue, line.contains(privateValue))
        assertEquals("SCHEDULE_SEATS " + line.removePrefix("schedule seats "), ProximityDiagnosticJournal.event("sched", line))
        assertEquals("SCHEDULE_SEATS at=load sent=- slots=none",
            ProximityDiagnosticJournal.event("sched", ScheduleDiagnostics.seatsLine("load", null, emptyList())))
        assertNull("a name", ProximityDiagnosticJournal.event("sched", line.replace("2:1=1+", "2:Test=1+")))
        assertNull("trailing data", ProximityDiagnosticJournal.event("sched", "$line 04:00"))
    }

    @Test fun theSeatsLineShowsTheLevelAsTheCarStoresIt() {
        // 0.3.18: an off seat is stored with a level outside I-III; the line shows it as stored (up to 999).
        val slot = empty.copy(heatSettings = listOf(com.openzeekr.app.net.model.TravelSeatSetting(15, 255, 1, 0),
            com.openzeekr.app.net.model.TravelSeatSetting(15, 3, 2, 1)))
        val line = ScheduleDiagnostics.seatsLine("load", null, listOf(slot))
        assertEquals("schedule seats at=load sent=- slots=2:1=255-15/2=3+15", line)
        assertEquals("SCHEDULE_SEATS " + line.removePrefix("schedule seats "), ProximityDiagnosticJournal.event("sched", line))
        assertNull("four digits", ProximityDiagnosticJournal.event("sched", line.replace("255", "2550")))
        // 0.4.12: the ventilation entries the same way; this slot has none.
        val vents = ScheduleDiagnostics.ventsLine("load", null, listOf(slot))
        assertEquals("schedule vents at=load sent=- slots=2:none", vents)
        assertEquals("SCHEDULE_VENTS at=load sent=- slots=2:none", ProximityDiagnosticJournal.event("sched", vents))
    }

    @Test fun theShapeLineShowsFlagsAndCountsOnly() {
        // 0.3.14: how a plan the car accepted is built next to the one we send - never a value.
        val own = used.copy(csSetting = com.openzeekr.app.net.model.TravelClimateSetting(15, 1, "22.0"),
            fragSetting = com.openzeekr.app.net.model.TravelFragranceSetting(1, 15, 1, 0))
        val sent = Departures.fromSlot(empty, DepartureDraft("Test", "04:00:00", setOf(7), tempC = 21.0))
        val line = ScheduleDiagnostics.shapeLine("create", sent, listOf(own, empty))
        assertEquals("schedule shape at=create sent=2:s1.d1.b0.p0.tp.c7.a1.nu.cs+.v2+.h4+.w+.f- " +
            "slots=1:s1.d1.b1.p0.tp.c7.a2.nu.cs+.v0.h0.w-.f+,2:s0.d0.b1.p0.tp.c0.a0.ne.cs-.v0.h0.w-.f-", line)
        for (privateValue in listOf("Plan A", "Test", "06:40", "04:00", "22.0", "21.0", Departures.PLACEHOLDER_TIME))
            assertFalse(privateValue, line.contains(privateValue))
        assertEquals("SCHEDULE_SHAPE " + line.removePrefix("schedule shape "), ProximityDiagnosticJournal.event("sched", line))
        val load = ScheduleDiagnostics.shapeLine("load", null, emptyList())
        assertEquals("SCHEDULE_SHAPE at=load sent=- slots=none", ProximityDiagnosticJournal.event("sched", load))
        assertNull("a name", ProximityDiagnosticJournal.event("sched", line.replace(".nu.", ".nTest.")))
        assertNull("trailing data", ProximityDiagnosticJournal.event("sched", "$line 04:00"))
    }

    @Test fun aDeleteSendsTheShapeOfTheCarsFreeSlots() {
        // 0.3.16: the slot written back reads like the car's own free slots (field 18/10: s0.d0.b1 and the empty name).
        val sent = Departures.emptied(Departures.fromSlot(empty, DepartureDraft("Test", "04:00:00", setOf(7), tempC = 21.0)))
        val line = ScheduleDiagnostics.shapeLine("delete", sent, listOf(used, empty))
        assertEquals("schedule shape at=delete sent=2:s0.d0.b1.p0.tp.c7.a0.ne.cs+.v2+.h4+.w+.f- " +
            "slots=1:s1.d1.b1.p0.tp.c7.a2.nu.cs-.v0.h0.w-.f-,2:s0.d0.b1.p0.tp.c0.a0.ne.cs-.v0.h0.w-.f-", line)
        assertEquals("SCHEDULE_SHAPE " + line.removePrefix("schedule shape "), ProximityDiagnosticJournal.event("sched", line))
    }

    @Test fun refusalCodesAndChargeReadBackStayCategorical() {
        assertEquals("refused code=A1001", ScheduleDiagnostics.refused("A1001"))
        assertEquals("refused code=carasleep", ScheduleDiagnostics.refused("car asleep!"))
        assertEquals("refused code=none", ScheduleDiagnostics.refused(null))
        assertEquals("start/app", ScheduleDiagnostics.charge("start", "APP"))
        assertEquals("other/other", ScheduleDiagnostics.charge("weird", "CAR"))
        val charge = ScheduleDiagnostics.line(ScheduleWrites.Kind.CHARGE, null, "refused code=A1001", "-", "failed", 0, "none", "-")
        assertEquals("SCHEDULE_WRITE " + charge.removePrefix("schedule write "), ProximityDiagnosticJournal.event("sched", charge))
    }
}
