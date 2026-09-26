package com.openzeekr.app.ble

/** Pure decision used by [ProximityService] when a stationary key wakes on real motion. */
internal object DeepSleepRecoveryPolicy {
    enum class Route { OFFLOADED_PRESENCE, FOREGROUND_SCAN }

    enum class WakeTiming { IMMEDIATE, CONFIRM_MOTION }

    /** Only a wake-capable hardware sensor may bypass the anti-relay motion debounce. */
    fun wakeTiming(wakeCapableHardwareSignal: Boolean): WakeTiming =
        if (wakeCapableHardwareSignal) WakeTiming.IMMEDIATE else WakeTiming.CONFIRM_MOTION

    fun route(offloadEnabled: Boolean, presenceArmSucceeded: Boolean): Route =
        if (offloadEnabled && presenceArmSucceeded) Route.OFFLOADED_PRESENCE
        else Route.FOREGROUND_SCAN

    /** Prevent keepConnected's recent-link optimisation from replacing a newly armed screen-off
     * presence listener with the callback scan that the field trace proved is silent in this state. */
    fun keepAliveRoute(
        presenceRecoveryActive: Boolean,
        presenceArmed: Boolean,
        normallyAggressive: Boolean,
    ): Route = if (presenceRecoveryActive && presenceArmed) Route.OFFLOADED_PRESENCE
        else if (normallyAggressive) Route.FOREGROUND_SCAN
        else Route.OFFLOADED_PRESENCE
}
