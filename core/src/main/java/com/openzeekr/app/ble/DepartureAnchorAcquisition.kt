package com.openzeekr.app.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Retry a poor initial fix without moving the reference window into a later departure. */
internal object DepartureAnchorAcquisition {
    private const val WINDOW_MS = 7_000L
    private const val MAX_ATTEMPTS = 3

    suspend fun acquire(
        unlockedAtElapsedMs: Long,
        now: () -> Long,
        enabled: () -> Boolean,
        current: suspend () -> DepartureFix?,
        pause: suspend () -> Unit = { delay(600L) },
    ): DepartureFix? {
        repeat(MAX_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            if (!enabled() || now() > unlockedAtElapsedMs + WINDOW_MS) return null
            val fix = current()
            coroutineContext.ensureActive()
            if (!enabled() || now() > unlockedAtElapsedMs + WINDOW_MS) return null
            if (fix != null && DepartureSafetyEvidence.valid(fix) &&
                fix.elapsedAtMs in (unlockedAtElapsedMs - 1_000L)..now()) return fix
            if (attempt < MAX_ATTEMPTS - 1) pause()
        }
        return null
    }
}
