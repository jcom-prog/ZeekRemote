package com.openzeekr.app.ble.sim

import com.openzeekr.app.ble.ControlResult
import com.openzeekr.app.ble.DepartureFix
import com.openzeekr.app.ble.DepartureLocator
import com.openzeekr.app.ble.DepartureObservationWindow
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkProtocol
import com.openzeekr.app.ble.DkSession
import com.openzeekr.app.ble.LockAlertPolicy
import com.openzeekr.app.ble.MotionMonitor
import com.openzeekr.app.ble.NearDepartureAnchor
import com.openzeekr.app.ble.ObservedVehicleStatus
import com.openzeekr.app.ble.ProximityAlerts
import com.openzeekr.app.ble.ProximityClock
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.ProximityDecisionPolicy
import com.openzeekr.app.ble.ProximityLink
import com.openzeekr.app.ble.ProximityMotion
import com.openzeekr.app.ble.SafetyWakeLocks
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Whole-app simulator. The REAL ProximityController (and every policy it uses) runs on virtual time.
 * Everything around it is a MODEL, calibrated on the field logs of 30/09-03/10 2026:
 *  - the walker (distance to the car, facing, carry),
 *  - radio (path loss, body shadow, slow fading, read noise, multipath spikes),
 *  - the S24+ motion stack (Activity Recognition with long lags, significant motion, step assist),
 *  - the BLE link + ProximityService (keep-alive, presence scan, security sleep, reconnect latency),
 *  - the car (key receipts, status pushes, self-lock after 2 min without a door),
 *  - GNSS fixes fed to the real NearDepartureAnchor / DepartureObservationWindow policies.
 * Model numbers are approximations; the requirement checks use distance margins accordingly.
 *
 * Fase 0 (14/10): every physical constant lives in [SimPhysics]. The "calibrated" world is the fit
 * above; the "stress" suite samples a different world per run (another phone, more fading, worse
 * GNSS, a slower step counter, a car that relocks sooner or refuses a key Lock now and then, a slow
 * or failing cloud). Each subsystem draws from its own random stream, so two app versions run on
 * the same seed see the same world even where their decisions differ.
 */

/** Deterministic RNG (xorshift) so every scenario is reproducible from its seed. */
class SimRandom(seed: Long) {
    private var x = seed xor 0x5DEECE66DL or 1L
    fun nextLong(): Long { x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); return x }
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()
    fun uniform(a: Double, b: Double) = a + (b - a) * nextDouble()
    fun chance(p: Double) = nextDouble() < p
    fun gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12); val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}

enum class Carry(val db: Double) { HAND(0.0), FRONT_POCKET(-2.0), BACK_POCKET(-6.0) }
enum class Facing(val db: Double) { TOWARD(0.0), SIDE(-2.0), AWAY(-4.0) }

/** How the phone's motion stack behaves (field: S24+ screen off = long AR lags, no steps). */
data class MotionProfile(
    val name: String,
    /** Walking must last this long before the first MOVING (significant motion / AR WALKING). */
    val onLagMinMs: Long, val onLagMaxMs: Long,
    /** Standing still this long before STILL. */
    val offLagMinMs: Long, val offLagMaxMs: Long,
    /** Probability that a walking bout is never reported at all (slow walk, AR misses it). */
    val missBoutP: Double,
    /** Steps reach the app only while the CPU is held (non-wake step detector). */
    val stepAssistWhileAwake: Boolean,
    /** A (non-wake) step detector exists, even if it delivers nothing screen-off (S24+). */
    val hasStepDetector: Boolean = true,
) {
    companion object {
        val S24_SCREEN_OFF = MotionProfile("s24-off", 4_000, 18_000, 3_000, 10_000, 0.15, false)
        val S24_STEPS_AWAKE = MotionProfile("s24-steps", 4_000, 18_000, 3_000, 10_000, 0.10, true)
        val GOOD_SENSOR = MotionProfile("good", 1_500, 4_000, 2_000, 5_000, 0.0, true)
        val ALL = listOf(S24_SCREEN_OFF, S24_STEPS_AWAKE, GOOD_SENSOR)
    }
}

/** The physical world of one run. [calibrated] is the field fit; [random] samples a plausible other world. */
internal data class SimPhysics(
    val name: String = "calibrated",
    /** Phone/case/firmware difference in received signal (dB, + = hears the car better). */
    val phoneOffsetDb: Double = env("SIM_PHONE_OFFSET_DB", 0.0),
    val pathSlope: Double = env("SIM_PATH_SLOPE", 14.7),
    val fadeSigmaDb: Double = env("SIM_FADE_SIGMA_DB", 1.5),
    /** null = the scenario's fixed GNSS accuracy (calibrated); else the median of a log-normal per fix. */
    val gnssAccMedianM: Double? = System.getenv("SIM_GNSS_ACC")?.toDouble(),
    val gnssAccSpread: Double = 0.0,
    /** Time to the first fix after the app asks for location. */
    val gnssTtffMs: Long = 0L,
    val hubLagMs: Long = env("SIM_HUB_LAG_MS", 1_000.0).toLong(),
    val stepWarmup: Double = 8.0,
    /** The car relocks itself this long after an unlock without a door being opened. */
    val selfLockMs: Long = 120_000L,
    /** Chance that the car acknowledges a key Lock but does not execute it (field 07/10, 08/10, 12/10). */
    val keyLockRefuseP: Double = 0.0,
    val statusPushLossP: Double = 0.0,
    val cloudLockDelayMs: Long = 2_000L,
    val cloudFailP: Double = 0.0,
    /** The cloud reports the lock state this much later than it changed. */
    val cloudStatusLagMs: Long = 0L,
    /**
     * Android deep sleep: without a wakelock the app's timers (Handler/coroutine delays run on uptime)
     * stop until something wakes the CPU - a presence hit, a motion transition, a GATT callback, a
     * location fix, network traffic or a random system wakeup. Off in the calibrated world.
     */
    val cpuSleep: Boolean = System.getenv("SIM_CPU_SLEEP") == "1",
    /** Chance that this run's user walks with the screen on (phone in use); the field default is off. */
    val screenOnWhileWalkingP: Double = 0.0,
    /**
     * 0.3.2: chance per walk up to the car that the signal stays flat over the last metres and only
     * jumps at the door (field 14/10 18:31, S24+ in the front trouser pocket: -85..-91 smoothed from
     * ~12 m to ~1 m, then -77 at the door and -63 at the car). Body and car shadow along the walking
     * line; the log-distance model alone always kept rising. SIM_PLATEAU_P.
     */
    val approachPlateauP: Double = env("SIM_PLATEAU_P", 0.0),
) {
    fun describe() = "%s phone%+.1fdB slope%.1f fade%.1f gnss%s ttff%.0fs hub%.0fs warm%.0f self%.0fs refuse%.2f cloud%.0fs/fail%.2f/lag%.0fs".format(
        name, phoneOffsetDb, pathSlope, fadeSigmaDb, gnssAccMedianM?.let { "%.1fm".format(it) } ?: "-", gnssTtffMs / 1000.0,
        hubLagMs / 1000.0, stepWarmup, selfLockMs / 1000.0, keyLockRefuseP, cloudLockDelayMs / 1000.0, cloudFailP, cloudStatusLagMs / 1000.0)

    companion object {
        private fun env(name: String, default: Double) = System.getenv(name)?.toDouble() ?: default
        fun calibrated() = SimPhysics()
        /** A plausible other world (sensitivity / stress). Ranges from the field logs 28/09-14/10. */
        fun random(r: SimRandom) = SimPhysics(
            name = "random",
            phoneOffsetDb = (3.0 * r.gaussian()).coerceIn(-6.0, 6.0),
            pathSlope = r.uniform(13.0, 20.0),
            fadeSigmaDb = r.uniform(1.2, 3.5),
            // Field: "accuracy over 8 m" in nearly every episode of 14/10.
            gnssAccMedianM = r.uniform(4.0, 11.0),
            gnssAccSpread = 0.35,
            gnssTtffMs = r.uniform(2_000.0, 20_000.0).toLong(),
            hubLagMs = r.uniform(1_000.0, 10_000.0).toLong(),
            stepWarmup = r.uniform(5.0, 12.0),
            selfLockMs = r.uniform(90_000.0, 150_000.0).toLong(),
            keyLockRefuseP = r.uniform(0.0, 0.15),
            statusPushLossP = r.uniform(0.0, 0.05),
            cloudLockDelayMs = r.uniform(2_000.0, 20_000.0).toLong(),
            cloudFailP = r.uniform(0.0, 0.10),
            cloudStatusLagMs = r.uniform(0.0, 30_000.0).toLong(),
            cpuSleep = r.chance(0.5),
            screenOnWhileWalkingP = 0.3,
        )
    }
}

