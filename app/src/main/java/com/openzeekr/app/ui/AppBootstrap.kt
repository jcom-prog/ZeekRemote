package com.openzeekr.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.ProximityService

/**
 * Side-effect-only composable that runs the app's startup housekeeping, mirroring
 * the stock app: on open it requests the location (incl. background) + Bluetooth +
 * notification permissions needed to range the car (RSSI/MAC) and hold the key
 * connection, prompts to lift battery optimization, and starts/stops the
 * foreground key service to follow the provisioned-key state (a provisioned key and a VIN; it does
 * not depend on the cloud sign-in, see AppRoot).
 */
@Composable
fun AppBootstrap(deps: Deps, serviceEnabled: Boolean) {
    val context = LocalContext.current
    var foregroundReady by remember { mutableStateOf(hasAll(context, foregroundPerms())) }

    // Background location must be requested on its own, after foreground location
    // is granted (Android 10+ hard requirement).
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort; scanning still works foreground-only if denied */ }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        foregroundReady = hasAll(context, foregroundPerms())
        maybeRequestBackgroundLocation(context, backgroundLauncher::launch)
    }

    // Ask once per process launch (dialogs are no-ops when already granted/exempt).
    LaunchedEffect(Unit) {
        val missing = foregroundPerms().filterNot {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) foregroundLauncher.launch(missing.toTypedArray())
        else maybeRequestBackgroundLocation(context, backgroundLauncher::launch)

        requestBatteryExemption(context)

        // Register our FCM token with the message-centre so the car's pushes (esp. security alarms)
        // arrive when the phone is asleep. No-op until logged in + the Firebase resources are present.
        deps.push.registerIfLoggedIn()
    }

    // Keep the foreground key service running exactly while a key is provisioned (with a VIN),
    // but only once the connectedDevice FGS is actually allowed to start.
    LaunchedEffect(serviceEnabled, foregroundReady) {
        if (serviceEnabled && canStartKeyService(context)) ProximityService.start(context)
        else if (!serviceEnabled) ProximityService.stop(context)
    }
}

/** Runtime permissions we request up-front (background location is requested separately). */
private fun foregroundPerms(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Activity Recognition (Q+ runtime perm) — the proximity motion fallback when the phone has no
    // hardware MOTION/STATIONARY_DETECT trigger sensors, so we still notice you start walking to the car.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
}

private fun hasAll(context: Context, perms: List<String>): Boolean = perms.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun maybeRequestBackgroundLocation(context: Context, launch: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val bg = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    if (fine == PackageManager.PERMISSION_GRANTED && bg != PackageManager.PERMISSION_GRANTED) {
        launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}

/** The connectedDevice FGS needs BLUETOOTH_CONNECT held on Android 12+ to start. */
private fun canStartKeyService(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

private fun requestBatteryExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    @Suppress("BatteryLife")
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
