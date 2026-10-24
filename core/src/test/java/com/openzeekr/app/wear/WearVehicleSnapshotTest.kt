package com.openzeekr.app.wear

import com.openzeekr.app.net.model.AdditionalStatusVo
import com.openzeekr.app.net.model.BasicVehicleStatusVo
import com.openzeekr.app.net.model.ClimateStatusVo
import com.openzeekr.app.net.model.DrivingSafetyStatusVo
import com.openzeekr.app.net.model.ElectricStatusVo
import com.openzeekr.app.net.model.VehicleStatusBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wear 2.0: the vehicle snapshot the phone mirrors to the watch - built from the cloud status, numbers only. */
class WearVehicleSnapshotTest {
    private val bean = VehicleStatusBean(
        basicVehicleStatus = BasicVehicleStatusVo(distanceToEmpty = "300"),
        additionalVehicleStatus = AdditionalStatusVo(
            climateStatus = ClimateStatusVo(interiorTemp = "21.5", preClimateActive = true),
            drivingSafetyStatus = DrivingSafetyStatusVo(centralLockingStatus = "1"),
            electricVehicleStatus = ElectricStatusVo(stateOfCharge = "67", distanceToEmptyOnBatteryOnly = "312", chargeLidAcStatus = "1"),
        ),
    )

    @Test fun theSnapshotFollowsThePhoneScreen() {
        val s = WearVehicleSnapshot.from(bean, cloudOnline = true, nowMs = 1_000L)
        assertTrue(s.cloudOnline)
        assertEquals(true, s.locked)
        assertEquals(67, s.soc)
        assertEquals("the EV-only range wins over the basic range", 312, s.rangeKm)
        assertTrue(s.portOpen)
        assertTrue(s.acOn)
        assertEquals(21.5, s.insideTempC!!, 1e-9)
        assertEquals(1_000L, s.updatedAt)
    }

    @Test fun unknownsStayUnknownAndTheBasicRangeIsTheFallback() {
        val b = VehicleStatusBean(basicVehicleStatus = BasicVehicleStatusVo(distanceToEmpty = "300"),
            additionalVehicleStatus = AdditionalStatusVo(drivingSafetyStatus = DrivingSafetyStatusVo(centralLockingStatus = "7")))
        val s = WearVehicleSnapshot.from(b, cloudOnline = true, nowMs = 0L)
        assertNull("an unknown lock code is unknown, not locked", s.locked)
        assertNull(s.soc)
        assertEquals(300, s.rangeKm)
        assertFalse(s.acOn); assertFalse(s.charging); assertFalse(s.portOpen)
        val none = WearVehicleSnapshot.from(null, cloudOnline = true, nowMs = 0L)
        assertFalse("no status: the cloud is not online for the watch", none.cloudOnline)
        assertNull(none.soc)
    }

    @Test fun theSnapshotSurvivesTheDataLayerAsJson() {
        val s = WearVehicleSnapshot.from(bean, cloudOnline = true, nowMs = 42L)
        val back = WearVehicleSnapshot.decode(s.encode())
        assertEquals(s, back)
        assertEquals("garbage decodes to the empty snapshot", WearVehicleSnapshot(), WearVehicleSnapshot.decode("not json"))
        assertFalse(s.encode().contains("lat"))
    }

    @Test fun theControlNamesAreTheWireProtocol() {
        assertEquals(WearControl.CLIMATE_ON, WearControl.valueOf("CLIMATE_ON"))
        assertEquals(7, WearControl.values().size)
    }
}
