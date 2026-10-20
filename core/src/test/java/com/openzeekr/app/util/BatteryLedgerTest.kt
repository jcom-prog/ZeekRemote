package com.openzeekr.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measurement version (battery plan 20/10): the day book counts only unplugged drain, splits the
 *  days automatically, and books pulses by current difference - the three measurements of the plan. */
class BatteryLedgerTest {
    private fun s(tMin: Long, mah: Long, plugged: Boolean = false, screenOn: Boolean = false) =
        BatteryLedger.Sample(tMin * 60_000L, mah * 1000L, plugged, screenOn)

    @Test fun unpluggedDrainAndScreenOffHoursAreBooked() {
        val l = BatteryLedger()
        assertNull(l.sample(100, s(0, 4000)))
        assertNull(l.sample(100, s(60, 3950)))             // 50 mAh in één scherm-uit-uur
        assertNull(l.sample(100, s(120, 3940, screenOn = true)))
        val line = l.sample(101, s(130, 3939))!!           // nieuwe dag sluit de oude af
        assertTrue(line, "usedMah=60" in line)
        assertTrue(line, "screenOffH=2.0" in line)
        assertTrue(line, "perScreenOffH=30.0" in line)
        assertTrue(line, "mode=pulse" in line)             // dag 100 is even
    }

    @Test fun chargingAndGaugeJumpsNeverCount() {
        val l = BatteryLedger()
        l.sample(101, s(0, 3000))
        l.sample(101, s(30, 3100))                          // lading stijgt (sprong/lader): telt niet
        l.sample(101, s(60, 3080, plugged = true))          // aan de lader: interval telt niet
        l.sample(101, s(120, 4000, plugged = true))
        l.sample(101, s(121, 4000))                         // net van de lader
        l.sample(101, s(181, 3990))                         // 10 mAh in 1 uur
        val line = l.sample(102, s(182, 3990))!!
        assertTrue(line, "usedMah=10" in line)
        assertTrue(line, "mode=off" in line)                // dag 101 is oneven
    }

    @Test fun pulsesAreBookedByCurrentDifference() {
        val l = BatteryLedger()
        l.sample(102, s(0, 4000))
        l.onSignificantFire(); l.onSignificantFire()
        // 10 s puls op 60 mA tegen 24 mA rust: (36 mA x 10 s) = 0,1 mAh
        l.onPulse(10_000L, meanUa = 60_000.0, baselineUa = 24_000.0, walking = true, headStartS = 7.0)
        l.onPulse(10_000L, meanUa = 60_000.0, baselineUa = 24_000.0, walking = false, headStartS = null)
        val line = l.summary()
        assertTrue(line, "fires=2" in line)
        assertTrue(line, "pulses=2" in line)
        assertTrue(line, "pulseS=20" in line)
        assertTrue(line, "pulseMah=0.20" in line)
        assertTrue(line, "walking=1" in line)
        assertTrue(line, "headStartS=7.0" in line)
    }

    @Test fun aNewDayStartsItsOwnBooks() {
        val l = BatteryLedger()
        l.sample(103, s(0, 4000))
        l.onSignificantFire()
        val closed = l.sample(104, s(10, 3999))!!
        assertTrue(closed, "fires=1" in closed)
        val fresh = l.summary()
        assertTrue(fresh, "fires=0" in fresh)
        assertEquals(true, l.pulseDay(104))
        assertEquals(false, l.pulseDay(103))
    }
}
