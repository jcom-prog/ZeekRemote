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
        // 0.3.19: which check kept an automatic Lock back (a category, never the readings behind it).
        private val lockVetoed = Regex("(ble-departure|verified-link-departure|walk-away-lock|idle-far-lock|far-no-motion): " +
            "(?:cloud )?Lock vetoed - (fresh reading|signal back in the lock range|only [0-9]{1,5} steps since the return to the car|" +
            "only [0-9]{1,5} steps since the car|strong reading|walking back)\\b.*")
        private val returnToCar = Regex("return to the car without a strong reading \\(rssi=(-[0-9]{2,3})\\) .*")
        // 0.3.29: an alarm kept sounding because the signal alone said "at the car" - the level, the motion state, a category.
        private val alarmKept = Regex("alarm kept: signal at the car \\(rssi=(-[0-9]{2,3}) motion=([A-Z_]{1,12}) steps=(none|same)\\) " +
            "without walking evidence")
        // 0.3.28: a signal return after a Lock held for lack of walking evidence - the level, the motion state, a category.
        private val returnHeld = Regex("return after Lock held \\(rssi=(-[0-9]{2,3}) motion=([A-Z_]{1,12}) steps=(none|same)\\): " +
            "no walking evidence -> stays locked")
        private val bleDepartureRevoked = Regex(
            "ble departure revoked \\((strong_near|signal_recovered|time_not_advancing)\\)")
        private val vehicleStatus = Regex("vehicle status observed session=([1-9][0-9]{0,18}) approach=([01]) walkAway=([01]) pe=([01]) ps=([01]) central=([0-3])")
        // Measurement log (0.3.9): when the step sensors deliver - counts and seconds only.
        private const val STEP_TRACE = "steps \\+([0-9]{1,5}) in ([0-9]{1,5}[.,][0-9]) s \\(([0-9]{1,4}) updates?, " +
            "longest wait ([0-9]{1,5}[.,][0-9]) s\\)"
        private val stepSensor = Regex("step (counter|detector): $STEP_TRACE")
        // 0.3.26: the own tracker's window line and the battery day line - counts, seconds and milliamp-hours only.
        private val ownWindow = Regex("own tracker OFF \\(([a-z ]{1,32})\\): own steps=([0-9]{1,6}) samples=[0-9]{1,9} " +
            "turn=(none|[0-9]{1,6}s ago) secs=([0-9]{1,6}) detector=([0-9]{1,9}) counter=([0-9]{1,9}|none) " +
            "ar=([A-Z_]{1,12}) screen=(on|off)")
        private val batteryDay = Regex("battery day: (mode=(?:pulse|off) fires=[0-9]{1,6} pulses=[0-9]{1,6} pulseS=[0-9]{1,8} " +
            "pulseMah=[0-9]{1,6}[.,][0-9]{2} walking=[0-9]{1,6} headStartS=(?:-|[0-9]{1,6}[.,][0-9]) usedMah=[0-9]{1,7} " +
            "screenOffH=[0-9]{1,5}[.,][0-9] unpluggedH=[0-9]{1,5}[.,][0-9] perScreenOffH=(?:-|[0-9]{1,7}[.,][0-9]))")
        private val stepSummary = Regex("steps \\+([0-9]{1,5}) in ([0-9]{1,5}[.,][0-9]) s \\(([0-9]{1,4}) updates?, " +
            "longest wait ([0-9]{1,5}[.,][0-9]) s\\); detector=([0-9]{1,7}) counter=([0-9]{1,7}|none)")
        // Activity Recognition (Google Play) transitions: the activity type and enter/exit only.
        private val arTransition = Regex("AR transition: activity=([0-9]{1,2}) trans=([01])")
        // Why an alarm episode ended (0.3.9) and whether a sounding alarm kept sounding (0.3.10).
        private val alarmEpisodeClosed = Regex("(presence hit at the car after an alarm \\(user back\\)|" +
            "sustained signal at the car after an alarm \\(user back\\)|link back [0-9]{1,6} s, then lost out of range)" +
            " -> alarm episode closed( \\(a sounding alarm keeps sounding\\))?")
        private val AR_ACTIVITIES = mapOf("0" to "vehicle", "1" to "bicycle", "2" to "on_foot", "3" to "still",
            "7" to "walking", "8" to "running")
        // One schedule write (0.3.8): categories and slot numbers only - no name, time or temperature fits.
        private const val SCHEDULE_SLOT = "[0-9]{1,2}:(?:on|off)/(?:used|empty|hidden)/[0-7]"
        private val scheduleWrite = Regex("schedule write (kind=(?:create|edit|switch|delete|charge) " +
            "slot=(?:[0-9]{1,2}|-) sent=(?:accepted|error|refused code=[A-Za-z0-9_.-]{1,16}) session=(?:yes|no|-) " +
            "result=(?:confirmed|pending|failed) polls=[0-9]{1,3} " +
            "readback=(?:none|(?:start|stop|other)/(?:app|other)|$SCHEDULE_SLOT(?:,$SCHEDULE_SLOT){0,9}) " +
            "found=(?:-|none|slot[0-9]{1,2} name=same days=(?:same|other) time=(?:same|other) on=(?:same|other)" +
            "(?: temp=(?:same|other) seats=(?:same|other))?))")
        // How the plans are built (0.3.14): flags and counts per slot only - no name, time, day or temperature fits.
        private const val SCHEDULE_SHAPE = "[0-9]{1,2}:s[0-9]\\.d[0-9]\\.b[0-9]\\.p[0-9]\\.t[0px]\\.c[0-9]\\.a[0-7]\\.n[eu]" +
            "\\.cs[-+0]\\.v[0-9][+0]?\\.h[0-9][+0]?\\.w[-+0]\\.f[-+0]"
        private val scheduleShape = Regex("schedule shape (at=(?:load|create|edit|switch|delete) sent=(?:-|$SCHEDULE_SHAPE) " +
            "slots=(?:none|$SCHEDULE_SHAPE(?:,$SCHEDULE_SHAPE){0,9}))")
        // The seat heating per plan (0.3.17): seat codes, levels and on/off only.
        // 0.3.18: the level as the car stores it, up to three digits (an off seat is stored outside I-III).
        private const val SCHEDULE_SEATS = "[0-9]{1,2}:(?:none|[0-9]{1,2}=[0-9]{1,3}[+-](?:/[0-9]{1,2}=[0-9]{1,3}[+-]){0,8})"
        private val scheduleSeats = Regex("schedule seats (at=(?:load|create|edit|switch|delete) sent=(?:-|$SCHEDULE_SEATS) " +
            "slots=(?:none|$SCHEDULE_SEATS(?:,$SCHEDULE_SEATS){0,9}))")
        // One read of the departure list (0.3.18): counts or a failure category only.
        private val scheduleList = Regex("schedule list (read=(?:ok plans=[0-9]{1,2} filled=[0-9]{1,2} unreadable=[0-9]{1,2}|" +
            "failed reason=(?:http|network|parse|refused|other)))")
        private val outcomes = setOf("fine_permission_missing", "provider_cancelled", "provider_failure",
            "provider_no_fix", "accuracy_missing", "mock_rejected", "fix_received", "security_exception",
            "request_timeout", "session_disabled", "window_expired_before_request",
            "window_expired_during_request", "no_fix", "invalid_or_inaccurate_fix",
            "fix_predates_unlock", "future_fix", "accepted", "coordinates_invalid", "accuracy_invalid",
            "accuracy_over_eight_meters", "fix_time_invalid", "near_unverified", "near_window_expired",
            "near_stale", "near_fix_stale", "waiting_for_pair", "observation_expired",
            "departure_fix_stale", "departure_time_not_advancing", "departure_confirmed",
            "departure_steps_insufficient", "departure_returning", "departure_timing_invalid",
            "departure_accuracy_invalid", "departure_clearance_insufficient", "departure_fix_implausible")

        // 0.4.0c: the doubt reference and the GNSS gate of the own rules ("gnss:" live, "shadow: gnss:" in the shadow
        // mode): levels, metres, accuracies, own steps and seconds only - never a position.
        private val gnssLine = Regex("^(shadow: )?gnss: (.{1,160}) \\(rssi=(-[0-9]{2,3})(?: own=(?:[0-9]{1,7}|null))?\\)$")
        private val gnssDoubtFresh = Regex("doubt fresh link at (-[0-9]{2,3}) dBm, walk (?:([0-9]{1,4}) s old|unknown)")
        private val gnssDoubtJump = Regex("doubt jump (-[0-9]{2,3}) -> (-[0-9]{2,3}) dBm \\((-?[0-9]{1,6}) own steps between the readings\\)")
        private val gnssDoubtFar = Regex("doubt far (-[0-9]{2,3}) dBm(?: for ([0-9]{1,5}) s)?")
        private val gnssReleased = Regex("released: (?:escape at (-[0-9]{2,3}) dBm|near ([0-9]{1,5}) m acc ([0-9]{1,4}) after (-?[0-9]{1,6}) own steps|" +
            "rise (-[0-9]{2,3}) dBm over the doubt level (-[0-9]{2,3}) after (-?[0-9]{1,6}) own steps|steps (-?[0-9]{1,6}) own steps since the doubt)")
        private val gnssFarHold = Regex("far ([0-9]{1,5}) m acc ([0-9]{1,4}) after (-?[0-9]{1,6}) own steps - holds ([a-z_]{1,24}) at (-[0-9]{2,3}) dBm")
        private val gnssShortHold = Regex("short (-?[0-9]{1,6}) own steps since the doubt \\((fresh|jump|far) [^()]{0,80}(?:\\([^()]{0,60}\\))?\\) - " +
            "holds ([a-z_]{1,24}) at (-[0-9]{2,3}) dBm")
        private val gnssVeto = Regex("veto ([a-z_]{1,24}) at (-[0-9]{2,3}) dBm: ([0-9]{1,5}) m acc ([0-9]{1,4}) from the car")
        // 0.4.0d: the GNSS approach route opened (level, metres, accuracy, trend, fix count, readings in a row).
        private val gnssOpen = Regex("open approach_gnss at (-[0-9]{2,3}) dBm: ([0-9]{1,5}) m acc ([0-9]{1,4}) trend (-?[0-9]{1,4}\\.[0-9]) " +
            "after ([0-9]{1,3}) fixes \\(([0-9]{1,3}) readings\\)")
        private val gnssCarPosition = Regex("^(shadow: )?gnss: car position (?:remembered \\(([1-9]) anchors, acc ([0-9]{1,4})\\)|" +
            "forgotten \\(the phone rides in the car\\))$")
        private val gnssFixWatch = Regex("^(shadow: )?gnss: fix watch (?:starts \\((doubt|in band)\\)|over)$")

        // 0.4.3: the engine's stand-down, the fail-safe reclaim, an ignored (stale) report or resume, the key push/purge.
        private val watchLine = Regex("^watch: (?:control ([A-Z_]{1,24}) (ok|err|ignored)(?: \\([a-z -]{1,40}\\))?|" +
            "key (sent|refused|unavailable|pushed|purge sent)(?: \\([a-z ]{1,40}\\))?|" +
            "link (paused for the watch|resumed(?: \\([a-z -]{1,30}\\))?|reclaimed with the car open[a-z -]{0,60}|" +
            "stood down(?: \\([a-z -]{1,60}\\))?|resume ignored(?: \\([a-z -]{1,30}\\))?))$")

        // 0.4.5: the guard automaton's steps - lower-case words, counts, percentages, a source in brackets; nothing else.
        private val guardLine = Regex("^guard: ([a-z][a-z0-9 %():>-]{0,70})$")

        /** 0.4.0c: the body of a "gnss:" decision line as a journal entry; null for a body the journal does not know. */
        private fun gnssEntry(body: String, shadow: Boolean): String? {
            val tail = if (shadow) " shadow" else ""
            gnssDoubtFresh.matchEntire(body)?.let { m ->
                val walk = m.groupValues[2]
                return "GNSS_DOUBT fresh ${m.groupValues[1]} walk=" + (if (walk.isEmpty()) "unknown" else "${walk}s") + tail
            }
            gnssDoubtJump.matchEntire(body)?.let { m ->
                return "GNSS_DOUBT jump ${m.groupValues[1]} ${m.groupValues[2]} steps=${m.groupValues[3]}$tail" }
            gnssDoubtFar.matchEntire(body)?.let { m ->
                return "GNSS_DOUBT far ${m.groupValues[1]}" + (if (m.groupValues[2].isEmpty()) "" else " for=${m.groupValues[2]}s") + tail }
            gnssReleased.matchEntire(body)?.let { m ->
                val g = m.groupValues
                return "GNSS_RELEASED " + when {
                    g[1].isNotEmpty() -> "escape ${g[1]}"
                    g[2].isNotEmpty() -> "near m=${g[2]} acc=${g[3]} steps=${g[4]}"
                    g[5].isNotEmpty() -> "rise ${g[5]} from=${g[6]} steps=${g[7]}"
                    else -> "steps ${g[8]}"
                } + tail
            }
            gnssFarHold.matchEntire(body)?.let { m ->
                return "GNSS_HOLD far ${m.groupValues[4]} ${m.groupValues[5]} m=${m.groupValues[1]} acc=${m.groupValues[2]} steps=${m.groupValues[3]}$tail" }
            gnssShortHold.matchEntire(body)?.let { m ->
                return "GNSS_HOLD short ${m.groupValues[3]} ${m.groupValues[4]} steps=${m.groupValues[1]} doubt=${m.groupValues[2]}$tail" }
            gnssVeto.matchEntire(body)?.let { m ->
                return "GNSS_VETO ${m.groupValues[1]} ${m.groupValues[2]} m=${m.groupValues[3]} acc=${m.groupValues[4]}$tail" }
            gnssOpen.matchEntire(body)?.let { m ->
                return "GNSS_APPROACH_OPEN ${m.groupValues[1]} m=${m.groupValues[2]} acc=${m.groupValues[3]} trend=${m.groupValues[4]} " +
                    "fixes=${m.groupValues[5]} readings=${m.groupValues[6]}$tail" }
            return null
        }

        internal fun event(area: String, message: String): String? {
            if (area == "guard") return guardLine.matchEntire(message)?.let { "GUARD " + it.groupValues[1] }
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
                // 0.4.0c: the car's remembered position (a count and an accuracy), the fix watch and the gate's decisions.
                gnssCarPosition.matchEntire(message)?.let { m ->
                    return "GNSS_CAR_POSITION " + (if (m.groupValues[2].isEmpty()) "forgotten"
                        else "remembered anchors=${m.groupValues[2]} acc=${m.groupValues[3]}") + (if (m.groupValues[1].isEmpty()) "" else " shadow")
                }
                gnssFixWatch.matchEntire(message)?.let { m ->
                    return "GNSS_FIX_WATCH " + m.groupValues[2].ifEmpty { "over" }.replace(' ', '_') + (if (m.groupValues[1].isEmpty()) "" else " shadow")
                }
                gnssLine.matchEntire(message)?.let { m -> return gnssEntry(m.groupValues[2], m.groupValues[1].isNotEmpty()) }
                // Watch companion (wear 2.0): what the watch asked the phone to do - names and outcomes only.
                watchLine.matchEntire(message)?.let { m ->
                    return when {
                        m.groupValues[1].isNotEmpty() -> "WATCH_CONTROL ${m.groupValues[1]} ${m.groupValues[2]}"
                        m.groupValues[3].isNotEmpty() -> "WATCH_KEY ${m.groupValues[3].replace(' ', '_')}"
                        else -> "WATCH_LINK " + when {
                            m.groupValues[4].startsWith("paused") -> "paused"
                            m.groupValues[4].startsWith("reclaimed") -> "reclaimed_open"   // W2 (R3)
                            m.groupValues[4].startsWith("stood down") -> "stood_down"        // 0.4.3 (T1)
                            m.groupValues[4].startsWith("resume ignored") -> "resume_ignored" // 0.4.3 (T6)
                            else -> "resumed"
                        }
                    }
                }
                batteryDay.matchEntire(message)?.let { return "BATTERY_DAY ${it.groupValues[1]}" }
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
                    returnToCar.matches(message) -> returnToCar.matchEntire(message)?.let { "RETURN_TO_CAR ${it.groupValues[1]}" }
                    returnHeld.matches(message) -> returnHeld.matchEntire(message)?.let {
                        "RETURN_HELD ${it.groupValues[1]} ${it.groupValues[2]} steps=${it.groupValues[3]}" }
                    lockVetoed.matches(message) -> lockVetoed.matchEntire(message)?.let { m ->
                        val why = m.groupValues[2].let {
                            when {
                                it.startsWith("fresh") -> "strong_now"
                                it.startsWith("signal") -> "lock_range"
                                it.endsWith("return to the car") -> "steps_since_return"
                                it.startsWith("only") -> "steps_since_car"
                                it.startsWith("strong") -> "strong_recent"
                                else -> "walking_back"
                            }
                        }
                        "LOCK_VETOED ${m.groupValues[1]} $why"
                    }
                    message.startsWith("manual Lock not executed:") -> "MANUAL_LOCK_NOT_EXECUTED"
                    Regex("(ble-departure|verified-link-departure|walk-away-lock|idle-far-lock|far-no-motion): " +
                        "locked via cloud; a newer cloud report confirms it").matches(message) -> "AUTO_LOCK_CLOUD_VERIFIED"
                    Regex("(ble-departure): cloud Lock backed by a fresh BLE departure proof").matches(message) ->
                        "CLOUD_LOCK_BLE_BACKED"
                    // 0.3.9: why an alarm episode ended (the user back at the car, or a new departure);
                    // 0.3.10: "kept" when weak evidence left a sounding alarm sounding.
                    message == "strong signal at the car after an alarm -> user back; alarm silenced" -> "ALARM_EPISODE_CLOSED strong"
                    alarmKept.matches(message) -> alarmKept.matchEntire(message)?.let {
                        "ALARM_KEPT ${it.groupValues[1]} ${it.groupValues[2]} steps=${it.groupValues[3]}" }
                    alarmEpisodeClosed.matches(message) -> alarmEpisodeClosed.matchEntire(message)?.let { m ->
                        val why = m.groupValues[1].let {
                            when { it.startsWith("presence") -> "presence"; it.startsWith("sustained") -> "signal"; else -> "link" }
                        }
                        "ALARM_EPISODE_CLOSED $why" + if (m.groupValues[2].isEmpty()) "" else " kept"
                    } ?: "ALARM_EPISODE_CLOSED"
                    message.startsWith("manual cloud Lock verified (") -> "MANUAL_CLOUD_LOCK_VERIFIED"
                    message == "car reported locked -> 'Auto gesloten' notification" -> "CAR_LOCKED_CHIME"
                    message.startsWith("car reported unlocked without our Unlock (") -> "CAR_REOPENED"
                    message.startsWith("phone far from the unlocked car") -> "STATIONARY_FAR_ALARM"
                    message.startsWith("armed idle far read ") -> "IDLE_FAR_FAST"
                    message.startsWith("measurement mode on") -> "MEASURE_MODE on"
                    message == "measurement mode off" -> "MEASURE_MODE off"
                    message.startsWith("independent departure unverified:") -> "DEPARTURE_UNVERIFIED"
                    message == "ble departure confirmed" -> "BLE_DEPARTURE_CONFIRMED"
                    // 0.3.20 (study J): the alarm waited for a very-deep proof; the drive's motion held one back.
                    message.startsWith("departure alarm held: a very-deep proof is under way") -> "LOCK_ALARM_HELD very_deep"
                    message.startsWith("very-deep proof held: MOVING from the drive only") -> "BLE_DEPARTURE_HELD drive_motion"
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
            if (area == "motion") ownWindow.matchEntire(message)?.let { m ->
                return "OWN_WINDOW reason=${m.groupValues[1].replace(' ', '_')} own=${m.groupValues[2]} " +
                    "turn=${if (m.groupValues[3] == "none") "none" else "yes"} secs=${m.groupValues[4]} " +
                    "detector=${m.groupValues[5]} counter=${m.groupValues[6]} ar=${m.groupValues[7]} screen=${m.groupValues[8]}"
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
                ?: scheduleShape.matchEntire(message)?.let { "SCHEDULE_SHAPE ${it.groupValues[1]}" }
                ?: scheduleSeats.matchEntire(message)?.let { "SCHEDULE_SEATS ${it.groupValues[1]}" }
                ?: scheduleList.matchEntire(message)?.let { "SCHEDULE_LIST ${it.groupValues[1]}" }
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
