package com.openzeekr.app.ble

/** Categorical diagnostics; never serialize the location itself. */
internal object DepartureAnchorDiagnostic {
    fun rejection(fix: DepartureFix?, unlockedAt: Long, now: Long): String? = when {
        fix == null -> "no_fix"
        !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 -> "coordinates_invalid"
        !fix.accuracyM.isFinite() || fix.accuracyM <= 0f -> "accuracy_invalid"
        fix.accuracyM > 8f -> "accuracy_over_eight_meters"
        fix.elapsedAtMs <= 0L -> "fix_time_invalid"
        fix.elapsedAtMs < unlockedAt - 1_000L -> "fix_predates_unlock"
        fix.elapsedAtMs > now -> "future_fix"
        else -> null
    }
}
