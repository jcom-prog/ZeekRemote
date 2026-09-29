package com.openzeekr.app.ble

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Every cloud retry needs its own departure observation; earlier evidence cannot authorize it. */
internal object DepartureCheckedLockAttempt {
    /** Null means no command was authorized; false means the command was sent but rejected. */
    suspend fun send(
        enabled: () -> Boolean,
        departure: suspend () -> Boolean,
        command: suspend () -> Boolean,
    ): Boolean? {
        coroutineContext.ensureActive()
        if (!enabled() || !departure()) return null
        coroutineContext.ensureActive()
        if (!enabled()) return null
        return command()
    }
}
