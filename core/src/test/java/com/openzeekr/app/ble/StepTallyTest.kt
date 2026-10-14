package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepTallyTest {
    @Test fun `no step sensor at all - unavailable`() {
        assertNull(StepTally().total(detectorAvailable = false))
    }

    @Test fun `detector present but silent screen-off - zero, as before`() {
        assertEquals(0L, StepTally().total(detectorAvailable = true))
    }

    @Test fun `hub counter counts from its first reading (field 14-10 shape - detector silent)`() {
        val t = StepTally()
        t.onCounter(51_200)           // baseline at start
        t.onCounter(51_241)           // ~30 m walked with the screen off
        assertEquals(41L, t.total(detectorAvailable = true))
        assertEquals("detector=0 counter=41", t.summary())
    }

    @Test fun `the larger source wins, so neither can lower the count`() {
        val t = StepTally()
        t.onCounter(100)
        repeat(12) { t.onDetectorStep() }
        t.onCounter(105)              // hub lags behind the detector
        assertEquals(12L, t.total(true))
        t.onCounter(130)
        assertEquals(30L, t.total(true))
    }

    @Test fun `a hub reset (reboot) does not lose or invent steps`() {
        val t = StepTally()
        t.onCounter(5_000); t.onCounter(5_020)
        t.onCounter(3)                // total restarted
        assertEquals(20L, t.counterSteps())
        t.onCounter(13)
        assertEquals(30L, t.counterSteps())
    }

    @Test fun `repeated identical flush results add nothing`() {
        val t = StepTally()
        t.onCounter(700); t.onCounter(710); t.onCounter(710); t.onCounter(710)
        assertEquals(10L, t.total(true))
    }
}
