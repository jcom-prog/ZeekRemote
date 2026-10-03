package com.openzeekr.app.net

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cross-cutting session signals surfaced to the UI (outside the request/response types).
 */
object SessionSignal {
    /**
     * Flips true when the TSP gateway reports the account is active on another device
     * (`079021 "The account is currently logged in elsewhere"`) — e.g. the stock Zeekr app
     * grabbed the single online slot for the same account. [KickoutInterceptor] also clears
     * the token, so the UI reflects the signed-out state; the UI shows this to explain why.
     * Reset it to false once the notice has been shown.
     */
    val loggedInElsewhere = MutableStateFlow(false)

    /** Count of 079021 kick-outs seen (never reset; the UI resets [loggedInElsewhere] after showing it). */
    @Volatile var kickouts: Long = 0L
}
