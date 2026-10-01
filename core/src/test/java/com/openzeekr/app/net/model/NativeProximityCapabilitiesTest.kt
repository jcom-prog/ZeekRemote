package com.openzeekr.app.net.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NativeProximityCapabilitiesTest {
    private fun parse(s: String) = NativeProximityCapabilities.parse(Json.parseToJsonElement(s))
    @Test fun exactServiceCodesAdvertiseThreeIndependentCapabilities() {
        val s = parse("""[{"functionCategory":"services","functionCode":"V_DKB_1"},
            {"functionCategory":"services","functionCode":"V_DKB_3"}]""")
        assertEquals(CapabilityEvidence.ADVERTISED, s.noSenseLock)
        assertEquals(CapabilityEvidence.NOT_ADVERTISED, s.peMode)
        assertEquals(CapabilityEvidence.ADVERTISED, s.smartCalibration)
    }
    @Test fun parameterCodesWrongCategoryAndSubstringsDoNotAdvertise() {
        val s = parse("""[{"functionCategory":"remote","functionCode":"V_DKB_1"},
            {"functionCategory":"services","functionCode":"prefix_V_DKB_2"},
            {"functionCategory":"services","functionCode":"other","paramCode":"V_DKB_3"}]""")
        assertEquals(NativeProximityCapabilities(CapabilityEvidence.NOT_ADVERTISED,
            CapabilityEvidence.NOT_ADVERTISED, CapabilityEvidence.NOT_ADVERTISED), s)
    }
    @Test fun missingEmptyAndInvalidResponsesRemainUnknown() {
        assertEquals(NativeProximityCapabilities(), NativeProximityCapabilities.parse(null))
        for (s in listOf("null", "[]", "{}", "[42]", "[{\"functionCode\":\"V_DKB_1\"}]",
            "[{\"functionCategory\":42,\"functionCode\":\"V_DKB_1\"}]",
            "{\"unrelated\":[]}")) assertEquals(NativeProximityCapabilities(), parse(s))
    }
    @Test fun incompleteResponseCannotProveAbsenceOfOtherCapabilities() {
        val s = parse("""[{"functionCategory":"services","functionCode":"V_DKB_2"},{}]""")
        assertEquals(CapabilityEvidence.UNKNOWN, s.noSenseLock)
        assertEquals(CapabilityEvidence.ADVERTISED, s.peMode)
        assertEquals(CapabilityEvidence.UNKNOWN, s.smartCalibration)
    }
    @Test fun explicitWrappersWorkButAmbiguousWrappersRemainUnknown() {
        val row = """[{"functionCategory":"services","functionCode":"V_DKB_1"}]"""
        for (key in listOf("list", "records", "data"))
            assertEquals(parse(row), parse("{\"$key\":$row}"))
        assertEquals(NativeProximityCapabilities(), parse("{\"list\":$row,\"records\":$row}"))
    }
    @Test fun advertisedDoesNotRepresentSwitchStateOrCalibrationResult() {
        val s = parse("""[{"functionCategory":"services","functionCode":"V_DKB_1",
            "paramValueUse":"N"}]""")
        assertEquals(CapabilityEvidence.ADVERTISED, s.noSenseLock)
    }
}
