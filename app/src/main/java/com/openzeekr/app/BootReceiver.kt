package com.openzeekr.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.openzeekr.app.ble.ProximityService
import com.openzeekr.app.util.Logx

/** Restores the phone's Digital Key foreground service after a completed reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val deps = (context.applicationContext as? DepsHolder)?.deps ?: return
        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

        if (deps.ble.hasCredential && bluetoothGranted) {
            Logx.d("svc", "${intent.action}: restoring ZeekRemote Digital Key service")
            ProximityService.start(context)
        } else {
            Logx.d("svc", "${intent.action}: boot start skipped (key=${deps.ble.hasCredential}, btPermission=$bluetoothGranted)")
        }
    }
}
