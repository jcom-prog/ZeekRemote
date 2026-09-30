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
        diagnostic: (Int, String) -> Unit = { _, _ -> },
    ): DepartureFix? {
        repeat(MAX_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            if (!enabled()) { diagnostic(attempt + 1, "session_disabled"); return null }
            if (now() > unlockedAtElapsedMs + WINDOW_MS) {
                diagnostic(attempt + 1, "window_expired_before_request"); return null
            }
            val fix = current()
            coroutineContext.ensureActive()
            if (!enabled()) { diagnostic(attempt + 1, "session_disabled"); return null }
            if (now() > unlockedAtElapsedMs + WINDOW_MS) {
                diagnostic(attempt + 1, "window_expired_during_request"); return null
            }
            val rejection = DepartureAnchorDiagnostic.rejection(fix, unlockedAtElapsedMs, now())
            diagnostic(attempt + 1, rejection ?: "accepted")
            if (rejection == null) return fix
            if (attempt < MAX_ATTEMPTS - 1) pause()
        }
        return null
    }
}
