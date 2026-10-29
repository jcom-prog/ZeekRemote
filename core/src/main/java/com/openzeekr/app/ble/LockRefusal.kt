package com.openzeekr.app.ble

/**
 * 0.4.10 (G2, field 29/10 parking garage): what the car reports about its openings when it refuses a Lock, as raw codes
 * only - "0" is closed, anything else is whatever the car says (the owner's car reported the tailgate "not 0" while the
 * tailgate was closed; the raw code tells the next time what it claimed). Nothing here identifies the car.
 */
internal object LockRefusal {
    /** "doors=ABCD bonnet=X tailgate=Y port=PQ": one code per field, "-" when the field is missing, "?" when not a digit. */
    fun openings(doors: List<String?>, bonnet: String?, tailgate: String?, port: List<String?>): String =
        "doors=" + doors.joinToString("") { code(it) } + " bonnet=" + code(bonnet) + " tailgate=" + code(tailgate) +
            " port=" + port.joinToString("") { code(it) }

    private fun code(v: String?): String = when {
        v == null || v.isBlank() -> "-"
        v.length == 1 && v[0].isDigit() -> v
        v.all { it.isDigit() } -> "9"   // a multi-digit code: "open, unknown code"
        else -> "?"
    }

    /** The opening the car reports as not closed - the tailgate first (field 29/10), then a door, then the bonnet; the
     *  charge port never (the car locks while charging). Null: everything reads closed, or the status was unavailable. */
    fun blocker(openings: String?): String? {
        val f = fields(openings) ?: return null
        return when {
            f["tailgate"]?.any { it != '0' && it != '-' } == true -> "the tailgate"
            f["doors"]?.any { it != '0' && it != '-' } == true -> "a door"
            f["bonnet"]?.any { it != '0' && it != '-' } == true -> "the bonnet"
            else -> null
        }
    }

    /** Doors, bonnet and tailgate all read "0" (the port does not count). False when the status is unavailable. */
    fun allClosed(openings: String?): Boolean {
        val f = fields(openings) ?: return false
        return listOf("doors", "bonnet", "tailgate").all { k -> f[k]?.all { it == '0' || it == '-' } == true }
    }

    /** The notice text (one sentence, exact words in the build report). */
    fun noticeText(blocker: String?, statusKnown: Boolean): String = when {
        blocker != null -> "The car reports $blocker open. Close it; ZeekRemote sends the Lock again when the car reports it closed."
        statusKnown -> "The car reports no open door, bonnet or tailgate. ZeekRemote sends the Lock again shortly."
        else -> "The car's status could not be read. Lock manually and check the car."
    }

    private fun fields(openings: String?): Map<String, String>? {
        if (openings == null) return null
        val m = openings.split(" ").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return if (m.containsKey("tailgate")) m else null
    }
}
