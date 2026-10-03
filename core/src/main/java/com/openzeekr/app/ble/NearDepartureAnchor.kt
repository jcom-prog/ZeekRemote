package com.openzeekr.app.ble

/** A later location is a car reference only while fresh, sustained BLE proximity still holds.
 * Coordinates stay in memory. Weak or stale BLE evidence rejects a later reference.
 */
internal class NearDepartureAnchor(private val unlockedAtMs: Long) {
    private var lastSampleAtMs = 0L
    private var nearSinceMs: Long? = null

    fun observe(rssi: Int, elapsedMs: Long) {
        if (elapsedMs < unlockedAtMs || elapsedMs < lastSampleAtMs) {
            nearSinceMs = null
            lastSampleAtMs = 0L
            return
        }
        if (lastSampleAtMs != 0L && elapsedMs - lastSampleAtMs > MAX_SAMPLE_AGE_MS)
            nearSinceMs = null
        lastSampleAtMs = elapsedMs
        if (rssi >= STRONG_NEAR_RSSI) {
            if (nearSinceMs == null) nearSinceMs = elapsedMs
        } else nearSinceMs = null
    }

    fun rejection(fix: DepartureFix, nowMs: Long): String? {
        DepartureAnchorDiagnostic.rejection(fix, unlockedAtMs, nowMs)?.let { return it }
        val nearSince = nearSinceMs ?: return "near_unverified"
        return when {
            nowMs < unlockedAtMs || nowMs - unlockedAtMs > WINDOW_MS -> "near_window_expired"
            nowMs < lastSampleAtMs || nowMs - lastSampleAtMs > MAX_SAMPLE_AGE_MS -> "near_stale"
            nowMs - nearSince < MIN_NEAR_HOLD_MS -> "near_unverified"
            fix.elapsedAtMs < nearSince || nowMs - fix.elapsedAtMs > MAX_FIX_AGE_MS -> "near_fix_stale"
            else -> null
        }
    }

    companion object {
        const val WINDOW_MS = 120_000L
        // At the door with the phone in a trouser pocket the field read -78..-81 (03/10); -72 left
        // the GNSS reference (and so every GNSS departure proof) unavailable in that common case.
        // -79 is still the last ~2-3 m to the car; the departure proof keeps its 2x-accuracy margin.
        private const val STRONG_NEAR_RSSI = -79
        private const val MIN_NEAR_HOLD_MS = 1_000L
        private const val MAX_SAMPLE_AGE_MS = 1_500L
        private const val MAX_FIX_AGE_MS = 1_500L
    }
}
