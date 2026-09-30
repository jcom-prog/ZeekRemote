package com.openzeekr.app.ble

/** Short-lived RSSI observations while a previously unlocked car's lock state is checked.
 * Only the caller's independently verified relock may restore these into the unlock policy.
 */
internal class RelockApproachEvidence {
    private data class Sample(val atMs: Long, val rssi: Int, val moving: Boolean)
    private val samples = ArrayDeque<Sample>()

    fun clear() = samples.clear()

    fun observe(nowMs: Long, rssi: Int, moving: Boolean) {
        if (samples.lastOrNull()?.atMs?.let { nowMs < it } == true) clear()
        samples.addLast(Sample(nowMs, rssi, moving))
        while (samples.size > 64 || samples.first().atMs < nowMs - WINDOW_MS) samples.removeFirst()
    }

    fun restoreAfterVerifiedRelock(policy: ProximityDecisionPolicy, nowMs: Long, threshold: Int) {
        policy.resetLocked()
        val recent = samples.filter { it.atMs in (nowMs - WINDOW_MS)..nowMs }
        // Standing next to a car is insufficient: retain only a recent moving approach.
        if (recent.any { it.moving && policy.isStrongNear(it.rssi) }) {
            recent.forEach { policy.shouldUnlock(it.atMs, it.rssi, it.moving, threshold) }
        }
        clear()
    }

    private companion object { const val WINDOW_MS = 5_000L }
}
