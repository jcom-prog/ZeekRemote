package com.openzeekr.app.ble

/** Explicit safety gate for the field experiment. Passive departure observations must never actuate. */
internal class AutomaticLockGate(
    private val mode: Mode = Mode.OBSERVE_ONLY,
) {
    enum class Mode { OBSERVE_ONLY, ACTUATE }
    enum class Action { WARN_MANUAL_LOCK, LOCK }

    fun onVerifiedDeparture(): Action = when (mode) {
        Mode.OBSERVE_ONLY -> Action.WARN_MANUAL_LOCK
        Mode.ACTUATE -> Action.LOCK
    }
}
