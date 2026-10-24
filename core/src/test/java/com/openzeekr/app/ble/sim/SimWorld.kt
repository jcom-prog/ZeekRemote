package com.openzeekr.app.ble.sim

import com.openzeekr.app.ble.ControlResult
import com.openzeekr.app.ble.DepartureFix
import com.openzeekr.app.ble.DepartureLocator
import com.openzeekr.app.ble.DepartureFixMemory
import com.openzeekr.app.ble.DepartureObservationWindow
import com.openzeekr.app.ble.OwnRules
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.exp
import kotlin.math.sqrt

/*
 * Whole-app simulator. The REAL ProximityController (and every policy it uses) runs on virtual time.
 * Everything around it is a MODEL, calibrated on the field logs:
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
    /** Chance that the car acknowledges a key Lock but does not execute it (field test). */
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
     * jumps at the door (field, S24+ in the front trouser pocket: -85..-91 smoothed from
     * ~12 m to ~1 m, then -77 at the door and -63 at the car). Body and car shadow along the walking
     * line; the log-distance model alone always kept rising. SIM_PLATEAU_P.
     */
    val approachPlateauP: Double = env("SIM_PLATEAU_P", 0.0),
    /**
     * 0.3.9 worst case (field 15/10, 0.3.6: 151.6 s): after a 0-6 s burst of prompt updates the hub step
     * counter goes silent for half to all of this while the user walks, then hands over the total at once.
     * 0 = always prompt. SIM_HUB_FREEZE_MS.
     */
    val hubFreezeMs: Long = env("SIM_HUB_FREEZE_MS", 0.0).toLong(),
    /** 0.3.9 worst case: the hub step counter never delivers a step. SIM_HUB_NONE=1. */
    val hubNone: Boolean = System.getenv("SIM_HUB_NONE") == "1",
    /**
     * 0.3.11 worst case (field 16/10, S24+ in the front pocket, screen off): after standing still for
     * [LATE_STEPS_AFTER_STILL_MS] or more, the step counter AND the step detector deliver the first steps
     * of a walk only this long after it started - all at once - and promptly after that (three walks:
     * 8-14 s; quick walk 18/10: later still, after only a short stop at the car, so 8-18 s from 5 s of standing). 0 = off.
     * SIM_LATE_STEPS=1.
     */
    val lateStepsMinMs: Long = if (System.getenv("SIM_LATE_STEPS") == "1") 8_000L else 0L,
    val lateStepsMaxMs: Long = if (System.getenv("SIM_LATE_STEPS") == "1") 18_000L else 0L,
    /**
     * 0.3.11 worst case (field 16/10): a spot at [SPOT_FROM_M]..[SPOT_TO_M] where the signal is this much
     * stronger while the user stands there (the front door at ~25 m read -86/-87 in the front pocket - the
     * unlock threshold - where the path-loss fit gives ~-93.5). 0 = off. SIM_SPOT_DB.
     */
    val spotDb: Double = env("SIM_SPOT_DB", 0.0),
    /**
     * 0.3.12 (field 16/10, phone in the hand): walking away the signal wobbled 1.9-2.4 dB at the 1 s scale
     * (arm swing, body shadow; single short bumps), walking back 1.0; pocket walks 0.6-0.9 and
     * standing 0.8-2.0 match the time fade. Distance-correlated, only while walking with the phone in
     * the hand, full size facing away and half facing the car. 0 = off. SIM_HAND_WOBBLE_DB.
     */
    val handWobbleDb: Double = env("SIM_HAND_WOBBLE_DB", 0.0),
    /**
     * 0.3.13 worst case (field 16/10): the strong spot ([spotDb]) also while walking - walking from 30 to 25 m read
     * -81..-87 in the front pocket, 9-10 dB above the path-loss fit. Here it holds at [SPOT_FROM_M]..[SPOT_TO_M] on every
     * walk and every visit (a second visit read 7 dB weaker): an upper bound. Measurement walk C (0.3.12) found no strong
     * spot there, standing or walking, and that place measured much closer to the car than estimated: a generous one.
     */
    val spotWhileWalking: Boolean = false,
    /** 0.3.19 worst case (field 18/10): a few seconds into half the walks the detector and the counter stall this long,
     *  then hand over the held steps at once. 0 = off. */
    val midStallMinMs: Long = 0L,
    val midStallMaxMs: Long = 0L,
    /** 0.3.19 (field 18/10): the true RSSI below which the link drops after [SimWorld.SUPERVISION_MS]. The link dropped
     *  after a sustained -103..-106 and held for minutes at -100..-103. SIM_DROP_RSSI. */
    val dropRssi: Double = env("SIM_DROP_RSSI", -104.0),
    /** Study J worst case (field 16/10-19/10, S24+): after a drive the counter and the detector hold every step until the
     *  user has walked this long without a stop of more than [AFTER_DRIVE_STOP_MS], then hand them over at once or (in
     *  [afterDriveDropP] of the drives) drop them; Activity Recognition still reports the drive (MOVING) for minutes after
     *  it. 0 = off. */
    val afterDriveReleaseMinMs: Long = 0L,
    val afterDriveReleaseMaxMs: Long = 0L,
    val afterDriveDropP: Double = 0.5,
    val afterDriveMovingMinMs: Long = 180_000L,
    val afterDriveMovingMaxMs: Long = 450_000L,
    /** 0.3.21 (field 20/10, phone in a pocket, indoors): the hardware presence scan heard the car down to this true
     *  RSSI (field: about -100 inside the house). SIM_PRESENCE_EDGE=1 (field 0.2.15) sets -100. */
    val presenceFloorRssi: Double = if (System.getenv("SIM_PRESENCE_EDGE") == "1") -100.0 else SimWorld.PRESENCE_MIN_RSSI,
    /** 0.3.21 worst case (field 20/10): every connect attempt below this true RSSI ends in status 133 after 0.3-0.7 s
     *  (two attempts at an advert of about -90 failed; one a few dB stronger, at the car, connected). null = off.
     *  SIM_PRESENCE_EDGE=1 sets -94 (field 0.2.15: no link below about -94). */
    val edge133Rssi: Double? = if (System.getenv("SIM_PRESENCE_EDGE") == "1") -94.0 else null,
    /** Replays 0.3.20's presence-scan handling (the model check of field 20/10): a mode change stops the running scan
     *  before the ration is asked, the status-133 recovery arms BALANCED, a presence hit stops the scan before its
     *  direct connect, every foreground scan next to it starts. SIM_SCAN_0320=1. */
    val scan0320: Boolean = System.getenv("SIM_SCAN_0320") == "1",
    /** 0.4.0 study worst case (field 22/10): the hub counter and the detector deliver nothing while the user walks and
     *  hand the walk over in ONE batch this long after it ended (field: 1665 s). 0 = off. */
    val hubBatchMinMs: Long = 0L,
    val hubBatchMaxMs: Long = 0L,
) {
    fun describe() = "%s phone%+.1fdB slope%.1f fade%.1f gnss%s ttff%.0fs hub%.0fs warm%.0f self%.0fs refuse%.2f cloud%.0fs/fail%.2f/lag%.0fs".format(
        name, phoneOffsetDb, pathSlope, fadeSigmaDb, gnssAccMedianM?.let { "%.1fm".format(it) } ?: "-", gnssTtffMs / 1000.0,
        hubLagMs / 1000.0, stepWarmup, selfLockMs / 1000.0, keyLockRefuseP, cloudLockDelayMs / 1000.0, cloudFailP, cloudStatusLagMs / 1000.0) +
        (if (hubNone) " counter-none" else if (hubFreezeMs > 0) " counter-silent%.0fs".format(hubFreezeMs / 1000.0) else "") +
        (if (lateStepsMaxMs > 0) " steps-late%.0f-%.0fs".format(lateStepsMinMs / 1000.0, lateStepsMaxMs / 1000.0) else "") +
        (if (spotDb != 0.0) " spot%+.0fdB".format(spotDb) + (if (spotWhileWalking) "-walking" else "") else "") +
        (if (handWobbleDb != 0.0) " hand-wobble%.1fdB".format(handWobbleDb) else "") +
        (if (midStallMaxMs > 0) " steps-stall%.0f-%.0fs".format(midStallMinMs / 1000.0, midStallMaxMs / 1000.0) else "") +
        (if (afterDriveReleaseMaxMs > 0) " steps-after-drive%.0f-%.0fs".format(afterDriveReleaseMinMs / 1000.0, afterDriveReleaseMaxMs / 1000.0) else "") +
        (if (presenceFloorRssi != SimWorld.PRESENCE_MIN_RSSI) " presence%.0f".format(presenceFloorRssi) else "") +
        (edge133Rssi?.let { " 133-below%.0f".format(it) } ?: "") +
        (if (hubBatchMaxMs > 0) " steps-batch%.0f-%.0fs".format(hubBatchMinMs / 1000.0, hubBatchMaxMs / 1000.0) else "")

    companion object {
        private fun env(name: String, default: Double) = System.getenv(name)?.toDouble() ?: default
        /** Study J: a stop longer than this restarts the walk the held steps wait for ([afterDriveReleaseMinMs]). */
        const val AFTER_DRIVE_STOP_MS = 2_000L
        /** Standing still at least this long makes the next walk's first steps late ([lateStepsMinMs]);
         *  field 18/10: a short stop at the car was enough. */
        const val LATE_STEPS_AFTER_STILL_MS = 5_000L
        /** Where the strong spot ([spotDb]) is: the front door at ~25 m, with some margin. */
        const val SPOT_FROM_M = 18.0
        const val SPOT_TO_M = 32.0
        fun calibrated() = SimPhysics()
        /** A plausible other world (sensitivity / stress). Ranges from the field logs. */
        fun random(r: SimRandom) = SimPhysics(
            name = "random",
            phoneOffsetDb = (3.0 * r.gaussian()).coerceIn(-6.0, 6.0),
            pathSlope = r.uniform(13.0, 20.0),
            fadeSigmaDb = r.uniform(1.2, 3.5),
            // Field: "accuracy over 8 m" in nearly every episode of one field day.
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
    /** The phone lies still inside the car, out of the pocket (field: -46..-49 dBm). */
    data class PhoneInCar(val ms: Long) : Leg()
    object ManualLock : Leg()
    /** The car is locked without the phone key and without the app noticing (fob, handle, other phone). */
    object LockByOther : Leg()
    /** The car unlocks itself (parking after a drive: status 2 then 1, field). */
    object CarUnlocksItself : Leg()
    /** W2: the watch locks or unlocks the car with its own key: pause (the phone stands down), act, report, resume.
     *  [resume] false: the watch app died after acting - the phone's fail-safe reclaims the link after 45 s. */
    /** 0.4.3: [whenLooping] walks on toward [walkToM] and taps the watch the moment the phone's own Lock or Unlock loop runs
     *  (review T1: a hand-over mid-loop); [actMs] is what the watch needs for connect + action (50 s outruns the 45 s
     *  fail-safe: the phone takes the link back before the watch reports). */
    data class WatchLock(val resume: Boolean = true, val whenLooping: Boolean = false, val walkToM: Double = 30.0, val actMs: Long = 1_500L) : Leg()
    data class WatchUnlock(val resume: Boolean = true, val whenLooping: Boolean = false, val walkToM: Double = 30.0, val actMs: Long = 1_500L) : Leg()
    /** 0.4.3 (H5/T5): the watch asks for the link, gets no usable ack or gives up, and hands it back after [afterMs] without acting. */
    data class WatchAbort(val afterMs: Long = 10_000L) : Leg()
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
    /** Field: the car acknowledges a key Lock (0x0111) but does not lock. */
    val carRefusesKeyLock: Boolean = false,
    /** Field: at the edge of the link (~-102) the key Lock got no answer although the
     *  session was still up. Below this true RSSI a key Lock times out without reaching the car. */
    val keyEdgeRssi: Double? = null,
    val physics: SimPhysics = SimPhysics.calibrated(),
    /** The cloud never answers a Lock (outage) - field (garage). */
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
    /**
     * Time-keyed noise (0.3.2, from the simulation audit; SIM_KEYED=0 restores the sequential streams):
     * the slow fade has its own stream, and the read noise, link delays and GNSS noise come from
     * generators keyed by (seed, 100 ms tick, draw index in that tick, purpose). Two app versions then
     * meet the same noise at the same moment however often they read, so a before/after comparison on
     * the same seeds differs only where decisions differ (sequential draws re-rolled ~35 far unlocks
     * each way on unrelated families).
     */
    private val rFade = if (KEYED) stream(9) else null
    /** 0.3.12: own stream, so runs without the hand wobble draw exactly as before. */
    private val rWobble = stream(10)
    private var keyedTick = -1L
    private val keyedIdx = IntArray(8)
    private fun keyed(salt: Int): SimRandom {
        val tick = now() / TICK_MS
        if (tick != keyedTick) { keyedTick = tick; keyedIdx.fill(0) }
        val i = keyedIdx[salt]++
        return SimRandom(mix(mix(seed + 0x632BE59BD9B4E019L * salt) + tick * 0x9E3779B97F4A7C15uL.toLong() + i))
    }
    private fun mix(z0: Long): Long {
        var z = z0 + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
    private fun rRead() = if (KEYED) keyed(1) else rRadio
    private fun rLinkNow() = if (KEYED) keyed(2) else rLink
    /** SIM_KEYED: the GNSS multipath bias and jumps evolve once a second as a world process. */
    private val rGnssWorld = stream(10)
    private var worldGnssBias = 0.0; private var worldGnssJumpLeft = 0; private var worldGnssJump = 0.0
    private fun stepWorldGnss() {
        worldGnssBias = 0.9 * worldGnssBias + 1.5 * rGnssWorld.gaussian()
        if (worldGnssJumpLeft > 0) worldGnssJumpLeft-- else if (rGnssWorld.chance(0.01)) {
            worldGnssJumpLeft = 3 + (rGnssWorld.nextDouble() * 5).toInt(); worldGnssJump = rGnssWorld.uniform(10.0, 25.0) }
    }
    private val rPlateau = stream(8)
    /** 0.3.9: when the hub step counter goes silent (own stream, so other draws stay paired). */
    private val rHub = stream(11)
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
    /** 0.4.0 study: the walker turns or shuffles at one spot (a Shuffle leg): the own counter may count a false step. */
    private var shuffling = false
    private val rOwn = stream(12)

    // ---------------- radio ----------------
    private var fade = 0.0
    private var handWobble = 0.0
    var trueRssi = -120.0; private set
    private fun updateRadio() {
        // Field (standing at ~16 m): raw readings -87..-91, one -94 dip.
        fade = 0.95 * fade + sqrt(1 - 0.9025) * physics.fadeSigmaDb * (rFade ?: rRadio).gaussian()
        val d = max(distanceM, 0.7)
        // Fitted on the field pocket readings: ~-79 at 2.5 m, -83 at 5 m, -85 at 7 m (approach ARM),
        // -87 at 10 m, -88..-90 facing at 15 m, -92..-98 walking away at 15-20 m (field logs).
        trueRssi = -71.0 - physics.pathSlope * log10(d) + carryNow.db + facing.db + extraShadowDb + fade + physics.phoneOffsetDb
        // 0.3.11 worst case: the strong spot (front door) while the user stands there.
        if (physics.spotDb != 0.0 && (!walking || physics.spotWhileWalking) &&
            distanceM in SimPhysics.SPOT_FROM_M..SimPhysics.SPOT_TO_M) trueRssi += physics.spotDb
        if (physics.handWobbleDb > 0.0) {
            if (walking && carryNow == Carry.HAND) {
                val rho = exp(-walkSpeed * TICK_MS / 1000.0 / HAND_WOBBLE_CORR_M)
                val sigma = if (facing == Facing.TOWARD) physics.handWobbleDb / 2 else physics.handWobbleDb
                handWobble = rho * handWobble + sqrt(1 - rho * rho) * sigma * rWobble.gaussian()
            } else handWobble *= 0.8
            trueRssi += handWobble
        }
        plateauCapDb?.let { cap -> if (distanceM in 1.0..plateauFromM) trueRssi = minOf(trueRssi, cap + fade) }
    }
    fun readRssi(): Int {
        val rr = rRead()
        var v = trueRssi + 1.0 * rr.gaussian()
        if (rr.chance(0.02)) v += rr.uniform(-8.0, 3.0)
        return v.toInt().coerceAtMost(-30)
    }

    // ---------------- facts for the oracles ----------------
    data class Event(val atMs: Long, val kind: String, val distanceM: Double, val detail: String = "")
    val events = mutableListOf<Event>()
    fun record(kind: String, detail: String = "") { events += Event(now(), kind, distanceM, detail) }
    val logLines = ArrayList<String>()
    data class Sample(val atMs: Long, val distanceM: Double, val carLocked: Boolean, val moving: Boolean,
                      val own: Long? = null, val ownOn: Boolean = false,
                      val appBelievesUnlocked: Boolean = false, val cpuAwake: Boolean = false, val gnssOn: Boolean = false,
                      val linkUp: Boolean = false, val appAlive: Boolean = true,
                      /** Diagnostics only: the true RSSI, the motion state and the steps the app sees. */
                      val trueRssi: Double = 0.0, val motionState: String = "", val steps: Long? = null,
                      /** 0.3.21 diagnostics: the app can find the car now (a link in progress or up, or a hardware
                       *  presence scan that delivers), and the key is awake. */
                      val listening: Boolean = false, val keyAsleep: Boolean = false)
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
            // The 7GT relocks itself ~2 min after an unlock when no door was opened (field test).
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
        private var freezeFromMs = Long.MAX_VALUE; private var freezeUntilMs = Long.MIN_VALUE
        /** 0.4.0 study worst case: the batch world holds every step until this moment (MAX while walking). */
        private var batchHoldUntilMs = Long.MIN_VALUE
        // ---- 0.4.0 study: the app's own step counter (accelerometer) ----
        // Field 23/10 (three measurement walks, front pocket, screen off): counts per second from the first step,
        // 516 for 500 counted steps (+3 %), 0 false steps in stops of 14-20 s; the hub counter 5-14 s late, in batches.
        private var ownAcc = 0.0; private var ownTotal = 0L; private var ownStepAtMs = Long.MIN_VALUE
        private var ownSeen = 0L; private var ownSeenAtMs = Long.MIN_VALUE; private var ownSeenStepWallMs: Long? = null
        private val ownStepWalls = ArrayDeque<Long>()
        private var ownEpochOpen = false; private var ownApproachUntilMs = Long.MIN_VALUE
        private var ownWasCounting = false
        /** Every own step the model counted (study dump). */
        var ownCounted = 0L; private set
        private fun ownWindowOpen() = ownEpochOpen || now() < ownApproachUntilMs
        override val ownCounting: Boolean get() = OWN && appAlive && ownWindowOpen()
        override val ownSteps: Long? get() = if (OWN) ownSeen else null
        override val ownLastStepAtMs: Long? get() = if (OWN) ownSeenStepWallMs else null
        override fun ownStepsSince(wallMs: Long): Long = if (!OWN) 0L else ownStepWalls.count { it >= wallMs }.toLong()
        /** The app's window while it believes the car open (opened at the unlock, closed at the Lock). */
        override fun ownWindow(active: Boolean) {
            if (!OWN) return
            if (active != ownEpochOpen) log('D', "sim", "own window ${if (active) "open" else "closed"} (car open)")
            ownEpochOpen = active
        }
        /** The locked-side window: extended by the controller per presence hit / reading in the band. */
        override fun ownApproachWindow(active: Boolean, hit: Boolean) {
            if (!OWN) return
            if (active) {
                if (now() >= ownApproachUntilMs && !ownEpochOpen) log('D', "sim", "own window open (locked side)")
                ownApproachUntilMs = now() + OWN_APPROACH_WINDOW_MS
            } else ownApproachUntilMs = Long.MIN_VALUE
        }
        /** The process died: the tracker's windows die with it. */
        fun ownReset() { ownEpochOpen = false; ownApproachUntilMs = Long.MIN_VALUE }
        /** Called by the world every tick. The counter runs only in an open window ([ownCounting]); the app holds the CPU then. */
        fun ownTick(dtMs: Long, shufflingNow: Boolean) {
            if (!OWN) return
            val counting = ownCounting
            if (counting != ownWasCounting) {
                ownWasCounting = counting
                if (!counting) log('D', "sim", "own window closed (counted $ownTotal)")
            }
            if (!counting) { ownAcc = 0.0; return }
            if (walking) ownAcc += dtMs / 550.0 * OWN_OVERCOUNT
            else {
                val rate = if (shufflingNow) OWN_FALSE_SHUFFLE else OWN_FALSE_STAND
                if (rate > 0.0 && rOwn.chance(rate * dtMs / 1000.0)) ownAcc += 1.0
            }
            while (ownAcc >= 1.0) { ownAcc -= 1.0; ownTotal++; ownCounted++; ownStepAtMs = now() }
            if (ownSeenAtMs == Long.MIN_VALUE || now() - ownSeenAtMs >= OWN_LAG_MS) {
                ownSeenAtMs = now()
                if (ownTotal > ownSeen) {
                    val wall = clock.wallMs() - (now() - ownStepAtMs)
                    repeat((ownTotal - ownSeen).toInt().coerceAtMost(50)) { ownStepWalls.addLast(wall) }
                    while (ownStepWalls.size > 200) ownStepWalls.removeFirst()
                    ownSeen = ownTotal; ownSeenStepWallMs = wall
                    if (OWN_MOTION) { vehicleMotion = false; lastStepMs = now(); setMoving() }
                }
            }
        }
        override val observedSteps: Long? get() =
            if (!motionProfile.hasStepDetector) null
            else if (STEP_COUNTER) maxOf(steps, hubSeen) else steps
        override val stepSummary: String get() = "detector=$steps counter=${if (STEP_COUNTER) hubSeen.toString() else "none"}"
        override val counterSteps: Long? get() = if (motionProfile.hasStepDetector && STEP_COUNTER) hubSeen else null
        override var onMovingEdge: (() -> Unit)? = null
        /** Study J2: see [com.openzeekr.app.ble.ProximityMotion.vehicleMotion]. */
        override var vehicleMotion = false; private set
        private var running = false
        override fun start() { running = true; if (_state.value == MotionMonitor.Motion.UNKNOWN) setStill() }
        override fun stop() { running = false; _state.value = MotionMonitor.Motion.UNKNOWN }

        private var boutStartMs = -1L; private var boutLagMs = 0L; private var boutMissed = false
        private var stopStartMs = -1L; private var stopLagMs = 0L
        private var stillSinceMs = -1L; private var movingSinceMs = -1L
        private var lastStepMs = -1L; private var stepAccum = 0.0
        /** 0.3.11 worst case: when the user last stopped walking, and until when this walk's steps are held. */
        private var walkEndedMs = Long.MIN_VALUE; private var wasWalking = false
        private var lateUntilMs = Long.MIN_VALUE; private var lateDetectorSteps = 0L
        /** 0.3.19 worst case: when this walk may stall ([SimPhysics.midStallMaxMs]). */
        private var stallCheckAtMs = Long.MIN_VALUE
        /** Study J worst case: the steps held after a drive ([SimPhysics.afterDriveReleaseMaxMs]). */
        private var driving = false; private var driveHold = false
        private var driveWalkMs = 0L; private var driveStopMs = 0L; private var driveReleaseMs = 0L
        private var heldHub = 0.0; private var heldDetector = 0L
        private var vehicleMovingUntilMs = Long.MIN_VALUE
        private val vehicleMoving get() = driving || now() < vehicleMovingUntilMs

        fun driveStarted() {
            if (physics.afterDriveReleaseMaxMs <= 0) return
            driving = true; driveHold = true; driveWalkMs = 0L; driveStopMs = 0L; heldHub = 0.0; heldDetector = 0L
            vehicleMotion = true
            driveReleaseMs = rHub.uniform(physics.afterDriveReleaseMinMs.toDouble(), physics.afterDriveReleaseMaxMs.toDouble()).toLong()
            setMoving()
        }

        fun driveEnded() {
            if (!driving) return
            driving = false
            vehicleMovingUntilMs = now() + rMotion.uniform(physics.afterDriveMovingMinMs.toDouble(), physics.afterDriveMovingMaxMs.toDouble()).toLong()
        }

        fun tick(dtMs: Long, cpuAwake: Boolean) {
            // Study J: the walk the held steps wait for; then all at once, or (half the drives) never.
            if (driveHold && !driving) {
                if (walking) { driveWalkMs += dtMs; driveStopMs = 0L }
                else { driveStopMs += dtMs; if (driveStopMs > SimPhysics.AFTER_DRIVE_STOP_MS) driveWalkMs = 0L }
                if (driveWalkMs >= driveReleaseMs) {
                    driveHold = false
                    if (!rHub.chance(physics.afterDriveDropP)) {
                        hubSteps += heldHub
                        if (heldDetector > 0) { steps += heldDetector; lastStepMs = now(); vehicleMotion = false; setMoving() }
                    }
                    heldHub = 0.0; heldDetector = 0L
                }
            }
            if (walking && !wasWalking && physics.lateStepsMaxMs > 0 &&
                (walkEndedMs == Long.MIN_VALUE || now() - walkEndedMs >= SimPhysics.LATE_STEPS_AFTER_STILL_MS))
                lateUntilMs = now() + rHub.uniform(physics.lateStepsMinMs.toDouble(), physics.lateStepsMaxMs.toDouble()).toLong()
            if (!walking && wasWalking) walkEndedMs = now()
            // 0.3.19 worst case (field 18/10): the step stream stalled for seconds in the middle of a short walk.
            if (physics.midStallMaxMs > 0) {
                if (walking && !wasWalking) stallCheckAtMs = now() + rHub.uniform(3_000.0, 8_000.0).toLong()
                if (!walking) stallCheckAtMs = Long.MIN_VALUE
                if (walking && stallCheckAtMs != Long.MIN_VALUE && now() >= stallCheckAtMs) {
                    stallCheckAtMs = Long.MIN_VALUE
                    if (rHub.chance(0.5)) lateUntilMs = maxOf(lateUntilMs,
                        now() + rHub.uniform(physics.midStallMinMs.toDouble(), physics.midStallMaxMs.toDouble()).toLong())
                }
            }
            wasWalking = walking
            // 0.4.0 study worst case (field 22/10): nothing while walking, one batch 30-300 s after the walk ends.
            if (physics.hubBatchMaxMs > 0) {
                if (walking) batchHoldUntilMs = Long.MAX_VALUE
                else if (batchHoldUntilMs == Long.MAX_VALUE)
                    batchHoldUntilMs = now() + rHub.uniform(physics.hubBatchMinMs.toDouble(), physics.hubBatchMaxMs.toDouble()).toLong()
            }
            val late = now() < lateUntilMs || (physics.hubBatchMaxMs > 0 && (walking || now() < batchHoldUntilMs))
            if (walking) {
                hubBoutSteps += dtMs / 550.0
                if (hubBoutSteps >= physics.stepWarmup) { if (driveHold) heldHub += 0.9 * dtMs / 550.0 else hubSteps += 0.9 * dtMs / 550.0 }
            } else hubBoutSteps = 0.0
            // 0.3.9 worst cases: a counter that goes silent while the user walks, or never delivers.
            if (physics.hubFreezeMs > 0 && walking && now() >= freezeUntilMs) {
                freezeFromMs = now() + rHub.uniform(0.0, 6_000.0).toLong()
                freezeUntilMs = freezeFromMs + rHub.uniform(physics.hubFreezeMs / 2.0, physics.hubFreezeMs.toDouble()).toLong()
            }
            val hubSilent = physics.hubNone || (now() >= freezeFromMs && now() < freezeUntilMs)
            if (!hubSilent && !late && now() - hubSnapMs >= physics.hubLagMs) {
                hubSnapMs = now(); if (hubSteps.toLong() > hubSeen) vehicleMotion = false; hubSeen = hubSteps.toLong()
            }
            // The held detector steps arrive at once when the late window ends (field: "+9 in 73,8 s").
            if (!late && lateDetectorSteps > 0) { steps += lateDetectorSteps; lateDetectorSteps = 0L; lastStepMs = now(); vehicleMotion = false; setMoving() }
            if (walking) {
                stopStartMs = -1L
                if (boutStartMs < 0) {
                    boutStartMs = now(); boutLagMs = rMotion.uniform(motionProfile.onLagMinMs.toDouble(), motionProfile.onLagMaxMs.toDouble()).toLong()
                    boutMissed = rMotion.chance(motionProfile.missBoutP)
                }
                // Study J: Activity Recognition still reports the drive - no walking transition either.
                if (!boutMissed && now() - boutStartMs >= boutLagMs && !vehicleMoving) { vehicleMotion = false; setMoving() }
                if (motionProfile.stepAssistWhileAwake && cpuAwake) {
                    stepAccum += dtMs / 550.0
                    while (stepAccum >= 1.0) {
                        stepAccum -= 1.0
                        if (driveHold) heldDetector++ else if (late) lateDetectorSteps++ else { steps++; lastStepMs = now(); vehicleMotion = false; setMoving() }
                    }
                }
            } else {
                boutStartMs = -1L
                if (stopStartMs < 0) {
                    stopStartMs = now(); stopLagMs = rMotion.uniform(motionProfile.offLagMinMs.toDouble(), motionProfile.offLagMaxMs.toDouble()).toLong()
                }
                // Study J: Activity Recognition still reports the drive.
                if (now() - stopStartMs >= stopLagMs && !vehicleMoving) setStill()
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
            vehicleMotion = false; vehicleMovingUntilMs = Long.MIN_VALUE
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
        /** Reads are serialised like DkBleManager's rssiPollLock (0.3.2, simulation audit C: two reads
         *  finishing at the same virtual millisecond revoked fresh departure proofs as time_not_advancing). */
        private val rssiPollLock = Mutex()
        override suspend fun pollRemoteRssi(): Int? = rssiPollLock.withLock { pollRemoteRssiNow() }
        private suspend fun pollRemoteRssiNow(): Int? {
            if (_state.value != DkBleManager.State.SESSION_READY && _state.value != DkBleManager.State.CONNECTED) return null
            // A real GATT RSSI read takes 25-55 ms: field (0.1.63) the 1 s cadence ran
            // 988-1018 ms apart, so a span of two readings is just as often 995 ms as 1005 ms.
            linkOps++
            try {
                delay(rLinkNow().uniform(25.0, 55.0).toLong())
                return if (_state.value == DkBleManager.State.SESSION_READY || _state.value == DkBleManager.State.CONNECTED) readRssi() else null
            } finally { linkOps-- }
        }
        /**
         * 0.3.21 (field 20/10): DkBleManager rations scan starts ([com.openzeekr.app.ble.ScanStartLimiter], four per
         * 30 s; Android allows five): every foreground scan and every (re)registration of the hardware presence scan
         * is a start. A direct connect (a presence result, [reconnectLast]) is not. A new process starts a new ration.
         */
        var scanStarts = com.openzeekr.app.ble.ScanStartLimiter(); private set
        /** DkBleManager's status-133 recovery (the real policy); a new process starts a new one. */
        private var status133 = com.openzeekr.app.ble.Status133RecoveryPolicy()
        /** DkBleManager.lastDevice: the route of a direct reconnect, forgotten when the 133 recovery is exhausted. */
        private var hasLastDevice = false
        /** Android's own limit (five starts per 30 s, kept across a new process): a start beyond it is accepted
         *  but the scan delivers nothing. Every start counts, the refused ones too. */
        private val androidStarts = ArrayDeque<Long>()
        fun androidStart(): Boolean {
            while (androidStarts.isNotEmpty() && now() - androidStarts.first() >= com.openzeekr.app.ble.ScanStartLimiter.WINDOW_MS)
                androidStarts.removeFirst()
            androidStarts.addLast(now())
            if (androidStarts.size > ANDROID_SCAN_STARTS) { record("SCAN_BLIND", "Android: more than $ANDROID_SCAN_STARTS starts in 30 s"); return false }
            return true
        }
        /** The app process died: DkBleManager starts over. */
        fun newProcess() {
            scanStarts = com.openzeekr.app.ble.ScanStartLimiter(); status133 = com.openzeekr.app.ble.Status133RecoveryPolicy()
            hasLastDevice = false
        }
        fun deferWeakPresence(rssi: Int) = status133.shouldDeferWeakPresence(rssi, clock.elapsedMs())
        private val linkBusy get() = _state.value == DkBleManager.State.CONNECTING ||
            _state.value == DkBleManager.State.CONNECTED || _state.value == DkBleManager.State.SESSION_READY
        private val keyAsleep get() = service.keySleeping && now() >= service.sleepGraceUntilMs

        /** DkBleManager.reconnectLast: straight to the last device, no scan; false without one. A direct connect
         *  waits for the car's advert (here up to 10 s; Android ends it in a 133 later). */
        override fun reconnectLast(): Boolean {
            if (!btOn || !appAlive || keyAsleep) return false
            if (!hasLastDevice || linkBusy || _state.value == DkBleManager.State.SCANNING) return false
            _state.value = DkBleManager.State.CONNECTING
            val rc = rLinkNow()
            connectJob = scope.launch {
                var waited = 0L
                while (trueRssi < CONNECT_MIN_RSSI && waited < 10_000L) { delay(200); waited += 200 }
                if (trueRssi < CONNECT_MIN_RSSI) { _state.value = DkBleManager.State.IDLE; return@launch }
                gatt(rc)
            }
            return true
        }
        /**
         * DkBleManager.connectFromPresence: a direct connect from the offloaded scan's result, no scan start. False
         * for an advert at the edge of range ([com.openzeekr.app.ble.WeakConnectGate]): the caller scans instead.
         * A foreground scan in progress is stopped for it.
         */
        fun connectFromPresence(advertRssi: Int): Boolean {
            if (!btOn || !appAlive) return false
            if (linkBusy) return true
            if (com.openzeekr.app.ble.WeakConnectGate.tooWeak(advertRssi)) return false
            connectJob?.cancel(); connectJob = null
            hasLastDevice = true
            _state.value = DkBleManager.State.CONNECTING
            val rc = rLinkNow()
            connectJob = scope.launch { gatt(rc) }
            return true
        }
        /** DkBleManager.connect(null): a foreground scan (a scan start) that connects to the first advert in range,
         *  or gives up after [SCAN_TIMEOUT_MS]. */
        override fun connect(deviceMac: String?) {
            if (!btOn || !appAlive) return
            if (connectJob?.isActive == true || linkBusy || _state.value == DkBleManager.State.SCANNING) return
            if (keyAsleep) return
            if (!scanStarts.tryStart(clock.wallMs())) {
                log('W', "ble", "scan start deferred: Android allows 5 scan starts per 30 s; the keep-alive retries")
                record("SCAN_DEFERRED", "foreground")
                _state.value = DkBleManager.State.IDLE
                return
            }
            val delivers = androidStart()
            _state.value = DkBleManager.State.SCANNING
            val rc = rLinkNow()
            connectJob = scope.launch {
                var waited = 0L
                while ((!delivers || trueRssi < CONNECT_MIN_RSSI) && waited < SCAN_TIMEOUT_MS) { delay(200); waited += 200 }
                if (!delivers || trueRssi < CONNECT_MIN_RSSI) { _state.value = DkBleManager.State.IDLE; return@launch }
                hasLastDevice = true
                gatt(rc)
            }
        }
        /**
         * connectGatt to a car in range: connected after 250-700 ms, the DK handshake (field ~3.4 s) after that. Below
         * the world's [SimPhysics.edge133Rssi] the attempt ends in status 133 instead, and DkBleManager's recovery runs:
         * one fast direct retry of the recent route, then a fresh presence scan through the service.
         */
        private suspend fun gatt(rc: SimRandom) {
            _state.value = DkBleManager.State.CONNECTING
            val edge = physics.edge133Rssi
            if (edge != null && trueRssi < edge) {
                delay(rc.uniform(300.0, 700.0).toLong())
                record("STATUS_133", "%.0f dBm".format(trueRssi))
                connectJob = null
                _state.value = DkBleManager.State.IDLE
                if (status133.onFailure(hasLastDevice, clock.elapsedMs()) == com.openzeekr.app.ble.Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE) {
                    log('W', "ble", "status 133 - one fast retry of recent presence route")
                    scope.launch {
                        delay(STATUS_133_DIRECT_RETRY_DELAY_MS)
                        if (_state.value == DkBleManager.State.IDLE && !reconnectLast()) service.onStatus133Recovery()
                    }
                } else {
                    hasLastDevice = false
                    log('W', "ble", "status 133 - recent route exhausted; immediate fresh scan")
                    scope.launch {
                        delay(STATUS_133_RESCAN_DELAY_MS)
                        if (_state.value == DkBleManager.State.IDLE) service.onStatus133Recovery()
                    }
                }
                return
            }
            delay(rc.uniform(250.0, 700.0).toLong())
            _state.value = DkBleManager.State.CONNECTED
            lastNonIdleMs = now()
            delay(rc.uniform(2_600.0, 3_600.0).toLong())   // GATT + DK handshake (field ~3.4 s)
            if (trueRssi < physics.dropRssi) { _state.value = DkBleManager.State.IDLE; return }
            generation++
            status133.onSessionReady()
            _state.value = DkBleManager.State.SESSION_READY
            record("LINK_READY")
            delay(rc.uniform(300.0, 5_000.0).toLong())
            // The car pushes its status early in a new session. SIM_FIRST_STATUS_LOST=1 replays 0.2.6, which
            // dropped that push (it arrives before "established").
            if (System.getenv("SIM_FIRST_STATUS_LOST") != "1") pushStatus()
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
                if (trueRssi < physics.dropRssi) {
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
            keySleeping = false; lastAtCarMs = -1L; sleepGraceUntilMs = 0L; disarm(); lastStepsSeen = null; lastStepChangeMs = -1L
            earlyUntilMs = 0L; savedEarlyRssi = null; fastPresenceUntilMs = 0L; recoveryUntilMs = 0L
            lastEngagedMs = -1L; initialProbe = false; lastWakeProbeMs = -1L; nextKeepAliveMs = 0L; nextSleepPollMs = 0L
            rearm133AtMs = 0L
        }
        var keySleeping = false; private set
        private var lastAtCarMs = -1L
        /** ProximityService (0.3.2): the last step count seen and when it last changed. */
        private var lastStepsSeen: Long? = null
        private var lastStepChangeMs = -1L
        var sleepGraceUntilMs = 0L; private set
        var presenceArmed = false; private set
        private var presenceMode = 0 // 0 low power, 1 balanced (approach), 2 low latency
        /** False when Android refused the registration silently (more than five starts in 30 s): armed, but blind. */
        private var presenceDelivers = true
        /** Diagnostics: a hardware presence scan is registered and delivers. */
        val presenceListening get() = presenceArmed && presenceDelivers
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

        /**
         * DkBleManager.armPresenceScan. A scan already running in this mode keeps running (study C, 18/10: restarting
         * its latency on every keep-alive re-arm (8 s) let a low-power scan find the car only at the door). Another
         * mode re-registers the scan as [com.openzeekr.app.ble.PresenceScanArmPolicy] decides within the scan-start
         * ration ([SimLink.scanStarts]). [SimPhysics.scan0320] replays 0.3.20, which stopped the running scan first:
         * when the ration then refused the new one nothing was left (field 20/10: 24 s without a scan).
         */
        private fun arm(lowLatency: Boolean, approach: Boolean): Boolean {
            if (!btOn) return false
            val mode = if (approach && lowLatency) 2 else if (approach) 1 else 0
            if (physics.scan0320) {
                if (presenceArmed && presenceMode == mode) return true
                if (presenceArmed) disarm()
                if (!link.scanStarts.tryStart(clock.wallMs())) return deferred()
            } else {
                val now = clock.wallMs()
                when (com.openzeekr.app.ble.PresenceScanArmPolicy.decide(presenceArmed, presenceMode == mode, link.scanStarts.free(now))) {
                    com.openzeekr.app.ble.PresenceScanArmPolicy.Action.KEEP -> {
                        if (presenceMode != mode) log('D', "ble", "presence scan kept (${MODE_NAMES[presenceMode]}): " +
                            "a restart for ${MODE_NAMES[mode]} would leave no scan start in reserve")
                        return true
                    }
                    com.openzeekr.app.ble.PresenceScanArmPolicy.Action.DEFER -> return deferred()
                    else -> {}
                }
                link.scanStarts.tryStart(now)
                if (presenceArmed) disarm()
            }
            presenceDelivers = link.androidStart()
            presenceArmed = true; presenceSeenSinceMs = -1L
            presenceMode = mode
            log('D', "ble", "presence scan ARMED (${MODE_NAMES[mode]})")
            return true
        }
        private fun deferred(): Boolean {
            log('W', "svc", "presence scan arm deferred (Android allows 5 scan starts per 30 s)")
            record("SCAN_DEFERRED", "presence")
            return false
        }
        private fun disarm() {
            if (presenceArmed) log('D', "ble", "presence scan DISARMED")
            presenceArmed = false; presenceSeenSinceMs = -1L
        }
        /** ProximityService.fastPresenceWanted: LOW_LATENCY while moving within the window after a motion wake. */
        private fun fastWanted() = moving && now() < fastPresenceUntilMs
        /** ProximityService (0.3.21): the status-133 recovery re-arms once a scan start is free again. */
        private var rearm133AtMs = 0L

        fun tick(appRuns: Boolean) {
            if (appRuns && rearm133AtMs in 1..now()) {
                rearm133AtMs = 0L
                if (!keySleeping && link.state.value == DkBleManager.State.IDLE && arm(lowLatency = fastWanted(), approach = true)) {
                    recoveryUntilMs = now() + MOTION_PRESENCE_RECOVERY_MS
                    log('D', "svc", "status 133: hardware presence re-armed; preserving arrival epoch")
                }
            }
            if (appRuns && now() >= nextSleepPollMs) { nextSleepPollMs = now() + KEY_SLEEP_POLL_MS; stationaryKeySecurity() }
            if (appRuns && now() >= nextKeepAliveMs) keepConnected()
            presenceDelivery()
        }

        /** onMotionEscalate: a motion edge (a wake-up event) prepares an early or a fast presence scan. */
        fun onMovingEdge() {
            if (!appAlive || !btOn) return
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) return // W2: the watch holds the link
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
            // 0.4.3 (T3): as ProximityService - the fail-safe is checked every round, also while the key sleeps.
            if (com.openzeekr.app.wear.WearLinkArbiter.expired()) {
                log('D', "svc", "keep-alive: watch link-borrow expired — reclaiming")
                log('D', "prox", "watch: link resumed (fail-safe)")
                com.openzeekr.app.wear.WearLinkArbiter.resume()
            }
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
            // W2: the watch is borrowing the car link (WearLinkArbiter): release ours and do not reconnect until it
            // resumes us or the fail-safe deadline passes - as ProximityService does (WATCH_YIELD_POLL_MS).
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) {
                if (link.state.value != DkBleManager.State.IDLE) { log('D', "svc", "keep-alive: releasing link for watch"); link.disconnect() }
                nextKeepAliveMs = now() + 1_000L
                return
            }
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
                    } else if (!arm(lowLatency = fastWanted(), approach = moving)) {
                        val wait = if (physics.scan0320) 0L else link.scanStarts.waitMs(clock.wallMs())
                        if (wait > 0) {
                            nextKeepAliveMs = now() + minOf(wait + SCAN_START_SLACK_MS, RECONNECT_INTERVAL_MS)
                            log('W', "svc", "keep-alive: no scan start left; presence retried in ${nextKeepAliveMs - now()} ms")
                        } else {
                            log('W', "svc", "keep-alive: hardware presence registration failed; trying foreground fallback")
                            link.connect(null)
                        }
                    }
                } else {
                    if (aggressive) log('D', "svc", "keep-alive: bounded approach recovery — aggressive scan-connect")
                    link.connect(null)
                }
            } else {
                lastEngagedMs = now()
                // 0.3.21: not during a direct connect (a 133 then needs no new scan start).
                if (presenceArmed && (physics.scan0320 || link.state.value != DkBleManager.State.CONNECTING)) disarm()
            }
        }

        private fun stationaryKeySecurity() {
            val atCar = if (System.getenv("SIM_SLEEP_AT_CAR") == "1") false else
                com.openzeekr.app.ble.KeySleepPolicy.atCar(link.state.value == DkBleManager.State.SESSION_READY,
                    controller.state.value.smoothedRssi)
            if (atCar) lastAtCarMs = now()
            val sinceAtCar = if (lastAtCarMs >= 0) now() - lastAtCarMs else null
            val stepsNow = motion.observedSteps
            if (stepsNow != null && stepsNow != lastStepsSeen) { if (lastStepsSeen != null) lastStepChangeMs = now(); lastStepsSeen = stepsNow }
            val sinceStep = if (lastStepChangeMs >= 0) now() - lastStepChangeMs else null
            if (!keySleeping && com.openzeekr.app.ble.KeySleepPolicy.maySleep(motion::isStillFor, atCar, sinceAtCar, sinceStep)) {
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
                        if (!link.connectFromPresence(prepared)) recoveryProbe("confirmed motion", confirmedMotionWake = true)
                    } else recoveryProbe("confirmed motion", confirmedMotionWake = true)
                } else if (!com.openzeekr.app.ble.EarlyPresencePolicy.mayRetain(keySleeping, moving, earlyUntilMs, now())) {
                    disarm()
                }
            }
        }

        private fun recoveryProbe(reason: String, confirmedMotionWake: Boolean) {
            if (lastWakeProbeMs >= 0 && now() - lastWakeProbeMs < WAKE_PROBE_DEBOUNCE_MS) return
            if (keySleeping || !btOn || link.state.value != DkBleManager.State.IDLE) return
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) return // W2: deferred to the watch
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

        /** ProximityService.onStatus133Recovery: re-arm the hardware presence scan in approach mode, else scan. */
        fun onStatus133Recovery() {
            if (!appAlive || !btOn) return
            val armed = offload && arm(lowLatency = !physics.scan0320 && fastWanted(), approach = true)
            if (armed) {
                recoveryUntilMs = now() + MOTION_PRESENCE_RECOVERY_MS
                log('D', "svc", "status 133: hardware presence re-armed; preserving arrival epoch")
            } else if (!physics.scan0320 && offload && link.scanStarts.waitMs(clock.wallMs()) > 0) {
                val wait = link.scanStarts.waitMs(clock.wallMs())
                log('W', "svc", "status 133: no scan start left; presence re-armed in $wait ms")
                rearm133AtMs = now() + wait + SCAN_START_SLACK_MS
            } else {
                log('W', "svc", "status 133: offload unavailable; bounded foreground fallback")
                link.connect(null)
            }
        }

        /** The offloaded scan runs in the BT controller (no CPU); a match is a PendingIntent wake-up. */
        private fun presenceDelivery() {
            if (!presenceArmed || !presenceDelivers || !btOn || link.state.value != DkBleManager.State.IDLE) return
            if (trueRssi < physics.presenceFloorRssi) { presenceSeenSinceMs = -1L; return }
            if (presenceSeenSinceMs < 0) presenceSeenSinceMs = now()
            val latency = when (presenceMode) { 2 -> 400L; 1 -> 1_500L; else -> 5_000L }
            if (now() - presenceSeenSinceMs < latency) return
            presenceSeenSinceMs = -1L
            val r = readRssi()
            wake(10_000L)   // the PendingIntent broadcast holds a wakelock
            if (keySleeping) {
                if (com.openzeekr.app.ble.EarlyPresencePolicy.mayRetain(keySleeping, moving, earlyUntilMs, now()) &&
                    !link.deferWeakPresence(r)) {
                    savedEarlyRssi = r; disarm()
                    log('D', "svc", "early presence saved pending confirmed motion (rssi=$r)")
                } else {
                    log('D', "svc", "presence ignored: stationary key is sleeping")
                    if (!com.openzeekr.app.ble.EarlyPresencePolicy.mayRetain(keySleeping, moving, earlyUntilMs, now())) disarm()
                }
                return
            }
            log('D', "svc", "presence MATCH rssi=$r")
            val action = if (WEAK_GATE) com.openzeekr.app.ble.WeakConnectGate.onPresence(r, moving)
                else com.openzeekr.app.ble.WeakConnectGate.PresenceAction.CONNECT
            when (action) {
                com.openzeekr.app.ble.WeakConnectGate.PresenceAction.CONNECT -> {
                    if (link.deferWeakPresence(r)) {
                        log('D', "svc", "status 133: deferring weak presence at rssi=$r; keeping offloaded scan armed")
                        return
                    }
                    controller.onPresenceMatch(r)
                    // 0.3.21: the scan listens on through the direct connect (0.3.20 stopped it first).
                    if (physics.scan0320) disarm()
                    if (!link.connectFromPresence(r)) link.connect(null)
                }
                com.openzeekr.app.ble.WeakConnectGate.PresenceAction.FOREGROUND_SCAN -> {
                    controller.onPresenceMatch(r)
                    if (physics.scan0320 || com.openzeekr.app.ble.PresenceScanArmPolicy.mayScanAlongside(link.scanStarts.free(clock.wallMs()))) {
                        log('D', "svc", "presence at the edge of range while moving - foreground scan")
                        link.connect(null)
                    } else log('D', "svc", "presence at the edge of range while moving - no scan start in reserve; " +
                        "the presence scan connects once the car is in range")
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
        override fun newAlarmEpisode() { alarmRaisedAt = 0L }
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
            if (KEYED) {
                val rg = keyed(3)
                val acc = physics.gnssAccMedianM?.let { (it * kotlin.math.exp(physics.gnssAccSpread * rg.gaussian())).toFloat() } ?: base
                val noise = acc / 2.0 * rg.gaussian() + worldGnssBias + if (worldGnssJumpLeft > 0) worldGnssJump else 0.0
                log('D', "sim", "gnss d=%.1f read=%.1f acc=%.1f%s".format(distanceM, distanceM + noise, acc,
                    if (worldGnssJumpLeft > 0) " jump=%.0f".format(worldGnssJump) else ""))
                return DepartureFix(52.0 + (distanceM + noise) / 111_000.0, 5.0, acc, clock.elapsedMs())
            }
            val acc = physics.gnssAccMedianM?.let { (it * kotlin.math.exp(physics.gnssAccSpread * rGnss.gaussian())).toFloat() } ?: base
            bias = 0.9 * bias + 1.5 * rGnss.gaussian()
            if (jumpLeft > 0) jumpLeft-- else if (rGnss.chance(0.01)) { jumpLeft = 3 + (rGnss.nextDouble() * 5).toInt(); jump = rGnss.uniform(10.0, 25.0) }
            val noise = acc / 2.0 * rGnss.gaussian() + bias + if (jumpLeft > 0) jump else 0.0
            val metres = distanceM + noise
            log('D', "sim", "gnss d=%.1f read=%.1f acc=%.1f%s".format(distanceM, metres, acc,
                if (jumpLeft > 0) " jump=%.0f".format(jump) else ""))
            // 1 degree latitude ~ 111 km; the car sits at (52.0, 5.0), the walker moves north.
            return DepartureFix(52.0 + metres / 111_000.0, 5.0, acc, clock.elapsedMs())
        }
        /** 0.4.0c: the locked-side watch - a fix a second while [enabled], at most [maxMs]; counts as a GNSS request. */
        override suspend fun watchFixes(maxMs: Long, enabled: () -> Boolean, onFix: (DepartureFix) -> Unit) {
            val until = now() + maxMs
            requestStartMs = now(); gnssActive++
            try {
                while (now() < until && enabled()) {
                    delay(1_000L)
                    if (!enabled()) return
                    fix()?.let(onFix)
                }
            } finally { gnssActive-- }
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
                                               bleCorroborated: () -> Boolean, memory: DepartureFixMemory?,
                                               lastMotionAtMs: () -> Long): Boolean {
            if (!enabled()) return false
            val window = DepartureObservationWindow(anchor, clock.elapsedMs(), memory)
            val until = now() + DepartureObservationWindow.WINDOW_MS
            requestStartMs = now(); gnssActive++
            try {
                while (now() < until) {
                    delay(1_000L)
                    if (!enabled()) return false
                    if (window.observe(fix(), steps(), clock.elapsedMs(), bleCorroborated(), lastMotionAtMs()) && enabled()) return true
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
    // The cloud Lock works whether or not the key link does (field: the stock app's cloud
    // Lock locked a car that had ignored two key Locks); it takes ~2 s and reports its status.
    var btOn = true; private set
    var appAlive = true; private set
    /** App-private storage: survives a process restart (SharedPreferences in the app). */
    private var savedState: com.openzeekr.app.ble.ProximityStateStore.Saved? = null
    /** 0.4.0c: the car's last known positions, on disk like the calibration profile (survive the restart). */
    private var savedCarFixes: List<DoubleArray> = emptyList()
    val stateStore = object : com.openzeekr.app.ble.ProximityStateStore {
        override fun load() = savedState
        override fun save(saved: com.openzeekr.app.ble.ProximityStateStore.Saved) { savedState = saved }
        override fun loadCarFixes(): List<DoubleArray> = savedCarFixes
        override fun saveCarFixes(latLonAcc: List<DoubleArray>) { savedCarFixes = latLonAcc }
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
        walkBackReturn = WALK_BACK,
    )
    var controller = newController(); private set

    /** The process dies: every in-memory state of the app (controller + service) is lost. */
    /** W2: the watch companion takes the car over its own key, played as the phone's PhoneKeySyncService and
     *  WearLinkArbiter see it: PAUSE (the phone releases the link and stands down) -> the watch connects and acts
     *  (~1.5 s) -> it reports the outcome (R1) -> RESUME, unless the watch app died ([resume] false). */
    private fun watchActs(lock: Boolean, resume: Boolean, whenLooping: Boolean = false, walkToM: Double = 30.0, actMs: Long = 1_500L) {
        if (whenLooping) {
            // 0.4.3 (T1): walk on until the phone's own loop runs (or the target is reached): the tap comes mid-loop.
            walking = true; walkSpeed = 1.3; walkTarget = walkToM; extraShadowDb = 0.0
            facing = if (walkToM < distanceM) Facing.TOWARD else Facing.AWAY
            var waited = 0L
            while (!(controller.lockLoopActive || controller.unlockLoopActive) && waited < 60_000L &&
                kotlin.math.abs(walkToM - distanceM) > 0.05) { run(TICK_MS); waited += TICK_MS }
            walking = false
            record("WATCH_TAP", when { controller.lockLoopActive -> "lock loop running"; controller.unlockLoopActive -> "unlock loop running"; else -> "no loop" })
        }
        record("WATCH_PAUSE")
        com.openzeekr.app.wear.WearLinkArbiter.suspendForWatch()
        link.disconnect()
        run(actMs)
        if (car.locked != lock) car.actuate(lock, "watch")
        record(if (lock) "WATCH_LOCK" else "WATCH_UNLOCK")
        if (appAlive) { if (lock) controller.onWatchLockConfirmed() else controller.onExternalUnlockConfirmed("watch Unlock") }
        run(600)
        if (resume) { com.openzeekr.app.wear.WearLinkArbiter.resume(); record("WATCH_RESUME") }
    }

    /** 0.4.3 (H5/T5): a hand-over without an action - the watch gives the link back after [afterMs]. */
    private fun watchAborts(afterMs: Long) {
        record("WATCH_PAUSE")
        com.openzeekr.app.wear.WearLinkArbiter.suspendForWatch()
        link.disconnect()
        run(afterMs)
        com.openzeekr.app.wear.WearLinkArbiter.resume(); record("WATCH_RESUME", "abort")
    }

    private fun killApp() {
        record("APP_KILLED")
        controller.stop()
        appScope.cancel()
        link.disconnect()
        safetyHolds.clear()   // a dead process holds no wakelock
        appAlive = false
        service.reset()
        motion.ownReset()
        link.newProcess()
    }
    private fun startApp() {
        com.openzeekr.app.wear.WearLinkArbiter.reset { clock.wallMs() }   // W2: a new process remembers no hand-over
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
        link.state.value == DkBleManager.State.CONNECTED || safetyHeld() ||
        // 0.4.0 study: the app holds the CPU while its own step counter runs (the design's battery cost).
        (OWN_HOLD && motion.ownCounting))
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
            motion.ownTick(dt, shuffling)
            val t0 = scheduler.currentTime
            scheduler.advanceTimeBy(dt); scheduler.runCurrent()
            appAdvancing = true
            if (runs) { tickWorld0 = t0; tickApp0 = appScheduler.currentTime; appScheduler.advanceTimeBy(dt) }
            tickWorld0 = scheduler.currentTime; tickApp0 = appScheduler.currentTime
            appScheduler.runCurrent()
            appAdvancing = false
            onTick?.invoke(now())
            if (KEYED && now() % 1_000L < dt) stepWorldGnss()
            if (now() % 1_000L < dt) samples += Sample(now(), distanceM, car.locked, walking,
                appBelievesUnlocked = appAlive && controller.believesCarUnlocked, cpuAwake = awake, gnssOn = gnssActive > 0,
                linkUp = link.state.value == DkBleManager.State.SESSION_READY, appAlive = appAlive,
                trueRssi = trueRssi, motionState = motion.state.value.name, steps = motion.observedSteps,
                listening = appAlive && (link.state.value != DkBleManager.State.IDLE || service.presenceListening),
                keyAsleep = service.keySleeping, own = motion.ownSteps, ownOn = motion.ownCounting)
            left -= dt
        }
    }
    /** Test hook: called after every tick (diagnostics). */
    var onTick: ((Long) -> Unit)? = null
    private var walkTarget = 0.0

    fun play(legs: List<Leg>, learning: List<Leg> = emptyList()) {
        Logx.testSink = { level, area, msg -> if (area in LOG_AREAS) log(level, area, msg) }
        try {
            // W2: the arbiter is process-global; every run starts without a hand-over and on this world's clock.
            com.openzeekr.app.wear.WearLinkArbiter.reset { clock.wallMs() }
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
                    walking = false; shuffling = true; var left = leg.ms
                    while (left > 0) {
                        facing = Facing.values()[(rnd.nextDouble() * 3).toInt().coerceAtMost(2)]
                        extraShadowDb = if (rnd.chance(0.3)) leg.shadowDb else 0.0
                        val d = minOf(left, rnd.uniform(2_000.0, 6_000.0).toLong()); run(d); left -= d
                    }
                    extraShadowDb = 0.0; shuffling = false
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
                    motion.driveStarted()
                    run(leg.ms)
                    motion.driveEnded()
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
                is Leg.WatchLock -> watchActs(lock = true, resume = leg.resume, whenLooping = leg.whenLooping, walkToM = leg.walkToM, actMs = leg.actMs)
                is Leg.WatchUnlock -> watchActs(lock = false, resume = leg.resume, whenLooping = leg.whenLooping, walkToM = leg.walkToM, actMs = leg.actMs)
                is Leg.WatchAbort -> watchAborts(leg.afterMs)
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
        /** 0.3.12: the hand wobble decorrelates over this walked distance. */
        const val HAND_WOBBLE_CORR_M = 1.5
        /** Time-keyed noise for paired comparisons (default on; SIM_KEYED=0 = the sequential streams). */
        val KEYED = System.getenv("SIM_KEYED") != "0"
        const val CONNECT_MIN_RSSI = -96.0
        /** DkBleManager: a foreground scan gives up after 20 s; the 133 recovery waits these before its next step. */
        const val SCAN_TIMEOUT_MS = 20_000L
        const val STATUS_133_DIRECT_RETRY_DELAY_MS = 350L
        const val STATUS_133_RESCAN_DELAY_MS = 400L
        /** ProximityService (0.3.21): a retry after a spent ration comes this long after a start is free again. */
        const val SCAN_START_SLACK_MS = 100L
        /** Android: an app's sixth BLE scan start within 30 s is accepted but delivers nothing. */
        const val ANDROID_SCAN_STARTS = 5
        private val MODE_NAMES = listOf("LOW_POWER idle", "BALANCED approach", "LOW_LATENCY motion wake")
        val WEAK_GATE = System.getenv("SIM_WEAK_GATE") != "0"
        val STEP_COUNTER = System.getenv("SIM_STEP_COUNTER") != "0"
        /** 0.4.0 study: the app's own step counter exists (SIM_OWN=1), with its field-calibrated parameters (23/10). */
        val OWN = OwnRules.enabled
        /** Own steps feed the motion state (step assist -> MOVING), as the hub detector's steps do. */
        val OWN_MOTION = (System.getenv("SIM_OWN_MOTION") ?: (if (OwnRules.live) "1" else "0")) == "1"
        /** The app holds the CPU while the counter runs. */
        val OWN_HOLD = System.getenv("SIM_OWN_HOLD") != "0"
        val OWN_LAG_MS = (System.getenv("SIM_OWN_LAG_MS") ?: "1000").toLong()
        val OWN_OVERCOUNT = (System.getenv("SIM_OWN_OVERCOUNT") ?: "1.03").toDouble()
        /** False own steps per second while turning/shuffling on the spot, and while standing still (field: 0). */
        val OWN_FALSE_SHUFFLE = (System.getenv("SIM_OWN_FALSE_SHUFFLE") ?: "0.1").toDouble()
        val OWN_FALSE_STAND = (System.getenv("SIM_OWN_FALSE_STAND") ?: "0").toDouble()
        /** The locked-side window closes this long after the last presence hit / reading in the band. */
        val OWN_APPROACH_WINDOW_MS = (System.getenv("SIM_OWN_WINDOW_MS") ?: "90000").toLong()
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
        /** SIM_WALK_BACK=0: no walk-back return at the approach level after a Lock (0.3.1 behaviour). */
        val WALK_BACK = System.getenv("SIM_WALK_BACK") != "0"
        /** SIM_APPROACH=unbroken|two_in_a_row|two_of_three: walking-approach confirmation (0.3.2). */
        val APPROACH_CONFIRM: ProximityDecisionPolicy.ApproachConfirm = System.getenv("SIM_APPROACH")
            ?.let { ProximityDecisionPolicy.ApproachConfirm.valueOf(it.uppercase()) }
            ?: ProximityDecisionPolicy.ApproachConfirm.UNBROKEN
    }
}
