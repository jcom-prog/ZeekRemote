package com.openzeekr.app.remote

import com.openzeekr.app.net.model.AdditionalStatusVo
import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.ClimateStatusVo
import com.openzeekr.app.net.model.CycleTime
import com.openzeekr.app.net.model.VehicleStatusBean
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.4.12: the status read two minutes after a departure plan's time. */
class PlanRunWatchTest {
    private val zone = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun plan(id: Int, sts: Int, vararg days: Pair<Int, String>) = BookingTravelSetting(btId = id, name = "p", sts = sts, displaySts = 1,
        bookingType = 0, temporaryTime = "", cycleTimes = days.map { CycleTime(it.first, 1, it.second) }, preHeatSts = 0)

    @Test fun thePlanMomentIsDueBetweenTwoAndThirtyMinutesAfterIts() {
        // 2026-10-30 is a Friday (day 5); the plan runs Fridays at 11:25 and Mondays at 07:10.
        PlanRunWatch.update(listOf(plan(5, 1, 5 to "11:25:00", 1 to "07:10:00"), plan(2, 0, 5 to "11:00:00")), nowMs = 0L)
        assertNull("too early", PlanRunWatch.due(at(2026, 10, 30, 11, 26), zone))
        assertEquals(5 to at(2026, 10, 30, 11, 25), PlanRunWatch.due(at(2026, 10, 30, 11, 27), zone))
        assertEquals(5 to at(2026, 10, 30, 11, 25), PlanRunWatch.due(at(2026, 10, 30, 11, 55), zone))
        assertNull("too late", PlanRunWatch.due(at(2026, 10, 30, 11, 56), zone))
        assertNull("another weekday", PlanRunWatch.due(at(2026, 10, 29, 11, 27), zone))
        assertNull("a plan that is off", PlanRunWatch.due(at(2026, 10, 30, 11, 3), zone))
        // just after midnight the previous day's late plan is still found
        PlanRunWatch.update(listOf(plan(3, 1, 4 to "23:50:00")), nowMs = 0L)
        assertEquals(3 to at(2026, 10, 29, 23, 50), PlanRunWatch.due(at(2026, 10, 30, 0, 5), zone))
    }

    @Test fun theLineCarriesLevelsAndTheClimateFlagOnly() {
        assertEquals("plan run check slot=5 status=unavailable", PlanRunWatch.line(5, null))
        val st = VehicleStatusBean(additionalVehicleStatus = AdditionalStatusVo(climateStatus = ClimateStatusVo(
            preClimateActive = true, drvHeatSts = "3", passHeatingSts = "0", rlHeatingSts = null, rrHeatingSts = "12")))
        assertEquals("plan run check slot=5 status=read seats=3/0/0/9 climate=on", PlanRunWatch.line(5, st))
        assertEquals("plan run check slot=1 status=read seats=0/0/0/0 climate=?", PlanRunWatch.line(1, VehicleStatusBean()))
    }
}