/** One leg of the walker's script. */
internal sealed class Leg {
    data class Stand(val ms: Long, val facing: Facing = Facing.TOWARD, val shadowDb: Double = 0.0,
                     /** Inside a building: no GNSS fix. */ val indoor: Boolean = false) : Leg()
    data class Walk(val toM: Double, val speedMps: Double = 1.3) : Leg()
    /** Small random movements on the spot (turning, shuffling) without changing place. */
    data class Shuffle(val ms: Long, val shadowDb: Double = 0.0) : Leg()
    /** Keep walking away for [ms] while the car is briefly in sight (a door, window or gap: +[boostDb]). */
    data class WalkPeak(val ms: Long, val boostDb: Double, val speedMps: Double = 1.3) : Leg()
    /** Busy on the spot while moving (locking a door or a gate): Activity Recognition sees walking. */
    data class Fidget(val ms: Long, val shadowDb: Double = 0.0) : Leg()
    object OpenDoor : Leg()
    /** The car is opened without the phone key (inside handle, card, stock app). */
    object ReopenByOther : Leg()
    /** The phone lies still inside the car, out of the pocket (field 10/10 16:44: -46..-49 dBm). */
    data class PhoneInCar(val ms: Long) : Leg()
    object ManualLock : Leg()
    /** The car is locked without the phone key and without the app noticing (fob, handle, other phone). */
    object LockByOther : Leg()
    /** The car unlocks itself (parking after a drive: status 2 then 1, field 04/10 18:36). */
    object CarUnlocksItself : Leg()
    /** The app process dies (update, One UI, crash); START_STICKY restarts it after [downMs]. */
    data class ProcessRestart(val downMs: Long = 4_000L) : Leg()
    /** Bluetooth is switched off for [ms] (airplane mode, user, BT stack restart). */
    data class BluetoothOff(val ms: Long) : Leg()
    /** Bluetooth off or on until the next toggle (the walker carries on meanwhile). */
    data class Bluetooth(val on: Boolean) : Leg()
    data class Mark(val label: String) : Leg()
    /** Learning period only: how the phone is carried from now on (null = the run's own carry). */
    data class Carrying(val carry: Carry?) : Leg()
}

