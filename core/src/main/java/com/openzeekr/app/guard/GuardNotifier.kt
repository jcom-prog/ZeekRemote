package com.openzeekr.app.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.app.util.AppBranding

/**
 * 0.4.5: the guard automaton's notifications - "not confirmed by the car" and "switched off for the battery".
 * 0.4.6: the open-window notice carries a "Close windows" button ([GuardActionReceiver]) and the daily check
 * reports on its own id. Own channel (Settings > Notifications > ZeekRemote > "Sentry guard"), default
 * importance, no alarm sound; nothing in the text identifies the car or a place.
 */
internal object GuardNotifier {
    private const val CHANNEL_ID = "guard_v1"
    const val NOTIFICATION_ID = 46
    const val WINDOWS_NOTIFICATION_ID = 47
    private const val CLOSE_WINDOWS_REQUEST = 4601
    private const val VISIBLE_MS = 2 * 60 * 60 * 1000L

    fun show(context: Context, title: String, text: String, action: GuardAction? = null) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val system = context.getSystemService(NotificationManager::class.java) ?: return
                if (system.getNotificationChannel(CHANNEL_ID) == null)
                    system.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Sentry guard", NotificationManager.IMPORTANCE_DEFAULT).apply {
                            description = "The guard automaton: not confirmed by the car, switched off for the battery, open windows, the daily check."
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        })
                if (system.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(AppBranding.statusIcon(context))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(VISIBLE_MS)
            if (action == GuardAction.CLOSE_WINDOWS) builder.addAction(closeWindowsAction(context))
            val windows = action != null || title.startsWith("Windows")
            manager.notify(if (windows) WINDOWS_NOTIFICATION_ID else NOTIFICATION_ID, builder.build())
        }
    }

    /** The "Close windows" button: our own broadcast, handled by [GuardActionReceiver]; the phone must be unlocked. */
    private fun closeWindowsAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, GuardActionReceiver::class.java).setAction(GuardActionReceiver.ACTION_CLOSE_WINDOWS)
        val pending = PendingIntent.getBroadcast(context, CLOSE_WINDOWS_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(AppBranding.statusIcon(context), "Close windows", pending)
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .build()
    }
}
