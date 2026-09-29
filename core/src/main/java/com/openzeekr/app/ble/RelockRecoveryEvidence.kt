package com.openzeekr.app.ble

/** A cloud lock observation, including the car's update time rather than the HTTP response time. */
data class CloudLockSnapshot(val locked: Boolean, val updatedAtMs: Long?)

/** Never rearm an unlock merely because GATT dropped or a weak RSSI estimate looked distant. */
internal object RelockRecoveryEvidence {
    // A parked car can remain silent for an hour or more. Bound the snapshot while allowing that case.
    private const val MAX_STATUS_AGE_MS = 2 * 60 * 60_000L
    private const val FUTURE_CLOCK_SKEW_MS = 60_000L

    /** Bounded diagnostic; never log a raw cloud response or vehicle identifier. */
    fun diagnostic(first: CloudLockSnapshot?, second: CloudLockSnapshot?, unlockedAtMs: Long): String = when {
        first == null || second == null -> "cloud status unavailable"
        !first.locked || !second.locked -> "cloud does not report two locked states"
        first.updatedAtMs == null || second.updatedAtMs == null -> "cloud update time unavailable"
        first.updatedAtMs <= unlockedAtMs || second.updatedAtMs < first.updatedAtMs ->
            "cloud lock evidence precedes unlock or is out of order"
        else -> "cloud time or local state fails safety gate"
    }

    fun confirmsRelock(
        first: CloudLockSnapshot?, second: CloudLockSnapshot?,
        unlockedAtMs: Long, nowMs: Long,
    ): Boolean {
        if (unlockedAtMs <= 0L || first?.locked != true || second?.locked != true) return false
        val a = first.updatedAtMs ?: return false
        val b = second.updatedAtMs ?: return false
        return a > unlockedAtMs && b >= a &&
            b <= nowMs + FUTURE_CLOCK_SKEW_MS && nowMs - b <= MAX_STATUS_AGE_MS
    }
}
