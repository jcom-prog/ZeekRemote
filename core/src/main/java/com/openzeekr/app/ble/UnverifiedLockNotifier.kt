package com.openzeekr.app.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.core.R

/** A separate, visible warning when proximity can no longer confirm the car's lock state. */
internal object UnverifiedLockNotifier {
    private const val CHANNEL_ID = "unverified_lock"
    private const val NOTIFICATION_ID = 43

    fun show(context: Context): Boolean = post(context,
        "Vehicle lock not confirmed",
        "ZeekRemote cannot confirm the car is locked. Lock manually and check the car.")

    fun showManualLockRequired(context: Context): Boolean = post(context,
        "Check vehicle Lock before leaving",
        "Automatic Lock requires verified departure. Check the car and use Lock manually if it remains open.")

    fun showPossibleDeparture(context: Context): Boolean = post(context,
        "Possible departure: lock manually",
        "Departure could not be verified. The car may still be unlocked. Lock manually and check the car.")

    private fun post(context: Context, title: String, message: String): Boolean = runCatching {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val system = context.getSystemService(NotificationManager::class.java) ?: return false
            if (system.getNotificationChannel(CHANNEL_ID) == null) {
                system.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Unverified vehicle lock", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            if (system.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE)
                return false
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = launch?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .apply { tap?.let { setContentIntent(it) } }
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    /** Clear only when a new unlock starts, or an explicit BLE/manual lock was confirmed. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
