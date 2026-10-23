package com.openzeekr.app.net.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small fixes ported from upstream OpenZeekr 0.1.8/0.1.9. */
class UpstreamPortsTest {
    @Test fun threePhaseAcPowerUsesLineToLineFactor() {
        val p = ElectricStatusVo(chargeIAct = "16", chargeUAct = "400").chargePowerW!!
        assertEquals(11_085.0, p, 1.0) // sqrt(3) * 400 V * 16 A, not 6.4 kW
    }

    @Test fun singlePhaseAcPowerIsThePlainProduct() {
        assertEquals(3_680.0, ElectricStatusVo(chargeIAct = "16", chargeUAct = "230").chargePowerW!!, 0.01)
    }

    @Test fun dcPowerWinsAndZeroOrMissingIsNull() {
        val dc = ElectricStatusVo(chargeIAct = "0", chargeUAct = "0", dcChargePileIAct = "200", dcChargePileUAct = "400")
        assertEquals(80_000.0, dc.chargePowerW!!, 0.01)
        assertNull(ElectricStatusVo(chargeIAct = "0", chargeUAct = "230").chargePowerW)
        assertNull(ElectricStatusVo().chargePowerW)
        assertFalse(ElectricStatusVo(chargeIAct = "0", chargeUAct = "230").chargingActive)
    }

    /** 0.3.30 (upstream 0.2.2): the DC pile pair reaches the model through the real status parser, as magnitudes. */
    @Test fun dcFastChargePowerIsReadFromTheStatusAndSigned() {
        val root = Json.parseToJsonElement("""{"additionalVehicleStatus":{"electricVehicleStatus":{
            "chargerState":"3","chargeIAct":"0","chargeUAct":"0","dcChargePileIAct":"-150","dcChargePileUAct":"380"}}}""")
        val e = VehicleStatus.parse(root as kotlinx.serialization.json.JsonObject).additionalVehicleStatus?.electricVehicleStatus!!
        assertEquals("the signed pile current is a magnitude", 57_000.0, e.chargePowerW!!, 0.01)
        assertTrue(e.chargingActive)
        val ac = Json.parseToJsonElement("""{"additionalVehicleStatus":{"electricVehicleStatus":{"chargeIAct":"16","chargeUAct":"230"}}}""")
        assertEquals(3_680.0, VehicleStatus.parse(ac as kotlinx.serialization.json.JsonObject)
            .additionalVehicleStatus?.electricVehicleStatus!!.chargePowerW!!, 0.01)
    }

    private fun caps(json: String) = VehicleCapabilityParse.parse(Json.parseToJsonElement(json))

    @Test fun rearSeatVentilationOnlyWhenARearPositionIsAdvertised() {
        val frontOnly = caps("""[{"functionCode":"seat_ventilation","paramValueUse":"Y"},
            {"functionCode":"x","paramCode":"new_seat_ventilation_position","paramValueCode":"main_driver_seat"},
            {"functionCode":"x","paramCode":"new_seat_ventilation_position","paramValueCode":"copilot_seat"}]""")
        assertTrue(frontOnly.seatCool)
        assertFalse(frontOnly.rearSeatCool)
        val withRear = caps("""[{"functionCode":"seat_ventilation","paramValueUse":"Y"},
            {"functionCode":"x","paramCode":"new_seat_ventilation_position","paramValueCode":"second_row_left"}]""")
        assertTrue(withRear.rearSeatCool)
        assertFalse("unknown list fails closed", VehicleCapabilities.UNKNOWN.rearSeatCool)
    }

    @Test fun otaStatusParsesVersionsAndTolerantlyHandlesMissingFields() {
        val st = Ota.parse(Json.parseToJsonElement("""{"currentVehicleVersion":{"displayVersion":"1.2.0"},
            "targetVehicleVersion":{"displayVersion":"1.3.0","bssPackageReleaseNotes":[{"description":" Faster charging "}]},
            "hasNewAssignment":true}"""))
        assertEquals("1.2.0", st.currentVersion)
        assertEquals("1.3.0", st.targetVersion)
        assertTrue(st.updateAvailable)
        assertEquals(listOf("Faster charging"), st.releaseNotes)
        val none = Ota.parse(Json.parseToJsonElement("""{"currentVehicleVersion":{"displayVersion":"1.2.0"}}"""))
        assertFalse(none.updateAvailable)
        assertNull(Ota.parse(null).currentVersion)
        val same = Ota.parse(Json.parseToJsonElement(
            """{"currentVehicleVersion":{"displayVersion":"1.3.0"},"targetVehicleVersion":{"displayVersion":"1.3.0"}}"""))
        assertFalse("installed target is not an update", same.updateAvailable)
    }

    @Test fun vehicleListCarriesPlatformCodesForTheStatusQuery() {
        val v = VehicleGarage.parse(Json.parseToJsonElement("""[{"vin":"X","appModelCode":"CC1E","appYearCode":"2025"}]"""))!!
        assertEquals("CC1E", v.appModelCode)
        assertEquals("2025", v.appYearCode)
    }
}
