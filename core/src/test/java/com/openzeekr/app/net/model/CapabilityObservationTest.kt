package com.openzeekr.app.net.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CapabilityObservationTest {
    private val good = BaseResponse(code = "000000", data = Json.parseToJsonElement(
        """[{"functionCategory":"services","functionCode":"V_DKB_1"}]"""))
    private fun observe(epoch: Long = 1, current: Long = 1, auth: String? = "synthetic-account",
        vin: String? = "synthetic-encrypted-vin", host: String = "example.test", ok: Boolean = true,
        response: BaseResponse<kotlinx.serialization.json.JsonElement>? = good) =
        CapabilityObservation.evaluate(epoch, current, "synthetic-account", auth,
            "synthetic-encrypted-vin", vin, "example.test", host, ok, response)

    @Test fun exactBoundSuccessfulResponseReportsCategoricalEvidence() {
        assertEquals("noSense=ADVERTISED peMode=NOT_ADVERTISED calibration=NOT_ADVERTISED", observe())
    }
    @Test fun epochChangeRejectsIncludingChangeAwayAndBack() {
        assertEquals("binding_rejected", observe(current = 2))
        assertEquals("binding_rejected", observe(current = 3))
    }
    @Test fun actualRequestMustMatchAccountVehicleAndEndpoint() {
        for (auth in listOf(null, "", "other")) assertEquals("binding_rejected", observe(auth = auth))
        for (vin in listOf(null, "", "other")) assertEquals("binding_rejected", observe(vin = vin))
        assertEquals("binding_rejected", observe(host = "other.test"))
    }
    @Test fun HttpAndEnvelopeFailuresNeverBecomePositiveEvidence() {
        assertEquals("response_rejected", observe(ok = false))
        assertEquals("response_rejected", observe(response = null))
        assertEquals("response_rejected", observe(response = good.copy(code = "079021", success = true)))
        assertEquals("response_rejected", observe(response = good.copy(code = null, success = false)))
    }
    @Test fun UnknownDataIsNotUnsupportedAndOutputContainsNoCredentials() {
        val text = observe(response = good.copy(data = null))
        assertEquals("noSense=UNKNOWN peMode=UNKNOWN calibration=UNKNOWN", text)
        assertFalse(text.contains("synthetic"))
        assertFalse(text.contains("example.test"))
    }
}
