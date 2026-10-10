package com.openzeekr.app.net

import com.openzeekr.app.net.model.VehicleStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumptionCandidatesTest {
    @Test fun logsOnlyNumericConsumptionLikeFieldsNeverIdentifiersOrLocation() {
        val root = Json.parseToJsonElement("""
            {"vin":"X1","basicVehicleStatus":{"position":{"latitude":"52.1","longitude":"5.1"},"distanceToEmpty":"157"},
             "additionalVehicleStatus":{"electricVehicleStatus":{"averPowerConsumption":"30.9","chargeLevel":"35",
               "tripAvgConsumption":"21.3","tripId":"7"},"runningStatus":{"aveFuelConsumption":"0","tripMeter1":"812.4",
               "engineStatus":"off"}}}
        """.trimIndent()).jsonObject
        assertEquals(
            "additionalVehicleStatus.electricVehicleStatus.averPowerConsumption=30.9 " +
                "additionalVehicleStatus.electricVehicleStatus.tripAvgConsumption=21.3 " +
                "additionalVehicleStatus.runningStatus.aveFuelConsumption=0 " +
                "additionalVehicleStatus.runningStatus.tripMeter1=812.4 " +
                "basicVehicleStatus.distanceToEmpty=157",
            VehicleStatus.consumptionCandidates(root))
    }
}
