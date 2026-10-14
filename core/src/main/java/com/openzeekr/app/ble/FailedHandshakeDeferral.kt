package com.openzeekr.app.ble

/**
 * Another Zeekr nearby (plan H3, 0.3.1). Every Zeekr advertises the same identifiers and, with the
 * screen off, often no name, so the scan cannot tell our car from another before connecting; the
 * key module also rotates its address, so no address can be learned for good. Field 12/10: ~35 s of
 * doomed handshakes with a car parked nearby. What can be known: the address whose DK handshake
 * just failed. For [REMEMBER_MS] such an address waits [DEFER_MS] into each scan, so any other
 * candidate - our car - is tried first. It is never refused: our own car can fail a handshake too
 * (a stale session, the edge of range), and refusing it would leave the user without a key.
 * Pure Kotlin.
 */
internal class FailedHandshakeDeferral {
    private val failedAt = HashMap<String, Long>()

    fun onFailure(address: String, nowMs: Long) {
        failedAt.entries.removeAll { nowMs - it.value >= REMEMBER_MS }
        failedAt[address] = nowMs
    }

    fun onSuccess(address: String) { failedAt.remove(address) }

    /** True while [address] should still wait in the scan that started at [scanStartedAtMs]. */
    fun deferNow(address: String, nowMs: Long, scanStartedAtMs: Long): Boolean {
        val at = failedAt[address] ?: return false
        if (nowMs - at >= REMEMBER_MS || nowMs < at) { failedAt.remove(address); return false }
        return nowMs - scanStartedAtMs < DEFER_MS
    }

    companion object {
        const val REMEMBER_MS = 10 * 60_000L
        const val DEFER_MS = 3_000L
    }
}
