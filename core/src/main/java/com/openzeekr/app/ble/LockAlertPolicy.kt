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
        /** Automatic BLE Lock acknowledged, but no fresh vehicle state confirmed it. */
        AUTO_LOCK_STATE_UNVERIFIED,
        /** The key link stayed down while the car was unlocked and the user was walking. */
        LINK_LOST_WHILE_UNLOCKED,
        /** The key link stayed down while the car was unlocked, but the phone was not moving. */
        LINK_LOST_STATIONARY,
        /** Departure was suspected but could not be verified: no Lock was sent. */
        DEPARTURE_UNVERIFIED,
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
        Event.AUTO_LOCK_STATE_UNVERIFIED,
        Event.LINK_LOST_WHILE_UNLOCKED,
        Event.DEPARTURE_UNVERIFIED -> true
        Event.LINK_LOST_STATIONARY,
        Event.PROXIMITY_RECOVERED_BEFORE_LOCK,
        Event.MANUAL_CLOUD_LOCK_UNVERIFIED,
        Event.LOCATION_REFERENCE_UNAVAILABLE -> false
    }

    /** False when the event says nothing the user needs to know right now. */
    fun shouldPost(event: Event, bleDepartureRouteActive: Boolean): Boolean =
        !(event == Event.LOCATION_REFERENCE_UNAVAILABLE && bleDepartureRouteActive)
}
