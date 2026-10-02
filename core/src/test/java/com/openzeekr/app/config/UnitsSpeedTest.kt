package com.openzeekr.app.config

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsSpeedTest {
    @Test fun speedFollowsTheDistanceUnit() {
        assertEquals(100, Units.speedValue(100, "km"))
        assertEquals(62, Units.speedValue(100, "mi"))
        assertEquals("mph", Units.speedUnitLabel("MI"))
        assertEquals("km/h", Units.speedUnitLabel("km"))
    }
}
