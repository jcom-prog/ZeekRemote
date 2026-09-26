package com.openzeekr.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wakelock-free motion state, used to gate how aggressively proximity polls the BLE link, and to WAKE
 * the CPU (via [onMovingEdge]) when the phone starts moving while the proximity loop sleeps. Layered by
 * preference:
 *  1. Hardware one-shot trigger sensors [Sensor.TYPE_MOTION_DETECT]/[Sensor.TYPE_STATIONARY_DETECT] —
 *     lowest power, fire even in Doze with the CPU asleep, instant STILL↔MOVING. Preferred.
 *  2. Wake-up **[Sensor.TYPE_STEP_DETECTOR]** — sensor-hub backed, fires per step (< 2 s latency) and,
 *     in its wake-up variant, wakes the AP without us holding a wakelock. Only fires on real gait, so
 *     it won't false-trigger on handling/vibration. STILL is inferred from a no-step timeout.
 *  3. Play Services **Activity Recognition** transitions (STILL / WALKING / IN_VEHICLE …) — coarse,
 *     confidence-gated (10–60 s), last resort. Needs ACTIVITY_RECOGNITION + Google Play Services.
 *  4. Nothing → [Motion.UNKNOWN]; callers fall back to RSSI steadiness.
 *
 * [source]/[hasSource] tell the caller which (if any) is active. [onMovingEdge] fires on a STILL→MOVING
 * transition so the caller can break a long idle sleep / re-acquire its wakelock the instant you move.
 */
class MotionMonitor(context: Context) {
    enum class Motion { UNKNOWN, STILL, MOVING }
    enum class Source { NONE, SENSORS, STEP, ACTIVITY }

