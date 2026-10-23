package com.openzeekr.app.ble

import kotlin.math.PI
import kotlin.math.abs

/**
 * The app's own step and turn detection from raw sensor samples (measurement version, doorbraak C).
 *
 * The hub step counter delivers late or not at all with the screen off (field 16/10-20/10: 10-25 s
 * after a drive, once 151 s of silence while walking), so departures waited for GNSS. While the car
 * is unlocked the proximity service holds the CPU awake anyway; in that window the app reads the
 * accelerometer itself (~50 Hz) and counts steps in real time. In the front pocket (the owner's only
 * carry) the phone rides the thigh: every step is a clear swing, and a turn shows in the yaw of the
 * game rotation vector within a step.
 *
 * Pure Kotlin: the Android sensor glue lives in [MotionMonitor]. This class only turns streams of
 * (time, acceleration magnitude) and (time, yaw) into a step total and a latest-turn time, so the
 * unit tests can feed synthetic walks.
 *
 * Measurement version: the outputs are LOGGED only (one line per second, no behavior change). The
 * same numbers drive the departure proof once the shadow score reaches the owner's targets.
 */
internal class OwnStepLogic {
    /** Slow gravity estimate (EMA): the magnitude's DC component, so tilt changes don't count. */
    private var gravityEst = 9.81
    private var gravitySeeded = false
    /** Fast-smoothed dynamic acceleration (|a| minus gravity estimate). */
    private var dyn = 0.0
    private var lastAccelAtMs = Long.MIN_VALUE
    private var rising = false
    private var lastStepAtMs = Long.MIN_VALUE
    /** 0.4.0b: the latest step (elapsed ms; null before the first) and the recent steps' times - the controller's own
     *  walk evidence ([OwnRules]). Read from another thread than the sensor callback, hence synchronized. */
    val lastStepElapsedMs: Long? get() = synchronized(this) { if (lastStepAtMs == Long.MIN_VALUE) null else lastStepAtMs }
    private val stepTimes = ArrayDeque<Long>()
    fun stepsSince(elapsedMs: Long): Long = synchronized(this) { stepTimes.count { it >= elapsedMs }.toLong() }

    /** Steps counted since [reset]. */
    var steps = 0L
        private set
    /** Accelerometer samples seen since [reset] (diagnostic: does the sensor deliver screen-off?). */
    var samples = 0L
        private set

    // ---- Turn detection (yaw of the game rotation vector, no magnetometer) ----
    /** Recent unwrapped yaw samples (time, yaw rad), kept [YAW_WINDOW_MS] long. */
    private val yaw = ArrayDeque<Pair<Long, Double>>()
    private var yawUnwrapped = 0.0
    private var lastRawYaw: Double? = null
    /** When the latest turn (walking direction reversal) was observed; null: none yet. */
    var lastTurnAtMs: Long? = null
        private set
    private var turnQuietUntilMs = Long.MIN_VALUE

    fun reset() = synchronized(this) {
        gravitySeeded = false; gravityEst = 9.81; dyn = 0.0; rising = false
        lastAccelAtMs = Long.MIN_VALUE; lastStepAtMs = Long.MIN_VALUE
        steps = 0L; samples = 0L; stepTimes.clear()
        yaw.clear(); yawUnwrapped = 0.0; lastRawYaw = null
        lastTurnAtMs = null; turnQuietUntilMs = Long.MIN_VALUE
    }

    /** One accelerometer sample: [magnitude] = |a| in m/s² (gravity included). */
    fun onAccel(atMs: Long, magnitude: Double) = synchronized(this) {
        samples++
        if (!gravitySeeded) { gravityEst = magnitude; gravitySeeded = true }
        // The gravity estimate follows slowly (tilt, pocket shifts); the step band stays in `dyn`.
        gravityEst += GRAVITY_ALPHA * (magnitude - gravityEst)
        val raw = magnitude - gravityEst
        dyn += DYN_ALPHA * (raw - dyn)
        // A step is one clear swell: `dyn` rises through the threshold (rising edge), at a human
        // cadence (>= STEP_GAP_MS since the last). Fiddling gives narrow spikes the smoothing damps.
        if (dyn >= STEP_THRESHOLD) {
            if (!rising) {
                rising = true
                if (lastStepAtMs == Long.MIN_VALUE || atMs - lastStepAtMs >= STEP_GAP_MS) {
                    steps++
                    lastStepAtMs = atMs
                    stepTimes.addLast(atMs); while (stepTimes.size > STEP_TIMES_KEPT) stepTimes.removeFirst()
                }
            }
        } else if (dyn <= STEP_THRESHOLD * 0.5) {
            // Hysteresis: the edge re-arms only after a real dip, not on threshold noise.
            rising = false
        }
        lastAccelAtMs = atMs
    }

    /** One game-rotation-vector sample reduced to yaw (rad, any zero point, -pi..pi). */
    fun onYaw(atMs: Long, yawRad: Double) {
        // Unwrap so a -pi/pi crossing is no fake turn.
        val prev = lastRawYaw
        if (prev != null) {
            var d = yawRad - prev
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            yawUnwrapped += d
        }
        lastRawYaw = yawRad
        yaw.addLast(atMs to yawUnwrapped)
        while (yaw.isNotEmpty() && atMs - yaw.first().first > YAW_WINDOW_MS) yaw.removeFirst()
        // A turn is a heading change of at least TURN_RAD within the window, while actually walking
        // (steps in the same window): standing and fiddling swings the pocket less and gives no steps.
        if (atMs < turnQuietUntilMs) return
        val oldest = yaw.first()
        if (atMs - oldest.first < MIN_TURN_SPAN_MS) return
        if (abs(yawUnwrapped - oldest.second) < TURN_RAD) return
        if (lastStepAtMs == Long.MIN_VALUE || atMs - lastStepAtMs > WALKING_RECENT_MS) return
        lastTurnAtMs = atMs
        turnQuietUntilMs = atMs + TURN_QUIET_MS
        yaw.clear(); yaw.addLast(atMs to yawUnwrapped)
    }

    /** One log line per second (measurement version): own count, delivery rate, latest turn. */
    fun summary(nowMs: Long): String {
        val turn = lastTurnAtMs?.let { "${(nowMs - it) / 1000}s ago" } ?: "none"
        return "own steps=$steps samples=$samples turn=$turn"
    }

    companion object {
        /** Gravity EMA: ~2 s time constant at 50 Hz, well below the step band (1.5-2.5 Hz). */
        const val GRAVITY_ALPHA = 0.01
        /** Step-band EMA: passes the swing, damps hand/pocket jitter. */
        const val DYN_ALPHA = 0.35
        /** m/s² above gravity that counts as a step swell (front pocket: 2-4 m/s² per step). */
        const val STEP_THRESHOLD = 1.1
        /** Two steps are never closer than this (a 3 steps/s sprint stays countable). */
        const val STEP_GAP_MS = 320L
        /** 0.4.0b: recent step times kept for [stepsSince]. */
        const val STEP_TIMES_KEPT = 200
        /** Heading change that counts as a turn... */
        val TURN_RAD = Math.toRadians(110.0)
        /** ...within this window, and not slower than this (a bend in a path is no turn). */
        const val YAW_WINDOW_MS = 4_000L
        const val MIN_TURN_SPAN_MS = 700L
        /** Turns only count while walking: a step this recent. */
        const val WALKING_RECENT_MS = 2_500L
        /** One turn per about-face: the window restarts after each. */
        const val TURN_QUIET_MS = 2_500L
    }
}