internal class SimWorld(
    val seed: Long,
    val motionProfile: MotionProfile,
    val carry: Carry,
    startDistanceM: Double,
    val gnssAccuracyM: Float? = 4f,
    val notificationPermission: Boolean = true,
    /** Field 05/10 16:08/16:18: the car acknowledges a key Lock (0x0111) but does not lock. */
    val carRefusesKeyLock: Boolean = false,
    /** Field 12/10 17:52:39: at the edge of the link (~-102) the key Lock got no answer although the
     *  session was still up. Below this true RSSI a key Lock times out without reaching the car. */
    val keyEdgeRssi: Double? = null,
    val physics: SimPhysics = SimPhysics.calibrated(),
    /** The cloud never answers a Lock (outage) - field 04/10 19:10 (garage). */
    val cloudDown: Boolean = false,
) {
    private fun stream(k: Long) = SimRandom(seed * -0x61C8864680B583EBL + k * 0x2545F4914F6CDD1DL)
    /** The walker's own randomness (shuffles); every subsystem below has its own stream. */
    val rnd = stream(1)
    private val rRadio = stream(2)
    private val rMotion = stream(3)
    private val rLink = stream(4)
    private val rGnss = stream(5)
    private val rCar = stream(6)
    private val rCloud = stream(7)
    private val rPlateau = stream(8)
    /** The flat final approach of the current walk (see [SimPhysics.approachPlateauP]): cap and start. */
    private var plateauCapDb: Double? = null
    private var plateauFromM = 0.0
    /** World time: radio, car, BT controller, cloud servers. Always runs. */
    val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    /** The app's own coroutines (controller, locator loops): their delays stop while the CPU sleeps. */
    private val appScheduler = TestCoroutineScheduler()
    private val appDispatcher = StandardTestDispatcher(appScheduler)
    private val rCpu = SimRandom(seed * 31 + 0xC0FFEEL)
    private var wakeUntilMs = 0L
    private var nextSystemWakeMs = 0L
    private var cloudInFlight = 0
    private var linkOps = 0
    /** Something outside the app woke the CPU (PendingIntent, GATT callback, sensor event). */
    fun wake(ms: Long) { wakeUntilMs = maxOf(wakeUntilMs, now() + ms) }
    // While the app's coroutines run, real time is the tick start plus the app's progress in the tick
    // (a 25-55 ms RSSI read must advance the clock, as on the phone).
    private var appAdvancing = false
    private var tickWorld0 = 0L
    private var tickApp0 = 0L
    private fun realMs(): Long =
        if (appAdvancing) tickWorld0 + (appScheduler.currentTime - tickApp0) else scheduler.currentTime
    val clock = object : ProximityClock {
        override fun elapsedMs() = 100_000_000L + realMs()
        override fun wallMs() = 1_790_000_000_000L + realMs()
    }
    fun now() = scheduler.currentTime

    // ---------------- walker ----------------
    var distanceM = startDistanceM; private set
    private val startM = startDistanceM
    /** The learning period (0.3.1) carries the phone differently on each visit; the scenario uses [carry]. */
    private var learnCarry: Carry? = null
    private val carryNow get() = learnCarry ?: carry
    var facing = Facing.TOWARD; private set
    var walking = false; private set
    var walkSpeed = 0.0; private set
    private var extraShadowDb = 0.0
    var indoor = false; private set
    /** Screen on? The field phone is in a pocket (off); a stress world may use it while walking. */
    private val screenOnWhileWalking = SimRandom(seed * 7 + 0x5C2EE7L).chance(physics.screenOnWhileWalkingP)
    val screenOn get() = screenOnWhileWalking && walking
    var label = ""; private set

    // ---------------- radio ----------------
    private var fade = 0.0
    var trueRssi = -120.0; private set
    private fun updateRadio() {
        // Field (03/10 18:20:31-42, standing at ~16 m): raw readings -87..-91, one -94 dip.
        fade = 0.95 * fade + sqrt(1 - 0.9025) * physics.fadeSigmaDb * rRadio.gaussian()
        val d = max(distanceM, 0.7)
        // Fitted on the field pocket readings: ~-79 at 2.5 m, -83 at 5 m, -85 at 7 m (approach ARM),
        // -87 at 10 m, -88..-90 facing at 15 m, -92..-98 walking away at 15-20 m (03/10 logs).
        trueRssi = -71.0 - physics.pathSlope * log10(d) + carryNow.db + facing.db + extraShadowDb + fade + physics.phoneOffsetDb
        plateauCapDb?.let { cap -> if (distanceM in 1.0..plateauFromM) trueRssi = minOf(trueRssi, cap + fade) }
    }
    fun readRssi(): Int {
        var v = trueRssi + 1.0 * rRadio.gaussian()
        if (rRadio.chance(0.02)) v += rRadio.uniform(-8.0, 3.0)
        return v.toInt().coerceAtMost(-30)
    }

    // ---------------- facts for the oracles ----------------
    data class Event(val atMs: Long, val kind: String, val distanceM: Double, val detail: String = "")
    val events = mutableListOf<Event>()
    fun record(kind: String, detail: String = "") { events += Event(now(), kind, distanceM, detail) }
    val logLines = ArrayList<String>()
    data class Sample(val atMs: Long, val distanceM: Double, val carLocked: Boolean, val moving: Boolean,
                      val appBelievesUnlocked: Boolean = false, val cpuAwake: Boolean = false, val gnssOn: Boolean = false,
                      val linkUp: Boolean = false, val appAlive: Boolean = true)
    val samples = ArrayList<Sample>()

    // ---------------- car ----------------
    val car = SimCar()
    inner class SimCar {
        var locked = true; private set
        var unlockedAtMs = -1L; private set
        /** Wall time of the last lock-state change, as the cloud status reports it. */
        var changedAtWallMs = 0L; private set
        var doorOpenedSinceUnlock = false; private set
        /** (wall ms, locked) history, so the cloud can report a lagging state. */
        private val history = ArrayList<Pair<Long, Boolean>>().apply { add(0L to true) }
        fun cloudView(): Pair<Boolean, Long> {
            val at = clock.wallMs() - physics.cloudStatusLagMs
            val h = history.lastOrNull { it.first <= at } ?: history.first()
            return h.second to h.first
        }
        fun actuate(lock: Boolean, by: String) {
            locked = lock
            changedAtWallMs = clock.wallMs()
            history += changedAtWallMs to lock
            if (!lock) { unlockedAtMs = now(); doorOpenedSinceUnlock = false } else unlockedAtMs = -1L
            record(if (lock) "CAR_LOCKED" else "CAR_UNLOCKED", by)
            link.pushStatus()
        }
        fun openDoor() { if (!locked) { doorOpenedSinceUnlock = true; record("DOOR_OPENED") } }
        fun tick() {
            // The 7GT relocks itself ~2 min after an unlock when no door was opened (field 03/10).
            if (!locked && unlockedAtMs >= 0 && !doorOpenedSinceUnlock && now() - unlockedAtMs >= physics.selfLockMs)
                actuate(true, "self-lock")
        }
    }

    // ---------------- motion ----------------
    val motion = SimMotion()
    inner class SimMotion : ProximityMotion {
        private val _state = MutableStateFlow(MotionMonitor.Motion.UNKNOWN)
        override val state: StateFlow<MotionMonitor.Motion> = _state
        override val source = MotionMonitor.Source.ACTIVITY
        override val hasSource = true
        override val hasStepAssist = motionProfile.hasStepDetector
        private var steps = 0L
        /**
         * Hub step counter (TYPE_STEP_COUNTER, 0.2.17): counts while the CPU sleeps; conservatively it
         * drops the first [SimPhysics.stepWarmup] steps of every bout, undercounts 10 % and the app sees its
         * total ~1 s late (flush). SIM_STEP_COUNTER=0 replays 0.2.16 (detector only).
         */
        private var hubSteps = 0.0; private var hubBoutSteps = 0.0
        private var hubSeen = 0L; private var hubSnapMs = 0L
        override val observedSteps: Long? get() =
            if (!motionProfile.hasStepDetector) null
            else if (STEP_COUNTER) maxOf(steps, hubSeen) else steps
        override val stepSummary: String get() = "detector=$steps counter=${if (STEP_COUNTER) hubSeen.toString() else "none"}"
        override var onMovingEdge: (() -> Unit)? = null
        private var running = false
        override fun start() { running = true; if (_state.value == MotionMonitor.Motion.UNKNOWN) setStill() }
        override fun stop() { running = false; _state.value = MotionMonitor.Motion.UNKNOWN }

        private var boutStartMs = -1L; private var boutLagMs = 0L; private var boutMissed = false
        private var stopStartMs = -1L; private var stopLagMs = 0L
        private var stillSinceMs = -1L; private var movingSinceMs = -1L
        private var lastStepMs = -1L; private var stepAccum = 0.0

        fun tick(dtMs: Long, cpuAwake: Boolean) {
            if (walking) {
                hubBoutSteps += dtMs / 550.0
                if (hubBoutSteps >= physics.stepWarmup) hubSteps += 0.9 * dtMs / 550.0
            } else hubBoutSteps = 0.0
            if (now() - hubSnapMs >= physics.hubLagMs) { hubSnapMs = now(); hubSeen = hubSteps.toLong() }
            if (walking) {
                stopStartMs = -1L
                if (boutStartMs < 0) {
                    boutStartMs = now(); boutLagMs = rMotion.uniform(motionProfile.onLagMinMs.toDouble(), motionProfile.onLagMaxMs.toDouble()).toLong()
                    boutMissed = rMotion.chance(motionProfile.missBoutP)
                }
                if (!boutMissed && now() - boutStartMs >= boutLagMs) setMoving()
                if (motionProfile.stepAssistWhileAwake && cpuAwake) {
                    stepAccum += dtMs / 550.0
                    while (stepAccum >= 1.0) { stepAccum -= 1.0; steps++; lastStepMs = now(); setMoving() }
                }
            } else {
                boutStartMs = -1L
                if (stopStartMs < 0) {
                    stopStartMs = now(); stopLagMs = rMotion.uniform(motionProfile.offLagMinMs.toDouble(), motionProfile.offLagMaxMs.toDouble()).toLong()
                }
                if (now() - stopStartMs >= stopLagMs) setStill()
            }
            // Step assist: no step for 5 s -> STILL (MotionMonitor.STEP_STILL_TIMEOUT_MS), when awake.
            if (lastStepMs >= 0 && cpuAwake && now() - lastStepMs >= 5_000L && !(walking && !boutMissed && now() - boutStartMs >= boutLagMs)) {
                lastStepMs = -1L; setStill()
            }
            if (_state.value == MotionMonitor.Motion.UNKNOWN && !walking && now() > 3_000) setStill()
        }
        private fun setMoving() {
            stillSinceMs = -1L
            if (_state.value != MotionMonitor.Motion.MOVING) {
                movingSinceMs = now(); _state.value = MotionMonitor.Motion.MOVING
                wake(5_000L)
                log('D', "motion", "-> MOVING"); onMovingEdge?.invoke(); service.onMovingEdge()
            }
        }
        private fun setStill() {
            movingSinceMs = -1L
            if (_state.value != MotionMonitor.Motion.STILL) {
                stillSinceMs = now(); _state.value = MotionMonitor.Motion.STILL; wake(5_000L); log('D', "motion", "-> STILL")
            }
        }
        fun isStillFor(ms: Long) = _state.value == MotionMonitor.Motion.STILL && stillSinceMs >= 0 && now() - stillSinceMs >= ms
        fun isMovingFor(ms: Long) = _state.value == MotionMonitor.Motion.MOVING && movingSinceMs >= 0 && now() - movingSinceMs >= ms
    }

    // ---------------- link + car session ----------------
    val link = SimLink()
    inner class SimLink : ProximityLink {
        private val _state = object {
            private val flow = MutableStateFlow(DkBleManager.State.IDLE)
            val asFlow: StateFlow<DkBleManager.State> get() = flow
            var value: DkBleManager.State
                get() = flow.value
                set(v) { if (flow.value != v) { flow.value = v; wake(2_000L) } }
        }
        override val state: StateFlow<DkBleManager.State> get() = _state.asFlow
        override var onInboundActivity: (() -> Unit)? = null
        override var lastInboundMs: Long = 0L
        private var generation = 0L
        private var connectJob: Job? = null
        private var weakSinceMs = -1L
        var lastNonIdleMs = -1L; private set
        private val statusFlow = MutableSharedFlow<ObservedVehicleStatus>(extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val session: DkSession = object : DkSession {
            override val isEstablished get() = _state.value == DkBleManager.State.SESSION_READY
            override suspend fun establish() {}
            override suspend fun sendFrame(cmd: Int, payload: ByteArray) = isEstablished
            override suspend fun control(ctrl: Byte, timeoutMs: Long): ControlResult {
                linkOps++
                try { return controlInner(ctrl, timeoutMs) } finally { linkOps-- }
            }
            private suspend fun controlInner(ctrl: Byte, timeoutMs: Long): ControlResult {
                if (!isEstablished) return ControlResult.WRITE_FAILED
                val lock = when (ctrl) { DkProtocol.CTRL_LOCK -> true; DkProtocol.CTRL_UNLOCK -> false; else -> null }
                delay(380L)
                if (!isEstablished) return ControlResult.NO_RESPONSE
                if (lock != null) {
                    record(if (lock) "KEY_LOCK_CMD" else "KEY_UNLOCK_CMD")
                    if (lock && keyEdgeRssi != null && trueRssi < keyEdgeRssi) {
                        delay(timeoutMs)
                        return ControlResult.NO_RESPONSE
                    }
                    if (lock && (carRefusesKeyLock || rCar.chance(physics.keyLockRefuseP))) {
                        // Receipt only, no "locked" status. SIM_RECEIPT_ONLY=1 replays 0.2.2, which
                        // counted the receipt as a Lock; the real session now waits and reports it.
                        if (System.getenv("SIM_RECEIPT_ONLY") == "1") return ControlResult.CONFIRMED
                        delay(com.openzeekr.app.ble.LockExecutionWatch.STATUS_WAIT_MS)
                        return ControlResult.NOT_EXECUTED
                    }
                    car.actuate(lock, "key")
                }
                delay(20L)
                return ControlResult.CONFIRMED
            }
            override suspend fun ping(timeoutMs: Long): Boolean { linkOps++; try { delay(60); return isEstablished } finally { linkOps-- } }
            override val vehicleStatus: Flow<ObservedVehicleStatus> get() = statusFlow
            override fun answerChallenge(randX: Int, randY: Int) = ByteArray(0)
            override fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit) {}
            override fun close() {}
        }
        fun pushStatus() {
            if (_state.value != DkBleManager.State.SESSION_READY) return
            if (rCar.chance(physics.statusPushLossP)) { record("STATUS_LOST"); return }
            wake(2_000L)
            statusFlow.tryEmit(ObservedVehicleStatus(generation, if (car.locked) 3 else 1))
            lastInboundMs = clock.wallMs(); onInboundActivity?.invoke()
        }
        override suspend fun pollRemoteRssi(): Int? {
            if (_state.value != DkBleManager.State.SESSION_READY && _state.value != DkBleManager.State.CONNECTED) return null
            // A real GATT RSSI read takes 25-55 ms: field 03/10 19:57 (0.1.63) the 1 s cadence ran
            // 988-1018 ms apart, so a span of two readings is just as often 995 ms as 1005 ms.
            linkOps++
            try {
                delay(rLink.uniform(25.0, 55.0).toLong())
                return if (_state.value == DkBleManager.State.SESSION_READY || _state.value == DkBleManager.State.CONNECTED) readRssi() else null
            } finally { linkOps-- }
        }
        override fun reconnectLast(): Boolean { connect(null); return true }
        /**
         * Direct connect from an offloaded presence result. SIM_PRESENCE_EDGE=1 replays field 13/10
         * 19:41: the controller hears the car at -100 dBm, but a link cannot be established below
         * about -94 - each attempt ends in status 133 after ~0.7 s, plus one immediate retry.
         */
        fun connectFromPresence(advertRssi: Int) {
            if (!btOn || !appAlive) return
            if (!PRESENCE_EDGE || advertRssi >= com.openzeekr.app.ble.WeakConnectGate.CONNECT_FLOOR_RSSI) { connect(null); return }
            if (connectJob?.isActive == true || _state.value != DkBleManager.State.IDLE) return
            _state.value = DkBleManager.State.CONNECTING
            connectJob = scope.launch {
                repeat(2) { attempt ->
                    delay(if (attempt == 0) 700L else 400L + 700L)
                    record("STATUS_133", "advert $advertRssi")
                    if (trueRssi >= com.openzeekr.app.ble.WeakConnectGate.CONNECT_FLOOR_RSSI) {
                        _state.value = DkBleManager.State.IDLE; connectJob = null; connect(null); return@launch
                    }
                }
                _state.value = DkBleManager.State.IDLE
            }
        }
        override fun connect(deviceMac: String?) {
            if (!btOn || !appAlive) return
            if (connectJob?.isActive == true || _state.value == DkBleManager.State.SESSION_READY ||
                _state.value == DkBleManager.State.CONNECTED) return
            if (service.keySleeping && now() >= service.sleepGraceUntilMs) return
            _state.value = DkBleManager.State.SCANNING
            connectJob = scope.launch {
                // A foreground scan finds the car only in range; give up after 10 s.
                var waited = 0L
                while (trueRssi < CONNECT_MIN_RSSI && waited < 10_000L) { delay(200); waited += 200 }
                if (trueRssi < CONNECT_MIN_RSSI) { _state.value = DkBleManager.State.IDLE; return@launch }
                _state.value = DkBleManager.State.CONNECTING
                delay(rLink.uniform(250.0, 700.0).toLong())
                _state.value = DkBleManager.State.CONNECTED
                lastNonIdleMs = now()
                delay(rLink.uniform(2_600.0, 3_600.0).toLong())   // GATT + DK handshake (field ~3.4 s)
                if (trueRssi < DROP_RSSI) { _state.value = DkBleManager.State.IDLE; return@launch }
                generation++
                _state.value = DkBleManager.State.SESSION_READY
                record("LINK_READY")
                delay(rLink.uniform(300.0, 5_000.0).toLong())
                // The car pushes its status early in a new session. SIM_FIRST_STATUS_LOST=1 replays
                // 0.2.6, which dropped that push (it arrives before "established").
                if (System.getenv("SIM_FIRST_STATUS_LOST") != "1") pushStatus()
            }
        }
        override fun disconnect() {
            connectJob?.cancel(); connectJob = null
            if (_state.value != DkBleManager.State.IDLE) record("LINK_DOWN", "disconnect")
            _state.value = DkBleManager.State.IDLE
        }
        override fun noteUnlockConfirmed() {}
        fun tick() {
            val st = _state.value
            if (st == DkBleManager.State.SESSION_READY || st == DkBleManager.State.CONNECTED) {
                lastNonIdleMs = now()
                if (trueRssi < DROP_RSSI) {
                    if (weakSinceMs < 0) weakSinceMs = now()
                    if (now() - weakSinceMs >= SUPERVISION_MS) {
                        weakSinceMs = -1L; connectJob?.cancel(); connectJob = null
                        _state.value = DkBleManager.State.IDLE; record("LINK_DOWN", "out of range")
                    }
                } else weakSinceMs = -1L
            }
        }
    }

    // ---------------- ProximityService (keep-alive, presence, security sleep) ----------------
    /**
     * Mirrors ProximityService (14/10): keepConnected (8 s, 500 ms while asleep), stationaryKeySecurity
     * (500 ms), onMotionEscalate (motion edge), recoveryProbe and the hardware presence delivery,
     * using the same pure policies (KeySleepPolicy, EarlyPresencePolicy, DeepSleepRecoveryPolicy,
     * WeakConnectGate) and the same constants. Watch borrowing and drive authorization are not modelled.
     */
    val service = SimService()
    inner class SimService {
        /** A new process: all of the service's in-memory state starts over. */
        fun reset() {
            keySleeping = false; lastAtCarMs = -1L; sleepGraceUntilMs = 0L; disarm()
            earlyUntilMs = 0L; savedEarlyRssi = null; fastPresenceUntilMs = 0L; recoveryUntilMs = 0L
            lastEngagedMs = -1L; initialProbe = false; lastWakeProbeMs = -1L; nextKeepAliveMs = 0L; nextSleepPollMs = 0L
        }
        var keySleeping = false; private set
        private var lastAtCarMs = -1L
        var sleepGraceUntilMs = 0L; private set
        var presenceArmed = false; private set
        private var presenceMode = 0 // 0 low power, 1 balanced (approach), 2 low latency
        private var presenceSeenSinceMs = -1L
        private var earlyUntilMs = 0L
        private var savedEarlyRssi: Int? = null
        private var fastPresenceUntilMs = 0L
        private var recoveryUntilMs = 0L
        private var lastEngagedMs = -1L
        private var initialProbe = false
        private var lastWakeProbeMs = -1L
        private var nextKeepAliveMs = 0L
        private var nextSleepPollMs = 0L
        private val offload get() = config.presenceOffloadEnabled
        private val moving get() = motion.state.value == MotionMonitor.Motion.MOVING

        private fun arm(lowLatency: Boolean, approach: Boolean): Boolean {
            if (!btOn) return false
            presenceArmed = true; presenceSeenSinceMs = -1L
            presenceMode = if (lowLatency) 2 else if (approach) 1 else 0
            return true
        }
        private fun disarm() { presenceArmed = false; presenceSeenSinceMs = -1L }

        fun tick(appRuns: Boolean) {
            if (appRuns && now() >= nextSleepPollMs) { nextSleepPollMs = now() + KEY_SLEEP_POLL_MS; stationaryKeySecurity() }
            if (appRuns && now() >= nextKeepAliveMs) keepConnected()
            presenceDelivery()
        }

        /** onMotionEscalate: a motion edge (a wake-up event) prepares an early or a fast presence scan. */
        fun onMovingEdge() {
            if (!appAlive || !btOn) return
            if (com.openzeekr.app.ble.EarlyPresencePolicy.mayPrepare(keySleeping, true, false)) {
                if (offload && link.state.value == DkBleManager.State.IDLE) {
                    earlyUntilMs = now() + EARLY_PRESENCE_WINDOW_MS
                    arm(lowLatency = true, approach = true)
                    log('D', "svc", "motion edge: early filtered presence armed; key remains asleep")
                }
                return
            }
            if (keySleeping) return
            if (link.state.value == DkBleManager.State.IDLE) {
                fastPresenceUntilMs = now() + FAST_PRESENCE_WINDOW_MS
                arm(lowLatency = true, approach = true)
            }
        }

        private fun keepConnected() {
            if (keySleeping) {
                nextKeepAliveMs = now() + KEY_SLEEP_POLL_MS
                if (!com.openzeekr.app.ble.EarlyPresencePolicy.mayRetain(keySleeping, moving, earlyUntilMs, now())) {
                    earlyUntilMs = 0L; savedEarlyRssi = null; disarm()
                }
                if (now() >= sleepGraceUntilMs && link.state.value != DkBleManager.State.IDLE) link.disconnect()
                return
            }
            nextKeepAliveMs = now() + RECONNECT_INTERVAL_MS
            if (!btOn) return
            if (link.state.value == DkBleManager.State.IDLE) {
                val recentlyEngaged = lastEngagedMs >= 0 && now() - lastEngagedMs < AGGRESSIVE_RECONNECT_MS
                val route = com.openzeekr.app.ble.DeepSleepRecoveryPolicy.keepAliveRoute(
                    presenceRecoveryActive = now() < recoveryUntilMs, presenceArmed = presenceArmed,
                    normallyAggressive = moving && recentlyEngaged, screenInteractive = screenOn)
                val aggressive = route == com.openzeekr.app.ble.DeepSleepRecoveryPolicy.Route.FOREGROUND_SCAN
                if (offload && !aggressive) {
                    if (!initialProbe) {
                        initialProbe = true
                        log('D', "svc", "keep-alive: initial presence probe (already-at-car case)")
                        link.connect(null)
                    } else arm(lowLatency = moving && now() < fastPresenceUntilMs, approach = moving)
                } else {
                    if (aggressive) log('D', "svc", "keep-alive: bounded approach recovery — aggressive scan-connect")
                    link.connect(null)
                }
            } else {
                lastEngagedMs = now()
                if (presenceArmed) disarm()
            }
        }

        private fun stationaryKeySecurity() {
            val atCar = if (System.getenv("SIM_SLEEP_AT_CAR") == "1") false else
                com.openzeekr.app.ble.KeySleepPolicy.atCar(link.state.value == DkBleManager.State.SESSION_READY,
                    controller.state.value.smoothedRssi)
            if (atCar) lastAtCarMs = now()
            val sinceAtCar = if (lastAtCarMs >= 0) now() - lastAtCarMs else null
            if (!keySleeping && com.openzeekr.app.ble.KeySleepPolicy.maySleep(motion::isStillFor, atCar, sinceAtCar)) {
                keySleeping = true; sleepGraceUntilMs = now() + KEY_SLEEP_LOCK_GRACE_MS
                disarm(); link.disconnect()
                log('D', "svc", "security sleep: stationary — BLE session/presence disabled")
                record("SECURITY_SLEEP")
            } else if (keySleeping) {
                if (motion.isMovingFor(KEY_WAKE_MOTION_CONFIRM_MS)) {
                    keySleeping = false; sleepGraceUntilMs = 0L
                    log('D', "svc", "confirmed motion: waking stationary key; selecting screen-off recovery route")
                    val prepared = if (com.openzeekr.app.ble.EarlyPresencePolicy.mayConnect(true, false, earlyUntilMs, now()))
                        savedEarlyRssi else null
                    savedEarlyRssi = null; earlyUntilMs = 0L
                    if (prepared != null) {
                        disarm()
                        controller.onPresenceMatch(prepared)
                        log('D', "svc", "confirmed motion: engaging saved early presence (rssi=$prepared)")
                        link.connectFromPresence(prepared)
                    } else recoveryProbe("confirmed motion", confirmedMotionWake = true)
                } else if (!com.openzeekr.app.ble.EarlyPresencePolicy.mayRetain(keySleeping, moving, earlyUntilMs, now())) {
                    disarm()
                }
            }
        }

        private fun recoveryProbe(reason: String, confirmedMotionWake: Boolean) {
            if (lastWakeProbeMs >= 0 && now() - lastWakeProbeMs < WAKE_PROBE_DEBOUNCE_MS) return
            if (keySleeping || !btOn || link.state.value != DkBleManager.State.IDLE) return
            lastWakeProbeMs = now()
            val armed = if (confirmedMotionWake && offload) {
                fastPresenceUntilMs = now() + FAST_PRESENCE_WINDOW_MS
                arm(lowLatency = true, approach = true)
            } else false
            when (com.openzeekr.app.ble.DeepSleepRecoveryPolicy.route(offload && confirmedMotionWake, armed)) {
                com.openzeekr.app.ble.DeepSleepRecoveryPolicy.Route.OFFLOADED_PRESENCE -> {
                    recoveryUntilMs = now() + MOTION_PRESENCE_RECOVERY_MS
                    log('D', "svc", "$reason: LOW_LATENCY offloaded presence armed (screen-off recovery)")
                }
                com.openzeekr.app.ble.DeepSleepRecoveryPolicy.Route.FOREGROUND_SCAN -> {
                    log('D', "svc", "$reason: immediate bounded recovery scan")
                    disarm(); link.connect(null)
                }
            }
        }

        /** The offloaded scan runs in the BT controller (no CPU); a match is a PendingIntent wake-up. */
        private fun presenceDelivery() {
            if (!presenceArmed || !btOn || link.state.value != DkBleManager.State.IDLE) return
            if (trueRssi < (if (PRESENCE_EDGE) -100.0 else PRESENCE_MIN_RSSI)) { presenceSeenSinceMs = -1L; return }
            if (presenceSeenSinceMs < 0) presenceSeenSinceMs = now()
            val latency = when (presenceMode) { 2 -> 400L; 1 -> 1_500L; else -> 5_000L }
            if (now() - presenceSeenSinceMs < latency) return
            presenceSeenSinceMs = -1L
            val r = readRssi()
            wake(10_000L)   // the PendingIntent broadcast holds a wakelock
            if (keySleeping) {
                if (com.openzeekr.app.ble.EarlyPresencePolicy.mayRetain(keySleeping, moving, earlyUntilMs, now())) {
                    savedEarlyRssi = r; disarm()
                    log('D', "svc", "early presence saved pending confirmed motion (rssi=$r)")
                } else {
                    log('D', "svc", "presence ignored: stationary key is sleeping")
                    disarm()
                }
                return
            }
            log('D', "svc", "presence MATCH rssi=$r")
            val action = if (WEAK_GATE) com.openzeekr.app.ble.WeakConnectGate.onPresence(r, moving)
                else com.openzeekr.app.ble.WeakConnectGate.PresenceAction.CONNECT
            when (action) {
                com.openzeekr.app.ble.WeakConnectGate.PresenceAction.CONNECT -> {
                    controller.onPresenceMatch(r); disarm(); link.connectFromPresence(r)
                }
                com.openzeekr.app.ble.WeakConnectGate.PresenceAction.FOREGROUND_SCAN -> {
                    log('D', "svc", "presence at the edge of range while moving - foreground scan")
                    controller.onPresenceMatch(r); link.connect(null)
                }
                com.openzeekr.app.ble.WeakConnectGate.PresenceAction.IGNORE ->
                    log('D', "svc", "presence at the edge of range while still - not connecting")
            }
        }
    }

    // ---------------- alerts (UnverifiedLockNotifier semantics) ----------------
    val alerts = SimAlerts()
    internal inner class SimAlerts : ProximityAlerts {
        private var alarmRaisedAt = 0L
        private var alarmShowingUntil = -1L
        override fun show(event: LockAlertPolicy.Event, bleDepartureRouteActive: Boolean): Boolean {
            if (!LockAlertPolicy.shouldPost(event, bleDepartureRouteActive)) return false
            record("NOTIFY", event.name)
            if (LockAlertPolicy.audible(event)) {
                val showing = alarmShowingUntil > now()
                if (!LockAlertPolicy.alarmShouldSound(alarmRaisedAt, clock.elapsedMs(), showing)) return true
                alarmRaisedAt = clock.elapsedMs(); alarmShowingUntil = now() + LockAlertPolicy.ALARM_EPISODE_MS
                record("ALARM", event.name + if (car.locked) " (car LOCKED)" else " (car unlocked)")
            } else if (LockAlertPolicy.resolvesAlarm(event)) silenceAlarm()
            return notificationPermission
        }
        val alarmSounding get() = alarmShowingUntil > now()
        override fun clear() { if (alarmSounding) record("ALARM_STOP", "clear"); alarmRaisedAt = 0L; alarmShowingUntil = -1L }
        override fun silenceAlarm() { if (alarmSounding) record("ALARM_STOP", "silence"); alarmRaisedAt = 0L; alarmShowingUntil = -1L }
        override fun carLocked() { record("LOCKED_CHIME") }
    }

    // ---------------- GNSS (real NearDepartureAnchor / DepartureObservationWindow) ----------------
    /** GNSS requests in flight (for the battery budget). */
    var gnssActive = 0; private set
    private val locator = object : DepartureLocator {
        // Correlated drift (urban multipath): a slowly wandering bias plus rare jumps of 10-25 m
        // that persist for a few fixes while the reported accuracy stays optimistic (review 0.1.63).
        private var bias = 0.0
        private var jumpLeft = 0
        private var jump = 0.0
        private var requestStartMs = 0L
        private fun fix(): DepartureFix? {
            val base = gnssAccuracyM ?: return null
            if (indoor) return null
            if (now() - requestStartMs < physics.gnssTtffMs) return null
            val acc = physics.gnssAccMedianM?.let { (it * kotlin.math.exp(physics.gnssAccSpread * rGnss.gaussian())).toFloat() } ?: base
            bias = 0.9 * bias + 1.5 * rGnss.gaussian()
            if (jumpLeft > 0) jumpLeft-- else if (rGnss.chance(0.01)) { jumpLeft = 3 + (rGnss.nextDouble() * 5).toInt(); jump = rGnss.uniform(10.0, 25.0) }
            val noise = acc / 2.0 * rGnss.gaussian() + bias + if (jumpLeft > 0) jump else 0.0
            val metres = distanceM + noise
            // 1 degree latitude ~ 111 km; the car sits at (52.0, 5.0), the walker moves north.
            return DepartureFix(52.0 + metres / 111_000.0, 5.0, acc, clock.elapsedMs())
        }
        override suspend fun nearAnchor(policy: NearDepartureAnchor, enabled: () -> Boolean): DepartureFix? {
            val until = now() + NearDepartureAnchor.WINDOW_MS
            requestStartMs = now(); gnssActive++
            try {
                while (now() < until) {
                    delay(1_000L)
                    if (!enabled()) return null
                    val f = fix() ?: continue
                    // The anchor is the walker's own fix while BLE says "at the car" (real policy decides).
                    if (policy.rejection(f, clock.elapsedMs()) == null) return f
                }
                return null
            } finally { gnssActive-- }
        }
        override suspend fun confirmsDeparture(anchor: DepartureFix, steps: () -> Long?, enabled: () -> Boolean,
                                               bleCorroborated: () -> Boolean): Boolean {
            if (!enabled()) return false
            val window = DepartureObservationWindow(anchor, clock.elapsedMs())
            val until = now() + DepartureObservationWindow.WINDOW_MS
            requestStartMs = now(); gnssActive++
            try {
                while (now() < until) {
                    delay(1_000L)
                    if (!enabled()) return false
                    if (window.observe(fix(), steps(), clock.elapsedMs(), bleCorroborated()) && enabled()) return true
                }
                return false
            } finally { gnssActive-- }
        }
    }

    // Safety wakelocks as Android holds them: each until released or timed out (0.3.1: the model
    // ignored release, so a 3-min lock-loop wakelock always ran its full time in the battery figures).
    private val safetyHolds = HashMap<Int, Long>()
    private var nextSafetyHold = 0
    private val wakeLocks = object : SafetyWakeLocks {
        override fun acquire(timeoutMs: Long): Any? { val t = ++nextSafetyHold; safetyHolds[t] = now() + timeoutMs; return t }
        override fun release(token: Any?) { (token as? Int)?.let { safetyHolds.remove(it) } }
    }
    private fun safetyHeld(): Boolean { val n = now(); safetyHolds.values.removeAll { it <= n }; return safetyHolds.isNotEmpty() }

    // ---------------- the real controller ----------------
    val config = SecretsConfig(proximityEnabled = true, proximitySensitivity = "far")
    // The cloud Lock works whether or not the key link does (field 05/10 16:19: the stock app's cloud
    // Lock locked a car that had ignored two key Locks); it takes ~2 s and reports its status.
    var btOn = true; private set
    var appAlive = true; private set
    /** App-private storage: survives a process restart (SharedPreferences in the app). */
    private var savedState: com.openzeekr.app.ble.ProximityStateStore.Saved? = null
    val stateStore = object : com.openzeekr.app.ble.ProximityStateStore {
        override fun load() = savedState
        override fun save(saved: com.openzeekr.app.ble.ProximityStateStore.Saved) { savedState = saved }
    }
    /** The self-calibration profile (SharedPreferences in the app): survives a process restart. */
    var learnedProfile: com.openzeekr.app.ble.PhoneCalibration.Profile? = null; private set
    val calibrationStore = object : com.openzeekr.app.ble.PhoneCalibrationStore {
        override fun load() = learnedProfile
        override fun save(profile: com.openzeekr.app.ble.PhoneCalibration.Profile) { learnedProfile = profile }
    }
    /** What the learning period taught the app (for the report); null = no learning period. */
    var learnedSummary = ""; private set
    var learnedOffsetDb: Int? = null; private set
    var learnedRawOffsetDb: Double? = null; private set
    private var appScope = CoroutineScope(SupervisorJob() + appDispatcher)
    private fun newController() = ProximityController(
        config = { config }, ble = link, motion = motion, scope = appScope,
        cloudLock = {
            cloudInFlight++
            try {
                delay(physics.cloudLockDelayMs)
                if (cloudDown) { record("CLOUD_LOCK_FAILED", "outage"); false }
                else if (rCloud.chance(physics.cloudFailP)) { record("CLOUD_LOCK_FAILED", "rejected"); false }
                else { record("CLOUD_LOCK_CMD"); if (car.locked) Unit else car.actuate(true, "cloud"); true }
            } finally { cloudInFlight-- }
        },
        cloudIsLocked = { if (cloudDown) null else car.cloudView().first },
        cloudLockSnapshot = { if (System.getenv("SIM_NO_CLOUD_STATUS") == "1" || cloudDown) null
            else car.cloudView().let { com.openzeekr.app.ble.CloudLockSnapshot(it.first, it.second) } },
        clock = clock, alerts = alerts, departureLocationSource = locator, wakeLocks = wakeLocks,
        stateStore = stateStore, calibrationStore = calibrationStore, selfCalibration = SELF_CAL,
        arrivalAnchor = ARRIVAL_ANCHOR, awayReturnSteps = H2_STEPS, approachConfirm = APPROACH_CONFIRM,
    )
    var controller = newController(); private set

    /** The process dies: every in-memory state of the app (controller + service) is lost. */
    private fun killApp() {
        record("APP_KILLED")
        controller.stop()
        appScope.cancel()
        link.disconnect()
        safetyHolds.clear()   // a dead process holds no wakelock
        appAlive = false
        service.reset()
    }
    private fun startApp() {
        appScope = CoroutineScope(SupervisorJob() + appDispatcher)
        appAlive = true
        controller = newController()
        controller.start()
        record("APP_STARTED")
    }

    fun log(level: Char, area: String, msg: String) {
        if (logLines.size < 200_000) logLines += "%8.1f d=%5.1f %c [%s] %s".format(now() / 1000.0, distanceM, level, area, msg)
    }

    /** The app holds the CPU (its wakelocks, or a connection in progress). */
    private fun cpuAwake() = appAlive && (controller.wakeLockNeeded.value || link.state.value == DkBleManager.State.CONNECTING ||
        link.state.value == DkBleManager.State.CONNECTED || safetyHeld())
    /** Do the app's timers run? Always in the calibrated world; with [SimPhysics.cpuSleep] only while awake. */
    private fun appRuns(): Boolean {
        if (!appAlive) return false
        if (!physics.cpuSleep) return true
        if (now() >= nextSystemWakeMs) {   // other apps / the system wake the CPU now and then
            nextSystemWakeMs = now() + rCpu.uniform(60_000.0, 300_000.0).toLong(); wake(1_000L)
        }
        return cpuAwake() || gnssActive > 0 || cloudInFlight > 0 || linkOps > 0 || now() < wakeUntilMs
    }

    /** Advance the world by [ms] with the walker in its current state. */
    private fun run(ms: Long) {
        var left = ms
        while (left > 0) {
            val dt = minOf(TICK_MS, left)
            if (walking) {
                val step = walkSpeed * dt / 1000.0
                distanceM = if (walkTarget > distanceM) minOf(walkTarget, distanceM + step) else maxOf(walkTarget, distanceM - step)
            }
            updateRadio()
            val awake = cpuAwake()
            val runs = appRuns()
            car.tick(); link.tick(); motion.tick(dt, awake); if (appAlive) service.tick(runs)
            val t0 = scheduler.currentTime
            scheduler.advanceTimeBy(dt); scheduler.runCurrent()
            appAdvancing = true
            if (runs) { tickWorld0 = t0; tickApp0 = appScheduler.currentTime; appScheduler.advanceTimeBy(dt) }
            tickWorld0 = scheduler.currentTime; tickApp0 = appScheduler.currentTime
            appScheduler.runCurrent()
            appAdvancing = false
            onTick?.invoke(now())
            if (now() % 1_000L < dt) samples += Sample(now(), distanceM, car.locked, walking,
                appBelievesUnlocked = appAlive && controller.believesCarUnlocked, cpuAwake = awake, gnssOn = gnssActive > 0,
                linkUp = link.state.value == DkBleManager.State.SESSION_READY, appAlive = appAlive)
            left -= dt
        }
    }
    /** Test hook: called after every tick (diagnostics). */
    var onTick: ((Long) -> Unit)? = null
    private var walkTarget = 0.0

    fun play(legs: List<Leg>, learning: List<Leg> = emptyList()) {
        Logx.testSink = { level, area, msg -> if (area in LOG_AREAS) log(level, area, msg) }
        try {
            controller.start()
            if (learning.isNotEmpty()) {
                learning.forEach(::playLeg)
                endLearning()
            }
            legs.forEach(::playLeg)
        } finally {
            Logx.testSink = null
            if (appAlive) controller.stop()
            appScope.cancel()
            scope.cancel()
        }
    }

    /**
     * The learning period is over: the user is back where the scenario starts, the car is locked and
     * the app starts afresh with only what it keeps on disk (the calibration profile). The oracles
     * and traces see the scenario alone.
     */
    private fun endLearning() {
        learnCarry = null
        if (!car.locked) car.actuate(true, "by hand")
        if (appAlive) killApp()
        savedState = null
        if (!btOn) btOn = true
        walking = false; facing = Facing.TOWARD; extraShadowDb = 0.0; indoor = false
        distanceM = startM
        run(4_000L)
        startApp()
        learnedSummary = controller.calibrationSummary()
        learnedOffsetDb = controller.calibration.offsetDb
        learnedRawOffsetDb = controller.calibration.learnedOffsetDb
        if (System.getenv("SIM_KEEP_LEARN_LOG") != "1") { events.clear(); samples.clear(); logLines.clear() }
        log('D', "sim", "learning period over: $learnedSummary")
    }

    private fun playLeg(leg: Leg) {
            when (leg) {
                is Leg.Stand -> {
                    walking = false; facing = leg.facing; extraShadowDb = leg.shadowDb; indoor = leg.indoor
                    run(leg.ms); indoor = false
                }
                is Leg.Shuffle -> {
                    walking = false; var left = leg.ms
                    while (left > 0) {
                        facing = Facing.values()[(rnd.nextDouble() * 3).toInt().coerceAtMost(2)]
                        extraShadowDb = if (rnd.chance(0.3)) leg.shadowDb else 0.0
                        val d = minOf(left, rnd.uniform(2_000.0, 6_000.0).toLong()); run(d); left -= d
                    }
                    extraShadowDb = 0.0
                }
                is Leg.Walk -> {
                    walking = true; walkSpeed = leg.speedMps; walkTarget = leg.toM; extraShadowDb = 0.0
                    facing = if (leg.toM < distanceM) Facing.TOWARD else Facing.AWAY
                    // A walk up to the door may have a flat final approach (only that walk).
                    if (leg.toM < distanceM && leg.toM <= 1.5 && physics.approachPlateauP > 0.0 &&
                        rPlateau.chance(physics.approachPlateauP)) {
                        plateauCapDb = -88.0 + (carryNow.db - Carry.FRONT_POCKET.db) + physics.phoneOffsetDb +
                            rPlateau.uniform(-2.0, 2.0)
                        plateauFromM = rPlateau.uniform(8.0, 14.0)
                    }
                    val ms = (kotlin.math.abs(leg.toM - distanceM) / leg.speedMps * 1000).toLong()
                    run(ms); walking = false; plateauCapDb = null
                }
                is Leg.WalkPeak -> {
                    walking = true; walkSpeed = leg.speedMps; walkTarget = distanceM + leg.speedMps * leg.ms / 1000.0
                    facing = Facing.AWAY; extraShadowDb = leg.boostDb
                    run(leg.ms); walking = false; extraShadowDb = 0.0
                }
                is Leg.Fidget -> {
                    walking = true; walkSpeed = 0.0; walkTarget = distanceM; facing = Facing.TOWARD
                    extraShadowDb = leg.shadowDb
                    run(leg.ms); walking = false; extraShadowDb = 0.0
                }
                Leg.OpenDoor -> { car.openDoor(); run(100) }
                is Leg.PhoneInCar -> {
                    // In the cabin, no body loss: the outdoor path-loss fit reads ~-69 at 0.7 m.
                    // Stays in the cabin until the next walk (a reopen by hand does not move it).
                    walking = false; facing = Facing.TOWARD; extraShadowDb = 21.0 - carryNow.db
                    run(leg.ms)
                }
                Leg.ReopenByOther -> { if (car.locked) car.actuate(false, "by hand"); car.openDoor(); run(100) }
                Leg.ManualLock -> {
                    record("USER_MANUAL_LOCK")
                    scope.launch {
                        if (link.session.control(DkProtocol.CTRL_LOCK, 1_500L) == ControlResult.CONFIRMED)
                            controller.onExternalLockConfirmed("manual BLE control")
                    }
                    run(600)
                }
                Leg.LockByOther -> { record("USER_LOCK_OTHER"); if (!car.locked) car.actuate(true, "by hand"); run(100) }
                Leg.CarUnlocksItself -> { if (car.locked) car.actuate(false, "car itself"); run(100) }
                is Leg.ProcessRestart -> { killApp(); run(leg.downMs); startApp() }
                is Leg.BluetoothOff -> {
                    wake(2_000L); record("BT_OFF"); btOn = false; link.disconnect()
                    run(leg.ms)
                    wake(2_000L); btOn = true; record("BT_ON")
                }
                is Leg.Bluetooth -> {
                    wake(2_000L)
                    if (!leg.on) { record("BT_OFF"); btOn = false; link.disconnect() } else { btOn = true; record("BT_ON") }
                    run(100)
                }
                is Leg.Mark -> { label = leg.label; record("MARK", leg.label) }
                is Leg.Carrying -> learnCarry = leg.carry
            }
    }

    companion object {
        const val TICK_MS = 100L
        const val CONNECT_MIN_RSSI = -96.0
        val PRESENCE_EDGE = System.getenv("SIM_PRESENCE_EDGE") == "1"
        val WEAK_GATE = System.getenv("SIM_WEAK_GATE") != "0"
        val STEP_COUNTER = System.getenv("SIM_STEP_COUNTER") != "0"
        const val DROP_RSSI = -102.0
        const val SUPERVISION_MS = 3_000L
        const val PRESENCE_MIN_RSSI = -95.0
        // ProximityService constants (14/10).
        const val RECONNECT_INTERVAL_MS = 8_000L
        const val AGGRESSIVE_RECONNECT_MS = 30_000L
        const val WAKE_PROBE_DEBOUNCE_MS = 5_000L
        const val MOTION_PRESENCE_RECOVERY_MS = 30_000L
        const val FAST_PRESENCE_WINDOW_MS = 30_000L
        const val KEY_WAKE_MOTION_CONFIRM_MS = 1_500L
        const val KEY_SLEEP_POLL_MS = 500L
        const val EARLY_PRESENCE_WINDOW_MS = KEY_WAKE_MOTION_CONFIRM_MS + 2 * KEY_SLEEP_POLL_MS
        const val KEY_SLEEP_LOCK_GRACE_MS = 15_000L
        val LOG_AREAS = setOf("prox", "svc", "motion", "sim")
        /** SIM_SELF_CAL=0: the app learns but does not apply the correction (the comparison run). */
        val SELF_CAL = System.getenv("SIM_SELF_CAL") != "0"
        /** SIM_ARRIVAL_ANCHOR=0: a proven arrival does not anchor the departure proof. */
        val ARRIVAL_ANCHOR = System.getenv("SIM_ARRIVAL_ANCHOR") != "0"
        /** SIM_H2=0: the away-return after a far link loss ignores the step counter (0.3.0). */
        val H2_STEPS = System.getenv("SIM_H2") != "0"
        /** SIM_APPROACH=unbroken|two_in_a_row|two_of_three: walking-approach confirmation (0.3.2). */
        val APPROACH_CONFIRM: ProximityDecisionPolicy.ApproachConfirm = System.getenv("SIM_APPROACH")
            ?.let { ProximityDecisionPolicy.ApproachConfirm.valueOf(it.uppercase()) }
            ?: ProximityDecisionPolicy.ApproachConfirm.UNBROKEN
    }
}
