package com.openzeekr.app.ble

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat

/**
 * Can the "car not locked" warning reach the user? (plan K5, 0.3.0). With notifications switched
 * off, or the alarm channel muted, the alarm was posted nowhere and the car stayed open in silence.
 * The app shows these problems as a banner; [FallbackAlarmSound] sounds the alarm without them.
 */
object LockAlarmHealth {
    enum class Problem(val text: String) {
        NOTIFICATIONS_OFF("Notifications are off for ZeekRemote: a car left unlocked cannot be reported."),
        WARNING_CHANNEL_OFF("The \"Car not locked\" notifications are switched off."),
        ALARM_CHANNEL_OFF("The \"Car not locked alarm\" notifications are switched off."),
        ALARM_CHANNEL_SILENT("The \"Car not locked alarm\" notifications are set to silent."),
    }

    /** The checks themselves; importance null = channel not created yet (counts as fine). */
    internal fun evaluate(notificationsEnabled: Boolean, warningImportance: Int?, alarmImportance: Int?): List<Problem> {
        if (!notificationsEnabled) return listOf(Problem.NOTIFICATIONS_OFF)
        return buildList {
            if (warningImportance == NotificationManager.IMPORTANCE_NONE) add(Problem.WARNING_CHANNEL_OFF)
            when {
                alarmImportance == NotificationManager.IMPORTANCE_NONE -> add(Problem.ALARM_CHANNEL_OFF)
                alarmImportance != null && alarmImportance < NotificationManager.IMPORTANCE_DEFAULT ->
                    add(Problem.ALARM_CHANNEL_SILENT)
            }
        }
    }

    fun problems(context: Context): List<Problem> = runCatching {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val (warning, alarm) = UnverifiedLockNotifier.channelImportances(context)
        evaluate(enabled, warning, alarm)
    }.getOrDefault(emptyList())

    /** True when a posted alarm notification makes a sound. */
    internal fun alarmAudible(context: Context): Boolean = problems(context).none {
        it == Problem.NOTIFICATIONS_OFF || it == Problem.ALARM_CHANNEL_OFF || it == Problem.ALARM_CHANNEL_SILENT
    }
}

/**
 * The alarm sound without a notification (plan K5): the default alarm tone on the alarm stream, for
 * at most [MAX_MS] or until the situation resolves ([UnverifiedLockNotifier.silenceAlarm]). Used
 * only when the notification alarm cannot sound.
 */
internal object FallbackAlarmSound {
    private const val MAX_MS = 30_000L
    @Volatile private var ringtone: Ringtone? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun play(context: Context) {
        runCatching {
            stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
            val tone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
            tone.play()
            ringtone = tone
            handler.postDelayed({ stop() }, MAX_MS)
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { handler.removeCallbacksAndMessages(null) }
    }
}
