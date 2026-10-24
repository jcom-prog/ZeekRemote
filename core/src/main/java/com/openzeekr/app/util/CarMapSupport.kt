package com.openzeekr.app.util

import kotlin.math.roundToInt

/** 0.4.0e: the pure pieces of the "where's my car" map (the pin's placement and the position's age text). */
object CarMapSupport {
    /** Top-left corner (px) of a pin icon of [iconPx] whose tip must sit on the point ([x], [y]). */
    fun pinTopLeft(x: Float, y: Float, iconPx: Float): Pair<Int, Int> =
        (x - iconPx / 2f).roundToInt() to (y - iconPx).roundToInt()

    /** How old the car's cloud position is, in plain words; null without a timestamp or with one from the future. */
    fun positionAgeText(updateTimeMs: Long?, nowMs: Long): String? {
        if (updateTimeMs == null) return null
        val age = nowMs - updateTimeMs
        if (age < 0L) return null
        val min = age / 60_000L
        return when {
            min < 1L -> "zojuist"
            min < 60L -> "$min min geleden"
            min < 24L * 60L -> "${min / 60L} uur geleden"
            else -> "meer dan een dag geleden"
        }
    }
}
