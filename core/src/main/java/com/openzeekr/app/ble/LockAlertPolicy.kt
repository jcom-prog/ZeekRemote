package com.openzeekr.app.ble

/**
 * Which lock warnings exist and which of them must be heard.
 *
 * The user's screen is normally off while walking away, so a silent notification is not a warning
 * (field test 0.1.52, 02/10). Only situations where an automatic Lock was expected — the user is
 * leaving or has left — but the car's Lock is not confirmed sound an audible alarm. Situations in
 * which the user is at the car, or has just operated the app, stay silent. A reminder at every
 * unlock is not posted at all.
 */
internal object LockAlertPolicy {
    enum class Event {
        /** Automatic BLE Lock failed after its retries (cloud fallback may or may not have worked). */
        AUTO_LOCK_UNCONFIRMED,
        /** Automatic BLE Lock failed, but a cloud report newer than the attempt says locked. */
        AUTO_LOCK_CLOUD_CONFIRMED,
        /** Automatic BLE Lock acknowledged by the car, but no fresh cloud state confirmed it yet. */
        AUTO_LOCK_STATE_UNVERIFIED,
        /** The key link stayed down while the car was unlocked and the user was walking. */
        LINK_LOST_WHILE_UNLOCKED,
        /** The key link stayed down while the car was unlocked, but the phone was not moving. */
        LINK_LOST_STATIONARY,
        /** Departure was suspected but could not be verified: no Lock was sent. */
        DEPARTURE_UNVERIFIED,
        /** A weak-signal departure candidate that may be body shadowing at the car: no Lock sent. */
        DEPARTURE_CANDIDATE_UNVERIFIED,
        /** A pending Lock was withheld because the phone is near the car again. */
        PROXIMITY_RECOVERED_BEFORE_LOCK,
        /** The user's own cloud Lock was accepted, but its actuation is unverified. */
        MANUAL_CLOUD_LOCK_UNVERIFIED,
        /** No GNSS reference after unlock (matters only when GNSS is the sole departure route). */
        LOCATION_REFERENCE_UNAVAILABLE,
    }

    /** True when the event must produce sound even with the screen off. */
    fun audible(event: Event): Boolean = when (event) {
        Event.AUTO_LOCK_UNCONFIRMED,
        Event.LINK_LOST_WHILE_UNLOCKED,
        Event.DEPARTURE_UNVERIFIED -> true
        // The car acknowledged the Lock; the cloud often lags by minutes (review 0.1.53).
        Event.AUTO_LOCK_STATE_UNVERIFIED,
        Event.AUTO_LOCK_CLOUD_CONFIRMED,
        Event.DEPARTURE_CANDIDATE_UNVERIFIED,
        Event.LINK_LOST_STATIONARY,
        Event.PROXIMITY_RECOVERED_BEFORE_LOCK,
        Event.MANUAL_CLOUD_LOCK_UNVERIFIED,
        Event.LOCATION_REFERENCE_UNAVAILABLE -> false
    }

    /**
     * The user walked away: a moving walk-away candidate happened in this unlock and no strong
     * at-the-car reading was seen since (field test 0.1.53 part B: walked 30-40 m, stopped,
     * link dropped while standing still -> must not be treated as a stationary loss).
     */
    fun departureSuspected(lastWalkAwayCandidateAtMs: Long, lastStrongNearAtMs: Long): Boolean =
        lastWalkAwayCandidateAtMs != 0L && lastWalkAwayCandidateAtMs > lastStrongNearAtMs

    /** A link loss is audible when the user was walking during it or had already walked away. */
    fun linkLossEvent(movedDuringLoss: Boolean, departureSuspected: Boolean): Event =
        if (movedDuringLoss || departureSuspected) Event.LINK_LOST_WHILE_UNLOCKED else Event.LINK_LOST_STATIONARY

    /**
     * After a suspected departure, the car must be locked within [DEPARTURE_ALARM_DELAY_MS] or the
     * alarm sounds — unless the phone is near the car again ([latestRssi] above the lock threshold).
     * A missing reading (link down) counts as away.
     */
    fun departureAlarmDue(departureSuspected: Boolean, stillUnlocked: Boolean, latestRssi: Int?, lockThreshold: Int): Boolean =
        departureSuspected && stillUnlocked && (latestRssi == null || latestRssi <= lockThreshold)

    const val DEPARTURE_ALARM_DELAY_MS = 30_000L

    /** False when the event says nothing the user needs to know right now. */
    fun shouldPost(event: Event, bleDepartureRouteActive: Boolean): Boolean =
        !(event == Event.LOCATION_REFERENCE_UNAVAILABLE && bleDepartureRouteActive)
}
