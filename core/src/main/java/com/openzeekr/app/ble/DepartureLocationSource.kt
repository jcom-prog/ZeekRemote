package com.openzeekr.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** An on-demand fix; never persists coordinates or runs a continuous location listener. */
internal class DepartureLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    suspend fun current(): DepartureFix? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return null
        return withTimeoutOrNull(7_000L) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(1_000L)
                    .setDurationMillis(6_000L)
                    .build()
                try {
                    client.getCurrentLocation(request, cancellation.token).addOnCompleteListener { task ->
                        if (continuation.isActive) {
                            val fix = if (task.isSuccessful) task.result else null
                            continuation.resume(fix?.takeIf {
                                it.hasAccuracy() &&
                                    !(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                        it.isMock else it.isFromMockProvider)
                            }?.let {
                                DepartureFix(it.latitude, it.longitude, it.accuracy,
                                    it.elapsedRealtimeNanos / 1_000_000L)
                            })
                        }
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
}
