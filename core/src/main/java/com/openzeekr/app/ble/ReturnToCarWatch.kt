package com.openzeekr.app.ble

/**
 * 0.3.19 (study I, simulator 18/10): a return to the car that never reads strong. The Lock veto counts "steps since the
 * car" from the latest strong reading. With the phone in a back pocket and the body between phone and car, a user who
 * walked a few metres away, stood there and came back never read strong at the car, so the walk back counted as walking
 * away; a position proof from a GNSS reference taken in a multipath jump then locked the car 4 m from the user.
 *
 * After a deep spell - consecutive readings at least [DEEP_MARGIN_DB] beyond the lock threshold for [DEEP_SPELL_MS] - a
 * near reading (fresh and smoothed at least the lock threshold minus [NEAR_MARGIN_DB]) marks a return to the car; near
 * readings that follow within [STREAK_MS] keep moving the mark. A departure (strong, then weak) never marks one. Known
 * cost: a strong spot further away (simulator world strong-spot-walk) can look like a return and delays the Lock by the
 * steps the veto asks for. Pure Kotlin.
 */
internal class ReturnToCarWatch {
    enum class Mark { NONE, RETURN, STREAK }

    private var deepRunStartMs = UNSET
    private var hadDeepSpell = false

    /** When the latest return (or its streak) was seen; [UNSET] when none in this unlock. */
    var returnedAtMs = UNSET
        private set

    fun reset() {
        deepRunStartMs = UNSET
        hadDeepSpell = false
        returnedAtMs = UNSET
    }

    /** One fresh reading; [armed] is false while the car is not known to be open (the deep spell still counts). */
    fun observe(nowMs: Long, rssi: Int, smoothed: Double?, lockThreshold: Int, armed: Boolean): Mark {
        if (rssi <= lockThreshold - DEEP_MARGIN_DB) {
            if (deepRunStartMs == UNSET) deepRunStartMs = nowMs
            if (nowMs - deepRunStartMs >= DEEP_SPELL_MS) hadDeepSpell = true
        } else deepRunStartMs = UNSET
        val near = lockThreshold - NEAR_MARGIN_DB
        if (!armed || rssi < near || (smoothed != null && smoothed < near)) return Mark.NONE
        val streak = returnedAtMs != UNSET && nowMs - returnedAtMs <= STREAK_MS
        if (!hadDeepSpell && !streak) return Mark.NONE
        val mark = if (hadDeepSpell) Mark.RETURN else Mark.STREAK
        returnedAtMs = nowMs
        hadDeepSpell = false
        return mark
    }

    companion object {
        const val UNSET = Long.MIN_VALUE
        const val DEEP_MARGIN_DB = StationaryFarWatch.DEEP_MARGIN_DB
        const val DEEP_SPELL_MS = 5_000L
        const val NEAR_MARGIN_DB = 2
        const val STREAK_MS = 2_000L
    }
}
