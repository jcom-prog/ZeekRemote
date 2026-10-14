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

/** One leg of the walker's script. */
internal sealed class Leg {
    data class Stand(val ms: Long, val facing: Facing = Facing.TOWARD, val shadowDb: Double = 0.0) : Leg()
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
    data class Mark(val label: String) : Leg()
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
) {
    val rnd = SimRandom(seed)
    val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val clock = object : ProximityClock {
        override fun elapsedMs() = 100_000_000L + scheduler.currentTime
        override fun wallMs() = 1_790_000_000_000L + scheduler.currentTime
    }
    fun now() = scheduler.currentTime

    // ---------------- walker ----------------
    var distanceM = startDistanceM; private set
    var facing = Facing.TOWARD; private set
    var walking = false; private set
    var walkSpeed = 0.0; private set
    private var extraShadowDb = 0.0
    var label = ""; private set

    // ---------------- radio ----------------
    private var fade = 0.0
    var trueRssi = -120.0; private set
    private fun updateRadio() {
        // Field (03/10 18:20:31-42, standing at ~16 m): raw readings -87..-91, one -94 dip.
        fade = 0.95 * fade + sqrt(1 - 0.9025) * 1.5 * rnd.gaussian()
        val d = max(distanceM, 0.7)
        // Fitted on the field pocket readings: ~-79 at 2.5 m, -83 at 5 m, -85 at 7 m (approach ARM),
        // -87 at 10 m, -88..-90 facing at 15 m, -92..-98 walking away at 15-20 m (03/10 logs).
        trueRssi = -71.0 - 14.7 * log10(d) + carry.db + facing.db + extraShadowDb + fade
    }
    fun readRssi(): Int {
        var v = trueRssi + 1.0 * rnd.gaussian()
        if (rnd.chance(0.02)) v += rnd.uniform(-8.0, 3.0)
        return v.toInt().coerceAtMost(-30)
    }

    // ---------------- facts for the oracles ----------------
    data class Event(val atMs: Long, val kind: String, val distanceM: Double, val detail: String = "")
    val events = mutableListOf<Event>()
    fun record(kind: String, detail: String = "") { events += Event(now(), kind, distanceM, detail) }
    val logLines = ArrayList<String>()
    data class Sample(val atMs: Long, val distanceM: Double, val carLocked: Boolean, val moving: Boolean)
    val samples = ArrayList<Sample>()

    // ---------------- car ----------------
    val car = SimCar()
    inner class SimCar {
        var locked = true; private set
        var unlockedAtMs = -1L; private set
        /** Wall time of the last lock-state change, as the cloud status reports it. */
        var changedAtWallMs = 0L; private set
        var doorOpenedSinceUnlock = false; private set
        fun actuate(lock: Boolean, by: String) {
            locked = lock
            changedAtWallMs = clock.wallMs()
            if (!lock) { unlockedAtMs = now(); doorOpenedSinceUnlock = false } else unlockedAtMs = -1L
            record(if (lock) "CAR_LOCKED" else "CAR_UNLOCKED", by)
            link.pushStatus()
        }
        fun openDoor() { if (!locked) { doorOpenedSinceUnlock = true; record("DOOR_OPENED") } }
        fun tick() {
            // The 7GT relocks itself ~2 min after an unlock when no door was opened (field 03/10).
            if (!locked && unlockedAtMs >= 0 && !doorOpenedSinceUnlock && now() - unlockedAtMs >= SELF_LOCK_MS)
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
         * drops the first [HUB_COUNTER_WARMUP_STEPS] of every bout, undercounts 10 % and the app sees its
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
                if (hubBoutSteps >= HUB_COUNTER_WARMUP_STEPS) hubSteps += 0.9 * dtMs / 550.0
            } else hubBoutSteps = 0.0
            if (now() - hubSnapMs >= 1_000L) { hubSnapMs = now(); hubSeen = hubSteps.toLong() }
            if (walking) {
                stopStartMs = -1L
                if (boutStartMs < 0) {
                    boutStartMs = now(); boutLagMs = rnd.uniform(motionProfile.onLagMinMs.toDouble(), motionProfile.onLagMaxMs.toDouble()).toLong()
                    boutMissed = rnd.chance(motionProfile.missBoutP)
                }
                if (!boutMissed && now() - boutStartMs >= boutLagMs) setMoving()
                if (motionProfile.stepAssistWhileAwake && cpuAwake) {
                    stepAccum += dtMs / 550.0
                    while (stepAccum >= 1.0) { stepAccum -= 1.0; steps++; lastStepMs = now(); setMoving() }
                }
            } else {
                boutStartMs = -1L
                if (stopStartMs < 0) {
                    stopStartMs = now(); stopLagMs = rnd.uniform(motionProfile.offLagMinMs.toDouble(), motionProfile.offLagMaxMs.toDouble()).toLong()
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
                log('D', "motion", "-> MOVING"); onMovingEdge?.invoke()
            }
        }
        private fun setStill() {
            movingSinceMs = -1L
            if (_state.value != MotionMonitor.Motion.STILL) {
                stillSinceMs = now(); _state.value = MotionMonitor.Motion.STILL; log('D', "motion", "-> STILL")
            }
        }
        fun isStillFor(ms: Long) = _state.value == MotionMonitor.Motion.STILL && stillSinceMs >= 0 && now() - stillSinceMs >= ms
        fun isMovingFor(ms: Long) = _state.value == MotionMonitor.Motion.MOVING && movingSinceMs >= 0 && now() - movingSinceMs >= ms
    }

    // ---------------- link + car session ----------------
    val link = SimLink()
    inner class SimLink : ProximityLink {
        private val _state = MutableStateFlow(DkBleManager.State.IDLE)
        override val state: StateFlow<DkBleManager.State> = _state
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
                    if (lock && carRefusesKeyLock) {
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
            override suspend fun ping(timeoutMs: Long): Boolean { delay(60); return isEstablished }
            override val vehicleStatus: Flow<ObservedVehicleStatus> get() = statusFlow
            override fun answerChallenge(randX: Int, randY: Int) = ByteArray(0)
            override fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit) {}
            override fun close() {}
        }
        fun pushStatus() {
            if (_state.value != DkBleManager.State.SESSION_READY) return
            statusFlow.tryEmit(ObservedVehicleStatus(generation, if (car.locked) 3 else 1))
            lastInboundMs = clock.wallMs(); onInboundActivity?.invoke()
        }
        override suspend fun pollRemoteRssi(): Int? {
            if (_state.value != DkBleManager.State.SESSION_READY && _state.value != DkBleManager.State.CONNECTED) return null
            // A real GATT RSSI read takes 25-55 ms: field 03/10 19:57 (0.1.63) the 1 s cadence ran
            // 988-1018 ms apart, so a span of two readings is just as often 995 ms as 1005 ms.
            delay(rnd.uniform(25.0, 55.0).toLong())
            return if (_state.value == DkBleManager.State.SESSION_READY || _state.value == DkBleManager.State.CONNECTED) readRssi() else null
        }
        override fun reconnectLast(): Boolean { connect(null); return true }
        /**
         * Direct connect from an offloaded presence result. SIM_PRESENCE_EDGE=1 replays field 13/10
         * 19:41: the controller hears the car at -100 dBm, but a link cannot be established below
         * about -94 - each attempt ends in status 133 after ~0.7 s, plus one immediate retry.
         */
        fun connectFromPresence(advertRssi: Int) {
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
                delay(rnd.uniform(250.0, 700.0).toLong())
                _state.value = DkBleManager.State.CONNECTED
                lastNonIdleMs = now()
                delay(rnd.uniform(2_600.0, 3_600.0).toLong())   // GATT + DK handshake (field ~3.4 s)
                if (trueRssi < DROP_RSSI) { _state.value = DkBleManager.State.IDLE; return@launch }
                generation++
                _state.value = DkBleManager.State.SESSION_READY
                record("LINK_READY")
                delay(rnd.uniform(300.0, 5_000.0).toLong())
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
    val service = SimService()
    inner class SimService {
        var keySleeping = false; private set
        private var lastAtCarMs = -1L
        var sleepGraceUntilMs = 0L; private set
        private var presenceArmed = false
        private var presenceMode = 0 // 0 low power, 1 approach, 2 low latency
        private var presenceSeenSinceMs = -1L
        private var nextKeepAliveMs = 0L
        private var recoveryUntilMs = 0L
        private var initialProbe = false
        fun tick() {
            // Security sleep (ProximityService.stationaryKeySecurity, 500 ms poll).
            val atCar = if (System.getenv("SIM_SLEEP_AT_CAR") == "1") false else
                com.openzeekr.app.ble.KeySleepPolicy.atCar(link.state.value == DkBleManager.State.SESSION_READY,
                    controller.state.value.smoothedRssi)
            if (atCar) lastAtCarMs = now()
            val sinceAtCar = if (lastAtCarMs >= 0) now() - lastAtCarMs else null
            if (!keySleeping && com.openzeekr.app.ble.KeySleepPolicy.maySleep(motion::isStillFor, atCar, sinceAtCar)) {
                keySleeping = true; sleepGraceUntilMs = now() + 15_000L; presenceArmed = false
                link.disconnect(); log('D', "svc", "security sleep: stationary — BLE session/presence disabled")
                record("SECURITY_SLEEP")
            } else if (keySleeping && motion.isMovingFor(1_500L)) {
                keySleeping = false; sleepGraceUntilMs = 0L
                log('D', "svc", "confirmed motion: waking stationary key")
                presenceArmed = true; presenceMode = 2; presenceSeenSinceMs = -1L
                recoveryUntilMs = now() + 30_000L
            }
            // Presence scan (offloaded, in the BT controller): detection latency by duty mode.
            if (presenceArmed && !keySleeping && link.state.value == DkBleManager.State.IDLE) {
                if (trueRssi >= (if (PRESENCE_EDGE) -100.0 else PRESENCE_MIN_RSSI)) {
                    if (presenceSeenSinceMs < 0) presenceSeenSinceMs = now()
                    val latency = when (presenceMode) { 2 -> 400L; 1 -> 1_500L; else -> 5_000L }
                    if (now() - presenceSeenSinceMs >= latency) {
                        presenceSeenSinceMs = -1L
                        val r = readRssi()
                        log('D', "svc", "presence MATCH rssi=$r")
                        // 0.2.16: ProximityService gates edge-of-range hits (WeakConnectGate).
                        val action = if (WEAK_GATE) com.openzeekr.app.ble.WeakConnectGate.onPresence(r,
                            motion.state.value == MotionMonitor.Motion.MOVING)
                            else com.openzeekr.app.ble.WeakConnectGate.PresenceAction.CONNECT
                        when (action) {
                            com.openzeekr.app.ble.WeakConnectGate.PresenceAction.CONNECT -> {
                                presenceArmed = false
                                controller.onPresenceMatch(r)
                                link.connectFromPresence(r)
                            }
                            com.openzeekr.app.ble.WeakConnectGate.PresenceAction.FOREGROUND_SCAN -> {
                                presenceArmed = false
                                log('D', "svc", "presence at the edge of range while moving - foreground scan")
                                controller.onPresenceMatch(r)
                                link.connect(null)
                            }
                            com.openzeekr.app.ble.WeakConnectGate.PresenceAction.IGNORE ->
                                log('D', "svc", "presence at the edge of range while still - not connecting")
                        }
                    }
                } else presenceSeenSinceMs = -1L
            }
            // keepConnected (every 8 s).
            if (now() >= nextKeepAliveMs) {
                nextKeepAliveMs = now() + 8_000L
                if (!keySleeping && link.state.value == DkBleManager.State.IDLE) {
                    val moving = motion.state.value == MotionMonitor.Motion.MOVING
                    val recentlyEngaged = link.lastNonIdleMs >= 0 && now() - link.lastNonIdleMs < 30_000L
                    val aggressive = (moving && recentlyEngaged) && now() >= recoveryUntilMs
                    if (!initialProbe) { initialProbe = true; link.connect(null) }
                    else if (aggressive) link.connect(null)
                    else if (!presenceArmed || (presenceMode == 2 && now() >= recoveryUntilMs)) {
                        presenceArmed = true; presenceMode = if (moving) 1 else 0; presenceSeenSinceMs = -1L
                    } else if (presenceMode != 2) presenceMode = if (moving) 1 else 0
                }
            }
            if (keySleeping && now() >= sleepGraceUntilMs && link.state.value != DkBleManager.State.IDLE) link.disconnect()
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
            } else silenceAlarm()
            return notificationPermission
        }
        override fun clear() { alarmRaisedAt = 0L; alarmShowingUntil = -1L }
        override fun silenceAlarm() { alarmRaisedAt = 0L; alarmShowingUntil = -1L }
        override fun carLocked() { record("LOCKED_CHIME") }
    }

    // ---------------- GNSS (real NearDepartureAnchor / DepartureObservationWindow) ----------------
    private val locator = object : DepartureLocator {
        // Correlated drift (urban multipath): a slowly wandering bias plus rare jumps of 10-25 m
        // that persist for a few fixes while the reported accuracy stays optimistic (review 0.1.63).
        private var bias = 0.0
        private var jumpLeft = 0
        private var jump = 0.0
        private fun fix(): DepartureFix? {
            val acc = gnssAccuracyM ?: return null
            bias = 0.9 * bias + 1.5 * rnd.gaussian()
            if (jumpLeft > 0) jumpLeft-- else if (rnd.chance(0.01)) { jumpLeft = 3 + (rnd.nextDouble() * 5).toInt(); jump = rnd.uniform(10.0, 25.0) }
            val noise = acc / 2.0 * rnd.gaussian() + bias + if (jumpLeft > 0) jump else 0.0
            val metres = distanceM + noise
            // 1 degree latitude ~ 111 km; the car sits at (52.0, 5.0), the walker moves north.
            return DepartureFix(52.0 + metres / 111_000.0, 5.0, acc, clock.elapsedMs())
        }
        override suspend fun nearAnchor(policy: NearDepartureAnchor, enabled: () -> Boolean): DepartureFix? {
            val until = now() + NearDepartureAnchor.WINDOW_MS
            while (now() < until) {
                delay(1_000L)
                if (!enabled()) return null
                val f = fix() ?: continue
                // The anchor is the walker's own fix while BLE says "at the car" (real policy decides).
                if (policy.rejection(f, clock.elapsedMs()) == null) return f
            }
            return null
        }
        override suspend fun confirmsDeparture(anchor: DepartureFix, steps: () -> Long?, enabled: () -> Boolean,
                                               bleCorroborated: () -> Boolean): Boolean {
            if (!enabled()) return false
            val window = DepartureObservationWindow(anchor, clock.elapsedMs())
            val until = now() + DepartureObservationWindow.WINDOW_MS
            while (now() < until) {
                delay(1_000L)
                if (!enabled()) return false
                if (window.observe(fix(), steps(), clock.elapsedMs(), bleCorroborated()) && enabled()) return true
            }
            return false
        }
    }

    private val wakeLocks = object : SafetyWakeLocks {
        override fun acquire(timeoutMs: Long): Any? = null
        override fun release(token: Any?) {}
    }

    // ---------------- the real controller ----------------
    val config = SecretsConfig(proximityEnabled = true, proximitySensitivity = "far")
    // The cloud Lock works whether or not the key link does (field 05/10 16:19: the stock app's cloud
    // Lock locked a car that had ignored two key Locks); it takes ~2 s and reports its status.
    val controller = ProximityController(
        config = { config }, ble = link, motion = motion, scope = scope,
        cloudLock = { delay(2_000L); record("CLOUD_LOCK_CMD"); if (car.locked) Unit else car.actuate(true, "cloud"); true },
        cloudIsLocked = { car.locked },
        cloudLockSnapshot = { if (System.getenv("SIM_NO_CLOUD_STATUS") == "1") null
            else com.openzeekr.app.ble.CloudLockSnapshot(car.locked, car.changedAtWallMs) },
        clock = clock, alerts = alerts, departureLocationSource = locator, wakeLocks = wakeLocks,
    )

    fun log(level: Char, area: String, msg: String) {
        if (logLines.size < 200_000) logLines += "%8.1f d=%5.1f %c [%s] %s".format(now() / 1000.0, distanceM, level, area, msg)
    }

    private fun cpuAwake() = controller.wakeLockNeeded.value || link.state.value == DkBleManager.State.CONNECTING ||
        link.state.value == DkBleManager.State.CONNECTED

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
            car.tick(); link.tick(); motion.tick(dt, cpuAwake()); service.tick()
            scheduler.advanceTimeBy(dt); scheduler.runCurrent()
            onTick?.invoke(now())
            if (now() % 1_000L < dt) samples += Sample(now(), distanceM, car.locked, walking)
            left -= dt
        }
    }
    /** Test hook: called after every tick (diagnostics). */
    var onTick: ((Long) -> Unit)? = null
    private var walkTarget = 0.0

    fun play(legs: List<Leg>) {
        Logx.testSink = { level, area, msg -> if (area in LOG_AREAS) log(level, area, msg) }
        try {
            controller.start()
            for (leg in legs) when (leg) {
                is Leg.Stand -> { walking = false; facing = leg.facing; extraShadowDb = leg.shadowDb; run(leg.ms) }
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
                    val ms = (kotlin.math.abs(leg.toM - distanceM) / leg.speedMps * 1000).toLong()
                    run(ms); walking = false
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
                    walking = false; facing = Facing.TOWARD; extraShadowDb = 21.0 - carry.db
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
                is Leg.Mark -> { label = leg.label; record("MARK", leg.label) }
            }
        } finally {
            Logx.testSink = null
            controller.stop()
            scope.cancel()
        }
    }

    companion object {
        const val TICK_MS = 100L
        const val SELF_LOCK_MS = 120_000L
        const val CONNECT_MIN_RSSI = -96.0
        val PRESENCE_EDGE = System.getenv("SIM_PRESENCE_EDGE") == "1"
        val WEAK_GATE = System.getenv("SIM_WEAK_GATE") != "0"
        val STEP_COUNTER = System.getenv("SIM_STEP_COUNTER") != "0"
        const val HUB_COUNTER_WARMUP_STEPS = 8.0
        const val DROP_RSSI = -102.0
        const val SUPERVISION_MS = 3_000L
        const val PRESENCE_MIN_RSSI = -95.0
        val LOG_AREAS = setOf("prox", "svc", "motion")
    }
}
