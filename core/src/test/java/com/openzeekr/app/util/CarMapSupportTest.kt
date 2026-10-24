package com.openzeekr.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.4.0e: the pin sits with its tip on the car's point; the position's age reads naturally. */
class CarMapSupportTest {
    @Test fun thePinIsPlacedByItsTip() {
        assertEquals(100 - 20 to 200 - 40, CarMapSupport.pinTopLeft(100f, 200f, 40f))
        assertEquals(0 to -40, CarMapSupport.pinTopLeft(20f, 0f, 40f))
    }

    @Test fun theAgeTextFollowsTheMinutesHoursAndDays() {
        val now = 1_000_000_000L
        assertNull(CarMapSupport.positionAgeText(null, now))
        assertNull("a timestamp from the future is not shown", CarMapSupport.positionAgeText(now + 5_000L, now))
        assertEquals("zojuist", CarMapSupport.positionAgeText(now - 30_000L, now))
        assertEquals("7 min geleden", CarMapSupport.positionAgeText(now - 7L * 60_000L, now))
        assertEquals("3 uur geleden", CarMapSupport.positionAgeText(now - 3L * 3_600_000L - 10L * 60_000L, now))
        assertEquals("meer dan een dag geleden", CarMapSupport.positionAgeText(now - 26L * 3_600_000L, now))
    }

    @Test fun theRoadNameLayersAreRecognisedByTheirIds() {
        assertEquals(true, CarMapSupport.isRoadLabelLayer("highway_name_other"))
        assertEquals(true, CarMapSupport.isRoadLabelLayer("road_label"))
        assertEquals(false, CarMapSupport.isRoadLabelLayer("highway_shield"))
        assertEquals(false, CarMapSupport.isRoadLabelLayer("road_oneway"))
        assertEquals(false, CarMapSupport.isRoadLabelLayer("poi_z14"))
        assertEquals(false, CarMapSupport.isRoadLabelLayer("building-3d"))
    }

    @Test fun theAddressLineIsTheFirstUsableLineCleaned() {
        assertEquals("Straat 1, 1234 AB Plaats, Nederland", CarMapSupport.addressLine(listOf(" ", null, "  Straat 1,  1234 AB Plaats,\n Nederland ")))
        assertNull(CarMapSupport.addressLine(emptyList()))
        assertNull(CarMapSupport.addressLine(listOf(null, "")))
    }
}
