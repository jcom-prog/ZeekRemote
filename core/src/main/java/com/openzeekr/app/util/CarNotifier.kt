package com.openzeekr.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.app.net.model.InboxMessage

/**
 * Surfaces messages from the car's message centre (charging complete, security alarms, abnormal
 * parking, service reminders, OTA, …) as Android notifications on their OWN channel — deliberately
 * separate from the ongoing, low-importance "Digital key" foreground-service channel, so a real
 * alert from the car pops a heads-up and can be filtered/silenced independently by the user.
 *
 * There is no server push (see the live-status-polling notes), so [ProximityService] polls the inbox
 * and calls [notify] for each message newer than the last one it surfaced; de-dup is by message id.
 */
object CarNotifier {
    const val CHANNEL_ID = "car_alerts"
    private const val CHANNEL_NAME = "Car notifications"

    /** Intent extra carrying a message-centre deep link (push `url`, e.g. zeekr-overseas://…). */
    const val EXTRA_DEEP_LINK = "com.openzeekr.app.extra.DEEP_LINK"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts your car sends: charging, security, parking and service"
                },
            )
        }
    }

    /** Post one car message. No-ops (via runCatching) if POST_NOTIFICATIONS isn't granted. */
    fun notify(ctx: Context, msg: InboxMessage) {
        ensureChannel(ctx)
        val title = msg.title?.takeIf { it.isNotBlank() } ?: "Zeekr"
        val text = msg.body?.takeIf { it.isNotBlank() }.orEmpty()
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Carry the push/message deep link (redirectUrl) so a tap can route to it; harmless
            // when absent (falls back to plain "open the app").
            ?.apply { msg.redirectUrl?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_DEEP_LINK, it) } }
        val pi = launch?.let {
            PendingIntent.getActivity(
                ctx, msg.notifId(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(AppBranding.statusIcon(ctx))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(msg.notifId(), n) }
    }

    private fun InboxMessage.notifId(): Int = (id ?: "$title:$timeMs").hashCode()
}
