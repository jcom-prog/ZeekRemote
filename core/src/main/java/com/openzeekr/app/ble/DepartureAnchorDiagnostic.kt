package com.openzeekr.app.ble

/** Categorical diagnostics; never serialize the location itself. */
internal object DepartureAnchorDiagnostic {
    fun rejection(fix: DepartureFix?, unlockedAt: Long, now: Long): String? = when {
        fix == null -> "no_fix"
        !DepartureSafetyEvidence.valid(fix) -> "invalid_or_inaccurate_fix"
        fix.elapsedAtMs < unlockedAt - 1_000L -> "fix_predates_unlock"
        fix.elapsedAtMs > now -> "future_fix"
        else -> null
    }
}
