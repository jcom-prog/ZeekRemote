package com.openzeekr.app.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.app.util.AppBranding

/**
 * 0.4.5: the guard automaton's notifications - "not confirmed by the car" and "switched off for the battery".
 * Its own channel (Settings > Notifications > ZeekRemote > "Sentry guard"), default importance, no alarm sound;
 * nothing in the text identifies the car or a place.
 */
internal object GuardNotifier {
    private const val CHANNEL_ID = "guard_v1"
    private const val NOTIFICATION_ID = 46
    private const val VISIBLE_MS = 2 * 60 * 60 * 1000L

    fun show(context: Context, title: String, text: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val system = context.getSystemService(NotificationManager::class.java) ?: return
                if (system.getNotificationChannel(CHANNEL_ID) == null)
                    system.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Sentry guard", NotificationManager.IMPORTANCE_DEFAULT).apply {
                            description = "The guard automaton: not confirmed by the car, switched off for the battery."
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        })
                if (system.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(AppBranding.statusIcon(context))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(VISIBLE_MS)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
