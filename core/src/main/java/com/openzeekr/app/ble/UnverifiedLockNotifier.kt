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
    private const val CHANNEL_ID = "unverified_lock"
    private const val ALARM_CHANNEL_ID = "lock_alarm_v1"
    private const val NOTIFICATION_ID = 43
    private const val ALARM_NOTIFICATION_ID = 44
    /** The alarm repeats until the user opens it, the Lock is confirmed, or this time passes. */
    private const val ALARM_TIMEOUT_MS = 120_000L

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
            Event.DEPARTURE_UNVERIFIED, Event.PROXIMITY_RECOVERED_BEFORE_LOCK ->
                POSSIBLE_DEPARTURE_TITLE to POSSIBLE_DEPARTURE_TEXT
            Event.LOCATION_REFERENCE_UNAVAILABLE -> "Automatic Lock unavailable" to
                "No accurate location reference is available. Lock manually and check the car before leaving."
        }
        val posted = post(context, CHANNEL_ID, NOTIFICATION_ID, title, message, alarm = false)
        if (LockAlertPolicy.audible(event))
            post(context, ALARM_CHANNEL_ID, ALARM_NOTIFICATION_ID, title, message, alarm = true)
        return posted
    }

    private fun ensureChannels(system: NotificationManager) {
        if (system.getNotificationChannel(CHANNEL_ID) == null)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Unverified vehicle lock", NotificationManager.IMPORTANCE_HIGH))
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
        if (alarm) {
            // Pre-O devices take the sound from the notification itself.
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), android.media.AudioManager.STREAM_ALARM)
                .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
                .setAutoCancel(true)
                .setTimeoutAfter(ALARM_TIMEOUT_MS)
        } else {
            // The visible record stays until Lock is confirmed; it never makes sound itself.
            builder.setSilent(true).setAutoCancel(false).setOngoing(true)
        }
        val notification = builder.build()
        if (alarm) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(id, notification)
        true
    }.getOrDefault(false)

    /** Clear only when a new unlock starts, or an explicit BLE/manual lock was confirmed. */
    fun clear(context: Context) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(ALARM_NOTIFICATION_ID)
            manager.cancel(NOTIFICATION_ID)
        }
    }
}