    // Attributed context (API 30+) so the step-detector / sensor ops carry the manifest-declared
    // "proximity" tag — otherwise the continuously-registered wake-up sensor floods AppOps with
    // "attributionTag not declared". Below R it's the plain application context.
    private val appCtx: Context = context.applicationContext.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.createAttributionContext(ATTRIBUTION_TAG) else it
    }
    private val sensors = appCtx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val motionDetect: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)
    private val stationaryDetect: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT)
    // Galaxy S24-class devices expose this older one-shot as a genuine wake-up sensor even though
    // MOTION_DETECT/STATIONARY_DETECT and their step detector are not wake-capable.
    private val significantMotion: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            ?.takeIf { isWakeCapableSignificantMotion(it.isWakeUpSensor) }
    // A non-wake-up step detector MUST NOT be selected as the key's wake source: its events are only
    // delivered after something else wakes the AP, which stranded the sleeping key on the Galaxy
    // S24+. Keep it only as a low-cost latency assist while Activity Recognition owns Doze wake-up.
    private val wakeStepDetector: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            ?.takeIf { isWakeCapableStepDetector(it.isWakeUpSensor) }
    private val nonWakeStepDetector: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.takeUnless { it.isWakeUpSensor }

    private val _state = MutableStateFlow(Motion.UNKNOWN)
    val state: StateFlow<Motion> = _state.asStateFlow()

    /** True while a moving-vehicle activity is detected (IN_VEHICLE), from Activity Recognition. */
    private val _inVehicle = MutableStateFlow(false)
    val inVehicle: StateFlow<Boolean> = _inVehicle.asStateFlow()

    /** Which motion source is live. [hasSource] = "a real source can wake us on movement". */
    @Volatile var source: Source = Source.NONE
        private set
    val hasSource: Boolean get() = source != Source.NONE

    /** Invoked once on each STILL→MOVING edge (any source). Lets the caller cancel a long idle sleep. */
    @Volatile var onMovingEdge: (() -> Unit)? = null

    /** Lets the service bridge the 1.5 s security debounce with a short, bounded CPU wake. */
    @Volatile var onHardwareWake: (() -> Unit)? = null

    @Volatile private var running = false
    /** Elapsed-realtime anchors used by the key-sleep security gate. Elapsed time is immune to the
     * wall clock changing and includes deep sleep, which is exactly what "still for two minutes"
     * needs. A zero value means that state has not been confirmed yet. */
    @Volatile private var stillSinceElapsedMs = 0L
    @Volatile private var movingSinceElapsedMs = 0L
    private var arPendingIntent: PendingIntent? = null

    private val onMotion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { if (running) { setMoving(); armStationary() } }
    }
    private val onStationary = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { if (running) { setStill(); armMotion() } }
    }
    private val onSignificant = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (!running) return
            if (_state.value != Motion.MOVING) onHardwareWake?.invoke()
            setMoving()
            armSignificantMotion()
        }
    }

    // Step detector: each step = MOVING and re-arms a no-step timeout; the timeout firing = STILL. The
    // timeout runs on the main looper — while moving the caller holds a wakelock so it fires on time; a
    // stop lets the CPU sleep and it fires on the next wake, which is fine (we just linger MOVING a bit).
    private val stillHandler = Handler(Looper.getMainLooper())
    private val stepStillRunnable = Runnable {
        if (running && (source == Source.STEP || source == Source.ACTIVITY)) setStill()
    }
    private val onStep = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            if (!running) return
            setMoving()
            stillHandler.removeCallbacks(stepStillRunnable)
            stillHandler.postDelayed(stepStillRunnable, STEP_STILL_TIMEOUT_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        // 1) Hardware trigger sensors (preferred: Doze-proof, zero standing power).
        if (motionDetect != null || stationaryDetect != null) {
            armMotion(); armStationary()
            source = Source.SENSORS
            Logx.d("motion", "started via trigger sensors")
            return
        }
        // 2) A genuine wake-up step detector wakes the AP per step without our wakelock.
        if (wakeStepDetector != null) {
            source = Source.STEP
            sensors?.registerListener(onStep, wakeStepDetector, SensorManager.SENSOR_DELAY_NORMAL)
            Logx.d("motion", "started via wake-up step detector (no trigger sensors)")
            return
        }
        // 3) Activity Recognition uses a PendingIntent and can wake the app in Doze. On phones such
        // as the S24+ the available step detector is non-wake-up; register it only as a fast assist
        // once the CPU is awake, never as the source that promises to wake the secured key.
        if (startActivityRecognition()) {
            source = Source.ACTIVITY
            armSignificantMotion()
            nonWakeStepDetector?.let {
                sensors?.registerListener(onStep, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            Logx.d(
                "motion",
                "started via Activity Recognition" +
                    (if (significantMotion != null) " + wake-up significant motion" else "") +
                    (if (nonWakeStepDetector != null) " + non-wakeup step assist" else " (no step detector)"),
            )
            return
        }
        // 4) Nothing available.
        source = Source.NONE
        Logx.w("motion", "no motion source at all — RSSI-steadiness only")
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { motionDetect?.let { sensors?.cancelTriggerSensor(onMotion, it) } }
        runCatching { stationaryDetect?.let { sensors?.cancelTriggerSensor(onStationary, it) } }
        runCatching { significantMotion?.let { sensors?.cancelTriggerSensor(onSignificant, it) } }
        runCatching { sensors?.unregisterListener(onStep) }
        stillHandler.removeCallbacks(stepStillRunnable)
        stopActivityRecognition()
        active = null
        source = Source.NONE
        _state.value = Motion.UNKNOWN
        _inVehicle.value = false
        stillSinceElapsedMs = 0L
        movingSinceElapsedMs = 0L
    }

    private fun armMotion() { runCatching { motionDetect?.let { sensors?.requestTriggerSensor(onMotion, it) } } }
    private fun armStationary() { runCatching { stationaryDetect?.let { sensors?.requestTriggerSensor(onStationary, it) } } }
    private fun armSignificantMotion() {
        runCatching { significantMotion?.let { sensors?.requestTriggerSensor(onSignificant, it) } }
    }

    private fun setMoving() {
        stillSinceElapsedMs = 0L
        if (_state.value != Motion.MOVING) {
            movingSinceElapsedMs = SystemClock.elapsedRealtime()
            _state.value = Motion.MOVING
            Logx.d("motion", "-> MOVING")
            onMovingEdge?.invoke()
        }
    }
    private fun setStill() {
        movingSinceElapsedMs = 0L
        if (_state.value != Motion.STILL) {
            stillSinceElapsedMs = SystemClock.elapsedRealtime()
            _state.value = Motion.STILL
            _inVehicle.value = false
            Logx.d("motion", "-> STILL")
        }
    }

    /** True only after a sensor-confirmed STILL state has continuously lasted [durationMs]. */
    fun isStillFor(durationMs: Long): Boolean {
        val since = stillSinceElapsedMs
        return running && _state.value == Motion.STILL && since != 0L &&
            SystemClock.elapsedRealtime() - since >= durationMs
    }

    /** Debounces a motion edge before waking the digital key. */
    fun isMovingFor(durationMs: Long): Boolean {
        val since = movingSinceElapsedMs
        return running && _state.value == Motion.MOVING && since != 0L &&
            SystemClock.elapsedRealtime() - since >= durationMs
    }

    // ---------------- Activity Recognition layer ----------------

    @SuppressLint("MissingPermission")
    private fun startActivityRecognition(): Boolean {
        if (!hasActivityPermission()) { Logx.w("motion", "AR: ACTIVITY_RECOGNITION not granted"); return false }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appCtx) != ConnectionResult.SUCCESS) {
            Logx.w("motion", "AR: Google Play Services unavailable"); return false
        }
        return try {
            val transitions = intArrayOf(
                DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.ON_FOOT,
                DetectedActivity.RUNNING, DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE,
            ).map {
                ActivityTransition.Builder()
                    .setActivityType(it)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            }
            val pi = activityPendingIntent()
            arPendingIntent = pi
            active = this
            ActivityRecognition.getClient(appCtx)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi)
                .addOnFailureListener { Logx.w("motion", "AR request failed: ${it.message}") }
            true
        } catch (e: Exception) {
            Logx.w("motion", "AR start error: ${e.message}"); false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityRecognition() {
        val pi = arPendingIntent ?: return
        runCatching { ActivityRecognition.getClient(appCtx).removeActivityTransitionUpdates(pi) }
        runCatching { pi.cancel() }
        arPendingIntent = null
    }

    private fun activityPendingIntent(): PendingIntent {
        val intent = Intent(appCtx, ActivityRecognitionReceiver::class.java).setAction(ActivityRecognitionReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(appCtx, AR_REQUEST_CODE, intent, flags)
    }

    private fun hasActivityPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION
        else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        return ContextCompat.checkSelfPermission(appCtx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Called (via [ActivityRecognitionReceiver] → [deliver]) for each detected transition. */
    private fun onActivity(activityType: Int) {
        if (!running || source != Source.ACTIVITY) return
        when (activityType) {
            DetectedActivity.STILL -> setStill()
            DetectedActivity.IN_VEHICLE -> { _inVehicle.value = true; setMoving() }
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE -> setMoving()
        }
    }

    companion object {
        /** Regression policy: an ordinary step detector cannot wake a sleeping application processor. */
        internal fun isWakeCapableStepDetector(isWakeUpSensor: Boolean): Boolean = isWakeUpSensor

        internal fun isWakeCapableSignificantMotion(isWakeUpSensor: Boolean): Boolean = isWakeUpSensor

        // Must match the <attribution android:tag> declared in the manifest.
        private const val ATTRIBUTION_TAG = "proximity"
        private const val STEP_STILL_TIMEOUT_MS = 5_000L // no step for this long ⇒ back to STILL
        private const val AR_REQUEST_CODE = 0x2ee6
        @Volatile private var active: MotionMonitor? = null
        /** Route an Activity Recognition transition (from the receiver) to the live monitor. */
        internal fun deliver(activityType: Int) { active?.onActivity(activityType) }
    }
}
