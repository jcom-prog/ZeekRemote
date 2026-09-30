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
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** An on-demand fix; never persists coordinates or runs a continuous location listener. */
internal class DepartureLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    suspend fun current(): DepartureFix? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            Logx.d("prox", "departure location outcome=fine_permission_missing")
            return null
        }
        var completed = false
        val result = withTimeoutOrNull(7_000L) {
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
                            val mock = fix != null && (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                fix.isMock else fix.isFromMockProvider)
                            val outcome = when {
                                task.isCanceled -> "provider_cancelled"
                                !task.isSuccessful -> "provider_failure"
                                fix == null -> "provider_no_fix"
                                !fix.hasAccuracy() -> "accuracy_missing"
                                mock -> "mock_rejected"
                                else -> "fix_received"
                            }
                            Logx.d("prox", "departure location outcome=$outcome")
                            completed = true
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
                    if (continuation.isActive) {
                        Logx.d("prox", "departure location outcome=security_exception")
                        completed = true
                        continuation.resume(null)
                    }
                }
            }
        }
        if (!completed) Logx.d("prox", "departure location outcome=request_timeout")
        return result
    }
}
