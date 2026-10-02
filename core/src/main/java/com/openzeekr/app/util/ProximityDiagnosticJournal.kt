package com.openzeekr.app.util

import java.io.File
import java.io.FileOutputStream

/** Private, bounded event journal. No raw BLE/HTTP payloads or positions are accepted. */
internal class ProximityDiagnosticJournal(private val file: File, private val maxBytes: Long = 262_144L) {
    @Synchronized fun append(time: String, area: String, message: String) {
        val event = event(area, message) ?: return
        val entry = "$time $event\n".toByteArray(Charsets.UTF_8)
        require(entry.size <= maxBytes)
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) error("journal directory unavailable") }
        if (file.exists() && file.length() + entry.size > maxBytes) {
            val previous = File(file.path + ".previous")
            if (previous.exists() && !previous.delete()) error("journal rotation unavailable")
            if (!file.renameTo(previous)) error("journal rotation failed")
        }
        // Close after each event so it is readable without relying on a logcat flush or clean exit.
        FileOutputStream(file, true).use { it.write(entry) }
    }

    companion object {
        private val location = Regex("departure location outcome=([a-z_]+)")
        private val anchor = Regex("departure anchor attempt=([1-3]) outcome=([a-z_]+)")
        private val nearAnchor = Regex("departure near anchor outcome=([a-z_]+)")
        // Whitelisted categorical values only; anything else is not journaled.
        private val autoLockConfirmed = Regex(
            "auto lock confirmed \\((ble-departure|verified-link-departure|walk-away-lock|idle-far-lock)\\) by BLE receipt")
        private val bleDepartureRevoked = Regex(
            "ble departure revoked \\((strong_near|signal_recovered|time_not_advancing)\\)")
        private val vehicleStatus = Regex("vehicle status observed session=([1-9][0-9]{0,18}) approach=([01]) walkAway=([01]) pe=([01]) ps=([01]) central=([0-3])")
        private val outcomes = setOf("fine_permission_missing", "provider_cancelled", "provider_failure",
            "provider_no_fix", "accuracy_missing", "mock_rejected", "fix_received", "security_exception",
            "request_timeout", "session_disabled", "window_expired_before_request",
            "window_expired_during_request", "no_fix", "invalid_or_inaccurate_fix",
            "fix_predates_unlock", "future_fix", "accepted", "coordinates_invalid", "accuracy_invalid",
            "accuracy_over_eight_meters", "fix_time_invalid", "near_unverified", "near_window_expired",
            "near_stale", "near_fix_stale", "waiting_for_pair", "observation_expired",
            "departure_fix_stale", "departure_time_not_advancing", "departure_confirmed",
            "departure_steps_insufficient", "departure_returning", "departure_timing_invalid",
            "departure_accuracy_invalid", "departure_clearance_insufficient")

        internal fun event(area: String, message: String): String? {
            if (area == "dk") {
                val prefix = "vehicle capabilities observed "
                if (message.startsWith(prefix)) {
                    val value = message.removePrefix(prefix)
                    if (value in setOf("identity_missing", "binding_rejected", "response_rejected", "request_failed") ||
                        Regex("noSense=(UNKNOWN|ADVERTISED|NOT_ADVERTISED) peMode=(UNKNOWN|ADVERTISED|NOT_ADVERTISED) calibration=(UNKNOWN|ADVERTISED|NOT_ADVERTISED)").matches(value))
                        return "VEHICLE_CAPABILITIES $value"
                    return null
                }
                vehicleStatus.matchEntire(message)?.let {
                        return "VEHICLE_STATUS " + it.groupValues.drop(1).joinToString(" ")
                    }
                return null
            }
            if (area == "prox") {
                nearAnchor.matchEntire(message)?.let {
                    return it.groupValues[1].takeIf { outcome -> outcome in outcomes }
                        ?.let { outcome -> "NEAR_ANCHOR $outcome" }
                }
                location.matchEntire(message)?.let {
                    return it.groupValues[1].takeIf { outcome -> outcome in outcomes }?.let { outcome -> "LOCATION $outcome" }
                }
                anchor.matchEntire(message)?.let {
                    if (it.groupValues[2] in outcomes) return "ANCHOR ${it.groupValues[1]} ${it.groupValues[2]}"
                }
                return when {
                    message.startsWith("unlock confirmed (") -> "UNLOCK_CONFIRMED"
                    message.startsWith("lock confirmed (") -> "LOCK_CONFIRMED"
                    message.startsWith("lock requested (") -> "LOCK_REQUESTED"
                    message.startsWith("vehicle reports a newer confirmed Lock;") -> "RELOCK_REARMED"
                    message.startsWith("vehicle relock unverified (") -> "RELOCK_REJECTED"
                    message.startsWith("usable departure location anchor unavailable") -> "ANCHOR_UNAVAILABLE"
                    message.startsWith("usable departure location anchor available") -> "ANCHOR_AVAILABLE"
                    message.startsWith("approach-unlock ARM (") -> "UNLOCK_STARTED"
                    message.startsWith("independent departure unverified:") -> "DEPARTURE_UNVERIFIED"
                    message == "ble departure confirmed" -> "BLE_DEPARTURE_CONFIRMED"
                    message == "ble departure route disabled (uncalibrated preset)" -> "BLE_DEPARTURE_ROUTE disabled"
                    message.startsWith("ble departure route enabled (") -> "BLE_DEPARTURE_ROUTE enabled"
                    message == "ble departure route suspended for this epoch after unconfirmed Lock" ->
                        "BLE_DEPARTURE_SUSPENDED"
                    message.startsWith("ble departure lock authorized (") -> "AUTO_LOCK_STARTED ble"
                    message.startsWith("auto lock confirmed (") -> autoLockConfirmed.matchEntire(message)
                        ?.let { "AUTO_LOCK_CONFIRMED ${it.groupValues[1]}" }
                    else -> bleDepartureRevoked.matchEntire(message)
                        ?.let { "BLE_DEPARTURE_REVOKED ${it.groupValues[1]}" }
                }
            }
            if (area == "motion") return when (message) {
                "-> MOVING" -> "MOTION_MOVING"
                "-> STILL" -> "MOTION_STILL"
                else -> null
            }
            if (area == "ble") return when {
                message.startsWith("presence scan ARMED (") -> "BLE_PRESENCE_ARMED"
                message.startsWith("scanning (UNFILTERED,") -> "BLE_SCAN_STARTED"
                message.startsWith("connectGatt ") -> "BLE_CONNECT_STARTED"
                message == "DK session READY" -> "BLE_SESSION_READY"
                message.startsWith("onConnectionStateChange status=133 ") -> "BLE_STATUS_133"
                else -> null
            }
            if (area == "svc") return when (message) {
                "departure location foreground=enabled" -> "LOCATION_FOREGROUND_ENABLED"
                "departure location foreground=unavailable" -> "LOCATION_FOREGROUND_UNAVAILABLE"
                "departure location foreground=rejected" -> "LOCATION_FOREGROUND_REJECTED"
                else -> if (message.startsWith("security sleep:")) "SECURITY_SLEEP" else null
            }
            return null
        }
    }
}
