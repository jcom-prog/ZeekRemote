package com.openzeekr.app.ble

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.openzeekr.app.util.Logx
import kotlin.math.sqrt

/**
 * Measurement version (doorbraak C): the app's own step counter and turn detector while the car is
 * unlocked ([OwnStepLogic] holds the detection; this is the sensor glue).
 *
 * The window is opened when the app believes the car unlocked and re-extended by every strong
 * at-the-car reading, bounded to [WINDOW_MS] per extension - exactly the time the proximity service
 * already holds the CPU awake for the departure proof, so the accelerometer and game rotation vector
 * (~50 Hz, no magnetometer, no positions) add about 0.4 mA for at most minutes per visit.
 *
 * Measurement version: everything is LOGGED only (one "own:" line per second through the BLE-gated
 * log, so field data stays in the private journal) and nothing decides on it. The same numbers drive
 * the departure proof once the shadow score reaches the owner's targets.
 */
internal class OwnMotionTracker(
    private val sensors: SensorManager?,
    /** Extra fields for the per-second line: hub tally, motion state, screen - for the comparison. */
    private val context: () -> String,
) : SensorEventListener {
    private val logic = OwnStepLogic()
    private val handler = Handler(Looper.getMainLooper())
    private val accel: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotation: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    @Volatile private var active = false
    @Volatile private var deadlineElapsedMs = 0L
    private var lastSamples = 0L
    private val rotM = FloatArray(9)
    private val orient = FloatArray(3)

    /** True while a window is open (the early-wake pulse must not shrink the unlock window). */
    val running: Boolean get() = active
    /** Steps counted in the open window (diagnostic; the shadow journal reads it). */
    val steps: Long get() = logic.steps
    /** The latest observed turn (elapsed ms) in the open window; null: none. */
    val lastTurnAtMs: Long? get() = logic.lastTurnAtMs

    private val secondTick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            Logx.d("motion", "own: ${logic.summary(now)} (+${logic.samples - lastSamples}/s) ${context()}")
            lastSamples = logic.samples
            if (now >= deadlineElapsedMs) { stopNow("window over"); return }
            handler.postDelayed(this, 1_000L)
        }
    }

    /** (Re)opens the window. A re-extend while running only moves the deadline (cheap: called per
     *  strong reading at the car). Without an accelerometer this does nothing. */
    @Synchronized fun extend(windowMs: Long = WINDOW_MS) {
        deadlineElapsedMs = SystemClock.elapsedRealtime() + windowMs
        if (active || accel == null) return
        active = true
        logic.reset()
        lastSamples = 0L
        runCatching {
            sensors?.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
            rotation?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        Logx.d("motion", "own tracker ON (rotation ${if (rotation != null) "yes" else "no"})")
        handler.postDelayed(secondTick, 1_000L)
    }

    @Synchronized fun stop(reason: String) { stopNow(reason) }

    private fun stopNow(reason: String) {
        if (!active) return
        active = false
        runCatching { sensors?.unregisterListener(this) }
        handler.removeCallbacks(secondTick)
        Logx.d("motion", "own tracker OFF ($reason): ${logic.summary(SystemClock.elapsedRealtime())}")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val tMs = event.timestamp / 1_000_000L
        when (event.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                logic.onAccel(tMs, sqrt(x * x + y * y + z * z))
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                runCatching {
                    SensorManager.getRotationMatrixFromVector(rotM, event.values)
                    SensorManager.getOrientation(rotM, orient)
                    logic.onYaw(tMs, orient[0].toDouble())
                }
            }
        }
    }

    companion object {
        /** The design's bound per extension; every strong at-the-car reading re-extends. */
        const val WINDOW_MS = 120_000L
    }
}
