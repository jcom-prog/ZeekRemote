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
 * 0.4.0c adds the doubt reference and the GNSS gate of study round 4f: a fresh link that starts inside the band while
 * the walk is young (no approach was seen - a line of sight 25 m out reads like the last metres) is a doubt; while it
 * lives, the car's last known position decides through GNSS (far holds, near releases), and a usable far fix vetoes
 * any qualification. In SHADOW the shadow policy applies them and the controller logs ("shadow: gnss:"); the fix watch
 * itself does run, so the field shows what GNSS does on the locked side.
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

    // ---- 0.4.0c: the doubt reference and the GNSS gate (study round 4f, owner decision 24/10) ----
    /** The gate rules exist for the policy that applies the own rules (SHADOW: the shadow policy logs them; LIVE: the
     *  live policy decides with them). SIM_OWN_GNSS=0 gives 0.4.0b. */
    val gnssRules: Boolean = System.getenv("SIM_OWN_GNSS") != "0"
    /** A fresh link: the first reading after the link ended, or after this long without one. */
    val freshGapMs: Long = envLong("SIM_OWN_RISE_FRESH_GAP_MS", 15_000L)
    /** A walk at most this old at a fresh link that starts inside the band is a doubt (no approach was seen). */
    val youngWalkMs: Long = envLong("SIM_OWN_RISE_YOUNG_MS", 6_000L)
    /** No fresh-link doubt when a far reading was seen this recently, in any link (an approach seen from far). */
    val farMemoryMs: Long = envLong("SIM_OWN_RISE_FAR_MEMORY_MS", 60_000L)
    /** A far reading is a doubt reference when far readings dominated this trailing window (0 = every far reading). */
    val farStretchMs: Long = envLong("SIM_OWN_RISE_FAR_MIN_MS", 20_000L)
    /** A level jump this large within 3 s from a far level with at most two own steps between the readings (0 = off). */
    val jumpDb: Int = envInt("SIM_OWN_RISE_JUMP_DB", 8)
    /** Own steps since the doubt that release the gate. */
    val doubtSteps: Long = envLong("SIM_OWN_RISE_DOUBT_WALK", 12L)
    /** The walked rise over the doubt level (the maximum of its first 2 s) that releases the gate (0 = off). */
    val doubtRiseDb: Int = envInt("SIM_OWN_RISE_DOUBT_RISE_DB", 6)
    /** A reading this far above the unlock level is never held: the at-car level, unreachable from 25 m. */
    val escapeDb: Int = envInt("SIM_OWN_RISE_DOUBT_ESCAPE_DB", 12)
    /** A usable fix this far from the car's last position holds; closer releases (0 = no GNSS in the gate). */
    val gnssFarM: Double = envDouble("SIM_OWN_GNSS_M", 15.0)
    /** Margin factor on the fixes' accuracies in the far test (0 = the plain distance). */
    val gnssMarginK: Double = envDouble("SIM_OWN_GNSS_K", 0.0)
    /** Fixes less accurate than this are not used. */
    val gnssAccMaxM: Float = envDouble("SIM_OWN_GNSS_ACC_MAX", 12.0).toFloat()
    /** The veto: a usable far fix holds every approach and return qualification, doubt or no doubt. */
    val gnssVeto: Boolean = System.getenv("SIM_OWN_GNSS_ALL") != "0"
    /** The locked-side fix watch runs while the link is in the band, at most this long per in-band episode. */
    val gnssWatchBudgetMs: Long = envLong("SIM_OWN_GNSS_ALL_MAX_MS", 60_000L)
    /** A doubt reference expires after this long. */
    val doubtWindowMs: Long = envLong("SIM_OWN_RISE_WALK_MS", 60_000L)

    // ---- 0.4.0d (study 4g, owner decision 24/10): GNSS as positive approach evidence (variant g6-p2) ----
    /** The route "approach_gnss" opens when the median of the last fixes and the latest fix are within this many metres
     *  of the car's last position (0 = route off). Like every SIM_ knob only the simulator sets it; the app uses the default. */
    val gnssOpenM: Double = envDouble("SIM_OWN_GNSS_OPEN_M", 6.0)
    /** The summed accuracy of the fixes (the worst fix plus the car's position) may be at most this. */
    val gnssOpenAccMaxM: Double = envDouble("SIM_OWN_GNSS_OPEN_ACC", 8.0)
    /** The signal must be at most this far below the unlock level. (The far margin of the policy, 2 dB, returns earlier,
     *  so in practice the route sees readings from 1 dB below the level up; the knob is kept as studied.) */
    val gnssOpenBandDb: Int = envInt("SIM_OWN_GNSS_OPEN_BAND", 6)
    /** The distance must have fallen at least this much over the fresh fixes (0 = no trend needed). */
    val gnssOpenTrendM: Double = envDouble("SIM_OWN_GNSS_OPEN_TREND_M", 1.0)
    /** At least this many usable fixes in the fresh window of the fix filter. */
    val gnssOpenMinFixes: Int = envInt("SIM_OWN_GNSS_OPEN_FIXES", 3)
    /** The near condition must hold on this many readings in a row before the route opens (one lucky set of fixes is
     *  not enough; study 4g: two readings take a third of the early openings away for 0.3 points). */
    val gnssOpenPersist: Int = envInt("SIM_OWN_GNSS_OPEN_PERSIST", 2)
    /** Only a failing near condition restarts the streak of near readings; a far dip of the signal between two near
     *  readings does not (the pocket signal hovers around the far margin, and the fix set is the safety here) - the
     *  variant as measured in study 4g. SIM_OWN_GNSS_OPEN_STRICT=1 (simulator) also restarts it at a far reading and
     *  at a locked reset: 72.8 % against 73.1 % open before the door, otherwise alike. */
    val gnssOpenStrict: Boolean = System.getenv("SIM_OWN_GNSS_OPEN_STRICT") == "1"

    fun describe(): String = "own rules $mode (dr ${drMetres}m/${drDb}dB, hold ${if (riseHold) "+${riseDb}dB" else "off"}, " +
        "credit ${creditSteps} steps since -${creditFarDb}dB within ${creditWindowMs / 1000}s, " +
        "gnss ${if (gnssRules) "gate ${gnssFarM}m${if (gnssVeto) " + veto" else ""}" +
            (if (gnssOpenM > 0.0) " + open ${gnssOpenM}m x$gnssOpenPersist" else "") else "off"})"

    private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
    private fun envLong(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default
    private fun envDouble(name: String, default: Double): Double = System.getenv(name)?.toDoubleOrNull() ?: default
}
