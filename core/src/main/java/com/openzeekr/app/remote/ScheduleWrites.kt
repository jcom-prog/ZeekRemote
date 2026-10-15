package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a car-side schedule write ended, once the cloud accepted it. */
enum class CarWrite {
    /** The car's read-back shows it: the car stored it. */
    CONFIRMED,

    /**
     * The cloud accepted it, but the car did not show it within [ScheduleWrites.CONFIRM_WAIT_MS]. The car
     * is probably asleep and applies it when it wakes (or the cloud drops it). Not a failure, and not
     * something to send again.
     */
    PENDING,
}

/**
 * Bookkeeping for the car-side schedule writes (0.3.8, user decision 15/10). A write only counts as
 * stored once the car's read-back shows it; until then the cloud holds it.
 *  - [busy]: the one write in flight. The app waits up to [CONFIRM_WAIT_MS] for the car, the screen shows
 *    that it is waiting, and no second write starts meanwhile.
 *  - [awaiting]: a NEW departure the cloud accepted that the car has not shown yet. Create stays refused
 *    while it waits (another press would queue a second plan), until a read-back shows it or [HOLD_MS]
 *    passes (the cloud may have dropped it). Kept for the process lifetime only.
 */
class ScheduleWrites(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    enum class Kind { CHARGE, CREATE, EDIT, SWITCH, DELETE }

    data class Awaiting(val plan: BookingTravelSetting, val sentAtMs: Long)

    private val _busy = MutableStateFlow<Kind?>(null)
    val busy: StateFlow<Kind?> = _busy.asStateFlow()

    private val _awaiting = MutableStateFlow<Awaiting?>(null)
    val awaiting: StateFlow<Awaiting?> = _awaiting.asStateFlow()

    /** Runs [write] as the one write in flight; null, and nothing run, while another one still waits. */
    suspend fun <T> exclusive(kind: Kind, write: suspend () -> T): T? {
        if (!_busy.compareAndSet(null, kind)) return null
        try {
            return write()
        } finally {
            _busy.value = null
        }
    }

    /** True when a new departure may be sent: no write in flight and no new departure waiting for the car. */
    fun canCreate(): Boolean = _busy.value == null && stillAwaiting() == null

    /** The cloud accepted [plan] as a new departure: Create waits until the car shows it. */
    fun createSent(plan: BookingTravelSetting) {
        _awaiting.value = Awaiting(plan, nowMs())
    }

    /**
     * A fresh read-back of the car's plans. True when it shows the new departure that was waiting, which
     * then stops waiting (it is stored on the car).
     */
    fun readBack(plans: List<BookingTravelSetting>): Boolean {
        val a = stillAwaiting() ?: return false
        if (!Departures.landed(plans, a.plan)) return false
        _awaiting.compareAndSet(a, null)
        return true
    }

    /** The new departure still waiting for the car; a wait older than [HOLD_MS] is dropped here. */
    fun stillAwaiting(): Awaiting? {
        val a = _awaiting.value ?: return null
        if (nowMs() - a.sentAtMs < HOLD_MS) return a
        _awaiting.compareAndSet(a, null)
        return null
    }

    companion object {
        /** How long a write waits for the car to show it (was 16 s up to 0.3.7). */
        const val CONFIRM_WAIT_MS = 60_000L
        const val CONFIRM_POLL_MS = 3_000L

        /** How long a new departure the car has not shown blocks the next Create. */
        const val HOLD_MS = 10 * 60_000L

        const val BUSY =
            "Another schedule change is still waiting for the car. Try again when it has finished."
        const val CREATE_WAITS =
            "A new departure is still waiting for the car. You can add another once it appears in the list, " +
                "or after 10 minutes."
    }
}
