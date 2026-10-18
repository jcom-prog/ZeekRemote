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
            "readback=1:on/used/2,2:off/empty/0 found=slot1 name=same days=same time=other on=same", line)
        for (privateValue in listOf("Plan A", "06:40", "07:00", Departures.PLACEHOLDER_TIME)) assertFalse(privateValue, line.contains(privateValue))
        assertEquals("SCHEDULE_WRITE " + line.removePrefix("schedule write "), ProximityDiagnosticJournal.event("sched", line))
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
