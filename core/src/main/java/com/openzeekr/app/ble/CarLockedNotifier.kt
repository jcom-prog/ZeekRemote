package com.openzeekr.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.core.R

/**
 * Audible confirmation that the car itself reported locked (user request 05/10: "als de auto
 * locked wil ik een hoorbare melding krijgen", sound chosen in the phone's notification settings).
 * Posted only on the car's own report (key status "locked" after "open", or a fresh cloud report
 * after a cloud Lock), never on a mere receipt. The sound is the channel's: Settings > Notifications
 * > ZeekRemote > "Auto gesloten".
 */
internal object CarLockedNotifier {
    private const val CHANNEL_ID = "car_locked_v1"
    private const val NOTIFICATION_ID = 45
    /** One chime per Lock: the key status and the cloud can both report the same Lock. */
    private const val DEDUP_MS = 30_000L
    private const val VISIBLE_MS = 60_000L
    @Volatile private var lastShownElapsedMs = 0L

    fun show(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastShownElapsedMs != 0L && now - lastShownElapsedMs < DEDUP_MS) return
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val system = context.getSystemService(NotificationManager::class.java) ?: return
                if (system.getNotificationChannel(CHANNEL_ID) == null)
                    system.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Auto gesloten", NotificationManager.IMPORTANCE_DEFAULT).apply {
                            description = "Sound when the car reports that it is locked. Choose the sound here."
                            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        })
                if (system.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentTitle("Auto gesloten")
                .setContentText("The car reports that it is locked.")
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(VISIBLE_MS)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            lastShownElapsedMs = now
            com.openzeekr.app.util.Logx.d("prox", "car reported locked -> 'Auto gesloten' notification")
        }
    }
}
