package com.openzeekr.app.wear

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Phone-side BLE-link arbitration for the watch companion.
 *
 * The car accepts only ONE BLE peer at a time, so the phone and the watch must never hold
 * the DK session simultaneously. The phone's foreground keep-alive
 * ([com.openzeekr.app.ble.ProximityService]) is the sole owner of the connection; when the
 * watch needs the car (its "pause" message), the phone flips [linkSuspended] so the
 * keep-alive releases the link and stops reconnecting until the watch is done (its "resume"
 * message) — or until [AUTO_RESUME_MS] passes, in case the watch app dies mid-handover and
 * never sends resume (otherwise the phone would sit disconnected forever).
 *
 * W2 (24/10): the arbiter also remembers when the last hand-over ended, so the proximity engine
 * can tell a lock the car reports right after one apart from the car's own self-lock
 * ([recentHandOver]), and it takes its time from [now] (the simulator injects its clock).
 */
object WearLinkArbiter {
    /** True while the watch is borrowing the car link; the keep-alive must stay off it. */
    val linkSuspended = MutableStateFlow(false)

    /** Time source (wall ms). Production: the system clock; the simulator injects its own. */
    @Volatile var now: () -> Long = { System.currentTimeMillis() }

    @Volatile private var suspendedAtMs = 0L

    /** When the last hand-over ended (the watch's resume or the fail-safe); 0 = never. */
    @Volatile var handOverEndedAtMs = 0L
        private set

    fun suspendForWatch() {
        suspendedAtMs = now()
        linkSuspended.value = true
    }

    /** The watch is done (or the fail-safe reclaims): the keep-alive may take the link back. */
    fun resume() {
        if (linkSuspended.value) handOverEndedAtMs = now()
        suspendedAtMs = 0L
        linkSuspended.value = false
    }

    /** Fail-safe: has the watch held the link past the auto-resume deadline without resuming? */
    fun expired(nowMs: Long = now()): Boolean =
        linkSuspended.value && suspendedAtMs != 0L && nowMs - suspendedAtMs >= AUTO_RESUME_MS

    /** W2 (R2): a hand-over is running or ended less than [RECENT_HAND_OVER_MS] ago - a lock the car reports now is the watch's. */
    fun recentHandOver(nowMs: Long = now()): Boolean =
        linkSuspended.value || (handOverEndedAtMs != 0L && nowMs - handOverEndedAtMs in 0..RECENT_HAND_OVER_MS)

    /** A new process (and every simulator run) starts without any memory of a hand-over. */
    fun reset(clock: () -> Long = { System.currentTimeMillis() }) {
        now = clock
        suspendedAtMs = 0L
        handOverEndedAtMs = 0L
        linkSuspended.value = false
    }

    /** If the watch never sends "resume" (app killed mid-handover), reclaim the link after this. */
    const val AUTO_RESUME_MS = 45_000L

    /** W2: how long after a hand-over a reported lock still counts as the watch's. */
    const val RECENT_HAND_OVER_MS = 60_000L
}
