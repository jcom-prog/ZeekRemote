package com.openzeekr.app.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.app.DepsHolder

/**
 * 0.4.6: the "Close windows" button on the guard's open-window notice. Hands the tap to the automaton, which sends
 * the close command through the repositories and reports the outcome in a fresh notice. Only ever closes.
 */
class GuardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLOSE_WINDOWS) return
        val deps = (context.applicationContext as? DepsHolder)?.deps ?: return
        runCatching { NotificationManagerCompat.from(context).cancel(GuardNotifier.WINDOWS_NOTIFICATION_ID) }
        deps.guard.closeWindowsFromNotification()
    }

    companion object {
        const val ACTION_CLOSE_WINDOWS = "com.openzeekr.app.GUARD_CLOSE_WINDOWS"
    }
}
