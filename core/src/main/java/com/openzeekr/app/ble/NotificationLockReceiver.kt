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
import java.util.concurrent.atomic.AtomicBoolean

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
        if (!inFlight.compareAndSet(false, true)) {
            Logx.d("prox", "notification Lock tapped again while sending; ignored")
            return
        }
        Logx.w("prox", "notification Lock tapped -> sending Lock")
        // The user has acted on the alarm: stop the sound, keep the record until the Lock is confirmed.
        UnverifiedLockNotifier.silenceAlarm(app)
        UnverifiedLockNotifier.showLockSending(app)
        val pending = goAsync()
        scope.launch {
            try {
                val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { deps.vehicleControl.send(Command.LOCK) }
                val outcome = NotificationLockOutcome.of(result)
                Logx.w("prox", "notification Lock -> $outcome")
                if (outcome == NotificationLockOutcome.FAILED) UnverifiedLockNotifier.showLockFailed(app)
            } finally {
                inFlight.set(false)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOCK = "com.openzeekr.app.NOTIFICATION_LOCK"
        /** Below the background-broadcast limit; BLE needs ~1.5 s, the cloud a few seconds. */
        private const val SEND_TIMEOUT_MS = 30_000L
        private val inFlight = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
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
