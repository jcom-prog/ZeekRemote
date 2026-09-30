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
        private val outcomes = setOf("fine_permission_missing", "provider_cancelled", "provider_failure",
            "provider_no_fix", "accuracy_missing", "mock_rejected", "fix_received", "security_exception",
            "request_timeout", "session_disabled", "window_expired_before_request",
            "window_expired_during_request", "no_fix", "invalid_or_inaccurate_fix",
            "fix_predates_unlock", "future_fix", "accepted")

        internal fun event(area: String, message: String): String? {
            if (area == "prox") {
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
                    else -> null
                }
            }
            if (area == "motion") return when (message) {
                "-> MOVING" -> "MOTION_MOVING"
                "-> STILL" -> "MOTION_STILL"
                else -> null
            }
            return if (area == "svc" && message.startsWith("security sleep:")) "SECURITY_SLEEP" else null
        }
    }
}
