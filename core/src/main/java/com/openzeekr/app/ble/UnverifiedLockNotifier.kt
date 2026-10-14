package com.openzeekr.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.app.ble.LockAlertPolicy.Event
import com.openzeekr.core.R

/**
 * Visible warning when proximity can no longer confirm the car's lock state, plus an audible alarm
 * when an expected automatic Lock is not confirmed ([LockAlertPolicy]). The screen is normally off
 * while walking away, so a silent notification alone is not a warning.
 */
internal object UnverifiedLockNotifier {
    /**
     * The visible record. Field 05/10 16:08 (0.2.2): posted as a silent notification it never showed
     * on the Samsung lock screen (One UI hides silent notifications there). Its own high-importance
     * channel without sound keeps it in the alerting section; the alarm channel makes the sound.
     */
    private const val CHANNEL_ID = "car_not_locked_v2"
    private const val LEGACY_CHANNEL_ID = "unverified_lock"
    private const val ALARM_CHANNEL_ID = "lock_alarm_v1"
    private const val NOTIFICATION_ID = 43
    private const val RECORD_GROUP = "zeekremote_lock_state"
    private const val ALARM_GROUP = "zeekremote_lock_alarm"
    private const val ALARM_NOTIFICATION_ID = 44
    /** The alarm repeats until the user opens it, the Lock is confirmed, or this time passes. */
    private const val ALARM_TIMEOUT_MS = LockAlertPolicy.ALARM_EPISODE_MS
    /** Elapsed time of the alarm of the current unresolved episode; 0 = none. */
    @Volatile private var alarmRaisedAtElapsedMs = 0L

    private const val LOCK_ACTION_REQUEST = 4401
    private const val LOCK_SENDING_TITLE = "Locking the car..."
    private const val LOCK_SENDING_TEXT = "Lock sent from the notification. This warning clears when the car confirms it."
    private const val LOCK_FAILED_TITLE = "Lock failed: car may be unlocked"
    private const val LOCK_FAILED_TEXT =
        "Neither Bluetooth nor the cloud could lock the car. Tap Lock to try again, or lock at the car."

    private const val NOT_CONFIRMED_TITLE = "Vehicle lock not confirmed"
    private const val NOT_CONFIRMED_TEXT = "ZeekRemote cannot confirm the car is locked. Lock manually and check the car."
    private const val POSSIBLE_DEPARTURE_TITLE = "Possible departure: lock manually"
    private const val POSSIBLE_DEPARTURE_TEXT =
        "Departure could not be verified. The car may still be unlocked. Lock manually and check the car."

