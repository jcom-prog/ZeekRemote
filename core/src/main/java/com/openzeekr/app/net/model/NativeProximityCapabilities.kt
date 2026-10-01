package com.openzeekr.app.net.model

import kotlinx.serialization.json.*

/** Evidence from the vehicle capability response, never a proximity/Lock authorization.
 * ADVERTISED does not mean enabled, calibrated, accepted or physically working.
 * The caller must bind the response to the current vehicle/account before using it.
 */
internal enum class CapabilityEvidence { UNKNOWN, ADVERTISED, NOT_ADVERTISED }

internal data class NativeProximityCapabilities(
    val noSenseLock: CapabilityEvidence = CapabilityEvidence.UNKNOWN,
    val peMode: CapabilityEvidence = CapabilityEvidence.UNKNOWN,
    val smartCalibration: CapabilityEvidence = CapabilityEvidence.UNKNOWN,
) {
    companion object {
        fun parse(data: JsonElement?): NativeProximityCapabilities {
            val arrays = when (data) {
                is JsonArray -> listOf(data)
                is JsonObject -> listOf("list", "records", "data")
                    .mapNotNull { data[it] as? JsonArray }
                else -> emptyList()
            }
            if (arrays.size != 1 || arrays.single().isEmpty()) return NativeProximityCapabilities()
            var complete = true
            val advertised = mutableSetOf<String>()
            for (element in arrays.single()) {
                val row = element as? JsonObject
                fun text(key: String): String? = (row?.get(key) as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                val category = text("functionCategory")
                val code = text("functionCode")
                if (category == null || code == null) { complete = false; continue }
                if (category == "services") advertised += code
            }
            fun evidence(code: String) = when {
                code in advertised -> CapabilityEvidence.ADVERTISED
                complete -> CapabilityEvidence.NOT_ADVERTISED
                else -> CapabilityEvidence.UNKNOWN
            }
            return NativeProximityCapabilities(evidence("V_DKB_1"), evidence("V_DKB_2"),
                evidence("V_DKB_3"))
        }
    }
}
