package com.openzeekr.app.net.model

/** Internal equality checks only; credentials never become output or persisted identity. */
internal object CapabilityObservation {
    fun evaluate(epoch: Long, currentEpoch: Long, expectedAuth: String, actualAuth: String?,
        expectedVin: String, actualVin: String?, expectedHost: String, actualHost: String,
        httpOk: Boolean, response: BaseResponse<kotlinx.serialization.json.JsonElement>?
    ): String {
        if (epoch != currentEpoch || expectedAuth.isBlank() || expectedVin.isBlank() ||
            expectedAuth != actualAuth || expectedVin != actualVin || expectedHost != actualHost)
            return "binding_rejected"
        if (!httpOk || response == null ||
            (response.code != null && response.code != "000000") ||
            !(response.success || response.code == "000000")) return "response_rejected"
        val c = NativeProximityCapabilities.parse(response.data)
        return "noSense=${c.noSenseLock} peMode=${c.peMode} calibration=${c.smartCalibration}"
    }
}
