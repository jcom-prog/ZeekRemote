package com.openzeekr.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Bounded live position requests; coordinates remain in memory and listeners are removed. */
internal class DepartureLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /** Bounded live acquisition: a poor first fix does not terminate the opportunity to get an
     * anchor while the phone is still near the car. The controller cancels this on every epoch end.
     */
    suspend fun nearAnchor(policy: NearDepartureAnchor, enabled: () -> Boolean): DepartureFix? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            Logx.d("prox", "departure location outcome=fine_permission_missing")
            return null
        }
        var callback: LocationCallback? = null
        try {
            return withTimeoutOrNull(NearDepartureAnchor.WINDOW_MS) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            if (!continuation.isActive) return
                            if (!enabled()) { continuation.resume(null); return }
                            val location = result.lastLocation ?: return
                            val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                location.isMock else location.isFromMockProvider
                            if (!location.hasAccuracy() || mock) {
                                Logx.d("prox", "departure location outcome=" +
                                    if (mock) "mock_rejected" else "accuracy_missing")
                                return
                            }
                            val fix = DepartureFix(location.latitude, location.longitude,
                                location.accuracy, location.elapsedRealtimeNanos / 1_000_000L)
                            val reason = policy.rejection(fix, android.os.SystemClock.elapsedRealtime())
                            Logx.d("prox", "departure near anchor outcome=${reason ?: "accepted"}")
                            if (reason == null) continuation.resume(fix)
                        }
                    }
                    callback = listener
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                        .setMinUpdateIntervalMillis(1_000L)
                        .setMaxUpdateAgeMillis(0L)
                        .setWaitForAccurateLocation(true)
                        .setDurationMillis(NearDepartureAnchor.WINDOW_MS)
                        .build()
                    try {
                        client.requestLocationUpdates(request, listener, Looper.getMainLooper())
                            .addOnSuccessListener {
                                // Registration can finish after cancellation removed the listener.
                                if (!continuation.isActive) client.removeLocationUpdates(listener)
                            }
                            .addOnFailureListener {
                                if (continuation.isActive) {
                                    Logx.d("prox", "departure location outcome=provider_failure")
                                    continuation.resume(null)
                                }
                            }
                    } catch (_: SecurityException) {
                        Logx.d("prox", "departure location outcome=security_exception")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } finally {
            // Executed on success, timeout, manual Lock, new unlock and service stop.
            callback?.let { listener -> runCatching { client.removeLocationUpdates(listener) } }
        }
    }

    /** Keep one request alive while evaluating a fresh pair, including after poor early fixes. */
    suspend fun confirmsDeparture(
        anchor: DepartureFix,
        steps: () -> Long?,
        enabled: () -> Boolean,
    ): Boolean {
        if (!enabled()) return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            Logx.d("prox", "departure location outcome=fine_permission_missing")
            return false
        }
        val window = DepartureObservationWindow(anchor, android.os.SystemClock.elapsedRealtime())
        var callback: LocationCallback? = null
        try {
            val result = withTimeoutOrNull(DepartureObservationWindow.WINDOW_MS) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val listener = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            if (!continuation.isActive) return
                            if (!enabled()) {
                                Logx.d("prox", "departure location outcome=session_disabled")
                                continuation.resume(false)
                                return
                            }
                            // Process every batched observation, using its real monotonic timestamp.
                            for (location in result.locations) {
                                val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                    location.isMock else location.isFromMockProvider
                                val fix = if (!mock && location.hasAccuracy()) DepartureFix(
                                    location.latitude, location.longitude, location.accuracy,
                                    location.elapsedRealtimeNanos / 1_000_000L) else null
                                val confirmed = window.observe(fix, steps(), android.os.SystemClock.elapsedRealtime())
                                val outcome = when {
                                    mock -> "mock_rejected"
                                    !location.hasAccuracy() -> "accuracy_missing"
                                    else -> window.outcome
                                }
                                Logx.d("prox", "departure location outcome=$outcome")
                                if (confirmed && enabled()) {
                                    continuation.resume(true)
                                    return
                                }
                            }
                        }
                    }
                    callback = listener
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                        .setMinUpdateIntervalMillis(1_000L)
                        .setMaxUpdateAgeMillis(0L)
                        .setWaitForAccurateLocation(true)
                        .setDurationMillis(DepartureObservationWindow.WINDOW_MS)
                        .build()
                    try {
                        client.requestLocationUpdates(request, listener, Looper.getMainLooper())
                            .addOnSuccessListener {
                                if (!continuation.isActive) client.removeLocationUpdates(listener)
                            }
                            .addOnFailureListener {
                                if (continuation.isActive) {
                                    Logx.d("prox", "departure location outcome=provider_failure")
                                    continuation.resume(false)
                                }
                            }
                    } catch (_: SecurityException) {
                        if (continuation.isActive) {
                            Logx.d("prox", "departure location outcome=security_exception")
                            continuation.resume(false)
                        }
                    }
                }
            }
            if (result == null) Logx.d("prox", "departure location outcome=request_timeout")
            return result ?: false
        } finally {
            callback?.let { listener -> runCatching { client.removeLocationUpdates(listener) } }
        }
    }
}
