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
            "auto lock confirmed \\((ble-departure|verified-link-departure|walk-away-lock|idle-far-lock)\\): the car reports locked")
        private val unlockArm = Regex("^approach-unlock ARM \\(rssi=(-[0-9]{2,3}) .* route=(presence|approach|door|still|" +
            "deep_return|deep_window|manual_return|at_car|away_return|away_return_steps|self_lock_return|walk_back|none)" +
            "(?: screen=(true|false|\\?))?\\)")
        private val bleLockAuthorized = Regex("^ble departure lock authorized \\(rssi=(-[0-9]{2,3}) kind=(walk|very_deep|" +
            "very_deep_gradual|none)\\)")
        private val lockAlarm = Regex("lock alarm (raised|unavailable|suppressed) \\(([A-Z_]{1,40})\\)")
        private val bleDepartureRevoked = Regex(
            "ble departure revoked \\((strong_near|signal_recovered|time_not_advancing)\\)")
        private val vehicleStatus = Regex("vehicle status observed session=([1-9][0-9]{0,18}) approach=([01]) walkAway=([01]) pe=([01]) ps=([01]) central=([0-3])")
        // Measurement log (0.3.9): when the step sensors deliver - counts and seconds only.
        private const val STEP_TRACE = "steps \\+([0-9]{1,5}) in ([0-9]{1,5}[.,][0-9]) s \\(([0-9]{1,4}) updates?, " +
            "longest wait ([0-9]{1,5}[.,][0-9]) s\\)"
        private val stepSensor = Regex("step (counter|detector): $STEP_TRACE")
        private val stepSummary = Regex("steps \\+([0-9]{1,5}) in ([0-9]{1,5}[.,][0-9]) s \\(([0-9]{1,4}) updates?, " +
            "longest wait ([0-9]{1,5}[.,][0-9]) s\\); detector=([0-9]{1,7}) counter=([0-9]{1,7}|none)")
        // Activity Recognition (Google Play) transitions: the activity type and enter/exit only.
        private val arTransition = Regex("AR transition: activity=([0-9]{1,2}) trans=([01])")
        private val AR_ACTIVITIES = mapOf("0" to "vehicle", "1" to "bicycle", "2" to "on_foot", "3" to "still",
            "7" to "walking", "8" to "running")
        // One schedule write (0.3.8): categories and slot numbers only - no name, time or temperature fits.
        private const val SCHEDULE_SLOT = "[0-9]{1,2}:(?:on|off)/(?:used|empty)/[0-7]"
        private val scheduleWrite = Regex("schedule write (kind=(?:create|edit|switch|delete|charge) " +
            "slot=(?:[0-9]{1,2}|-) sent=(?:accepted|error|refused code=[A-Za-z0-9_.-]{1,16}) session=(?:yes|no|-) " +
            "result=(?:confirmed|pending|failed) polls=[0-9]{1,3} " +
            "readback=(?:none|(?:start|stop|other)/(?:app|other)|$SCHEDULE_SLOT(?:,$SCHEDULE_SLOT){0,9}) " +
            "found=(?:-|none|slot[0-9]{1,2} name=same days=(?:same|other) time=(?:same|other) on=(?:same|other)))")
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
                if (Regex("control 0x02: received, but no locked status within [0-9]{1,5} ms -> NOT_EXECUTED").matches(message))
                    return "LOCK_NOT_EXECUTED"
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
                    // Which rule opened, at which smoothed signal (a number, never a position).
                    message.startsWith("approach-unlock ARM (") -> unlockArm.find(message)
                        ?.let { m -> "UNLOCK_STARTED ${m.groupValues[2]} ${m.groupValues[1]}" +
                            m.groupValues[3].let { if (it.isEmpty()) "" else " screen=$it" } } ?: "UNLOCK_STARTED"
                    message.startsWith("car locked itself (") -> "CAR_SELF_LOCK"
                    message.startsWith("manual Lock not executed:") -> "MANUAL_LOCK_NOT_EXECUTED"
                    Regex("(ble-departure|verified-link-departure|walk-away-lock|idle-far-lock|far-no-motion): " +
                        "locked via cloud; a newer cloud report confirms it").matches(message) -> "AUTO_LOCK_CLOUD_VERIFIED"
                    Regex("(ble-departure): cloud Lock backed by a fresh BLE departure proof").matches(message) ->
                        "CLOUD_LOCK_BLE_BACKED"
                    // 0.3.9: why an alarm episode ended (the user back at the car, or a new departure).
                    message == "strong signal at the car after an alarm -> user back; alarm silenced" -> "ALARM_EPISODE_CLOSED strong"
                    message == "presence hit at the car after an alarm (user back) -> alarm episode closed" -> "ALARM_EPISODE_CLOSED presence"
                    message == "sustained signal at the car after an alarm (user back) -> alarm episode closed" -> "ALARM_EPISODE_CLOSED signal"
                    Regex("link back [0-9]{1,6} s, then lost out of range -> alarm episode closed").matches(message) ->
                        "ALARM_EPISODE_CLOSED link"
                    message.startsWith("manual cloud Lock verified (") -> "MANUAL_CLOUD_LOCK_VERIFIED"
                    message == "car reported locked -> 'Auto gesloten' notification" -> "CAR_LOCKED_CHIME"
                    message.startsWith("car reported unlocked without our Unlock (") -> "CAR_REOPENED"
                    message.startsWith("phone far from the unlocked car") -> "STATIONARY_FAR_ALARM"
                    message.startsWith("armed idle far read ") -> "IDLE_FAR_FAST"
                    message.startsWith("measurement mode on") -> "MEASURE_MODE on"
                    message == "measurement mode off" -> "MEASURE_MODE off"
                    message.startsWith("independent departure unverified:") -> "DEPARTURE_UNVERIFIED"
                    message == "ble departure confirmed" -> "BLE_DEPARTURE_CONFIRMED"
                    message.startsWith("lock alarm ") -> lockAlarm.matchEntire(message)
                        ?.let { "LOCK_ALARM ${it.groupValues[1]} ${it.groupValues[2]}" }
                    message == "ble departure route disabled (uncalibrated preset)" -> "BLE_DEPARTURE_ROUTE disabled"
                    message.startsWith("ble departure route enabled (") -> "BLE_DEPARTURE_ROUTE enabled"
                    message == "ble departure route suspended for this epoch after unconfirmed Lock" ->
                        "BLE_DEPARTURE_SUSPENDED"
                    message.startsWith("ble departure lock authorized (") -> bleLockAuthorized.find(message)
                        ?.let { "AUTO_LOCK_STARTED ble ${it.groupValues[2]} ${it.groupValues[1]}" } ?: "AUTO_LOCK_STARTED ble"
                    message.startsWith("auto lock confirmed (") -> autoLockConfirmed.matchEntire(message)
                        ?.let { "AUTO_LOCK_CONFIRMED ${it.groupValues[1]}" }
                    else -> bleDepartureRevoked.matchEntire(message)
                        ?.let { "BLE_DEPARTURE_REVOKED ${it.groupValues[1]}" }
                }
            }
            if (area == "motion") return when (message) {
                "-> MOVING" -> "MOTION_MOVING"
                "-> STILL" -> "MOTION_STILL"
                // Which sensors drive MOVING/STILL on this phone (0.3.9 measurement log).
                "step counter active" -> "STEP_COUNTER_SENSOR active"
                "step counter unavailable" -> "STEP_COUNTER_SENSOR unavailable"
                "started via trigger sensors" -> "MOTION_SOURCE sensors"
                "started via wake-up step detector (no trigger sensors)" -> "MOTION_SOURCE step"
                "no motion source at all — RSSI-steadiness only" -> "MOTION_SOURCE none"
                // Measurement log (0.3.9): step delivery, numbers only.
                else -> if (message.startsWith("started via Activity Recognition")) "MOTION_SOURCE activity"
                else stepSensor.matchEntire(message)?.let { m ->
                    "STEP_${m.groupValues[1].uppercase()} +${m.groupValues[2]} in=${m.groupValues[3]}s " +
                        "updates=${m.groupValues[4]} longest=${m.groupValues[5]}s" }
                    ?: stepSummary.matchEntire(message)?.let { m ->
                        "STEP_SUMMARY +${m.groupValues[1]} in=${m.groupValues[2]}s updates=${m.groupValues[3]} " +
                            "longest=${m.groupValues[4]}s detector=${m.groupValues[5]} counter=${m.groupValues[6]}" }
                    ?: arTransition.matchEntire(message)?.let {
                        "AR_TRANSITION ${AR_ACTIVITIES[it.groupValues[1]] ?: "other"} ${if (it.groupValues[2] == "0") "enter" else "exit"}" }
            }
            if (area == "ble") return when {
                message.startsWith("presence scan ARMED (") -> "BLE_PRESENCE_ARMED"
                message.startsWith("scanning (UNFILTERED,") -> "BLE_SCAN_STARTED"
                message.startsWith("connectGatt ") -> "BLE_CONNECT_STARTED"
                message == "DK session READY" -> "BLE_SESSION_READY"
                message.startsWith("onConnectionStateChange status=133 ") -> "BLE_STATUS_133"
                else -> null
            }
            if (area == "sched") return scheduleWrite.matchEntire(message)?.let { "SCHEDULE_WRITE ${it.groupValues[1]}" }
            if (area == "ctl") return Regex("(LOCK|UNLOCK): cloud request failed \\(key link ([A-Z_]{1,20})\\)")
                .matchEntire(message)?.let { "CLOUD_${it.groupValues[1]}_FAILED ${it.groupValues[2]}" }
            if (area == "svc") return when (message) {
                "departure location foreground=enabled" -> "LOCATION_FOREGROUND_ENABLED"
                "departure location foreground=unavailable" -> "LOCATION_FOREGROUND_UNAVAILABLE"
                "departure location foreground=rejected" -> "LOCATION_FOREGROUND_REJECTED"
                "screen on" -> "SCREEN on"
                "screen off" -> "SCREEN off"
                else -> if (message.startsWith("security sleep:")) "SECURITY_SLEEP" else null
            }
            return null
        }
    }
}
