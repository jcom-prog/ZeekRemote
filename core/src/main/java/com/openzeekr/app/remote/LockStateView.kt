package com.openzeekr.app.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Lock tile shows and which command a tap sends.
 *
 * The cloud status lags behind key (BLE) commands: after a proximity unlock the cloud can still
 * report "locked" for minutes, and a tile that trusts it turns a tap meant to Lock into an Unlock
 * (field test 0.1.52, 02/10). The newest of two sources wins:
 *  - the cloud status, timed by its own updateTime;
 *  - the latest Lock/Unlock the car confirmed over the key session ([LocalLockEvidence]).
 * A key receipt is a vehicle acknowledgement, not proof of physical actuation; it is used here only
 * to choose the display and the tap direction, never to authorize anything.
 *
 * When neither source is known the tile is [LockView.UNKNOWN] and a tap sends Lock: an unknown
 * state must never produce an Unlock.
 */
enum class LockView {
    LOCKED, UNLOCKED, UNKNOWN;

    /** The command a tap sends: Unlock only when the car is known to be locked. */
    val tapLocks: Boolean get() = this != LOCKED

    companion object {
        /** A cloud report must be this much newer than a key receipt to override it (clock skew). */
        const val CLOUD_OVERRIDE_MARGIN_MS = 5_000L

        fun resolve(cloudLocked: Boolean?, cloudUpdatedAtMs: Long?, local: LocalLockEvent?): LockView {
            val cloudView = when (cloudLocked) { true -> LOCKED; false -> UNLOCKED; null -> UNKNOWN }
            if (local == null) return cloudView
            val localView = if (local.locked) LOCKED else UNLOCKED
            if (cloudLocked == null || cloudUpdatedAtMs == null) return localView
            return if (cloudUpdatedAtMs > local.atMs + CLOUD_OVERRIDE_MARGIN_MS) cloudView else localView
        }

        /** Cloud centralLockingStatus: "1" = locked, "0" = unlocked, anything else unknown. */
        fun cloudLocked(centralLockingStatus: String?): Boolean? = when (centralLockingStatus) {
            "1" -> true; "0" -> false; else -> null
        }
    }
}

/** A Lock or Unlock the car acknowledged over the key session, at phone wall-clock time. */
data class LocalLockEvent(val locked: Boolean, val atMs: Long)

/** Process-wide latest key-confirmed Lock/Unlock, fed by the key session. */
object LocalLockEvidence {
    private val _latest = MutableStateFlow<LocalLockEvent?>(null)
    val latest: StateFlow<LocalLockEvent?> = _latest.asStateFlow()

    fun onKeyConfirmed(locked: Boolean, atMs: Long = System.currentTimeMillis()) {
        _latest.value = LocalLockEvent(locked, atMs)
    }
}
