package com.openzeekr.app.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The car's 0x0111 only acknowledges that it RECEIVED a Lock, not that it locked. Field 04/10 22:05
 * and 05/10 16:08 / 16:18 (0.2.1-0.2.2): Locks at 15-30 m were received but never executed (no
 * "locked" status push, the car stayed open) while the app showed "Locked". Every executed Lock in
 * the journal was followed by the car's own status push central=3 within 0.1-0.9 s (sometimes just
 * before the receipt). A Lock therefore only counts once the car reports locked.
 *
 * Fed with the authenticated status pushes of the current key session; never authorizes anything.
 */
internal class LockExecutionWatch {
    @Volatile var lastCentralLockCode: Int? = null; private set
    private var waiter: CompletableDeferred<Unit>? = null

    @Synchronized fun onStatus(centralLockCode: Int) {
        lastCentralLockCode = centralLockCode
        if (centralLockCode == LOCKED) waiter?.complete(Unit)
    }

    /** A new or dropped session: nothing is known about the car any more. */
    @Synchronized fun reset() {
        lastCentralLockCode = null
        waiter?.cancel(); waiter = null
    }

    /** Call BEFORE the Lock is sent: the car's "3" can precede its receipt. */
    @Synchronized fun arm(): Armed {
        val seen = CompletableDeferred<Unit>()
        waiter?.cancel(); waiter = seen
        return Armed(seen, alreadyLocked = lastCentralLockCode == LOCKED)
    }

    class Armed internal constructor(internal val seen: CompletableDeferred<Unit>, val alreadyLocked: Boolean)

    /**
     * After the receipt: true once the car reports locked within [waitMs]. A car that already
     * reported locked before the Lock may not push again, so that counts as executed.
     */
    suspend fun awaitExecuted(armed: Armed, waitMs: Long): Boolean = try {
        armed.alreadyLocked || armed.seen.isCompleted ||
            withTimeoutOrNull(waitMs) { armed.seen.await() } != null
    } finally {
        synchronized(this) { if (waiter === armed.seen) waiter = null }
    }

    /** True when the last status of this session reported the car open (1 unlocked, 2 partly). */
    val reportedOpen: Boolean get() = lastCentralLockCode.let { it == 1 || it == 2 }

    companion object {
        const val LOCKED = 3
        /** How long after the receipt the car's "locked" push may take (field: at most 0.9 s). */
        const val STATUS_WAIT_MS = 3_000L
    }
}
