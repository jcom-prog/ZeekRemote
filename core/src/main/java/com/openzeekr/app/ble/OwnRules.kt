package com.openzeekr.app.ble

/**
 * 0.4.0b: the app's own step counter as walking evidence - the rules of the 0.4.0a simulator study (candidate v2dr6,
 * the 2 dB rise hold and the walk credit of round 4, owner decision 23/10) as fixed code behind one mode switch.
 *
 *  OFF    - 0.3.30 behaviour; the own counter only logs in the unlock window (as since 0.3.22).
 *  SHADOW - the 0.4.0b measurement version: the live decisions stay 0.3.30; the own counter also runs in the approach
 *           window; a second decision policy fed with the own counter decides next to the live one and every
 *           divergence is logged ("shadow:" lines); the own-walk departure proof runs next to the location window and
 *           logs when it would have proven the departure.
 *  LIVE   - the rules decide (0.4.0, once the field confirms the shadow).
 *
 * The simulator sets the mode and the knobs through SIM_OWN_* environment variables; the app has none, so the defaults
 * below are the app's values (the study's winning variant r2w12far8).
 */
internal object OwnRules {
    enum class Mode { OFF, SHADOW, LIVE }

    val mode: Mode = when (System.getenv("SIM_OWN_MODE")) { "off" -> Mode.OFF; "live" -> Mode.LIVE; else -> Mode.SHADOW }
    /** The rules decide. */
    val live: Boolean get() = mode == Mode.LIVE
    /** The own counter is read at all: its windows, the shadow policy, the logging. */
    val enabled: Boolean get() = mode != Mode.OFF
    /** A second policy fed with the own counter runs next to the live one and logs the divergences. */
    val shadow: Boolean get() = mode == Mode.SHADOW

    // ---- the walk evidence (design 23/10 sections 3.3 and 4; study v1 + v2) ----
    /** The locked-side window follows every reading this far below the unlock level and every presence hit. */
    const val BAND_DB = 6
    /** A live own walk: at least 2 own steps within this window. */
    const val LIVE_MS = 3_000L
    /** No own step for this long is a pause: the own-walk count of the departure proof restarts. */
    const val PAUSE_MS = 3_000L
    const val TURN_WINDOW_MS = 10_000L
    /** A signal rise this large within [TURN_WINDOW_MS] while the car is open is a turn (0 = no turn rule). */
    val turnDb: Int = envInt("SIM_OWN_DR_TURN_DB", 6)
    /** The still route is off while the own counter counts (a signal rise without steps is no walk). */
    val noStill: Boolean = System.getenv("SIM_OWN_NO_STILL") != "0"

    // ---- the own-walk departure proof (design section 4 "sluiten") ----
    /** Own steps x [strideM] since the car must reach this many metres (0 = no own proof). */
    val drMetres: Double = envDouble("SIM_OWN_DR_M", 15.0)
    val strideM: Double = envDouble("SIM_OWN_STRIDE_M", 1.0)
    /** The smoothed signal must be this far below the unlock level. */
    val drDb: Int = envInt("SIM_OWN_DR_DB", 6)
    /** Polls (every 0.5 s) in a row that prove the departure. */
    val drStreak: Int = envInt("SIM_OWN_DR_STREAK", 6)
    val drBle: Boolean = System.getenv("SIM_OWN_DR_BLE") == "1"
    val drWhy: Boolean = System.getenv("SIM_OWN_DR_WHY") == "1"

    // ---- the rise hold (round 4) and the walk credit (round 4c) ----
    /** The hold exists (SIM_OWN_RISE=0 in a study run gives the candidate without it). */
    val riseHold: Boolean = System.getenv("SIM_OWN_RISE") != "0"
    val riseDb: Int = envInt("SIM_OWN_RISE_DB", 2)
    val riseStrongDb: Int = envInt("SIM_OWN_RISE_STRONG", 4)
    val riseFallDb: Int = envInt("SIM_OWN_RISE_FALL", 3)
    val riseMs: Long = envLong("SIM_OWN_RISE_MS", 12_000L)
    val riseSteps: Long = envLong("SIM_OWN_RISE_STEPS", 0L)
    val riseRoutes: String = System.getenv("SIM_OWN_RISE_ROUTES") ?: "all"
    /** The walk credit: no hold after this many own steps since the last reading <= unlock - [creditFarDb] within
     *  [creditWindowMs] (0 = no credit). */
    val creditSteps: Long = envLong("SIM_OWN_RISE_WALK", 12L)
    val creditFarDb: Int = envInt("SIM_OWN_RISE_FAR_DB", 8)
    val creditWindowMs: Long = envLong("SIM_OWN_RISE_WALK_MS", 60_000L)

    /** The locked-side window closes this long after the last presence hit / reading in the band. */
    val approachWindowMs: Long = envLong("SIM_OWN_WINDOW_MS", 90_000L)

    fun describe(): String = "own rules $mode (dr ${drMetres}m/${drDb}dB, hold ${if (riseHold) "+${riseDb}dB" else "off"}, " +
        "credit ${creditSteps} steps since -${creditFarDb}dB within ${creditWindowMs / 1000}s)"

    private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
    private fun envLong(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default
    private fun envDouble(name: String, default: Double): Double = System.getenv(name)?.toDoubleOrNull() ?: default
}