    /** Posts the warning for [event]; true when a notification was posted. */
    fun show(context: Context, event: Event, bleDepartureRouteActive: Boolean = false): Boolean {
        if (!LockAlertPolicy.shouldPost(event, bleDepartureRouteActive)) return false
        val (title, message) = when (event) {
            Event.AUTO_LOCK_UNCONFIRMED, Event.AUTO_LOCK_STATE_UNVERIFIED, Event.LINK_LOST_WHILE_UNLOCKED,
            Event.LINK_LOST_STATIONARY,
            Event.MANUAL_CLOUD_LOCK_UNVERIFIED -> NOT_CONFIRMED_TITLE to NOT_CONFIRMED_TEXT
            Event.DEPARTURE_UNVERIFIED, Event.DEPARTURE_CANDIDATE_UNVERIFIED, Event.PROXIMITY_RECOVERED_BEFORE_LOCK ->
                POSSIBLE_DEPARTURE_TITLE to POSSIBLE_DEPARTURE_TEXT
            Event.AUTO_LOCK_CLOUD_CONFIRMED -> "Car reported locked via cloud" to
                "The Bluetooth Lock was not confirmed; a newer cloud report says the car is locked. Check the car if in doubt."
            Event.LOCATION_REFERENCE_UNAVAILABLE -> "Automatic Lock unavailable" to
                "No accurate location reference is available. Lock manually and check the car before leaving."
            Event.LOCK_NOT_EXECUTED -> "Car NOT locked" to
                "The car received the Lock but did not lock. Check doors, boot, bonnet and windows, then lock again."
        }
        val lockButton = LockAlertPolicy.offersLock(event)
        val posted = post(context, CHANNEL_ID, NOTIFICATION_ID, title, message, alarm = false, lockButton = lockButton)
        if (LockAlertPolicy.audible(event)) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (NotificationLockGuard.active(now)) {
                // The user just tapped Lock; its outcome (confirmed, cloud, or "Lock failed") decides.
                com.openzeekr.app.util.Logx.w("prox", "lock alarm held: notification Lock in flight (${event.name})")
                return posted
            }
            if (!LockAlertPolicy.alarmShouldSound(alarmRaisedAtElapsedMs, now, alarmShowing(context))) {
                com.openzeekr.app.util.Logx.w("prox", "lock alarm suppressed (${event.name})")
                return posted
            }
            val sounded = post(context, ALARM_CHANNEL_ID, ALARM_NOTIFICATION_ID, title, message, alarm = true,
                lockButton = lockButton)
            val fallback = soundFallbackIfMuted(context, sounded)
            if (sounded || fallback) alarmRaisedAtElapsedMs = now
            com.openzeekr.app.util.Logx.w("prox", "lock alarm ${if (sounded) "raised" else "unavailable"}" +
                "${if (fallback) " + fallback sound (notifications muted)" else ""} (${event.name})")
        } else if (LockAlertPolicy.resolvesAlarm(event)) {
            // Only a resolved situation (car locked, user back at the car) ends a sounding alarm.
            silenceAlarm(context)
        }
        return posted
    }

    /**
     * The notification Lock button was tapped: the sound stops and the record shows that a Lock is on
     * its way (with the button, so a lost outcome never leaves a dead record). Audible warnings are
     * held until the outcome ([NotificationLockGuard]).
     */
    fun onUserLockStarted(context: Context) {
        FallbackAlarmSound.stop()
        runCatching { NotificationManagerCompat.from(context).cancel(ALARM_NOTIFICATION_ID) }
        post(context, CHANNEL_ID, NOTIFICATION_ID, LOCK_SENDING_TITLE, LOCK_SENDING_TEXT, alarm = false, lockButton = true)
    }

    /** The outcome is in: a later audible warning is a new episode and sounds again. */
    fun onUserLockFinished() { alarmRaisedAtElapsedMs = 0L }

    /** Neither the key link nor the cloud locked the car: the full alarm again, with the Lock button. */
    fun showLockFailed(context: Context) {
        post(context, CHANNEL_ID, NOTIFICATION_ID, LOCK_FAILED_TITLE, LOCK_FAILED_TEXT, alarm = false, lockButton = true)
        val sounded = post(context, ALARM_CHANNEL_ID, ALARM_NOTIFICATION_ID, LOCK_FAILED_TITLE, LOCK_FAILED_TEXT,
            alarm = true, lockButton = true)
        val fallback = soundFallbackIfMuted(context, sounded)
        if (sounded || fallback) alarmRaisedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        com.openzeekr.app.util.Logx.w("prox", "lock alarm ${if (sounded) "raised" else "unavailable"}" +
            "${if (fallback) " + fallback sound" else ""} (notification Lock failed)")
    }

    /**
     * Plan K5: notifications off or the alarm channel muted - the alarm would be silent. Sounds the
     * alarm tone directly instead; true when it did.
     */
    private fun soundFallbackIfMuted(context: Context, posted: Boolean): Boolean {
        if (posted && LockAlarmHealth.alarmAudible(context)) return false
        FallbackAlarmSound.play(context)
        return true
    }

    /** Importance of the warning and alarm channels (null before they exist), for [LockAlarmHealth]. */
    internal fun channelImportances(context: Context): Pair<Int?, Int?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null to null
        val system = context.getSystemService(NotificationManager::class.java) ?: return null to null
        runCatching { ensureChannels(system) }
        return system.getNotificationChannel(CHANNEL_ID)?.importance to
            system.getNotificationChannel(ALARM_CHANNEL_ID)?.importance
    }

    private fun ensureChannels(system: NotificationManager) {
        if (system.getNotificationChannel(CHANNEL_ID) == null)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Car not locked", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Stays visible, also on the lock screen, until the car is locked."
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
        if (system.getNotificationChannel(LEGACY_CHANNEL_ID) != null)
            runCatching { system.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        if (system.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
            system.createNotificationChannel(
                NotificationChannel(ALARM_CHANNEL_ID, "Car not locked alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Sounds when an expected automatic Lock is not confirmed."
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
        }
    }

    private fun post(
        context: Context, channel: String, id: Int, title: String, message: String, alarm: Boolean,
        lockButton: Boolean,
    ): Boolean = runCatching {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val system = context.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannels(system)
            if (system.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE)
                return false
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = launch?.let {
            PendingIntent.getActivity(context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (alarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { tap?.let { setContentIntent(it) } }
            .apply { if (lockButton) addAction(lockAction(context)) }
        if (alarm) {
            // Sound and vibration come from the alarm channel (minSdk 26).
            builder.setAutoCancel(true).setTimeoutAfter(ALARM_TIMEOUT_MS).setGroup(ALARM_GROUP)
        } else {
            // The visible record stays until Lock is confirmed. Its channel has no sound; it is NOT
            // flagged silent, so the lock screen shows it, and it is not folded into an app group.
            builder.setOnlyAlertOnce(true).setAutoCancel(false).setOngoing(true).setGroup(RECORD_GROUP)
        }
        val notification = builder.build()
        if (alarm) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(id, notification)
        true
    }.getOrDefault(false)

    /** "Lock" without unlocking the phone; it only ever locks (see [NotificationLockReceiver]). */
    private fun lockAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, NotificationLockReceiver::class.java).setAction(NotificationLockReceiver.ACTION_LOCK)
        val pending = PendingIntent.getBroadcast(context, LOCK_ACTION_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_logo, "Lock", pending)
            .setAuthenticationRequired(false)
            .setShowsUserInterface(false)
            .build()
    }

    private fun alarmShowing(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.any { it.id == ALARM_NOTIFICATION_ID } == true
    }.getOrDefault(true)

    /** Stops a sounding alarm but keeps the visible record (e.g. the user is back in range). */
    fun silenceAlarm(context: Context) {
        alarmRaisedAtElapsedMs = 0L
        FallbackAlarmSound.stop()
        runCatching { NotificationManagerCompat.from(context).cancel(ALARM_NOTIFICATION_ID) }
    }

    /** Clear only when a new unlock starts, or an explicit BLE/manual lock was confirmed. */
    fun clear(context: Context) {
        alarmRaisedAtElapsedMs = 0L
        FallbackAlarmSound.stop()
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(ALARM_NOTIFICATION_ID)
            manager.cancel(NOTIFICATION_ID)
        }
    }
}
