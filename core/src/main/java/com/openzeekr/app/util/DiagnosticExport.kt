package com.openzeekr.app.util

/**
 * "Deel diagnose": a readable bug report the user can share from the phone (no PC, no adb).
 *
 * Unlike the encrypted "Share" log this file is plaintext, so only what is safe goes in:
 *  - the private event journal (already a whitelist of categorical events, no payloads/positions);
 *  - the in-memory app log, filtered: only the decision areas ([AREAS]), only safe lines of the key
 *    session ([DK_SAFE]) and the BLE link ([BLE_SAFE]), never a line that names an identifier,
 *    token, key, certificate, raw frame or position ([DENY]), and with radio addresses, long hex
 *    runs and coordinate-like numbers masked ([redact]) as a second line of defence.
 */
object DiagnosticExport {
    /** Decision areas kept in full (still subject to [DENY] and [redact]). */
    private val AREAS = setOf("prox", "svc", "motion", "lock", "charge")
    /** Key-session lines worth keeping: status, session start, handshake progress. */
    private val DK_SAFE = listOf("vehicle status observed", "status pushed during the handshake",
        "=== DK session established", "handshake ")
    /** BLE link lines worth keeping: connection state and presence scanning. */
    private val BLE_SAFE = listOf("onConnectionStateChange", "presence scan", "presence MATCH", "connectGatt",
        "DK session READY", "services discovered", "starting DK handshake",
        "skip other Zeekr", "own key module")
    /** Any line mentioning one of these is dropped whole. */
    private val DENY = Regex(
        "(?i)vin|token|passw|secret|bearer|cert|cmac|digitalkey|dkid|phoneid|deviceid|userid|e-?mail|" +
            "rnd|hex=|der head|factor|\\bsig\\b|key=|keys=|\\blat\\b|\\blon\\b|latitude|longitude|" +
            "address|position|coord|broadcastrnd")
    private val LINE = Regex("^(\\S+) ([VDIWE])/([a-z]+)\\s+(.*)$")
    private val MAC = Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b")
    private val HEX = Regex("(?i)\\b[0-9a-f]{12,}\\b")
    private val COORD = Regex("-?\\d{1,3}[.,]\\d{5,}")

    private val LOGCAT = Regex("^\\d\\d-\\d\\d (\\S+)\\s+\\d+\\s+\\d+ ([VDIWE]) openzeekr\\s*: \\[([a-z]+)\\] (.*)$")

    /** The app's own logcat lines (`-v threadtime`) in the in-memory log format; other lines dropped. */
    fun fromLogcat(lines: List<String>): List<String> = lines.mapNotNull { line ->
        LOGCAT.matchEntire(line)?.destructured?.let { (time, level, area, msg) -> "$time $level/$area  $msg" }
    }

    fun redact(msg: String): String =
        msg.replace(MAC, "XX:XX:XX:XX:XX:XX").replace(HEX, "<hex>").replace(COORD, "<num>")

    /** Keeps the safe lines of the in-memory log ("HH:mm:ss.SSS L/area  message"), redacted. */
    fun filterAppLog(lines: List<String>): List<String> = lines.mapNotNull { line ->
        val m = LINE.matchEntire(line) ?: return@mapNotNull null
        val (time, level, area, msg) = m.destructured
        val keep = when (area) {
            in AREAS -> true
            "dk" -> DK_SAFE.any { msg.startsWith(it) }
            "ble" -> BLE_SAFE.any { msg.startsWith(it) }
            else -> false
        }
        if (!keep || DENY.containsMatchIn(msg)) null else "$time $level/$area  ${redact(msg)}"
    }

    /** The shared text: a short header, the journal, then the filtered app log. */
    fun build(appVersion: String, createdAt: String, journal: String?, appLog: List<String>): String = buildString {
        append("ZeekRemote diagnose ").append(appVersion).append(" · ").append(createdAt).append('\n')
        append("Geen VIN, tokens, sleutels, ruwe BLE-data of locatie.\n\n")
        append("== Dagboek ==\n")
        append(journal?.lineSequence()?.filter { it.isNotBlank() }?.joinToString("\n") { redact(it) }
            ?.ifBlank { null } ?: "(leeg)").append("\n\n")
        val log = filterAppLog(appLog)
        append("== App-log (gefilterd, ").append(log.size).append(" regels) ==\n")
        append(if (log.isEmpty()) "(leeg - staat BLE-logging aan?)" else log.joinToString("\n")).append('\n')
    }
}
