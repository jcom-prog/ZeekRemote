package com.openzeekr.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Lock" button on a lock warning (user choice 03/10: alarm + Lock button, usable from the lock
 * screen without unlocking the phone). Sends Lock through [com.openzeekr.app.remote.VehicleControl]
 * (BLE first, cloud fallback). A confirmed BLE Lock clears the warnings and a cloud acceptance posts
 * the "unverified" record through the existing callbacks; only a failure is reported here. Only ever
 * locks.
 */
class NotificationLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCK) return
        val app = context.applicationContext
        val deps = (app as? DepsHolder)?.deps ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!NotificationLockGuard.tryStart(now)) {
            Logx.d("prox", "notification Lock tapped again while sending; ignored")
            return
        }
        Logx.w("prox", "notification Lock tapped -> sending Lock")
        // The user has acted on the alarm: stop the sound (audible warnings stay quiet while the Lock
        // is on its way), keep the record until the Lock is confirmed.
        UnverifiedLockNotifier.onUserLockStarted(app)
        val pending = goAsync()
        val send = scope.launch {
            // The send is not cancelled by the broadcast deadline: the cloud path can take longer than a
            // receiver may live, and a cancelled request could already have reached the car.
            val result = try {
                runCatching { deps.vehicleControl.send(Command.LOCK) }.getOrNull()
            } finally {
                NotificationLockGuard.finish()
                UnverifiedLockNotifier.onUserLockFinished()
            }
            val outcome = NotificationLockOutcome.of(result)
            Logx.w("prox", "notification Lock -> $outcome")
            if (outcome == NotificationLockOutcome.FAILED) UnverifiedLockNotifier.showLockFailed(app)
        }
        scope.launch {
            // Hand the broadcast back well within its limit; the running service keeps the process.
            withTimeoutOrNull(RECEIVER_HOLD_MS) { send.join() }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_LOCK = "com.openzeekr.app.NOTIFICATION_LOCK"
        /** Well below the background-broadcast limit; BLE needs ~1.5 s, the cloud a few seconds. */
        private const val RECEIVER_HOLD_MS = 20_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * One notification Lock at a time. A send that never returns (process trouble) releases the guard
 * after [MAX_IN_FLIGHT_MS], so the button is never dead for good.
 */
internal object NotificationLockGuard {
    const val MAX_IN_FLIGHT_MS = 90_000L
    private var startedAtMs = 0L

    @Synchronized fun tryStart(nowElapsedMs: Long): Boolean {
        if (startedAtMs != 0L && nowElapsedMs - startedAtMs in 0 until MAX_IN_FLIGHT_MS) return false
        startedAtMs = nowElapsedMs
        return true
    }

    @Synchronized fun finish() { startedAtMs = 0L }

    @Synchronized fun active(nowElapsedMs: Long): Boolean =
        startedAtMs != 0L && nowElapsedMs - startedAtMs in 0 until MAX_IN_FLIGHT_MS
}

/** What the notification Lock achieved, as far as the phone can tell. */
internal enum class NotificationLockOutcome {
    /** The car confirmed the Lock over the key link. */
    CONFIRMED_BY_CAR,
    /** The cloud accepted the command; actuation unverified (the existing record says so). */
    ACCEPTED_BY_CLOUD,
    /** Neither path worked, or no answer within the time limit. */
    FAILED;

    companion object {
        fun of(result: CallResult<com.openzeekr.app.net.model.RemoteControlResponse>?): NotificationLockOutcome = when {
            result !is CallResult.Ok -> FAILED
            result.value.status == com.openzeekr.app.remote.VehicleControl.BLE_OK_STATUS -> CONFIRMED_BY_CAR
            else -> ACCEPTED_BY_CLOUD
        }
    }
}
