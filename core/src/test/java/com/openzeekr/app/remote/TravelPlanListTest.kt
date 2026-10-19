package com.openzeekr.app.remote

import com.openzeekr.app.util.ProximityDiagnosticJournal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelPlanListTest {

    private fun arr(s: String) = Json.parseToJsonElement(s) as JsonArray

    private val freeSlot = """{"btId":2,"name":"BookTravel","sts":0,"displaySts":0,"bookingType":1,
        "temporaryTime":"2099:12:12:08:00:00","cycleTimes":[],"preHeatSts":0,
        "heatSettings":[{"duration":15,"level":9,"location":1,"sts":0}]}"""
    private val plan = """{"btId":1,"name":"Plan A","sts":1,"displaySts":1,"bookingType":0,
        "temporaryTime":"2099:12:12:08:00:00","cycleTimes":[{"day":2,"sts":1,"time":"06:40:00"}],"preHeatSts":0,
        "csSetting":{"duration":15,"sts":1,"temp":"21.0"},"someNewField":true}"""

    @Test fun aListThatReadsAsBeforeIsUnchanged() {
        val r = TravelPlanList.read(arr("[$plan,$freeSlot]"))
        assertEquals(listOf(1, 2), r.plans.map { it.btId })
        assertEquals(emptySet<Int>(), r.readOnly)
        assertEquals(0 to 0, r.filled to r.unreadable)
        assertEquals("Plan A", r.plans[0].name)
        assertEquals(9, r.plans[1].heatSettings.single().level)
        assertEquals(listOf<Int>(), TravelPlanList.read(null).plans.map { it.btId })
    }

    @Test fun onePlanWithEmptyFieldsNoLongerLosesTheWholeList() {
        // Field 18/10: a plan made in the official app did not show here. A null or missing field used to fail the whole
        // list; now the plan is read with that field empty, shown, and never written.
        val odd = """{"btId":3,"name":"Test","sts":1,"displaySts":1,"bookingType":0,"temporaryTime":null,
            "cycleTimes":[{"day":7,"sts":1,"time":"04:00:00"}],
            "csSetting":{"duration":15,"sts":1,"temp":null},"heatSettings":[{"duration":15,"level":null,"location":1,"sts":1}]}"""
        val r = TravelPlanList.read(arr("[$plan,$freeSlot,$odd]"))
        assertEquals(listOf(1, 2, 3), r.plans.map { it.btId })
        assertEquals(setOf(3), r.readOnly)
        assertEquals(1 to 0, r.filled to r.unreadable)
        val p = r.plans[2]
        assertEquals("Test", p.name)
        assertEquals(setOf(7), p.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSet())
        assertFalse("a plan in use, not a free slot", Departures.isEmptySlot(p))
        assertEquals(listOf(p), Departures.visible(r.plans.filter { it.btId == 3 }))
    }

    @Test fun aPlanWithoutItsDisplayStateIsShown() {
        // 0.3.20: off and hidden is a plan the official app deleted; a plan that does not say whether it is shown is not
        // taken for one.
        val noDisplay = """{"btId":3,"name":"Test","sts":0,"bookingType":0,"temporaryTime":"2099:12:12:08:00:00",
            "cycleTimes":[{"day":7,"sts":1,"time":"04:00:00"}],"preHeatSts":0}"""
        val r = TravelPlanList.read(arr("[$plan,$freeSlot,$noDisplay]"))
        assertEquals(setOf(3), r.readOnly)
        assertEquals(listOf(1, 3), Departures.visible(r.plans).map { it.btId })
    }

    @Test fun aPlanThatCannotBeReadAtAllStillHoldsItsSlot() {
        val broken = """{"btId":4,"name":"Holiday","sts":"on?","cycleTimes":"weekly"}"""
        val noId = """{"name":"Mystery","sts":1}"""
        val r = TravelPlanList.read(arr("[$plan,$broken,\"junk\",$noId]"))
        assertEquals(listOf(1, 4, -3, -4), r.plans.map { it.btId })
        assertEquals(setOf(4, -3, -4), r.readOnly)
        assertEquals("Holiday", r.plans[1].name)
        assertEquals("Departure", r.plans[2].name)
        assertTrue("no read-only plan is ever a free slot", r.plans.filter { it.btId in r.readOnly }.none(Departures::isEmptySlot))
        // A free slot that reads in part is not offered for a new plan either (the screen leaves read-only slots out).
        val partFree = """{"btId":5,"name":"BookTravel","sts":0,"displaySts":0,"bookingType":1,"temporaryTime":null,"cycleTimes":[],"preHeatSts":0}"""
        val q = TravelPlanList.read(arr("[$plan,$partFree]"))
        assertEquals(setOf(5), q.readOnly)
        assertNull(Departures.freeSlotId(q.plans.filter { it.btId !in q.readOnly }))
    }

    @Test fun everyReadLeavesACategoricalLine() {
        val ok = ScheduleDiagnostics.listLine(TravelPlanList.Read(emptyList(), filled = 1, unreadable = 2), null)
        assertEquals("schedule list read=ok plans=0 filled=1 unreadable=2", ok)
        assertEquals("SCHEDULE_LIST read=ok plans=0 filled=1 unreadable=2", ProximityDiagnosticJournal.event("sched", ok))
        for ((e, why) in listOf(java.io.IOException("x") to "network", kotlinx.serialization.SerializationException("x") to "parse",
            IllegalStateException("x") to "refused", RuntimeException("x") to "other")) {
            val line = ScheduleDiagnostics.listLine(null, e)
            assertEquals("schedule list read=failed reason=$why", line)
            assertEquals("SCHEDULE_LIST read=failed reason=$why", ProximityDiagnosticJournal.event("sched", line))
        }
        assertNull("no free text", ProximityDiagnosticJournal.event("sched", "schedule list read=failed reason=car asleep"))
    }
}
