package com.openzeekr.app.ble

/**
 * 0.3.21 (field 20/10): how the hardware presence scan follows a requested duty cycle within the scan-start ration
 * ([ScanStartLimiter]). The car did not open on a walk out to it: two connects at the edge of range ended in status 133,
 * the presence scan the recovery had armed was stopped for a mode change, the ration refused the restart, and for 24 s
 * nothing listened for the car. A running scan now keeps running unless its restart can start, and a mere change of
 * duty cycle - like a foreground scan next to a listening presence scan - leaves [RESERVE] start for what cannot wait:
 * the scan a failed connect needs. Pure Kotlin.
 */
internal object PresenceScanArmPolicy {
    enum class Action {
        /** The scan runs (in this mode, or in another one the ration cannot afford to change). */
        KEEP,
        /** Stop the running scan and register it in the new mode. */
        RESTART,
        /** No scan runs: register one. */
        START,
        /** No scan runs and the ration is spent: try again when a start is free. */
        DEFER,
    }

    /** [armed]: a presence scan is registered; [sameMode]: in the requested mode; [freeStarts]: starts left now. */
    fun decide(armed: Boolean, sameMode: Boolean, freeStarts: Int): Action = when {
        armed && sameMode -> Action.KEEP
        armed -> if (freeStarts > RESERVE) Action.RESTART else Action.KEEP
        freeStarts > 0 -> Action.START
        else -> Action.DEFER
    }

    /** A foreground scan next to a listening presence scan only speeds a connect up: only with a start in reserve. */
    fun mayScanAlongside(freeStarts: Int): Boolean = freeStarts > RESERVE

    const val RESERVE = 1
}
