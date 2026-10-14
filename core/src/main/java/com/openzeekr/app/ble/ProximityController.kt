package com.openzeekr.app.ble

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.pow

/**
 * Approach unlock and independently corroborated walk-away lock.
 *
 * Rides the live keep-alive session (see [ProximityService]); it reads connected-GATT RSSI and
 * never owns the connection. RSSI is NOT a reliable physical distance: the 0.1.42 field test
 * produced the same confirmed departure pattern beside the car as during a real walk-away.
 *
 * Decision model (the tuning the user asked for):
 *  - **Latch**: after auto-unlock [armedUnlocked] prevents repeated unlocks until manual lock.
 *  - **Independent departure**: weak BLE and motion only start an on-demand position check;
 *    neither can authorize an automatic Lock without two fresh, accurate position fixes.
 *  - **Hysteresis**: unlock at/above [ConfigStore.sensitivityUnlockRssi] (≈ near), lock at/below
 *    [ConfigStore.sensitivityLockRssi] (≈ farther). The gap between them stops flapping.
 *  - **Cooldown**: after an action, ignore new triggers for [ACTION_COOLDOWN_MS].
 *  - **Adaptive cadence**: 200 ms burst polling while near a threshold or moving (cheap over an
 *    already-open link), 2 s when solidly far/near and steady (low power).
 *
 * Link loss alone cannot establish departure: body shadow can disconnect BLE while the phone
 * remains beside the vehicle. Keep the unlock armed and allow the BLE manager to reconnect.
 *
 * The RSSI→metre estimate is for display/logging only (log-distance path loss with a nominal
 * [TX_POWER_1M]/[PATH_LOSS_N] — calibrate at the car). Decisions use RSSI thresholds directly,
 * which are what the single sensitivity knob tunes.
 */
class ProximityController internal constructor(
    private val config: () -> com.openzeekr.app.config.SecretsConfig,
    ble: ProximityLink,
    private val motion: ProximityMotion,
    private val scope: CoroutineScope,
    /** Cloud lock fallback (POST Command.LOCK). Returns true if the cloud accepted it. Injected by Deps
     *  so :core stays decoupled from the remote-control catalog; defaults to a no-op for tests. */
    private val cloudLock: suspend () -> Boolean = { false },
    /** Cloud lock-state probe (GET vehicle status → centralLockingStatus). true=locked, false=unlocked,
     *  null=unknown/failed. Used to verify an explicitly confirmed departure's cloud fallback. */
    private val cloudIsLocked: suspend () -> Boolean? = { null },
    /** Vehicle-reported lock state with its own update time, for a new arrival after a lost link. */
    private val cloudLockSnapshot: suspend () -> CloudLockSnapshot? = { null },
    private val clock: ProximityClock = SystemProximityClock,
    alerts: ProximityAlerts,
    private val departureLocationSource: DepartureLocator,
    private val wakeLocks: SafetyWakeLocks,
    private val stateStore: ProximityStateStore = ProximityStateStore.None,
    calibrationStore: PhoneCalibrationStore = PhoneCalibrationStore.None,
    /** Apply the learned correction (the simulator's comparison run learns without applying). */
    private val selfCalibration: Boolean = true,
    /** A proven arrival (steps + rise) anchors the departure proof like a strong-near sample. */
    private val arrivalAnchor: Boolean = true,
    /** Plan H2: steps since a far link loss let a door-level signal count as the return. */
    private val awayReturnSteps: Boolean = true,
    /** How a walking approach confirms the approach band (0.3.2; the simulator compares the modes). */
    approachConfirm: ProximityDecisionPolicy.ApproachConfirm = ProximityDecisionPolicy.ApproachConfirm.UNBROKEN,
    /** The walk-back return after a Lock at the ordinary approach level (0.3.2; the simulator compares). */
    walkBackReturn: Boolean = true,
) {
    constructor(
        appContext: android.content.Context,
        store: ConfigStore,
        @Suppress("UNUSED_PARAMETER") lock: DkLockController,
        ble: DkBleManager,
        motion: MotionMonitor,
        scope: CoroutineScope,
        cloudLock: suspend () -> Boolean = { false },
        cloudIsLocked: suspend () -> Boolean? = { null },
        cloudLockSnapshot: suspend () -> CloudLockSnapshot? = { null },
    ) : this(store::current, ble, motion, scope, cloudLock, cloudIsLocked, cloudLockSnapshot,
        SystemProximityClock, AndroidProximityAlerts(appContext), DepartureLocationSource(appContext),
        AndroidSafetyWakeLocks(appContext), SharedPrefsProximityStateStore(appContext),
        SharedPrefsPhoneCalibrationStore(appContext))

    enum class Zone { UNKNOWN, FAR, NEAR }
    enum class Phase { PASSIVE, CONNECTING, MONITORING }
    enum class Source { NONE, GATT }

    data class State(
        val running: Boolean = false,
        val phase: Phase = Phase.PASSIVE,
        val rawRssi: Int? = null,
        val smoothedRssi: Int? = null,
        val distanceM: Double? = null,
        val source: Source = Source.NONE,
        val zone: Zone = Zone.UNKNOWN,
        val lastAction: String = "",
        val diagnostics: String = "",
        val error: String? = null,
    )

    /**
     * Every audible alarm about this unlock starts a bounded cloud watch (0.3.2): a car that was
     * locked while the key link could not see it - its own relock two minutes after a pass-by
     * unlock, a fob, the handle - ends the alarm (simulator F33/F11/F40: the alarm sounded its full
     * two minutes, or sounded at all, for a car that was already locked).
     */
    private val alerts: ProximityAlerts = alerts.let { port ->
        object : ProximityAlerts {
            override fun show(event: LockAlertPolicy.Event, bleDepartureRouteActive: Boolean): Boolean =
                port.show(event, bleDepartureRouteActive).also { if (LockAlertPolicy.audible(event)) startAlarmCloudWatch() }
            override fun clear() = port.clear()
            override fun silenceAlarm() = port.silenceAlarm()
            override fun carLocked() = port.carLocked()
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Self-calibration per phone (0.3.1): learns from this phone's RAW readings at known moments;
     * everything below sees readings on the reference phone's scale ([PhoneCalibration.normalize]),
     * so every threshold keeps the meaning it was tuned with.
     */
    internal val calibration = PhoneCalibration(calibrationStore).also { c ->
        c.onDecision = { Logx.d("prox", "self-calibration: $it (${motion.stepSummary})"); publishCalibration() }
        c.onArrival = { onProvenArrival(it) }
    }

    /** What the settings card shows (levels in dBm of THIS phone; no positions). */
    data class CalibrationView(
        val arrivals: Int, val arrivalsNeeded: Int,
        /** + = hears the car better than the reference phone; null while learning. */
        val learnedOffsetDb: Double?,
        /** dB subtracted from every reading (only ever >= 0). */
        val appliedOffsetDb: Int,
        val approachDb: Double?, val atCarDb: Double?, val farDb: Double?,
        val departures: Int, val departuresOutOfRange: Int,
    )

    private val _calibrationView = MutableStateFlow(calibrationViewNow())
    val calibrationView: StateFlow<CalibrationView> = _calibrationView

    private fun calibrationViewNow(): CalibrationView {
        val c = calibration
        val p = c.profile
        fun median(l: List<Double>) = l.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        return CalibrationView(p.approach.size, PhoneCalibration.MIN_SAMPLES, c.learnedOffsetDb, c.offsetDb,
            median(p.approach), median(p.atCar), median(p.far.filterNotNull()), p.far.size, p.far.count { it == null })
    }

    private fun publishCalibration() { _calibrationView.value = calibrationViewNow() }
    /** Diagnose (0.3.2): when step counts reach the app while the car is heard. */
    private val stepTrace = StepDeliveryTrace()
    /** "The walk has stopped" from the step counter, for the walking approach route (0.3.2). */
    private val stepStall = StepStallGate()
    private var stepStallLogged = false
    private val ble: ProximityLink = calibratedLink(ble)

    private fun calibratedLink(link: ProximityLink): ProximityLink = object : ProximityLink by link {
        override suspend fun pollRemoteRssi(): Int? = link.pollRemoteRssi()?.let { calibrated(it) }
    }

    private fun calibrated(raw: Int): Int {
        val before = calibration.offsetDb
        val steps = motion.observedSteps
        calibration.observe(clock.elapsedMs(), raw, steps)
        stepTrace.observe(clock.elapsedMs(), steps)?.let { Logx.d("motion", "$it; ${motion.stepSummary}") }
        if (calibration.offsetDb != before) Logx.d("prox", "self-calibration: ${calibration.summary()}")
        return normalized(raw)
    }

    private fun normalized(raw: Int) = if (selfCalibration) calibration.normalize(raw) else raw

    /**
     * The learner recognised a walk that ended at the car (it reports ~6-15 s after the stop). In the
     * current unlock epoch that is the at-the-car anchor the departure proof needs when no strong
     * sample came (back pocket), and the place the "steps since the car" count from.
     */
    private fun onProvenArrival(arrival: PhoneCalibration.Arrival) {
        if (!arrivalAnchor || !armedUnlocked) return
        val route = bleRoute ?: return
        val epochStartElapsed = clock.elapsedMs() - (clock.wallMs() - unlockObservedAtMs)
        if (arrival.atMs < epochStartElapsed - ARRIVAL_BEFORE_UNLOCK_MS) return
        route.arrivalObserved(normalized(arrival.carLevelDb.roundToInt()))
        val strongAfterArrival = lastStrongNearAtMs != 0L &&
            clock.wallMs() - lastStrongNearAtMs < clock.elapsedMs() - arrival.atMs
        if (!strongAfterArrival) { stepsAtLastStrongNear = arrival.stepsAtCar; counterAtLastStrongNear = null }
        Logx.d("prox", "proven arrival at the car (steps + rise) -> departure anchor")
    }

    /** One line for the settings card. */
    fun calibrationSummary(): String = calibration.summary()

    /** "Learn again" in Settings: forget the learned profile (the correction returns to 0). */
    fun resetCalibration() {
        calibration.reset()
        publishCalibration()
        Logx.d("prox", "self-calibration reset")
    }

    /** Service-level wake/recovery information for field diagnosis without logcat. */
    fun updateDiagnostics(value: String) {
        _state.value = _state.value.copy(diagnostics = value)
    }

    // Smoothing + trend.
    private var gattEma: Double? = null
    @Volatile private var nextIntervalMs = MONITOR_MID_MS

    // In-car detection: how long the "at the car" condition has held.
    private var inCarSinceMs = 0L
    // RSSI-steadiness (fallback "still" detector when there are no motion sensors).
    private var steadyRef: Double? = null
    private var steadySinceMs = 0L
    // Cadence-transition logging + connected-RSSI liveness bookkeeping.
    private var lastCadence = ""
    private var rssiNullStreak = 0
    // App-layer ping bookkeeping (approach state only).
    @Volatile private var pingInFlight = false
    private var pingJob: Job? = null
    private var pingFailStreak = 0
    private var lastPingMs = 0L

    // Hysteresis latch: true once the car has CONFIRMED our auto-unlock (next auto action is a lock).
    // Persisted on every change (plan K2): a new process restores it, see [start].
    private var armedUnlockedState = false
    private var armedUnlocked: Boolean
        get() = armedUnlockedState
        set(value) {
            if (armedUnlockedState == value) return
            armedUnlockedState = value
            runCatching { stateStore.save(ProximityStateStore.Saved(value, clock.wallMs(), lastUnlockSource)) }
        }
    /** Read-only view for the simulator's oracles: does the app believe the car is open (armed)? */
    internal val believesCarUnlocked: Boolean get() = armedUnlocked
    /** Wall time of the car's latest "locked" status push (any session); a Lock in flight counts it. */
    private var lastCarLockedReportAtMs = 0L
    // A new unlock can be followed by a quiet walk-away before the car emits another status frame.
    private var postUnlockFastUntilElapsedMs = 0L
    // Time-based evidence gate. This owns arrival/departure confirmation so neither the fast RSSI
    // path nor the periodic idle check can actuate from one noisy threshold crossing.
    private val decisionPolicy = ProximityDecisionPolicy(approachConfirm, walkBackReturn)
    // Step totals of the last [STEPS_RISING_WINDOW_MS] (wall ms, steps): "counted steps rising now".
    private val recentSteps = ArrayDeque<Pair<Long, Long>>()
    private val relockApproachEvidence = RelockApproachEvidence()
    // Confirmed-unlock retry loop: true while actively trying to unlock; the job is the loop itself.
    @Volatile private var needToUnlock = false
    private var unlockJob: Job? = null
    // Confirmed-lock loop (walk-away). Locking matters more than unlocking — never leave the car open —
    // so this is at least as persistent as unlock and falls back to a cloud lock if BLE won't confirm.
    private var lockJob: Job? = null
    private var lockConfirmationJob: Job? = null
    private val autoLockGate = AutomaticLockGate(AutomaticLockGate.Mode.ACTUATE)
    private var departureAnchor: DepartureFix? = null
    private var departureAnchorJob: Job? = null
    private var nearDepartureAnchor: NearDepartureAnchor? = null
    private var linkDepartureJob: Job? = null
    private var departureProofAtMs = 0L
    private var armedStillSinceMs = 0L
    private var gnssWindowsThisEpoch = 0
    private var gnssBudgetLogged = false
    /** Simulator report: the most GNSS windows any one visit used. */
    internal var maxGnssWindowsInAnEpoch = 0
        private set
    // BLE + walking departure route: one route (evidence + proof ledger) per unlock epoch, only
    // for the replayed sensitivity preset. GNSS remains an additional positive route for BLE Lock
    // and the only route for cloud Lock.
    private var bleRoute: BleDepartureRoute? = null
    private var bleDepartureLoggedReason = ""
    private var bleDepartureReasonLoggedAtMs = 0L
    private var unlockObservedAtMs = 0L
    private var stepsAtUnlock: Long? = null
    private var counterAtUnlock: Long? = null
    private var strongestRssiSinceUnlock = Int.MIN_VALUE
    private var lastStrongNearAtMs = 0L
        set(value) { field = value; stepsAtLastStrongNear = motion.observedSteps; counterAtLastStrongNear = motion.counterSteps }
    // Step count at the latest strong at-the-car reading (null without a step source).
    private var stepsAtLastStrongNear: Long? = null
    // The hub counter's own total at that reading: a baseline per source (0.3.2, simulation audit C).
    private var counterAtLastStrongNear: Long? = null
    // Walking and stopping from the step total, for the BLE departure proof (0.3.2).
    private val departureSteps = DepartureStepWatch()
    // A walk-away verification ended because the signal came back above the lock threshold.
    private var walkAwayRecovered = false
    private var lastShadowDepartureAtMs = 0L
    private var shadowDepartureWarned = false
    // Latest moving walk-away candidate in this unlock (wall clock); with no strong reading after
    // it, the user has walked away (LockAlertPolicy.departureSuspected).
    private var lastWalkAwayCandidateAtMs = 0L
    private var departureAlarmJob: Job? = null
    private val carSelfLock = CarSelfLockDetector()
    private val stationaryFar = StationaryFarWatch()
    private var idleFarStreak = 0
    /** Consistent clear separation measured right before the link dropped (frozen, not running on). */
    private var deepFarAtLinkLossMs = 0L
    private var idleFastWindows = 0
    private var vehicleStatusJob: Job? = null
    private var lockStateCheckJob: Job? = null
    private var lockStateCheckedForLossAt = 0L
    /** The link ended with the phone at the car (key asleep beside it, not a walk home). */
    private var linkLostNearCar = false
    /** The car's previous status push (for the "Auto gesloten" chime on open -> locked). */
    private var lastVehicleStatus: ObservedVehicleStatus? = null
    private var manualCloudLockCheck: Job? = null
    private var verifyLogAtMs = 0L
    private var lastUnlockSource = ""
    private var selfLockReopenPending = false
    @Volatile private var measureUntilElapsedMs = 0L
    private val _measuring = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True while Measurement mode samples fast for a calibration walk. */
    val measuring: kotlinx.coroutines.flow.StateFlow<Boolean> = _measuring

    /** Measurement mode: sample every 200 ms and log each reading for [MeasurementMode.DURATION_MS]. */
    fun startMeasurement() {
        measureUntilElapsedMs = clock.elapsedMs() + MeasurementMode.DURATION_MS
        _measuring.value = true
        Logx.d("prox", "measurement mode on (${MeasurementMode.DURATION_MS / 60_000} min)")
        activityWake?.complete(Unit); motionWake?.complete(Unit)
    }

    private fun measuringNow(): Boolean {
        val on = MeasurementMode.active(measureUntilElapsedMs, clock.elapsedMs())
        if (!on && _measuring.value) { _measuring.value = false; Logx.d("prox", "measurement mode off") }
        return on
    }
    private var shadowRecoveryLogged = false
    private var relockRecoveryPending = false
    private var relockProbeJob: Job? = null
    private var relockProbeStartedAtElapsedMs = 0L
    private var relockProbeLastAtElapsedMs = 0L
    // Set while the unlocked "activity watch" is idling; the car's next frame completes it (instant wake).
    @Volatile private var activityWake: CompletableDeferred<Unit>? = null

    private var monitorJob: Job? = null

    // ---- action gate ----
    private var lastTriggerMs = 0L
    @Volatile private var actionInFlight = false

    // ---- link-loss handling ----
    private var linkLostAtMs = 0L
    private var lostRssi: Int? = null
    // Step count and time when [lostRssi] was read: steps since then = the phone moved on.
    private var lostRssiSteps: Long? = null
    private var lostRssiCounter: Long? = null
    private var lostRssiAtMs = 0L
    private var pendingUnverifiedLockAlert: Job? = null
    private var unverifiedLockAlertRaised = false

    // ---- wakelock + two-state (NEAR/FAR) machine ----
    /** True whenever the proximity loop needs the CPU: NEAR (< 6 m, engaged) or a FAR approach burst.
     *  [ProximityService] observes this to hold/release the keep-alive wakelock; false in FAR-still so
     *  the phone sleeps and the motion sensor is the only thing that wakes us. */
    private val _wakeLockNeeded = MutableStateFlow(false)
    val wakeLockNeeded: StateFlow<Boolean> = _wakeLockNeeded
    // Set while FAR + still: the loop drops the wakelock and blocks until the motion sensor wakes us.
    @Volatile private var farAsleep = false
    @Volatile private var motionWake: CompletableDeferred<Unit>? = null
    // NEAR easing: hold-still reference (NEAR_STILL_BAND_M) + since-when, to step 200 → 500 → 1000 ms.
    private var nearRefDist: Double? = null
    private var nearStillSinceMs = 0L
    // FAR approach wakelock backstop: give up (sleep) if we don't get within ~5× the walk-time estimate.
    private var farApproachDeadline = 0L
    private var farApproachRefDist = Double.MAX_VALUE

    // ---------------- lifecycle ----------------

    fun start() {
        if (_state.value.running) return
        gattEma = null; nextIntervalMs = MONITOR_MID_MS
        inCarSinceMs = 0L; steadyRef = null; steadySinceMs = 0L; lastCadence = ""
        rssiNullStreak = 0; pingJob?.cancel(); pingJob = null
        pingInFlight = false; pingFailStreak = 0; lastPingMs = 0L
        linkLostAtMs = 0L; lostRssi = null; lostRssiSteps = null; lostRssiCounter = null; lostRssiAtMs = 0L
        relockRecoveryPending = false; relockProbeJob?.cancel(); relockProbeJob = null
        departureAnchorJob?.cancel(); departureAnchorJob = null; departureAnchor = null
        linkDepartureJob?.cancel(); linkDepartureJob = null
        departureProofAtMs = 0L
        clearBleDeparture()
        pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
        unverifiedLockAlertRaised = false
        // A fresh monitor starts from a known state; a car left open by this app is restored below
        // from the persisted latch (plan K2) - resetting here must not erase that record.
        val restore = runCatching { stateStore.load() }.getOrNull()
            ?.takeIf { it.armedUnlocked && clock.wallMs() - it.atWallMs in 0..RESTORE_MAX_AGE_MS }
        armedUnlockedState = false
        resetLinkedWatch()
        postUnlockFastUntilElapsedMs = 0L
        decisionPolicy.resetLocked()
        needToUnlock = false; unlockJob?.cancel(); unlockJob = null
        farAsleep = false; nearRefDist = null; nearStillSinceMs = 0L
        farApproachDeadline = 0L; farApproachRefDist = Double.MAX_VALUE
        _wakeLockNeeded.value = true   // hold until the first sample decides (bring-up needs the CPU)
        _state.value = State(running = true, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
        ble.onInboundActivity = { activityWake?.complete(Unit) } // wake the unlocked idle-wait instantly
        motion.onMovingEdge = {
            stepStall.onMovingEdge(clock.wallMs())
            motionWake?.complete(Unit)   // wake the FAR-still approach sleep
            activityWake?.complete(Unit) // resume armed RSSI tracking on the first motion edge
            if (linkLostAtMs != 0L && armedUnlocked) scope.launch { scheduleLinkDepartureCheck() }
            // The key may have slept while the car was opened by other means (inside handle, card,
            // the stock app): a walk away without a link would otherwise go unnoticed.
            if (linkLostAtMs != 0L && linkLostNearCar && !armedUnlocked && lockStateCheckedForLossAt != linkLostAtMs &&
                clock.wallMs() - linkLostAtMs >= LOCK_STATE_GAP_MS) {
                lockStateCheckedForLossAt = linkLostAtMs
                scope.launch { scheduleLockStateCheck("motion after ${(clock.wallMs() - linkLostAtMs) / 1000} s without link") }
            }
        }
        motion.start()
        vehicleStatusJob?.cancel()
        vehicleStatusJob = scope.launch { ble.session.vehicleStatus.collect { onVehicleStatus(it) } }
        Logx.d("prox", "monitor start (distance+trend hysteresis; rides keep-alive session)")
        if (restore != null) {
            // The app (re)started while it believed the car open after its own unlock: watch the
            // departure again. The car's first status of the next key session is the truth (a "3"
            // ends it, see CarSelfLockDetector.LOCKED_WHILE_AWAY); without a link the link-loss
            // checks (cloud, alarm) apply as before.
            lastUnlockSource = restore.source
            Logx.w("prox", "app restarted while the car was open (unlock ${(clock.wallMs() - restore.atWallMs) / 1000} s ago, " +
                "source=${restore.source.ifBlank { "?" }}) -> departure watch restored")
            recordUnlockConfirmed("restored after app restart", restored = true)
        }
        monitorJob = scope.launch {
            while (isActive) {
                farAsleep = false   // onSample sets it true only for FAR + still; cleared each tick
                val pollCycleAtMs = clock.elapsedMs()
                when (ble.state.value) {
                    DkBleManager.State.SESSION_READY, DkBleManager.State.CONNECTED -> {
                        if (linkLostAtMs != 0L) {
                            val lostAt = linkLostAtMs
                            val wasNearCar = linkLostNearCar
                            linkDepartureJob?.cancel(); linkDepartureJob = null
                            relockRecoveryPending = armedUnlocked &&
                                clock.wallMs() - linkLostAtMs >= 30_000L &&
                                clock.wallMs() - unlockObservedAtMs >= 90_000L
                            relockProbeStartedAtElapsedMs = 0L
                            relockProbeLastAtElapsedMs = 0L
                            relockApproachEvidence.clear()
                            // A real absence (not a flap) gives the linked watch a fresh window, a
                            // bounded number of times per lock period (review 0.1.62: battery).
                            if (LinkedApproachWatch.refillAllowed(clock.wallMs() - linkLostAtMs, linkedWatchRefills)) {
                                linkedWatchSinceElapsedMs = 0L
                                linkedWatchRefills++
                            }
                            linkLostAtMs = 0L
                            pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
                            // Plan K7: a restored link is not a return (a link flapping at 15-20 m
                            // re-sounded the alarm on every loss). A strong reading at the car or the
                            // car's Lock ends the alarm (markStrongNear, Lock paths).
                            if (unverifiedLockAlertRaised) {
                                _state.value = _state.value.copy(lastAction =
                                    "BLE restored · vehicle lock still unverified")
                            }
                            deepFarAtLinkLossMs = 0L
                            // Back in radio range: possibly walking back. A BLE departure proof now
                            // has to recede from the first reading of this session (0.3.0, stress F32).
                            bleRoute?.linkRestored()
                            Logx.d("prox", "link restored; proximity monitoring resumed")
                            val gapMs = clock.wallMs() - lostAt
                            if (!armedUnlocked && wasNearCar && gapMs >= LOCK_STATE_GAP_MS)
                                scheduleLockStateCheck("link restored after ${gapMs / 1000} s")
                        }
                        // Unlocked at the car (NEAR): don't burn battery polling — idle until the car
                        // pushes a frame (activity = you moving/leaving) or the link drops. Hold the
                        // wakelock: this is the at-the-car case and the inbound-frame wake needs the CPU.
                        if (armedUnlocked) { _wakeLockNeeded.value = !armedLongStill(); armedWatch(); continue }
                        val rssi = ble.pollRemoteRssi()
                        if (rssi != null) { rssiNullStreak = 0; onSample(rssi) }
                        else {
                            // Connected-RSSI reads are the reliable liveness signal (they succeed
                            // ~every tick on a healthy link). A run of nulls on a READY session means
                            // the link is wedged -> reconnect. (This replaces the 0x0120 app-ping: the
                            // car doesn't answer a bare 0x0120, so that probe only ever false-failed.)
                            rssiNullStreak++
                            Logx.d("prox", "connected but RSSI read null (#$rssiNullStreak)")
                            if (ble.state.value == DkBleManager.State.SESSION_READY && rssiNullStreak >= RSSI_NULL_RECONNECT) {
                                rssiNullStreak = 0
                                Logx.w("prox", "RSSI reads failing on a ready session — link wedged, forcing reconnect")
                                forceReconnect()
                            }
                        }
                    }
                    else -> onSessionDown()
                }
                if (measuringNow()) { farAsleep = false; nextIntervalMs = MONITOR_FAST_MS; _wakeLockNeeded.value = true }
                if (farAsleep) sleepUntilMotion() else delay(
                    (nextIntervalMs - (clock.elapsedMs() - pollCycleAtMs))
                        .coerceAtLeast(0L))
            }
        }
    }

    /**
     * FAR + still: release the wakelock and block until the motion sensor reports movement ([onMovingEdge]
     * completes [motionWake]) or a loose safety timeout elapses. With the wakelock dropped the CPU can
     * suspend; the wake-up step detector still fires and wakes it. On wake we re-take the wakelock so the
     * follow-up RSSI read is reliable, and the next [onSample] re-decides the state.
     */
    private suspend fun sleepUntilMotion() {
        val wake = CompletableDeferred<Unit>()
        motionWake = wake
        // Linked + locked on an Activity-Recognition-only phone: re-check far sooner (LinkedApproachWatch).
        val cap = if (LinkedApproachWatch.applies(ble.state.value == DkBleManager.State.SESSION_READY,
                armedUnlocked, motionWakesOnSteps()) && linkedWatchForMs() < LinkedApproachWatch.WATCH_MAX_MS)
            LinkedApproachWatch.LINKED_SLEEP_MS else FAR_SLEEP_SAFETY_MS
        val woke = try { withTimeoutOrNull(cap) { wake.await() } != null } finally { motionWake = null }
        _wakeLockNeeded.value = true
        farAsleep = false
        if (woke) Logx.d("prox", "far-idle -> woke on motion (re-poll)")
    }

    fun stop() {
        monitorJob?.cancel(); monitorJob = null
        vehicleStatusJob?.cancel(); vehicleStatusJob = null
        lockStateCheckJob?.cancel(); lockStateCheckJob = null
        manualCloudLockCheck?.cancel(); manualCloudLockCheck = null
        measureUntilElapsedMs = 0L; _measuring.value = false
        departureAnchorJob?.cancel(); departureAnchorJob = null; departureAnchor = null
        linkDepartureJob?.cancel(); linkDepartureJob = null
        departureProofAtMs = 0L
        clearBleDeparture()
        relockRecoveryPending = false; relockProbeJob?.cancel(); relockProbeJob = null
        needToUnlock = false; unlockJob?.cancel(); unlockJob = null
        lockJob?.cancel(); lockJob = null
        lockConfirmationJob?.cancel(); lockConfirmationJob = null
        farNoMotionCheck?.cancel(); farNoMotionCheck = null
        ble.onInboundActivity = null; activityWake?.complete(Unit); activityWake = null
        motion.onMovingEdge = null; motionWake?.complete(Unit); motionWake = null
        motion.stop()
        gattEma = null; linkLostAtMs = 0L
        pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
        decisionPolicy.resetLocked()
        inCarSinceMs = 0L; steadyRef = null; steadySinceMs = 0L; lastCadence = ""
        rssiNullStreak = 0; pingJob?.cancel(); pingJob = null
        pingInFlight = false; pingFailStreak = 0; lastPingMs = 0L
        farAsleep = false; nearRefDist = null; _wakeLockNeeded.value = false
        _state.value = _state.value.copy(running = false, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
    }

    /** Preserve the motion + edge-RSSI evidence delivered by the offloaded presence receiver. */
    fun onPresenceMatch(rawRssi: Int) {
        val rssi = normalized(rawRssi)
        // A PendingIntent can recreate the service/process. Start before recording evidence so the
        // normal runApproach loop cannot subsequently reset the just-created arrival epoch.
        if (!_state.value.running) start()
        val cfg = config()
        decisionPolicy.onPresenceMatch(
            nowMs = clock.wallMs(),
            rssi = rssi,
            moving = motion.state.value == MotionMonitor.Motion.MOVING,
            unlockThreshold = cfg.sensitivityUnlockRssi,
        )
        Logx.d("prox", "arrival epoch: presence rssi=$rssi motion=${motion.state.value}")
    }

    /** Make every confirmed unlock enter the same walk-away state, regardless of its origin. */
    fun onExternalUnlockConfirmed(source: String) {
        scope.launch {
            // A newly confirmed unlock starts a new episode, even if monitoring was toggled off.
            manualCloudLockCheck?.cancel(); manualCloudLockCheck = null
            pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
            unverifiedLockAlertRaised = false
            alerts.clear()
            if (_state.value.running && config().proximityEnabled) {
                recordUnlockConfirmed(source)
            } else {
                // Keep drive authorization alive, but respect the user's disabled proximity toggle.
                clearBleDeparture()
                ble.noteUnlockConfirmed()
                // No reminder notification: the screen is normally off, so it would not be seen.
                _state.value = _state.value.copy(lastAction =
                    "Automatic Lock is off · Lock manually when leaving")
                Logx.w("prox", "manual Lock required after unlock (proximity off); no reminder notification")
                Logx.d("prox", "unlock confirmed ($source); proximity disabled/stopped -> walk-away not armed")
            }
        }
    }

    /** Only the BLE receipt verifies a manually requested lock. */
    fun onExternalLockConfirmed(source: String) = onManualLockAcknowledged(source, bleConfirmed = true)

    /** The cloud acknowledged the command, but physical actuation remains unknown. */
    fun onExternalCloudLockAccepted() = onManualLockAcknowledged("manual cloud control", bleConfirmed = false)

    private fun onManualLockAcknowledged(source: String, bleConfirmed: Boolean, away: Boolean = false) {
        scope.launch {
            departureAnchorJob?.cancel(); departureAnchorJob = null; departureAnchor = null
            linkDepartureJob?.cancel(); linkDepartureJob = null
            farNoMotionCheck?.cancel(); farNoMotionCheck = null
            departureProofAtMs = 0L
            clearBleDeparture()
            stationaryFar.reset()
            carSelfLock.onLockedByUs()
            relockRecoveryPending = false; relockProbeJob?.cancel(); relockProbeJob = null
            pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
            if (bleConfirmed) {
                manualCloudLockCheck?.cancel(); manualCloudLockCheck = null
                unverifiedLockAlertRaised = false
                alerts.clear()
            } else {
                // Cloud Ok acknowledges receipt of a command, not physical actuation.
                val posted = alerts.show(LockAlertPolicy.Event.MANUAL_CLOUD_LOCK_UNVERIFIED)
                unverifiedLockAlertRaised = true
                _state.value = _state.value.copy(lastAction =
                    "Cloud Lock accepted · checking whether the car locked")
                Logx.w("prox", "manual cloud Lock accepted; physical lock unverified (notification posted=$posted)")
                checkManualCloudLock(clock.wallMs())
            }
            if (_state.value.running && config().proximityEnabled) {
                needToUnlock = false
                unlockJob?.cancel()
                unlockJob = null
                armedUnlocked = false
                resetLinkedWatch()
                decisionPolicy.onManualLockConfirmed(stepsAtLastStrongNear ?: motion.observedSteps)
                if (away) decisionPolicy.onLockedWhileAway()
                if (selfLockReopenPending && source == "car self-lock") {
                    decisionPolicy.armSelfLockReopen(clock.wallMs(), (gattEma ?: -100.0).toInt())
                    Logx.d("prox", "car relocked soon after our unlock -> walking up to the door reopens once")
                }
                selfLockReopenPending = false
                lastTriggerMs = clock.wallMs()
                _wakeLockNeeded.value = false
                activityWake?.complete(Unit)
                Logx.d("prox", "lock ${if (bleConfirmed) "confirmed" else "requested"} ($source) -> departure latched")
            } else {
                Logx.d("prox", "lock ${if (bleConfirmed) "confirmed" else "requested"} ($source); proximity disabled/stopped")
            }
        }
    }

    private fun recordUnlockConfirmed(source: String, restored: Boolean = false) {
        if (!restored) lastUnlockSource = source
        manualCloudLockCheck?.cancel(); manualCloudLockCheck = null
        resetLinkedWatch()
        val now = clock.wallMs()
        val unlockElapsed = clock.elapsedMs()
        lockConfirmationJob?.cancel(); lockConfirmationJob = null
        lockJob?.cancel(); lockJob = null
        departureAnchorJob?.cancel(); departureAnchor = null
        linkDepartureJob?.cancel(); linkDepartureJob = null
        farNoMotionCheck?.cancel(); farNoMotionCheck = null; farNoMotionCheckAtMs = 0L
        farNoMotionAttempts = 0; departureAlarmHolds = 0
        departureProofAtMs = 0L
        gnssWindowsThisEpoch = 0; gnssBudgetLogged = false
        bleRoute = BleDepartureRoute.forLockThreshold(config().sensitivityLockRssi)
        bleDepartureLoggedReason = ""
        bleDepartureReasonLoggedAtMs = 0L
        Logx.d("prox", if (bleRoute == null) "ble departure route disabled (uncalibrated preset)" else
            "ble departure route enabled (steps ${if (motion.observedSteps != null) "available" else "unavailable"})")
        relockRecoveryPending = false; relockProbeJob?.cancel(); relockProbeJob = null
        pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
        unverifiedLockAlertRaised = false
        // A restore keeps a sounding alarm (it is about this very car) and grants no start-key window.
        if (!restored) {
            alerts.clear()
            ble.noteUnlockConfirmed()
        }
        carSelfLock.onUnlockConfirmed(unlockElapsed)
        stationaryFar.reset()
        idleFarStreak = 0; idleFastWindows = 0; deepFarAtLinkLossMs = 0L
        armedUnlocked = true
        postUnlockFastUntilElapsedMs = clock.elapsedMs() + POST_UNLOCK_FAST_MONITOR_MS
        decisionPolicy.onUnlockConfirmed(now)
        unlockObservedAtMs = now
        val nearAnchor = NearDepartureAnchor(unlockElapsed)
        nearDepartureAnchor = nearAnchor
        departureAnchorJob = scope.launch {
            val fix = departureLocationSource.nearAnchor(nearAnchor) {
                armedUnlocked && unlockObservedAtMs == now &&
                    _state.value.running && config().proximityEnabled
            }
            if (fix != null) {
                if (!armedUnlocked || unlockObservedAtMs != now || !_state.value.running ||
                    !config().proximityEnabled) return@launch
                departureAnchor = fix
                Logx.d("prox", "usable departure location anchor available")
                if (linkLostAtMs != 0L)
                    scheduleLinkDepartureCheck()
            } else if (armedUnlocked && unlockObservedAtMs == now && _state.value.running &&
                config().proximityEnabled) {
                val posted = alerts.show(
                    LockAlertPolicy.Event.LOCATION_REFERENCE_UNAVAILABLE, bleDepartureRouteActive = bleRoute != null)
                _state.value = _state.value.copy(lastAction =
                    "Auto Lock unavailable · no accurate location reference; Lock manually")
                Logx.w("prox", "usable departure location anchor unavailable after bounded near acquisition; " +
                    "automatic Lock unavailable; manual Lock required; reminderPosted=$posted")
            }
        }
        stepsAtUnlock = motion.observedSteps
        counterAtUnlock = motion.counterSteps
        strongestRssiSinceUnlock = Int.MIN_VALUE
        lastStrongNearAtMs = 0L
        lastShadowDepartureAtMs = 0L
        shadowDepartureWarned = false
        lastWalkAwayCandidateAtMs = 0L
        departureAlarmJob?.cancel(); departureAlarmJob = null
        shadowRecoveryLogged = false
        lastTriggerMs = now
        _wakeLockNeeded.value = true
        activityWake?.complete(Unit)
        // No reminder notification at unlock: the screen is normally off, so it would not be seen.
        // An expected Lock that is not confirmed raises an audible alarm instead (LockAlertPolicy).
        _state.value = _state.value.copy(lastAction =
            "Unlocked · automatic Lock after verified departure")
        Logx.d("prox", "unlock confirmed ($source) -> departure watch; " +
            "independent departure proof required; fast RSSI for ${POST_UNLOCK_FAST_MONITOR_MS}ms")
        if (ble.state.value !in setOf(DkBleManager.State.SESSION_READY, DkBleManager.State.CONNECTED)) {
            if (linkLostAtMs == 0L) linkLostAtMs = now
            queueUnverifiedLockAlert()
        }
    }

    // ---------------- no live session ----------------

    private fun onSessionDown() {
        if (linkLostAtMs == 0L) {
            calibration.onLinkLost(clock.elapsedMs())
            decisionPolicy.onLinkEnded()
            carSelfLock.onLinkLost()
            linkLostAtMs = clock.wallMs()
            // Near the car only if the last reading was taken here: steps since it mean the phone
            // has moved on (stress F22/F42: the last reading -83 was 10 m and 17 s back, the loss
            // at 22 m counted as "near the car" and the alarm stayed silent).
            val stepsSinceReading = if (lostRssiSteps != null) stepsSince(lostRssiSteps, lostRssiCounter) else null
            val readingAgeMs = if (lostRssiAtMs == 0L) Long.MAX_VALUE else clock.wallMs() - lostRssiAtMs
            val movedSinceLastReading = stepsSinceReading != null &&
                (stepsSinceReading >= LOSS_NEAR_MAX_STEPS_SINCE_READING ||
                    (stepsSinceReading > 0L && readingAgeMs >= LOSS_NEAR_STALE_READING_MS))
            linkLostNearCar = !movedSinceLastReading && lostRssi?.let { it >= config().sensitivityUnlockRssi } ?: false
            deepFarAtLinkLossMs = stationaryFar.deepFarForMs(linkLostAtMs)
            // Locked car, link torn down clearly far from it (out of range): a physical round trip.
            // A fresh session later arriving sustained strong-near at the door may reopen once
            // (field 13/10 16:44: a slow reconnect completed at the door, standing still, and the
            // departure-ended latch otherwise kept it shut). Needs a real far reading, so a wedged
            // link beside the car or a phone left inside it never arms it.
            val lostFar = lostRssi?.let { it < config().sensitivityLockRssi } ?: false
            if (!armedUnlocked && lostFar)
                decisionPolicy.onFarLinkLossWhileLocked(motion.observedSteps)
            stationaryFar.onReadingsStopped()
            // RSSI/body shielding and a real departure can both tear down the link. Neither a timer
            // nor the last (possibly stale) RSSI can tell them apart; position must corroborate.
            val observedStepsSinceUnlock = stepsAtUnlock?.let { baseline ->
                motion.observedSteps?.let { (it - baseline).coerceAtLeast(0L) }
            }
            Logx.d("prox", "link down (lastRssi=$lostRssi armed=$armedUnlocked " +
                "stepsSinceUnlock=${observedStepsSinceUnlock ?: "unavailable"} ${motion.stepSummary}) " +
                "-> independent departure check; no Lock from link loss alone")
            queueUnverifiedLockAlert()
            scheduleLinkDepartureCheck()
            gattEma = null
            inCarSinceMs = 0L; steadyRef = null; steadySinceMs = 0L; lastCadence = ""
            rssiNullStreak = 0; pingInFlight = false; pingFailStreak = 0; lastPingMs = 0L
            _state.value = _state.value.copy(
                phase = Phase.PASSIVE, source = Source.NONE, zone = Zone.UNKNOWN,
                rawRssi = null, smoothedRssi = null, distanceM = null,
            )
        }
        // No live session: never sleep-until-motion here (the sensor can't feed us RSSI). Hold the CPU
        // while you're MOVING — a drop while walking up needs the CPU
        // held so keepConnected's aggressive foreground reconnect can run before you reach the car.
        // Otherwise let go — the offloaded presence scan owns the wakelock from here (parked-still).
        farAsleep = false
        _wakeLockNeeded.value = motion.state.value == MotionMonitor.Motion.MOVING
        nextIntervalMs = MONITOR_MID_MS
    }

    private fun scheduleLinkDepartureCheck() {
        if (!armedUnlocked || departureAnchor == null || linkDepartureJob?.isActive == true ||
            farNoMotionCheck?.isActive == true ||
            lockJob?.isActive == true) return
        linkDepartureJob = scope.launch {
            repeat(3) {
                // Plan H8: hold the CPU for the short wait (the GNSS check itself keeps it awake).
                val wl = acquireSafetyWakelock(10_000L)
                try { delay(5_000L) } finally { releaseSafetyWakelock(wl) }
                if (!armedUnlocked || !_state.value.running ||
                    !config().proximityEnabled || lockJob?.isActive == true) return@launch
                if (confirmPhysicalDeparture() && armedUnlocked &&
                    autoLockGate.onVerifiedDeparture() == AutomaticLockGate.Action.LOCK) {
                    Logx.d("prox", "pending departure corroborated by position; sending Lock")
                    startLockLoop("verified-link-departure")
                    return@launch
                }
            }
            Logx.w("prox", "pending departure unverified; manual Lock required")
        }
    }

    /** Warn after a sustained loss, without interpreting elapsed time as evidence to lock. */
    private fun queueUnverifiedLockAlert() {
        if (!armedUnlocked || pendingUnverifiedLockAlert?.isActive == true) return
        // Walking when the link went (or while waiting) = leaving radio range with the car open:
        // audible. A stationary loss (security sleep, Watch hand-over, phone set down near the car)
        // is not an expected Lock and stays silent.
        // Walked clearly away from the car before an out-of-range loss (stress F22/F42: the motion
        // sensor said STILL for the whole walk, the step counter did count).
        var movedDuringLoss = walkingOutsideHandOver() || departureSuspected() ||
            (!linkLostNearCar && stepsShortOfDeparture() == null && (stepsSinceUnlock() ?: 0L) > 0L)
        pendingUnverifiedLockAlert = scope.launch {
            val wl = acquireSafetyWakelock(UNVERIFIED_LOCK_ALERT_WAKE_MS)
            try {
                val deadline = clock.elapsedMs() + UNVERIFIED_LOCK_ALERT_DELAY_MS
                while (clock.elapsedMs() < deadline) {
                    delay(1_000L)
                    if (walkingOutsideHandOver()) movedDuringLoss = true
                }
            } finally {
                releaseSafetyWakelock(wl)
            }
            // A Lock or link-departure check in progress owns the outcome and raises its own alert -
            // for a bounded time: a stuck check must never keep the car open in silence.
            val ownerDeadline = clock.elapsedMs() + LINK_LOSS_ALERT_MAX_WAIT_MS
            // A link-departure check (GNSS) raises nothing when it fails: it may hold the alert for
            // one location window, not for all three (0.3.2; simulator F22: ~90 s indoors without a
            // fix, then the alarm came 94-110 s after the user passed 15 m).
            val checkDeadline = clock.elapsedMs() + LINK_DEPARTURE_ALERT_HOLD_MS
            while ((lockJob?.isActive == true && clock.elapsedMs() < ownerDeadline) ||
                (linkDepartureJob?.isActive == true && clock.elapsedMs() < minOf(ownerDeadline, checkDeadline))) delay(1_000L)
            if (linkLostAtMs == 0L || !armedUnlocked || !_state.value.running) return@launch
            val lossEvent = LockAlertPolicy.linkLossEvent(movedDuringLoss, departureSuspected())
            if (LockAlertPolicy.audible(lossEvent) && cloudConfirmsLockedSinceUnlock("link-loss alarm")) return@launch
            val posted = alerts.show(lossEvent)
            unverifiedLockAlertRaised = true
            _state.value = _state.value.copy(lastAction =
                if (posted) "BLE lost · vehicle lock unverified; check notification"
                else "BLE lost · vehicle lock unverified; notifications unavailable")
            Logx.w("prox", "BLE still down; vehicle lock unverified (notification posted=$posted)")
            if (movedDuringLoss) return@launch
            // Stationary loss so far (e.g. security sleep beside the car). If the user then walks
            // away while the car is still open and out of reach, sound the alarm after all. Waiting
            // on the motion flow needs no wakelock; link restore cancels this job.
            // The S24 with the screen off can report STILL for a whole walk away (field 09/10
            // 13:30), so "no motion" does not mean "beside the car". A link that went down on a
            // weak signal (out of range, not a deliberate disconnect at the car) and is still down
            // [STATIONARY_LINK_LOSS_CHECK_MS] after the loss is checked with the cloud: car still
            // open -> alarm. A phone beside the car reconnects within seconds.
            val outOfRangeLoss = !linkLostNearCar
            // 0.3.2: after a step-counted walk away from the car the cloud is asked after 60 s, not
            // 100 s (simulator F22: the late alarm). A disconnect beside the car (Bluetooth off, a
            // weak back pocket) keeps the 100 s: an early alarm at the car would use up the alarm of
            // the departure that follows (the 5-min reminder rule).
            val stepsSinceCarAtLoss = stepsAwayFromCar() ?: 0L
            val stationaryCheckMs = if (stepsSinceCarAtLoss >= LOSS_NEAR_MAX_STEPS_SINCE_READING)
                STATIONARY_LINK_LOSS_EARLY_CHECK_MS else STATIONARY_LINK_LOSS_CHECK_MS
            val checkInMs = (linkLostAtMs + stationaryCheckMs - clock.wallMs()).coerceAtLeast(0L)
            val checkWl = acquireSafetyWakelock(
                (if (outOfRangeLoss) checkInMs else NEAR_LOSS_STEP_WATCH_MS) + 30_000L)
            // Plan K8: the S24 with the screen off reports STILL during a whole walk away (field
            // 09/10 13:30); the hardware step counter still counts. Steps since the loss = walking.
            val stepsAtLoss = motion.observedSteps
            val counterAtLoss = motion.counterSteps
            fun walkingNow(): Boolean = (motion.state.value == MotionMonitor.Motion.MOVING ||
                (stepsAtLoss != null && (stepsSince(stepsAtLoss, counterAtLoss) ?: 0L) >= NEAR_LOSS_WALK_STEPS)) &&
                !com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value
            val walkedEarly = try {
                withTimeoutOrNull(if (outOfRangeLoss) checkInMs else NEAR_LOSS_STEP_WATCH_MS) {
                    while (!walkingNow()) delay(2_000L)
                    true
                } == true || (!outOfRangeLoss && withTimeoutOrNull(UNVERIFIED_LOCK_ESCALATION_MS - NEAR_LOSS_STEP_WATCH_MS) {
                    motion.state.first { it == MotionMonitor.Motion.MOVING &&
                        !com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value }
                } != null)
            } finally {
                releaseSafetyWakelock(checkWl)
            }
            if (linkLostAtMs == 0L || !armedUnlocked || !_state.value.running) return@launch
            if (walkedEarly && !outOfRangeLoss) {
                // A loss beside the car (Bluetooth off, key asleep, hand-over) followed by the first
                // steps: the link usually returns within seconds (simulator F39: restored 0.8 s after
                // the motion edge). A restored link hands over to the departure watch.
                delay(NEAR_LOSS_WALK_CONFIRM_MS)
                if (linkLostAtMs == 0L || !armedUnlocked || !_state.value.running) return@launch
            }
            if (walkedEarly) {
                if (cloudConfirmsLockedSinceUnlock("link-loss alarm (walking)")) return@launch
                alerts.show(LockAlertPolicy.Event.LINK_LOST_WHILE_UNLOCKED)
                Logx.w("prox", "BLE still down and walking; vehicle lock unverified -> alarm")
                return@launch
            }
            if (!outOfRangeLoss || com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) return@launch
            val snapshot = runCatching { cloudLockSnapshot() }.getOrNull()
            if (linkLostAtMs == 0L || !armedUnlocked || !_state.value.running) return@launch
            when {
                snapshot == null -> Logx.d("prox", "BLE down ${stationaryCheckMs / 1000} s after an " +
                    "out-of-range loss; cloud state unavailable -> still waiting for a walk")
                snapshot.locked -> Logx.d("prox", "BLE down ${stationaryCheckMs / 1000} s after an " +
                    "out-of-range loss; cloud says locked -> no alarm")
                else -> {
                    alerts.show(LockAlertPolicy.Event.LINK_LOST_WHILE_UNLOCKED)
                    Logx.w("prox", "BLE down ${stationaryCheckMs / 1000} s after an out-of-range " +
                        "loss and the cloud says unlocked -> alarm")
                    return@launch
                }
            }
            val walkedLater = withTimeoutOrNull(UNVERIFIED_LOCK_ESCALATION_MS - STATIONARY_LINK_LOSS_CHECK_MS) {
                motion.state.first { it == MotionMonitor.Motion.MOVING &&
                    !com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value }
            } != null
            if (walkedLater && linkLostAtMs != 0L && armedUnlocked && _state.value.running) {
                if (cloudConfirmsLockedSinceUnlock("link-loss alarm (walked later)")) return@launch
                alerts.show(LockAlertPolicy.Event.LINK_LOST_WHILE_UNLOCKED)
                Logx.w("prox", "BLE still down and walking; vehicle lock unverified -> alarm")
            }
        }
    }

    /**
     * A weak-RSSI walk-away candidate without position proof is also what body shadowing at the
     * car looks like. It sounds only if, a few seconds later, the link is gone or a fresh reading is
     * still weak; the BLE route (when enabled) and the link-loss alarm cover a real departure.
     */
    private suspend fun shadowDepartureEvent(): LockAlertPolicy.Event {
        if (bleRoute != null) return LockAlertPolicy.Event.DEPARTURE_CANDIDATE_UNVERIFIED
        delay(SHADOW_RECHECK_MS)
        if (ble.state.value != DkBleManager.State.SESSION_READY) return LockAlertPolicy.Event.DEPARTURE_UNVERIFIED
        val fresh = ble.pollRemoteRssi() ?: return LockAlertPolicy.Event.DEPARTURE_UNVERIFIED
        return if (fresh <= config().sensitivityLockRssi) LockAlertPolicy.Event.DEPARTURE_UNVERIFIED
            else LockAlertPolicy.Event.DEPARTURE_CANDIDATE_UNVERIFIED
    }

    /** Any fresh reading at the car ends a suspected departure. */
    private fun noteFreshReading(rssi: Int) {
        recordReading(rssi)
        if (rssi >= STRONG_NEAR_DIAGNOSTIC_RSSI) markStrongNear()
    }

    /** A strong at-the-car reading; after a gap it is the user's return, which ends an alarm (plan K7). */
    private fun markStrongNear() {
        val now = clock.wallMs()
        val returning = lastStrongNearAtMs == 0L || now - lastStrongNearAtMs > RETURN_GAP_MS
        lastStrongNearAtMs = now
        if (returning && armedUnlocked && unverifiedLockAlertRaised) {
            alerts.silenceAlarm()
            unverifiedLockAlertRaised = false
            Logx.d("prox", "strong signal at the car after an alarm -> user back; alarm silenced")
        }
    }

    /** The last few seconds of raw readings (wall ms, dBm) for the Lock veto. */
    private val recentReadings = ArrayDeque<Pair<Long, Int>>()
    private fun recordReading(rssi: Int) {
        val now = clock.wallMs()
        recentReadings.addLast(now to rssi)
        while (recentReadings.isNotEmpty() && now - recentReadings.first().first > RECENT_READINGS_MS) recentReadings.removeFirst()
    }

    /**
     * Plan K1: is the phone at, or walking back to, the car right now? Then no automatic Lock -
     * whatever a (possibly drifting or late) position proof says. Stress F08: a GNSS proof arrived
     * after the user had turned back, and the car locked at 1.0 m after -71..-77 dBm readings.
     * A real departure has been weak for seconds by the time it is proven (locks start at ~14 m).
     */
    private fun lockVeto(fresh: Int?): String? {
        val now = clock.wallMs()
        if (fresh != null && fresh >= STRONG_NEAR_DIAGNOSTIC_RSSI) return "fresh reading $fresh dBm at the car"
        // Back inside the lock range: the smoothed signal, including the fresh reading, is above the
        // lock threshold (calibrated F28: a GNSS proof completed with the user walking back at 3 m).
        if (fresh != null) {
            val level = gattEma?.let { it + ALPHA_FAST * (fresh - it) } ?: fresh.toDouble()
            if (level > config().sensitivityLockRssi) return "signal back in the lock range (${level.toInt()} dBm)"
        }
        stepsShortOfDeparture()?.let { return it }
        if (lastStrongNearAtMs != 0L && now - lastStrongNearAtMs <= LOCK_VETO_RECENT_STRONG_MS)
            return "strong reading ${(now - lastStrongNearAtMs) / 100 / 10.0} s ago"
        val earlier = recentReadings.filter { now - it.first in LOCK_VETO_RISE_FROM_MS..LOCK_VETO_RISE_TO_MS }.maxOfOrNull { it.second }
        if (fresh != null && earlier != null && fresh >= config().sensitivityUnlockRssi && fresh >= earlier + LOCK_VETO_RISE_DB)
            return "walking back ($earlier -> $fresh dBm)"
        // Not vetoed: a rise far away (-96 -> -91 at 30 m). Standing still and turning to face the
        // car gives the same +4..6 dB as walking back (calibrated F23: the cloud Lock was refused
        // and the car stayed open), so far from the car a rise is no evidence of a return.
        return null
    }

    /**
     * A body-shadowed phone beside the car reads like 40 m (stress F06: back pocket, -98..-104 at
     * 4 m). Where steps are counted in this unlock, a departure beyond the lock range has walked
     * at least [LOCK_MIN_STEPS_SINCE_CAR] steps since the car. The hub counter can lag the walk (it
     * may hold back the first steps of a walk), so the threshold stays low (calibrated F28: 15
     * counted at 19 m). Known limit: a slow walker has made that many steps at ~5 m (stress F04,
     * with an 8 s deep fade there).
     */
    private fun stepsShortOfDeparture(): String? {
        if ((stepsSinceUnlock() ?: 0L) <= 0L) return null
        val sinceCar = stepsAwayFromCar() ?: return null
        return if (sinceCar < LOCK_MIN_STEPS_SINCE_CAR) "only $sinceCar steps since the car (${motion.stepSummary})" else null
    }

    /**
     * Steps since a baseline pair: the larger of the merged total's and the hub counter's own
     * increment (0.3.2, simulation audit C). The merged total is the larger of two running totals;
     * once the detector is ahead, counter steps taken while the CPU slept do not show in it (stress
     * F22: 22 m walked, "3 steps since the unlock").
     */
    private fun stepsSince(mergedBase: Long?, counterBase: Long?): Long? {
        val merged = mergedBase?.let { base -> motion.observedSteps?.let { it - base } }
        val counter = counterBase?.let { base -> motion.counterSteps?.let { it - base } }
        return listOfNotNull(merged, counter).maxOrNull()
    }

    /** At least [STEPS_RISING_MIN] counted steps within the last [STEPS_RISING_WINDOW_MS] (false without steps). */
    private fun stepsRisingNow(nowMs: Long, steps: Long?): Boolean {
        val s = steps ?: return false
        if (recentSteps.lastOrNull()?.let { nowMs < it.first || s < it.second } == true) recentSteps.clear()
        recentSteps.addLast(nowMs to s)
        while (recentSteps.isNotEmpty() && nowMs - recentSteps.first().first > STEPS_RISING_WINDOW_MS) recentSteps.removeFirst()
        return recentSteps.size >= 2 && s - recentSteps.first().second >= STEPS_RISING_MIN
    }

    /** Steps since the latest strong reading at the car, else since the unlock (null without steps). */
    private fun stepsAwayFromCar(): Long? =
        if (stepsAtLastStrongNear != null) stepsSince(stepsAtLastStrongNear, counterAtLastStrongNear)
        else stepsSince(stepsAtUnlock, counterAtUnlock)

    private fun departureSuspected(): Boolean =
        LockAlertPolicy.departureSuspected(lastWalkAwayCandidateAtMs, lastStrongNearAtMs) ||
            maxOf(deepFarAtLinkLossMs, stationaryFar.deepFarForMs(clock.wallMs())) >=
            StationaryFarWatch.LINK_LOSS_FAR_MS || walkedAwayBySteps()

    /**
     * Plan K9: the phone has walked a clear distance since it was last strong at the car, and has
     * not been strong there for a while - even if the link stays up with a middling signal (a phone
     * that hears the car better than the S24: the car stayed open 2.5 min at 30 m without an alarm).
     * The hardware step counter works with the screen off (0.2.17). Only ever raises an alarm.
     */
    private fun walkedAwayBySteps(): Boolean {
        if (!armedUnlocked) return false
        val steps = stepsAwayFromCar() ?: return false
        val since = if (lastStrongNearAtMs != 0L) lastStrongNearAtMs else unlockObservedAtMs
        return steps >= WALKED_AWAY_STEPS && clock.wallMs() - since >= WALKED_AWAY_QUIET_MS
    }

    /** Plan K9: start the departure alarm clock once a step-proven walk-away is under way. */
    private fun maybeArmStepDepartureAlarm() {
        if (armedUnlocked && departureAlarmJob?.isActive != true && lockJob?.isActive != true && walkedAwayBySteps()) {
            Logx.d("prox", "walked away by steps (${motion.stepSummary}) without a strong reading -> departure alarm armed")
            armDepartureAlarm()
        }
    }

    /** Unlocked and clearly far for a minute without any motion signal: alarm, never a Lock. */
    /**
     * The key link shows a sustained, consistent clear separation (>= [StationaryFarWatch.CORROBORATION_MS]
     * of consecutive readings beyond the lock threshold, possibly frozen at a link loss). Replaces
     * the step count in the GNSS departure proof; never authorizes a Lock on its own.
     */
    private fun departureBleCorroborated(): Boolean {
        val now = clock.wallMs()
        // A separation frozen at a link loss only counts shortly after it: a return while the
        // radio is down cannot be seen (review 0.1.63).
        val frozen = if (linkLostAtMs != 0L && now - linkLostAtMs <= CORROBORATION_AFTER_LOSS_MS) deepFarAtLinkLossMs else 0L
        return maxOf(frozen, stationaryFar.deepFarForMs(now)) >= StationaryFarWatch.CORROBORATION_MS
    }

    private var farNoMotionCheck: Job? = null
    private var farNoMotionCheckAtMs = 0L
    private var farNoMotionAttempts = 0
    private var departureAlarmHolds = 0

    /**
     * Walked away without any motion or step signal (S24+ screen off): once the link shows a
     * sustained clear separation, ask GNSS for the independent departure proof and lock on it.
     */
    private fun maybeStartFarNoMotionCheck() {
        val now = clock.wallMs()
        if (!armedUnlocked || departureAnchor == null || !departureBleCorroborated() ||
            lockJob?.isActive == true || lockConfirmationJob?.isActive == true ||
            linkDepartureJob?.isActive == true || farNoMotionCheck?.isActive == true ||
            farNoMotionAttempts >= FAR_NO_MOTION_MAX_ATTEMPTS ||
            now - farNoMotionCheckAtMs < FAR_NO_MOTION_RETRY_MS) return
        // Bounded per unlock: every extra GNSS window is another chance for a drifting fix, and
        // costs battery while the car is deliberately left open (review 0.1.63).
        farNoMotionAttempts++
        farNoMotionCheckAtMs = now
        Logx.d("prox", "clear separation without a motion signal -> checking position for departure")
        farNoMotionCheck = scope.launch {
            if (confirmPhysicalDeparture() && armedUnlocked && _state.value.running && config().proximityEnabled &&
                lockJob?.isActive != true && autoLockGate.onVerifiedDeparture() == AutomaticLockGate.Action.LOCK) {
                Logx.d("prox", "departure corroborated by position and sustained separation; sending Lock")
                startLockLoop("far-no-motion")
            }
        }
    }

    private fun observeStationaryFar(rssi: Int, lockThresh: Int) {
        if (!armedUnlocked || lockJob?.isActive == true) return
        val due = stationaryFar.observe(clock.wallMs(), rssi, lockThresh)
        maybeStartFarNoMotionCheck()
        if (!due) return
        val posted = alerts.show(LockAlertPolicy.Event.DEPARTURE_UNVERIFIED)
        unverifiedLockAlertRaised = true
        _state.value = _state.value.copy(lastAction = "Far from the car · car not locked; Lock manually")
        Logx.w("prox", "phone far from the unlocked car for ${StationaryFarWatch.ALARM_AFTER_MS / 1000}s " +
            "without a motion signal; NO LOCK SENT; notification posted=$posted")
    }

    /**
     * After the user's cloud Lock (also the fallback when the car received a key Lock but stayed
     * open, field 05/10 16:08): only a car report newer than the Lock clears the warning; otherwise
     * the car is NOT locked and the alarm sounds (user decision 05/10).
     */
    private fun checkManualCloudLock(lockAtMs: Long) {
        manualCloudLockCheck?.cancel()
        manualCloudLockCheck = scope.launch {
            val lockedByCloud = verifyFreshLockStatus(lockAtMs)
            val lockedByKey = lastVehicleStatus?.centralLockCode == 3 &&
                ble.state.value == DkBleManager.State.SESSION_READY
            if (lockedByCloud || lockedByKey) {
                unverifiedLockAlertRaised = false
                alerts.clear()
                alerts.carLocked()
                _state.value = _state.value.copy(lastAction = "Cloud Lock · vehicle reports locked ✓")
                Logx.d("prox", "manual cloud Lock verified (cloud=$lockedByCloud key=$lockedByKey)")
            } else {
                val posted = alerts.show(LockAlertPolicy.Event.LOCK_NOT_EXECUTED)
                unverifiedLockAlertRaised = true
                _state.value = _state.value.copy(lastAction = "Lock NOT executed ✗ · car still open; check doors")
                Logx.w("prox", "manual Lock not executed: no fresh locked report; alarm posted=$posted")
            }
        }
    }

    /** The car's own status push: a fresh unlocked -> locked transition means the car locked itself. */
    /**
     * After a gap without the key link the car's lock state is unknown: it may have been opened
     * by other means (field 10/10 16:48-16:51: phone asleep in the car, door opened, then walked
     * away; the app still believed the car's self-lock of 16:44:51 and neither locked nor alarmed).
     * The car pushes its status at the start of a new session; if none arrives, ask the cloud.
     * A cloud "unlocked" re-arms the departure watch like a reopen seen by the key.
     */
    private fun scheduleLockStateCheck(reason: String) {
        if (lockStateCheckJob?.isActive == true) return
        val statusBefore = lastVehicleStatus
        lockStateCheckJob = scope.launch {
            delay(LOCK_STATE_STATUS_WAIT_MS)
            if (lastVehicleStatus !== statusBefore || armedUnlocked || !_state.value.running) return@launch
            val snapshot = runCatching { cloudLockSnapshot() }.getOrNull()
            Logx.d("prox", "lock state unknown ($reason): cloud says " +
                (snapshot?.let { if (it.locked) "locked" else "unlocked" } ?: "nothing"))
            if (snapshot == null || snapshot.locked || armedUnlocked || lastVehicleStatus !== statusBefore) return@launch
            Logx.w("prox", "car reported unlocked by the cloud without our Unlock -> departure watch re-armed")
            onExternalUnlockConfirmed("car reopened (cloud)")
        }
    }

    private fun onVehicleStatus(status: ObservedVehicleStatus) {
        val previous = lastVehicleStatus
        lastVehicleStatus = status
        if (status.centralLockCode == CarSelfLockDetector.LOCKED) lastCarLockedReportAtMs = clock.wallMs()
        if (previous != null && previous.sessionGeneration == status.sessionGeneration &&
            (previous.centralLockCode == 1 || previous.centralLockCode == 2) && status.centralLockCode == 3)
            alerts.carLocked()
        when (carSelfLock.observe(status, clock.elapsedMs())) {
            CarSelfLockDetector.Event.NONE -> Unit
            CarSelfLockDetector.Event.SELF_LOCK -> scope.launch {
                // A manual Lock's own "3" precedes its receipt by ~20 ms: let that receipt win first.
                delay(SELF_LOCK_SETTLE_MS)
                if (!armedUnlocked || lockJob?.isActive == true || !_state.value.running) return@launch
                handleCarSelfLock(status)
            }
            CarSelfLockDetector.Event.LOCKED_WHILE_AWAY -> scope.launch {
                delay(SELF_LOCK_SETTLE_MS)
                // A Lock in flight takes the report as its confirmation (lastCarLockedReportAtMs).
                if (!armedUnlocked || lockJob?.isActive == true || !_state.value.running) return@launch
                handleLockedWhileAway(status)
            }
            CarSelfLockDetector.Event.REOPENED -> scope.launch {
                // Our own Unlock's "1" also precedes its receipt: only an Unlock we did not send counts.
                delay(SELF_LOCK_SETTLE_MS)
                if (armedUnlocked || !_state.value.running) return@launch
                Logx.w("prox", "car reported unlocked without our Unlock (key status central=${status.centralLockCode}) " +
                    "-> departure watch re-armed")
                onExternalUnlockConfirmed("car reopened")
            }
        }
    }

    /**
     * Plan H1: the car reports locked at the start of a new key session while the app believed it
     * open - locked by fob, handle, another phone or the car itself while the link was down. The
     * phone at the car (strong reading) means a lock at the car: latch like a manual Lock, no reopen
     * right away. Away from the car it is a plain locked car: the next walk up is a normal arrival.
     */
    private suspend fun handleLockedWhileAway(status: ObservedVehicleStatus) {
        departureAlarmJob?.cancel(); departureAlarmJob = null
        lockConfirmationJob?.cancel(); lockConfirmationJob = null
        val fresh = if (ble.state.value == DkBleManager.State.SESSION_READY) ble.pollRemoteRssi() else null
        if (!armedUnlocked) return
        val atCar = fresh != null && fresh >= STRONG_NEAR_DIAGNOSTIC_RSSI
        com.openzeekr.app.remote.LocalLockEvidence.onKeyConfirmed(locked = true)
        Logx.w("prox", "car reports locked at the start of a new key session (central=${status.centralLockCode}, " +
            "rssi=${fresh ?: "?"}) while the app believed it open -> locked while the link was down; " +
            if (atCar) "phone at the car: manual-Lock latch" else "phone away: next walk up is a normal arrival")
        onManualLockAcknowledged("car locked while the link was down", bleConfirmed = true, away = !atCar)
    }

    private fun handleCarSelfLock(status: ObservedVehicleStatus) {
        departureAlarmJob?.cancel(); departureAlarmJob = null
        lockConfirmationJob?.cancel(); lockConfirmationJob = null
        alerts.silenceAlarm()
        com.openzeekr.app.remote.LocalLockEvidence.onKeyConfirmed(locked = true)
        Logx.w("prox", "car locked itself (key status central=${status.centralLockCode}) -> treated as Lock")
        val sinceUnlockMs = clock.wallMs() - unlockObservedAtMs
        selfLockReopenPending = lastUnlockSource == "proximity" && unlockObservedAtMs != 0L &&
            sinceUnlockMs in 0..SELF_LOCK_REOPEN_AFTER_UNLOCK_MS
        onExternalLockConfirmed("car self-lock")
    }

    /**
     * After a moving walk-away candidate the car must be locked soon, by the BLE route, GNSS or a
     * link-departure check. If it is still unlocked after [LockAlertPolicy.DEPARTURE_ALARM_DELAY_MS],
     * the phone has not been back at the car and a fresh reading is weak (or the link is gone),
     * sound the alarm. Any route that locks, a return to the car, or a new epoch cancels this.
     */
    private fun armDepartureAlarm() {
        if (departureAlarmJob?.isActive == true) return
        departureAlarmJob = scope.launch {
            // Plan H8: a timer stops while the CPU sleeps; hold it for this bounded wait.
            val wl = acquireSafetyWakelock(LockAlertPolicy.DEPARTURE_ALARM_DELAY_MS + 15_000L)
            try { delay(LockAlertPolicy.DEPARTURE_ALARM_DELAY_MS) } finally { releaseSafetyWakelock(wl) }
            // A Lock in progress owns the outcome and raises its own alert if it fails.
            while (lockJob?.isActive == true) delay(1_000L)
            // Best of a few fresh readings: one body-shadowed reading at the car must not sound it.
            var fresh: Int? = null
            repeat(DEPARTURE_ALARM_READS) { i ->
                if (i > 0) delay(1_000L)
                if (ble.state.value == DkBleManager.State.SESSION_READY)
                    ble.pollRemoteRssi()?.let { r -> noteFreshReading(r); fresh = maxOf(fresh ?: r, r) }
            }
            if (!_state.value.running || !LockAlertPolicy.departureAlarmDue(departureSuspected(), armedUnlocked,
                    fresh, config().sensitivityLockRssi)) return@launch
            // Moving around the car with the body between phone and car reads like a departure
            // (simulator 03/10, loading the boot). Where steps are counted, a real departure has
            // walked clearly further than that; hold the alarm and look again. The 60 s far watch
            // and the link-loss alarm stay as the backstop.
            val stepsSinceCar = if (stepsAtLastStrongNear != null) stepsSince(stepsAtLastStrongNear, counterAtLastStrongNear) else null
            if (departureAlarmHolds < DEPARTURE_ALARM_MAX_HOLDS &&
                !LockAlertPolicy.departureWalkedFarEnough(stepsSinceCar, stepsSinceUnlock())) {
                // Bounded: a step counter that stops delivering (screen off) must not hold it forever.
                departureAlarmHolds++
                Logx.d("prox", "departure alarm held: only $stepsSinceCar steps since the car; re-checking")
                departureAlarmJob = null
                armDepartureAlarm()
                return@launch
            }
            // No key link to report the car's state: ask the cloud first (0.3.2; simulator F40: the
            // car was locked by hand while Bluetooth was off and the alarm sounded anyway).
            if (ble.state.value != DkBleManager.State.SESSION_READY &&
                cloudConfirmsLockedSinceUnlock("departure alarm")) return@launch
            val posted = alerts.show(LockAlertPolicy.Event.DEPARTURE_UNVERIFIED)
            unverifiedLockAlertRaised = true
            _state.value = _state.value.copy(lastAction = "Walked away · car not locked; Lock manually")
            Logx.w("prox", "departure suspected and car still unlocked after " +
                "${LockAlertPolicy.DEPARTURE_ALARM_DELAY_MS / 1000}s; NO LOCK SENT; notification posted=$posted")
        }
    }

    private fun walkingOutsideHandOver(): Boolean =
        motion.state.value == MotionMonitor.Motion.MOVING &&
            !com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value

    /**
     * Battery (0.3.1): the open-car idle wait held the CPU for as long as the car stayed open with
     * the phone in range - hours, with the car open in the drive. After [ARMED_STILL_WAKE_MAX_MS]
     * of continuous stillness (no verification or Lock running) the CPU may sleep: the link stays
     * up in the Bluetooth controller, and a motion edge or a frame from the car (a door) wakes the
     * wait at once and takes the wakelock again.
     */
    private fun armedLongStill(): Boolean {
        val now = clock.elapsedMs()
        val still = motion.state.value == MotionMonitor.Motion.STILL && lockConfirmationJob?.isActive != true &&
            lockJob?.isActive != true && !measuringNow()
        if (!still) { armedStillSinceMs = 0L; return false }
        if (armedStillSinceMs == 0L) armedStillSinceMs = now
        val long = now - armedStillSinceMs >= ARMED_STILL_WAKE_MAX_MS
        if (long && _wakeLockNeeded.value) Logx.d("prox", "open car, phone still ${ARMED_STILL_WAKE_MAX_MS / 60_000} min: " +
            "CPU may sleep; motion or the car wakes it")
        return long
    }

    /**
     * Unlocked "activity watch" (replaces polling while armed). The car pushes status frames only on
     * CHANGE — bursts while you move, long silence while parked-still — so:
     *  - SILENT (no inbound for ≥ ARMED_ACTIVE_MS, after the post-unlock window): idle with
     *    periodic RSSI checks,
     *    BLOCKING on the car's next frame ([activityWake], completed by onInboundActivity for an instant
     *    wake) or a periodic safety timeout that does one RSSI check (catches a quiet drift-away).
     *  - ACTIVE (a frame just arrived = you're moving / getting out): track at FULL SPEED — read RSSI
     *    and run the normal decision ([onSample] locks the instant you're far + receding).
     * A hard link drop is handled by [onSessionDown] (reconnect without assuming departure).
     */
    private suspend fun armedWatch() {
        if (lockConfirmationJob?.isActive == true) {
            // The verification job (RSSI confirmation and/or the bounded GPS check) can run for
            // tens of seconds. Keep feeding fresh readings so the BLE evidence is not starved of
            // continuity exactly during a walk-away, and so a return revokes a held proof. The
            // RSSI poll mutex serializes this with confirmWalkAway's own reads.
            val pollStartedAtMs = clock.elapsedMs()
            if (ble.state.value == DkBleManager.State.SESSION_READY) {
                val rssi = ble.pollRemoteRssi()
                // Keep the same EMA semantics as onSample so "smoothed" really is smoothed here.
                val smoothed = rssi?.let { r ->
                    val ema = gattEma?.let { it + ALPHA_FAST * (r - it) } ?: r.toDouble()
                    gattEma = ema
                    ema.toInt()
                }
                val cooling = lastTriggerMs != 0L &&
                    clock.wallMs() - lastTriggerMs < ACTION_COOLDOWN_MS
                rssi?.let(::noteFreshReading)
                if (rssi != null && smoothed != null)
                    _state.value = _state.value.copy(rawRssi = rssi, smoothedRssi = smoothed)
                // Diagnostics (field 05/10 20:43): these readings were invisible in the log.
                if (rssi != null && clock.elapsedMs() - verifyLogAtMs >= 1_000L) {
                    verifyLogAtMs = clock.elapsedMs()
                    Logx.d("prox", "rssi=$rssi ema=$smoothed (verifying departure) motion=${motion.state.value}")
                }
                // Keep the far watch continuous during a verification (review 0.1.63): its clock is
                // the corroboration for a step-free GNSS proof and must see a walk back.
                rssi?.let { observeStationaryFar(it, config().sensitivityLockRssi) }
                if (rssi != null && smoothed != null && armedUnlocked && lockJob?.isActive != true &&
                    observeBleDeparture(rssi, smoothed) && !cooling && !actionInFlight) {
                    lockConfirmationJob?.cancel(); lockConfirmationJob = null
                    linkDepartureJob?.cancel(); linkDepartureJob = null
                    departureProofAtMs = clock.wallMs()
                    Logx.d("prox", "ble departure lock authorized (rssi=$rssi kind=${bleRoute?.confirmKind ?: "none"}); sending Lock")
                    startLockLoop("ble-departure")
                    return
                }
            }
            delay((MONITOR_FAST_MS - (clock.elapsedMs() - pollStartedAtMs))
                .coerceAtLeast(0L))
            return
        }
        val quietMs = clock.wallMs() - ble.lastInboundMs
        if (quietMs >= ARMED_ACTIVE_MS &&
            clock.elapsedMs() >= postUnlockFastUntilElapsedMs &&
            motion.state.value != MotionMonitor.Motion.MOVING && !measuringNow()) {
            if (postUnlockFastUntilElapsedMs != 0L) {
                postUnlockFastUntilElapsedMs = 0L
                Logx.d("prox", "post-unlock fast RSSI window ended; armed idle checks resumed")
            }
            val wake = CompletableDeferred<Unit>()
            activityWake = wake
            val woke = try { withTimeoutOrNull(ARMED_IDLE_MAX_MS) { wake.await() } != null } finally { activityWake = null }
            if (woke) { Logx.d("prox", "armed idle -> woke on car activity (tracking full-speed)"); return }
            // Safety re-check on the periodic timeout. A single RSSI read may be stale or
            // body-shadowed; confirm fresh separation before any idle walk-away lock.
            val rssi = ble.pollRemoteRssi()
            // Sparse idle reads must never build BLE departure evidence (a raw spike would also
            // become the "at the car" reference), but they must REVOKE a held proof on return.
            if (rssi != null) { revokeBleDeparture(rssi); noteFreshReading(rssi) }
            // 0.3.2: keep the published level fresh while idle - the key-sleep rule reads it, and a
            // stale body-shadowed value put the key to sleep beside the open car (simulator F05).
            if (rssi != null) publishIdleReading(rssi)
            // Plan H4: standing still at the car after the unlock must still yield the GNSS anchor's
            // "at the car" evidence (it was only fed by the fast samples; the anchor then expired).
            if (rssi != null && ble.state.value == DkBleManager.State.SESSION_READY)
                nearDepartureAnchor?.observe(rssi, clock.elapsedMs())
            maybeArmStepDepartureAlarm()
            if (rssi != null) {
                val lockThresh = config().sensitivityLockRssi
                Logx.d("prox", "armed idle read rssi=$rssi motion=${motion.state.value}")
                observeStationaryFar(rssi, lockThresh)
                // Beyond the lock threshold while the motion signal says STILL: look closer. A slow
                // walk-away with stops kept Activity Recognition at STILL for 1.5 min (03/10).
                idleFarStreak = if (rssi <= lockThresh) idleFarStreak + 1 else 0
                if (idleFarStreak >= 2 && idleFastWindows < StationaryFarWatch.MAX_IDLE_FAST_WINDOWS) {
                    idleFarStreak = 0
                    idleFastWindows++
                    postUnlockFastUntilElapsedMs =
                        clock.elapsedMs() + StationaryFarWatch.IDLE_FAR_FAST_MS
                    Logx.d("prox", "armed idle far read -> fast sampling ${StationaryFarWatch.IDLE_FAR_FAST_MS / 1000}s " +
                        "($idleFastWindows/${StationaryFarWatch.MAX_IDLE_FAST_WINDOWS})")
                }
            }
            val idleDecision = if (rssi == null) ProximityDecisionPolicy.ArmedDecision.NONE else
                decisionPolicy.onUnlockedSample(
                    clock.wallMs(), rssi,
                    motion.state.value == MotionMonitor.Motion.MOVING,
                    config().sensitivityLockRssi,
                )
            if (idleDecision == ProximityDecisionPolicy.ArmedDecision.LOCK) {
                Logx.d("prox", "armed idle safety-check rssi=$rssi -> verifying fresh separation")
                requestVerifiedWalkAwayLock("idle-far-lock")
            }
            return
        }
        // Recent car activity, the initial unlock window, or observed phone movement keeps RSSI
        // sampling fast. A silent car must not force a three-second cadence while walking away.
        val pollStartedAtMs = clock.elapsedMs()
        val rssi = ble.pollRemoteRssi()
        if (rssi != null) { rssiNullStreak = 0; onSample(rssi) }
        else if (++rssiNullStreak >= RSSI_NULL_RECONNECT) { rssiNullStreak = 0; forceReconnect() }
        delay((MONITOR_FAST_MS - (clock.elapsedMs() - pollStartedAtMs))
            .coerceAtLeast(0L))
    }

    /**
     * 0.3.2: two cloud snapshots agree that the car locked after this unlock (its own relock, a fob,
     * the handle, another phone) while the key link could not report it. The car is locked: latch it
     * like a lock seen at the start of a new key session (plan H1) and raise no alarm.
     */
    private suspend fun cloudConfirmsLockedSinceUnlock(why: String): Boolean {
        val epoch = unlockObservedAtMs
        if (!armedUnlocked || epoch == 0L) return false
        val first = runCatching { cloudLockSnapshot() }.getOrNull() ?: return false
        if (!first.locked) return false
        delay(CLOUD_SNAPSHOT_RECHECK_MS)
        val second = runCatching { cloudLockSnapshot() }.getOrNull()
        if (!armedUnlocked || unlockObservedAtMs != epoch || lockJob?.isActive == true) return false
        if (!RelockRecoveryEvidence.confirmsRelock(first, second, epoch, clock.wallMs())) return false
        if (ble.state.value == DkBleManager.State.SESSION_READY) {
            // A live key session reports the car's own lock itself (CarSelfLockDetector, which also
            // arms the walk-up reopen): only a status push the key missed is news here.
            delay(SELF_LOCK_SETTLE_MS + 2_000L)
            if (!armedUnlocked || unlockObservedAtMs != epoch || lockJob?.isActive == true || !_state.value.running) return false
            Logx.w("prox", "$why: the cloud reports the car locked after our unlock; the key session missed it " +
                "-> handled as the car's own lock")
            handleCarSelfLock(ObservedVehicleStatus(-1L, CarSelfLockDetector.LOCKED))
            return true
        }
        departureAlarmJob?.cancel(); departureAlarmJob = null
        lockConfirmationJob?.cancel(); lockConfirmationJob = null
        Logx.w("prox", "$why: the cloud reports the car locked after our unlock (not seen over the key link) " +
            "-> treated as locked; no alarm")
        onManualLockAcknowledged("car locked while unseen (cloud)", bleConfirmed = true, away = true)
        return true
    }

    /** Two cloud snapshots newer than this unlock say locked (the lock loop's check; no side effects). */
    private suspend fun cloudLockedSinceUnlockSnapshot(): Boolean {
        val epoch = unlockObservedAtMs
        if (epoch == 0L) return false
        val first = runCatching { cloudLockSnapshot() }.getOrNull() ?: return false
        if (!first.locked) return false
        delay(CLOUD_SNAPSHOT_RECHECK_MS)
        val second = runCatching { cloudLockSnapshot() }.getOrNull()
        return RelockRecoveryEvidence.confirmsRelock(first, second, epoch, clock.wallMs())
    }

    private var alarmCloudWatchJob: Job? = null

    /** While an alarm about this unlock is up, ask the cloud now and then whether the car locked (0.3.2). */
    private fun startAlarmCloudWatch() {
        if (!armedUnlocked || alarmCloudWatchJob?.isActive == true) return
        val epoch = unlockObservedAtMs
        alarmCloudWatchJob = scope.launch {
            val deadline = clock.elapsedMs() + ALARM_CLOUD_WATCH_MS
            while (clock.elapsedMs() < deadline) {
                delay(ALARM_CLOUD_POLL_MS)
                if (!armedUnlocked || unlockObservedAtMs != epoch || !_state.value.running) return@launch
                if (lockJob?.isActive == true) continue
                if (cloudConfirmsLockedSinceUnlock("alarm cloud watch")) return@launch
            }
        }
    }

    /** An armed idle read refreshes the published level only; it never feeds [gattEma]. */
    private fun publishIdleReading(rssi: Int) {
        val prev = _state.value.smoothedRssi
        val smoothed = prev?.let { (it + ALPHA_FAST * (rssi - it)).roundToInt() } ?: rssi
        _state.value = _state.value.copy(rawRssi = rssi, smoothedRssi = smoothed, distanceM = rssiToDistance(smoothed))
    }

    private fun stepsSinceUnlock(): Long? = stepsAtUnlock?.let { baseline ->
        motion.observedSteps?.let { (it - baseline).coerceAtLeast(0L) }
    }

    /** Feeds one fresh GATT reading to this epoch's BLE departure route; true when confirmed. */
    private fun observeBleDeparture(rawRssi: Int, smoothedRssi: Int): Boolean {
        val route = bleRoute ?: return false
        val now = clock.elapsedMs()
        departureSteps.observe(now, motion.observedSteps)
        // 0.3.2 (simulation audit C): a rising step total is walking, whatever the motion state says
        // (s24-off: no MOVING for a whole walk away, the car stayed open until the alarm). Close to
        // the car a stop in the step stream ends the walk at once: Activity Recognition says MOVING
        // for 3-10 s more, and the proof completed during a stop 5 m from the car (stress F04).
        val stoppedNearCar = departureSteps.stopped(now) &&
            (stepsAwayFromCar()?.let { it < STOP_RULE_MAX_STEPS_FROM_CAR } ?: true)
        val moving = (motion.state.value == MotionMonitor.Motion.MOVING || departureSteps.walking(now)) &&
            !stoppedNearCar
        val authorized = route.observe(now, rawRssi, smoothedRssi, moving, stepsSinceUnlock())
        logBleRouteRevocation(route)
        if (route.evidenceReason != bleDepartureLoggedReason) {
            val nowWall = clock.wallMs()
            if (route.evidenceReason == "confirmed" || route.evidenceReason == "steps_unavailable" ||
                nowWall - bleDepartureReasonLoggedAtMs >= 5_000L) {
                bleDepartureReasonLoggedAtMs = nowWall
                bleDepartureLoggedReason = route.evidenceReason
                Logx.d("prox", "ble departure evidence ${route.evidenceReason}")
            } // else: retried on a later sample, so a lasting transition is never swallowed
        }
        if (route.newlyConfirmed) Logx.d("prox", "ble departure confirmed")
        return authorized
    }

    /** A fresh reading that may only revoke a held proof (never builds evidence). */
    private fun revokeBleDeparture(rawRssi: Int) {
        val route = bleRoute ?: return
        route.revokeOnly(clock.elapsedMs(), rawRssi, rawRssi)
        logBleRouteRevocation(route)
    }

    /**
     * A vetoed Lock spends the BLE departure proof (0.3.0), so the next sample cannot simply
     * re-issue it when the veto no longer holds (stress F08: vetoed at 9 m, re-issued on the walk
     * back, locked at 2.9 m). The new proof must also recede from the level here: with only "a
     * complete new window" the walk back re-confirmed it (stress F08, locked at 5.1 m).
     */
    private fun spendBleProof(fresh: Int?) {
        val route = bleRoute ?: return
        route.vetoed(gattEma?.toInt() ?: fresh ?: VETO_UNKNOWN_LEVEL_RSSI)
        logBleRouteRevocation(route)
    }

    private fun logBleRouteRevocation(route: BleDepartureRoute) {
        route.revokedReason?.let { Logx.d("prox", "ble departure revoked ($it)") }
    }

    private fun bleLockAuthorized(): Boolean =
        bleRoute?.authorizesBleLock(clock.elapsedMs()) == true

    private fun clearBleDeparture() {
        departureAlarmJob?.cancel(); departureAlarmJob = null
        bleRoute = null
        bleDepartureLoggedReason = ""
        bleDepartureReasonLoggedAtMs = 0L
    }

    /** Require independent, newly completed GATT reads throughout departure confirmation. */
    private suspend fun confirmWalkAway(): Boolean {
        val confirmation = WalkAwayLockConfirmation(config().sensitivityLockRssi)
        var previousPollStartedAtMs = clock.elapsedMs()
        repeat(9) {
            delay((MONITOR_FAST_MS -
                (clock.elapsedMs() - previousPollStartedAtMs)).coerceAtLeast(0L))
            previousPollStartedAtMs = clock.elapsedMs()
            if (ble.state.value != DkBleManager.State.SESSION_READY ||
                motion.state.value != MotionMonitor.Motion.MOVING) return false
            val rssi = ble.pollRemoteRssi() ?: return@repeat
            revokeBleDeparture(rssi)
            recordReading(rssi)
            if (rssi >= STRONG_NEAR_DIAGNOSTIC_RSSI) markStrongNear()
            // Awaiting a callback can outlive a motion transition or session shutdown.
            if (ble.state.value != DkBleManager.State.SESSION_READY ||
                motion.state.value != MotionMonitor.Motion.MOVING) return false
            when (confirmation.observe(rssi)) {
                WalkAwayLockConfirmation.Decision.LOCK -> {
                    Logx.d("prox", "walk-away: fresh sustained departure confirmed")
                    return true
                }
                WalkAwayLockConfirmation.Decision.CANCEL -> {
                    Logx.d("prox", "walk-away: signal recovered ($rssi), retaining unlock")
                    walkAwayRecovered = true
                    return false
                }
                WalkAwayLockConfirmation.Decision.WAIT -> Unit
            }
        }
        Logx.d("prox", "walk-away: separation not confirmed, retaining unlock")
        return false
    }

    /** A fresh position change independent of BLE body shadowing is mandatory before any Lock. */
    private suspend fun confirmPhysicalDeparture(): Boolean {
        val anchor = departureAnchor
        if (anchor == null) {
            Logx.d("prox", "independent departure unverified: location anchor unavailable")
            return false
        }
        // Battery (0.3.1): every check is a 25 s GNSS window. Six per visit cover a departure, a
        // return and a second departure; past that the visit gets the alarm, not more GNSS.
        if (gnssWindowsThisEpoch >= MAX_GNSS_WINDOWS_PER_EPOCH) {
            if (!gnssBudgetLogged) Logx.d("prox", "independent departure unverified: location budget of this visit spent " +
                "($MAX_GNSS_WINDOWS_PER_EPOCH windows)")
            gnssBudgetLogged = true
            return false
        }
        gnssWindowsThisEpoch++
        maxGnssWindowsInAnEpoch = maxOf(maxGnssWindowsInAnEpoch, gnssWindowsThisEpoch)
        val observedUnlockAt = unlockObservedAtMs
        fun sameSession(): Boolean = armedUnlocked && unlockObservedAtMs == observedUnlockAt &&
            _state.value.running && config().proximityEnabled && departureAnchor === anchor
        val confirmed = departureLocationSource.confirmsDeparture(anchor, steps = {
            // 0.3.2 (simulation audit C): counted from the car (the latest strong reading), not from
            // the unlock - approach steps after an unlock at 9 m, or steps before a long stay, are no
            // evidence of leaving (calibrated F05: a location jump "proved" a departure at 1 m).
            if (stepsAtLastStrongNear != null) stepsSince(stepsAtLastStrongNear, counterAtLastStrongNear)?.coerceAtLeast(0L)
            else stepsAtUnlock?.let { baseline ->
                motion.observedSteps?.let { (it - baseline).coerceAtLeast(0L) }
            }
        }, enabled = ::sameSession, bleCorroborated = ::departureBleCorroborated) && sameSession()
        if (confirmed) departureProofAtMs = clock.wallMs()
        else Logx.d("prox", "independent departure unverified: bounded live location proof unavailable " +
            "(stepsSinceUnlock=${stepsSinceUnlock() ?: "unavailable"} ${motion.stepSummary})")
        return confirmed
    }

    private fun requestVerifiedWalkAwayLock(reason: String) {
        if (lockConfirmationJob?.isActive == true || linkDepartureJob?.isActive == true ||
            farNoMotionCheck?.isActive == true ||
            lockJob?.isActive == true) return
        if (clock.wallMs() - lastShadowDepartureAtMs < SHADOW_REARM_MS) {
            decisionPolicy.onWalkAwayVerificationFailed()
            return
        }
        lockConfirmationJob = scope.launch {
            walkAwayRecovered = false
            if (confirmWalkAway() && armedUnlocked && _state.value.running &&
                config().proximityEnabled) {
                when (autoLockGate.onVerifiedDeparture()) {
                    AutomaticLockGate.Action.WARN_MANUAL_LOCK -> {
                        lastShadowDepartureAtMs = clock.wallMs()
                        shadowRecoveryLogged = false
                        val steps = stepsAtUnlock?.let { baseline ->
                            motion.observedSteps?.let { (it - baseline).coerceAtLeast(0L) }
                        }
                        val lastStrongAgeMs = if (lastStrongNearAtMs == 0L) null
                            else lastShadowDepartureAtMs - lastStrongNearAtMs
                        // Do not buzz the user every time the noisy RSSI repeats the same candidate.
                        val posted = if (!shadowDepartureWarned) {
                            shadowDepartureWarned = true
                            alerts.show(shadowDepartureEvent())
                        } else false
                        _state.value = _state.value.copy(lastAction =
                            "Possible departure · auto Lock OFF · lock manually")
                        Logx.w("prox", "SHADOW_DEPARTURE source=$reason " +
                            "ageSinceUnlockMs=${lastShadowDepartureAtMs - unlockObservedAtMs} " +
                            "stepsSinceUnlock=${steps ?: "unavailable"} " +
                            "strongestRssi=$strongestRssiSinceUnlock " +
                            "lastStrongAgeMs=${lastStrongAgeMs ?: "unavailable"} " +
                            "motion=${motion.state.value} reminderPosted=$posted; NO LOCK SENT")
                        decisionPolicy.onWalkAwayVerificationFailed()
                    }
                    AutomaticLockGate.Action.LOCK -> {
                        if (confirmPhysicalDeparture() && armedUnlocked &&
                            _state.value.running && config().proximityEnabled) {
                            Logx.d("prox", "departure corroborated by independent position; sending Lock")
                            linkDepartureJob?.cancel(); linkDepartureJob = null
                            startLockLoop(reason)
                        } else {
                            lastShadowDepartureAtMs = clock.wallMs()
                            val posted = if (!shadowDepartureWarned) {
                                shadowDepartureWarned = true
                                alerts.show(shadowDepartureEvent())
                            } else false
                            Logx.w("prox", "departure position unverified; NO LOCK SENT; " +
                                "manual Lock required, reminderPosted=$posted")
                            decisionPolicy.onWalkAwayVerificationFailed()
                            scheduleLinkDepartureCheck()
                        }
                    }
                }
            } else {
                decisionPolicy.onWalkAwayVerificationFailed()
                // Stopping after the walk can cancel RSSI confirmation. It cannot erase two fresh
                // positions outside the conservative clearance; check them independently - unless the
                // signal came back above the lock threshold: that is evidence of nearness, and the
                // 75 s location search after it "proved" a departure at 4 m (0.3.2, simulator F25).
                if (walkAwayRecovered) Logx.d("prox", "walk-away: signal recovered -> no location check")
                else scheduleLinkDepartureCheck()
            }
            lockConfirmationJob = null
        }
    }

    // ---------------- RSSI → distance → decision ----------------

    private fun onSample(rssi: Int) {
        recordReading(rssi)
        val cfg = config()
        val unlockThresh = cfg.sensitivityUnlockRssi
        val lockThresh = cfg.sensitivityLockRssi

        val prev = gattEma
        val delta = if (prev != null) rssi - prev else 0.0
        val jumping = kotlin.math.abs(delta) >= JUMP_DB
        val alpha = if (nextIntervalMs <= MONITOR_FAST_MS || jumping) ALPHA_FAST else ALPHA_SLOW
        val smoothedD = prev?.let { it + alpha * (rssi - it) } ?: rssi.toDouble()
        gattEma = smoothedD
        val smoothed = smoothedD.toInt()
        val dist = rssiToDistance(smoothed)

        if (relockRecoveryPending) {
            relockApproachEvidence.observe(clock.wallMs(), smoothed,
                motion.state.value == MotionMonitor.Motion.MOVING)
        } else relockApproachEvidence.clear()

        if (armedUnlocked && relockRecoveryPending && relockProbeJob?.isActive != true &&
            lockJob?.isActive != true && lockConfirmationJob?.isActive != true &&
            ble.state.value == DkBleManager.State.SESSION_READY &&
            smoothed >= unlockThresh + 3) {
            val elapsed = clock.elapsedMs()
            if (relockProbeStartedAtElapsedMs == 0L) relockProbeStartedAtElapsedMs = elapsed
            if (RelockProbeSchedule.expired(relockProbeStartedAtElapsedMs, elapsed)) {
                relockRecoveryPending = false
                Logx.d("prox", "vehicle relock not verified within recovery window; keeping unlock latch")
            } else if (RelockProbeSchedule.due(relockProbeLastAtElapsedMs, elapsed)) {
                relockProbeLastAtElapsedMs = elapsed
                val observedUnlockAt = unlockObservedAtMs
                relockProbeJob = scope.launch {
                    val first = runCatching { cloudLockSnapshot() }.getOrNull()
                    delay(400L)
                    val second = runCatching { cloudLockSnapshot() }.getOrNull()
                    val confirmed = RelockRecoveryEvidence.confirmsRelock(
                        first, second, observedUnlockAt, clock.wallMs())
                    if (confirmed && armedUnlocked && unlockObservedAtMs == observedUnlockAt &&
                        _state.value.running && ble.state.value == DkBleManager.State.SESSION_READY &&
                        (_state.value.smoothedRssi ?: Int.MIN_VALUE) >= unlockThresh + 3) {
                        relockRecoveryPending = false
                        armedUnlocked = false
                        resetLinkedWatch()
                        relockApproachEvidence.restoreAfterVerifiedRelock(
                            decisionPolicy, clock.wallMs(), unlockThresh)
                        Logx.d("prox", "vehicle reports a newer confirmed Lock; rearming guarded approach unlock")
                    } else {
                        val evidence = RelockRecoveryEvidence.diagnostic(first, second, observedUnlockAt)
                        Logx.d("prox", "vehicle relock unverified ($evidence); keeping unlock latch (no blind rearm)")
                    }
                    relockProbeJob = null
                }
            }
        }

        // Trend from the smoothed value vs the previous smoothed value. (Unlock no longer needs a
        // rising trend — arrival is detected from the zone crossing — but `receding` still gates lock.)
        val trend = if (prev != null) smoothedD - prev else 0.0
        val receding = trend < -TREND_DEADBAND
        lostRssi = smoothed    // diagnostic only; never sufficient to lock after link loss
        lostRssiSteps = motion.observedSteps
        lostRssiCounter = motion.counterSteps
        lostRssiAtMs = clock.wallMs()

        val now = clock.wallMs()
        var bleDepartureConfirmed = false
        if (armedUnlocked) {
            if (ble.state.value == DkBleManager.State.SESSION_READY)
                nearDepartureAnchor?.observe(smoothed, clock.elapsedMs())
            if (ble.state.value == DkBleManager.State.SESSION_READY)
                bleDepartureConfirmed = observeBleDeparture(rssi, smoothed)
            strongestRssiSinceUnlock = maxOf(strongestRssiSinceUnlock, rssi)
            if (rssi >= STRONG_NEAR_DIAGNOSTIC_RSSI) {
                markStrongNear()
                if (lastShadowDepartureAtMs != 0L && !shadowRecoveryLogged) {
                    shadowRecoveryLogged = true
                    Logx.d("prox", "SHADOW_RECOVERY strong signal after " +
                        "${now - lastShadowDepartureAtMs}ms; manual Lock still required")
                }
            }
        }
        if (armedUnlocked) { observeStationaryFar(rssi, lockThresh); maybeArmStepDepartureAlarm() }
        val armedDecision = if (armedUnlocked) decisionPolicy.onUnlockedSample(
            now, smoothed, motion.state.value == MotionMonitor.Motion.MOVING, lockThresh,
        ) else ProximityDecisionPolicy.ArmedDecision.NONE
        // Observe locked RSSI even while CONNECTED is still handshaking. The deep-sleep field trace
        // reached the door at -61 dBm, but SESSION_READY arrived ~3.4 s later after the signal had
        // weakened; gating observation on READY discarded the only reliable arrival evidence.
        val stepsNow = motion.observedSteps
        val walkingBySteps = stepStall.walking(now, stepsNow)
        val unlockQualified = !armedUnlocked && !needToUnlock && decisionPolicy.shouldUnlock(
            now, smoothed, motion.state.value == MotionMonitor.Motion.MOVING, unlockThresh,
            if (awayReturnSteps) stepsNow else null, walkingBySteps, stepStall.stalledForMs,
            stepsRising = stepsRisingNow(now, stepsNow),
        )
        if (!walkingBySteps && !stepStallLogged) {
            stepStallLogged = true
            Logx.d("prox", "step count stalled ${stepStall.stalledForMs / 1000.0} s -> walking approach paused")
        } else if (walkingBySteps) stepStallLogged = false
        if (decisionPolicy.consumeDeepFarReturnRearm()) {
            Logx.d("prox", "return after Lock recognised from signal (clearly far, now near rssi=$smoothed) " +
                "-> approach unlock rearmed")
        }
        if (armedDecision == ProximityDecisionPolicy.ArmedDecision.ARRIVAL_CONFIRMED) {
            Logx.d("prox", "arrival confirmed (rssi=$smoothed) — sustained-near guard passed")
        }

        val prevZone = _state.value.zone
        val zone = when {
            armedUnlocked && smoothed <= lockThresh -> Zone.FAR
            smoothed >= unlockThresh -> Zone.NEAR
            !armedUnlocked && smoothed < unlockThresh -> Zone.FAR
            else -> prevZone
        }
        _state.value = _state.value.copy(
            phase = Phase.MONITORING, source = Source.GATT,
            rawRssi = rssi, smoothedRssi = smoothed, distanceM = dist, zone = zone, error = null,
        )

        // RSSI-steadiness anchor — the FAR "still" signal ONLY on devices with no motion sensor at all.
        val ref = steadyRef
        if (ref == null || kotlin.math.abs(smoothed - ref) > STEADY_BAND_DB) { steadyRef = smoothedD; steadySinceMs = now }
        val steady = now - steadySinceMs >= STEADY_HOLD_MS

        val near = dist < NEAR_DIST_M
        // Two-state cadence + wakelock machine (the design the user specified):
        //   NEAR (< 6 m, engaged): wakelock HELD. Poll eases 200 → 500 → 1000 ms the longer you hold
        //     still (still = distance within NEAR_STILL_BAND_M); any real move resets it to 200 ms.
        //   FAR (≥ 6 m): with a motion sensor we RELEASE the wakelock and sleep until the sensor reports
        //     movement (the loop's sleepUntilMotion); while it says MOVING we poll at dist / walking-speed
        //     and hold the wakelock for at most ~5× the walk-time (withinApproachBackstop) — stop on the
        //     way and the sensor goes STILL, so we drop the wakelock and sleep again. With NO sensor we
        //     can't sleep safely, so we fall back to blind walk-time polling with the wakelock held.
        if (near) {
            _wakeLockNeeded.value = true
            farAsleep = false
            farApproachDeadline = 0L; farApproachRefDist = Double.MAX_VALUE
            val nref = nearRefDist
            if (nref == null || kotlin.math.abs(dist - nref) > NEAR_STILL_BAND_M) { nearRefDist = dist; nearStillSinceMs = now }
            val stillFor = now - nearStillSinceMs
            nextIntervalMs = when {
                stillFor >= NEAR_EASE_SLOW_MS -> MONITOR_NEAR_SLOW_MS
                stillFor >= NEAR_EASE_MID_MS  -> MONITOR_NEAR_MID_MS
                else                          -> MONITOR_FAST_MS
            }
        } else {
            nearRefDist = null
            when {
                ble.state.value == DkBleManager.State.CONNECTED -> {
                    // Do not enter FAR sleep halfway through the handshake. Keep sampling for the
                    // short bring-up window so strong door-range evidence survives until READY.
                    _wakeLockNeeded.value = true; farAsleep = false
                    nextIntervalMs = MONITOR_FAST_MS
                }
                !motion.hasSource -> {
                    // No sensor to wake us: keep the wakelock; poll the blind walk-time formula (or the
                    // 30 s ceiling once RSSI says we've been steady for a while). Degraded path.
                    _wakeLockNeeded.value = true; farAsleep = false
                    nextIntervalMs = if (steady) MONITOR_STILL_NOSENSOR_MS else blindInterval(dist)
                }
                LinkedApproachWatch.holdAwake(ble.state.value == DkBleManager.State.SESSION_READY,
                    armedUnlocked, motionWakesOnSteps(), smoothed, motion.hasStepAssist,
                    linkedWatchForMs()) &&
                    motion.state.value != MotionMonitor.Motion.MOVING -> {
                    // Linked, locked, signal not far, but the motion source cannot wake us on steps:
                    // keep the CPU so the step assist can report MOVING, and keep sampling.
                    _wakeLockNeeded.value = true; farAsleep = false
                    // A stop ends the previous walk: the next MOVING starts a fresh walk-time backstop.
                    // Field 04/10 12:02 (0.1.66): the backstop of the walk out to 10 m expired during
                    // the 40 s stop, the walk back went to far-sleep and the car opened 5 s late.
                    farApproachDeadline = 0L; farApproachRefDist = Double.MAX_VALUE
                    nextIntervalMs = LinkedApproachWatch.WATCH_POLL_MS
                }
                motion.state.value == MotionMonitor.Motion.MOVING && withinApproachBackstop(now, dist) -> {
                    // Sensor says you're walking (still within the walk-time backstop): approach. Sample
                    // at full speed while movement is real. A 2–3 s cadence let a brisk walker cover the
                    // entire 3–4 m unlock zone between samples (field tests: 3–5 s late at the door).
                    // This reads RSSI on the live GATT link; it is NOT permanent BLE scanning. Motion
                    // stopping or the bounded approach window expiring returns us to far-sleep.
                    _wakeLockNeeded.value = true; farAsleep = false
                    nextIntervalMs = MONITOR_FAST_MS
                }
                else -> {
                    // Sensor says still (or the backstop expired): drop the wakelock and sleep until it fires.
                    // Not while the car is open: the armed loop owns the wakelock there (the 10-min still
                    // rule). A pocket at the car reads -79..-83, "6-9 m" by the display formula; the CPU
                    // slept through the walk away and no alarm came (0.3.2, simulator stress F22/F15).
                    if (!armedUnlocked) { _wakeLockNeeded.value = false; farAsleep = true }
                    farApproachDeadline = 0L; farApproachRefDist = Double.MAX_VALUE
                }
            }
        }
        // Liveness ping only while actively closing in on foot (fast NEAR cadence).
        val approachState = near && nextIntervalMs == MONITOR_FAST_MS

        val cadence = when {
            near     -> "near-${nextIntervalMs}ms"
            farAsleep -> "far-sleep"
            nextIntervalMs == LinkedApproachWatch.WATCH_POLL_MS &&
                motion.state.value != MotionMonitor.Motion.MOVING -> "linked-watch-${nextIntervalMs}ms"
            else     -> "far-approach-${nextIntervalMs}ms"
        }
        if (cadence != lastCadence) {
            lastCadence = cadence
            Logx.d("prox", "cadence -> $cadence (~${"%.1f".format(dist)}m rssi=$smoothed motion=${motion.state.value} wl=${_wakeLockNeeded.value})")
        }

        Logx.d("prox", "rssi=$rssi ema=$smoothed ~${"%.1f".format(dist)}m " +
            "trend=${"%+.1f".format(trend)} zone=$zone armed=$armedUnlocked " +
            "thr(u/l)=$unlockThresh/$lockThresh motion=${motion.state.value} next=${nextIntervalMs}ms")

        val cooling = lastTriggerMs != 0L && clock.wallMs() - lastTriggerMs < ACTION_COOLDOWN_MS
        if (cooling || actionInFlight) return
        // Never actuate until the DK session is fully up. Firing unlock during CONNECTED/handshake
        // raced the auto-handshake ("write failed for 0x0101" -> reset -> reconnect churn); waiting
        // for SESSION_READY makes the unlock instant instead. Cadence/zone above still update.
        if (ble.state.value != DkBleManager.State.SESSION_READY) return

        // UNLOCK on ARRIVAL: near enough AND we weren't already sitting near on the previous sample —
        // i.e. a clean FAR->NEAR crossing, OR a FRESH session (prevZone == UNKNOWN: the presence scan
        // just brought us into range / we just (re)connected). This is the fix for "woke the phone AT
        // the car and nothing happened": the handshake often only reaches SESSION_READY once you're
        // already standing still, so the old rising-RSSI-trend requirement missed it. The armedUnlocked
        // latch still guarantees a single unlock per approach (reconnects while parked won't re-fire).
        if (!armedUnlocked && !needToUnlock && unlockQualified) {
            needToUnlock = true
            decisionPolicy.onPendingUnlockStarted()
            // A liveness command must never occupy the shared reply slots while unlock starts.
            pingJob?.cancel(); pingJob = null; pingInFlight = false
            Logx.d("prox", "approach-unlock ARM (rssi=$smoothed ~${"%.1f".format(dist)}m prevZone=$prevZone " +
                "route=${decisionPolicy.unlockRoute}) — confirmed-unlock loop")
            startUnlockLoop()
            return
        }
        // WALK-AWAY cancels a pending unlock loop — only once we cross to FAR (≤ lockThresh). The NEAR/FAR
        // hysteresis gap is the "smoothing" so it can't flap while you hover at the door; cancelling also
        // stops any stale mid-retry command dead.
        val cancelPendingUnlock = needToUnlock && decisionPolicy.shouldCancelPendingUnlock(
            nowMs = now,
            rssi = smoothed,
            moving = motion.state.value == MotionMonitor.Motion.MOVING,
            unlockThreshold = unlockThresh,
            lockThreshold = lockThresh,
        )
        if (cancelPendingUnlock) {
            Logx.d("prox", "sustained walk-away — cancelling unlock loop (rssi=$smoothed)")
            needToUnlock = false
            unlockJob?.cancel(); unlockJob = null
        }
        // BLE + walking route: continuous fresh evidence of leaving, independent of GNSS geometry.
        // It supersedes a GNSS confirmation that may still be waiting for a usable fix pair.
        if (armedUnlocked && bleDepartureConfirmed && lockJob?.isActive != true) {
            lockConfirmationJob?.cancel(); lockConfirmationJob = null
            linkDepartureJob?.cancel(); linkDepartureJob = null
            departureProofAtMs = clock.wallMs()
            Logx.d("prox", "ble departure lock authorized (rssi=$smoothed kind=${bleRoute?.confirmKind ?: "none"}); sending Lock")
            startLockLoop("ble-departure")
            return
        }
        // A lock now requires time-based, moving-and-receding evidence from the shared policy. The same
        // rule is used by armedWatch, so its periodic safety sample cannot bypass this guard.
        if (armedUnlocked && armedDecision == ProximityDecisionPolicy.ArmedDecision.LOCK) {
            Logx.d("prox", "walk-away candidate (rssi=$smoothed ~${"%.1f".format(dist)}m) -> verifying")
            lastWalkAwayCandidateAtMs = now
            armDepartureAlarm()
            requestVerifiedWalkAwayLock("walk-away-lock")
            return
        }

        // Run liveness last: an unlock/lock decision for this sample always wins. RealDkSession also
        // serializes commands, protecting the single shared 0x0111/0x0112 waiter pair.
        if (approachState && !needToUnlock && ble.state.value == DkBleManager.State.SESSION_READY) maybePing()
        else pingFailStreak = 0
    }

    /**
     * Confirmed-unlock retry loop (replaces the old one-shot). Runs on its own coroutine until:
     *  - the car CONFIRMS the unlock (latch [armedUnlocked], done), or
     *  - we walk away ([needToUnlock] cleared + this job cancelled by onSample), or
     *  - we exhaust MAX_UNLOCK_ATTEMPTS.
     * Each attempt reads the car's ACTUAL answer via [DkSession.control]: a write-fail, no-response, or
     * reject all tear the link down (reusing the known device) and try again; only a confirm ends it.
     * The "took the phone out, unlock errored, had to toggle BT" wedge is exactly a no-response/reject,
     * so it now self-heals by reconnecting and retrying instead of silently "succeeding".
     */
    private fun startUnlockLoop() {
        if (unlockJob?.isActive == true) return
        unlockJob = scope.launch {
            var attempt = 0
            val loopStartedAtMs = clock.elapsedMs()
            while (isActive && needToUnlock && attempt < MAX_UNLOCK_ATTEMPTS) {
                attempt++
                if (clock.elapsedMs() - loopStartedAtMs > UNLOCK_LOOP_MAX_MS) {
                    Logx.w("prox", "unlock: no confirmation within ${UNLOCK_LOOP_MAX_MS / 1000} s - stopping (no stale retry)")
                    break
                }
                if (ble.state.value != DkBleManager.State.SESSION_READY) {
                    if (!awaitState(setOf(DkBleManager.State.SESSION_READY), UNLOCK_SESSION_WAIT_MS)) {
                        if (!needToUnlock) break
                        resetLink(); continue
                    }
                }
                if (!needToUnlock) break
                // Plan K6: a retry (after a failed attempt, a reset or a reconnect) needs fresh
                // evidence that the phone is still near - not a decision taken seconds ago, possibly
                // by a user who has since turned away (a reconnect at 15 m must not open the car).
                if (attempt > 1) {
                    val fresh = ble.pollRemoteRssi()
                    if (fresh == null || fresh < config().sensitivityUnlockRssi) {
                        Logx.w("prox", "unlock retry #$attempt without a fresh near reading (rssi=${fresh ?: "none"}) - stopping")
                        break
                    }
                }
                val r = runCatching { ble.session.control(DkProtocol.CTRL_UNLOCK, UNLOCK_ACK_TIMEOUT_MS) }
                    .getOrDefault(ControlResult.WRITE_FAILED)
                Logx.d("prox", "unlock attempt #$attempt -> $r")
                _state.value = _state.value.copy(lastAction = "unlock #$attempt · $r")
                if (r == ControlResult.CONFIRMED) {
                    recordUnlockConfirmed("proximity")
                    break
                }
                if (!needToUnlock) break
                resetLink()                    // write-fail / no-response / reject → clear the wedge
                delay(UNLOCK_RETRY_DELAY_MS)
            }
            if (!armedUnlocked && attempt >= MAX_UNLOCK_ATTEMPTS)
                Logx.w("prox", "unlock: gave up after $attempt attempts")
            needToUnlock = false
            unlockJob = null
        }
    }

    /**
     * Confirmed-lock loop (walk-away). Locking is MORE important than unlocking — we must never leave the
     * car open — so this is at least as aggressive: it retries the BLE lock reading the car's actual
     * answer ([DkSession.control]) up to MAX_LOCK_ATTEMPTS, reconnecting the known device between failures.
     * If BLE still won't confirm (link genuinely gone because you walked out of range), it falls back to a
     * CLOUD lock so the car locks regardless. Runs to completion — unlike unlock there's nothing to cancel.
     */
    private fun startLockLoop(reason: String) {
        // A second boundary guards future call sites. In observe mode no automatic BLE or cloud
        // lock path may execute; manual commands from VehicleControl remain available.
        check(autoLockGate.onVerifiedDeparture() == AutomaticLockGate.Action.LOCK) {
            "Automatic Lock is disabled in this test build"
        }
        if (lockJob?.isActive == true) return
        // A pending unlock loop is now moot (we've decided you're leaving) — stop it fighting us.
        needToUnlock = false; unlockJob?.cancel(); unlockJob = null
        // Terminal for this BLE session: a post-lock RSSI rebound must never emit CTRL_UNLOCK.
        // A genuine later arrival is rearmed only by link-down + a fresh hardware presence hit.
        decisionPolicy.onDepartureLockStarted(stepsAtLastStrongNear ?: motion.observedSteps)
        lockJob = scope.launch {
            val lockStartedAtMs = clock.wallMs()
            lastTriggerMs = lockStartedAtMs   // start the action cooldown
            var confirmed = false
            var attempt = 0
            var notExecuted = 0
            var lastFreshRssi: Int? = null
            val maxAttempts = if (ble.state.value == DkBleManager.State.SESSION_READY)
                MAX_LOCK_ATTEMPTS else 1 // a lost radio should reach the cloud fallback promptly
            while (isActive && attempt < maxAttempts) {
                // Plan H5: the car reported locked since this Lock started (its own push, a fob, a
                // manual Lock): that is the confirmation, not a reason to retry or alarm.
                if (lastCarLockedReportAtMs >= lockStartedAtMs) {
                    confirmed = true
                    Logx.d("prox", "$reason: the car reports locked (status push) -> Lock confirmed")
                    decisionPolicy.onAutomaticLockProvenDeparture(clock.wallMs())
                    departureAlarmJob?.cancel(); departureAlarmJob = null
                    alerts.silenceAlarm()
                    break
                }
                if (clock.wallMs() - departureProofAtMs > 5_000L &&
                    !bleLockAuthorized() && !confirmPhysicalDeparture()) {
                    val posted = alerts.show(LockAlertPolicy.Event.DEPARTURE_UNVERIFIED)
                    Logx.w("prox", "$reason: departure proof expired before BLE Lock; " +
                        "NO LOCK SENT; reminderPosted=$posted")
                    lockJob = null
                    return@launch
                }
                attempt++
                if (ble.state.value != DkBleManager.State.SESSION_READY &&
                    !awaitState(setOf(DkBleManager.State.SESSION_READY), LOCK_SESSION_WAIT_MS)) {
                    // No live session this round — kick a reconnect to the known car and try the next attempt.
                    if (!ble.reconnectLast()) runCatching { ble.connect(null) }
                    continue
                }
                // One independent fresh reading immediately before actuating: a user standing at
                // the car again revokes the BLE proof and refreshes the strong-near veto. A held
                // BLE proof authorizes this attempt only together with such a reading; a missing
                // reading (timeout, busy poll) authorizes nothing (third review F1).
                var bleProofFresh = false
                if (ble.state.value == DkBleManager.State.SESSION_READY) {
                    val fresh = ble.pollRemoteRssi()
                    lastFreshRssi = fresh
                    if (fresh != null) {
                        bleProofFresh = bleRoute?.freshBleLockAuthorized(
                            clock.elapsedMs(), fresh) == true
                        bleRoute?.let(::logBleRouteRevocation)
                        recordReading(fresh)
                        if (fresh >= STRONG_NEAR_DIAGNOSTIC_RSSI) markStrongNear()
                    }
                }
                lockVeto(lastFreshRssi.takeIf { ble.state.value == DkBleManager.State.SESSION_READY })?.let { why ->
                    spendBleProof(lastFreshRssi)
                    val posted = alerts.show(LockAlertPolicy.Event.PROXIMITY_RECOVERED_BEFORE_LOCK)
                    Logx.w("prox", "$reason: Lock vetoed - $why; user at the car; NO LOCK SENT; reminderPosted=$posted")
                    lockJob = null
                    return@launch
                }
                // The 5 s grace only applies to GNSS-started loops: a BLE-route Lock needs the
                // fresh confirming reading on EVERY attempt, including the first one.
                if ((reason == "ble-departure" || lastStrongNearAtMs > departureProofAtMs ||
                    clock.wallMs() - departureProofAtMs > 5_000L) &&
                    !bleProofFresh && !confirmPhysicalDeparture()) {
                    // Back at the car: inform silently. Otherwise the departure is unverified and
                    // the car is still open while the user leaves: sound the alarm.
                    val posted = alerts.show(
                        if (lastStrongNearAtMs > departureProofAtMs) LockAlertPolicy.Event.PROXIMITY_RECOVERED_BEFORE_LOCK
                        else LockAlertPolicy.Event.DEPARTURE_UNVERIFIED)
                    Logx.w("prox", "$reason: proximity recovered before BLE Lock; " +
                        "NO LOCK SENT; reminderPosted=$posted")
                    lockJob = null
                    return@launch
                }
                val r = runCatching { ble.session.control(DkProtocol.CTRL_LOCK, LOCK_ACK_TIMEOUT_MS) }
                    .getOrDefault(ControlResult.WRITE_FAILED)
                Logx.d("prox", "$reason: BLE lock attempt #$attempt -> $r")
                if (r == ControlResult.CONFIRMED) {
                    confirmed = true
                    Logx.d("prox", "auto lock confirmed ($reason): the car reports locked")
                    // The guard of the walk-back return starts at the confirmed Lock (review 0.1.63).
                    decisionPolicy.onAutomaticLockProvenDeparture(clock.wallMs())
                    // The car acknowledged: stop any alarm now, not after the cloud catches up.
                    departureAlarmJob?.cancel(); departureAlarmJob = null
                    alerts.silenceAlarm()
                    break
                }
                if (r == ControlResult.NOT_EXECUTED) {
                    // The link works; the car received the Lock but stayed open (field 05/10 16:08).
                    // One more try over the key, then the cloud below; no link reset.
                    if (++notExecuted >= MAX_NOT_EXECUTED_LOCKS) break
                    delay(UNLOCK_RETRY_DELAY_MS)
                    continue
                }
                // Field 12/10 17:52:37: proof at -102, the key Lock got no answer, four link resets
                // and status-133 reconnects took 30 s before the cloud locked. At the edge of the
                // link the key will not get through: go to the cloud while the proof is fresh.
                val fr = lastFreshRssi
                // The cloud path below re-verifies the departure before its request, whatever the route.
                // 0.3.2 (simulator F27): deep far (lock - 8 dB) or a link already gone counts as the edge;
                // five link resets there took ~27 s and the car locked at 54-57 m.
                val atEdge = (fr != null && (fr <= FAR_LINK_CLOUD_RSSI || fr <= config().sensitivityLockRssi - EDGE_CLOUD_MARGIN_DB)) ||
                    ble.state.value != DkBleManager.State.SESSION_READY
                if (atEdge && (r == ControlResult.NO_RESPONSE || r == ControlResult.WRITE_FAILED)) {
                    Logx.w("prox", "$reason: key Lock unanswered at the edge of the link (rssi=$fr) -> cloud now")
                    break
                }
                resetLink()   // write-fail / no-response / reject → clear the wedge and retry
                delay(UNLOCK_RETRY_DELAY_MS)
            }
            if (!isActive || !armedUnlocked) {
                lockJob = null
                return@launch
            }
            if (!confirmed) {
                // The user may have returned while a BLE reconnect was failing. A position that
                // proved departure before those retries cannot authorize a later cloud command;
                // verifyCloudLock checks fresh departure immediately before EACH request.
                Logx.w("prox", "$reason: BLE lock unconfirmed after $attempt attempts — falling back to CLOUD lock")
                // One full failed actuation per epoch for the BLE route: without this, evidence
                // that keeps holding restarts the loop every cooldown, repeating resets, cloud
                // requests and notifications on the same proof.
                // A fresh BLE departure proof backs the cloud Lock when the key link failed right
                // after it (field 05/10 20:44: proof at -104, the Lock write failed and the link
                // dropped; GNSS could not confirm, so nothing locked the car).
                val cloudStatusLocked = verifyCloudLock(reason,
                    bleProofAtMs = if (reason == "ble-departure") departureProofAtMs else 0L)
                // Plan H5: the user's manual Lock (or the car's own, handled there) ended this epoch
                // meanwhile: nothing left to warn about.
                if (!armedUnlocked) {
                    Logx.d("prox", "$reason: the epoch ended during the cloud fallback -> no warning")
                    lockJob = null
                    return@launch
                }
                // Plan H5: the car's own "locked" push since this Lock started is the confirmation
                // (its self-lock handler stands aside while a Lock runs).
                val reportedLocked = lastCarLockedReportAtMs >= lockStartedAtMs
                if (reason == "ble-departure") {
                    clearBleDeparture()
                    Logx.w("prox", "ble departure route suspended for this epoch after unconfirmed Lock")
                }
                // Repeated cloud status can be cached; it cannot replace the missing BLE receipt.
                // Only a cloud report newer than this Lock attempt keeps the warning silent.
                // A "locked" report newer than our own unlock is a lock after it - also when the car
                // was already locked (fob, handle) before this cloud Lock (simulator F40: a false
                // "not locked" alarm because the cloud had nothing newer than this Lock to report).
                val freshCloudLocked = reportedLocked || cloudStatusLocked && verifyFreshLockStatus(
                    if (unlockObservedAtMs > 0L) minOf(unlockObservedAtMs, lockStartedAtMs) else lockStartedAtMs) ||
                    // 0.3.2: no request went out (vetoed / unverified), but the cloud already reports
                    // a lock after our unlock (fob, handle, the car's own relock while the link was down).
                    (!cloudStatusLocked && cloudLockedSinceUnlockSnapshot())
                // This outcome is the warning for this Lock; a waiting link-loss alert must not
                // override it (it would sound after a cloud-confirmed Lock, or sound twice).
                pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
                departureAlarmJob?.cancel(); departureAlarmJob = null
                if (freshCloudLocked) {
                    // The car reports locked after this Lock: it IS locked. Without this the app kept
                    // believing "unlocked", retried and then sounded a false alarm (simulator 05/10).
                    if (armedUnlocked) {
                        armedUnlocked = false
                        resetLinkedWatch()
                        carSelfLock.onLockedByUs()
                        decisionPolicy.onAutomaticLockProvenDeparture(clock.wallMs())
                    }
                    com.openzeekr.app.remote.LocalLockEvidence.onKeyConfirmed(locked = true)
                    unverifiedLockAlertRaised = false
                    alerts.clear()
                    alerts.carLocked()
                    Logx.w("prox", if (reportedLocked) "$reason: locked; the car reports it (status push)"
                        else "$reason: locked via cloud; a newer cloud report confirms it")
                    _state.value = _state.value.copy(lastAction = "$reason · locked via cloud ✓")
                } else if (cloudLockWithheldUnverified && userBackAtCar()) {
                    // The cloud Lock was withheld because the departure no longer held, and the
                    // phone reads strong at the car again: the user came back (simulator F28, a
                    // late location proof while walking back). Open car + user present is no alarm;
                    // a new departure starts the normal check again.
                    Logx.w("prox", "$reason: Lock withheld, user back at the car (strong signal) -> no alarm")
                    _state.value = _state.value.copy(lastAction = "$reason · back at the car; not locked")
                } else {
                    val posted = alerts.show(LockAlertPolicy.Event.AUTO_LOCK_UNCONFIRMED)
                    unverifiedLockAlertRaised = true
                    Logx.w("prox", "$reason: BLE lock unconfirmed; cloud status locked=$cloudStatusLocked; notification posted=$posted")
                    _state.value = _state.value.copy(lastAction =
                        "$reason · ${if (cloudStatusLocked) "cloud reports LOCKED (2×); physical lock unverified" else "LOCK NOT VERIFIED ✗"}; check car")
                }
            } else {
                armedUnlocked = false
                resetLinkedWatch()
                pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
                // CONFIRMED now means the car itself reported locked (LockExecutionWatch); the
                // cloud lags by minutes and is no longer needed to verify it.
                unverifiedLockAlertRaised = false
                alerts.clear()
                _state.value = _state.value.copy(lastAction = "$reason · vehicle reports locked ✓")
            }
            lockJob = null
        }
        // Plan H8: the loop waits for the session, GNSS and the cloud; with the CPU asleep those
        // timers stop (stress F34: stuck after "sending Lock" at 80 m while the alarm waited on it).
        val loopWl = acquireSafetyWakelock(LOCK_LOOP_WAKE_MS)
        lockJob?.invokeOnCompletion { releaseSafetyWakelock(loopWl) }
    }

    private suspend fun verifyFreshLockStatus(lockStartedAtMs: Long): Boolean {
        // The cloud reports a lock seconds to tens of seconds late: look for ~35 s before alarming.
        return withTimeoutOrNull(45_000L) {
            repeat(10) {
                delay(3_000L)
                val first = runCatching { cloudLockSnapshot() }.getOrNull()
                delay(400L)
                val second = runCatching { cloudLockSnapshot() }.getOrNull()
                if (RelockRecoveryEvidence.confirmsRelock(first, second, lockStartedAtMs,
                        clock.wallMs())) return@withTimeoutOrNull true
            }
            false
        } ?: false
    }

    /** Set by [verifyCloudLock]: no cloud Lock was sent because the departure no longer held. */
    private var cloudLockWithheldUnverified = false

    /** A live key session reading strong-near right now (not a stale value). */
    private suspend fun userBackAtCar(): Boolean {
        if (ble.state.value != DkBleManager.State.SESSION_READY) return false
        val fresh = ble.pollRemoteRssi() ?: return false
        return fresh >= STRONG_NEAR_DIAGNOSTIC_RSSI
    }

    /** A successful HTTP response acknowledges the request, not a physical door lock. */
    private suspend fun verifyCloudLock(reason: String, bleProofAtMs: Long = 0L): Boolean {
        cloudLockWithheldUnverified = false
        val wl = acquireSafetyWakelock()
        // The level when the first request went out: a later request needs the user still away.
        var levelAtFirstRequest: Double? = null
        var firstRequestSentAtMs = 0L
        var firstRequestAcceptedSentAtMs = 0L
        try {
            repeat(CLOUD_LOCK_MAX_REQUESTS) { requestIndex ->
                // 0.3.2 (simulator stress F32/F02): the first request was accepted but the status
                // still said "unlocked" - the cloud reports a lock seconds to tens of seconds late.
                // Look for that report before a second request (or the alarm).
                if (requestIndex > 0 && firstRequestAcceptedSentAtMs != 0L &&
                    verifyFreshLockStatus(firstRequestAcceptedSentAtMs)) {
                    Logx.d("prox", "$reason: late cloud status confirms the first request")
                    return true
                }
                val accepted = DepartureCheckedLockAttempt.send(
                    enabled = { armedUnlocked && _state.value.running &&
                        config().proximityEnabled },
                    // Cloud Lock needs fresh GNSS departure: a BLE proof cannot be revoked by a
                    // return while the radio is down (third review F2/F4). Exception: a BLE proof
                    // of at most [BLE_PROOF_CLOUD_MS] (walking, very deep far) - too short a time
                    // to be back at the car from where the link gave out.
                    departure = departure@{
                        // Plan H6/K1: a reading at the car since the proof, or right now, vetoes it.
                        val fresh = if (ble.state.value == DkBleManager.State.SESSION_READY)
                            ble.pollRemoteRssi()?.also { noteFreshReading(it) } else null
                        lockVeto(fresh)?.let { why ->
                            spendBleProof(fresh)
                            Logx.w("prox", "$reason: cloud Lock vetoed - $why")
                            return@departure false
                        }
                        // 0.3.1 (stress F32): the first request locked the car, but the cloud
                        // status lagged and still said "unlocked"; a second request went out while
                        // the user walked back and executed 13 s later with the user at the door.
                        val level = gattEma
                        val first = levelAtFirstRequest
                        if (requestIndex > 0 && level != null && first != null && level >= first + LOCK_VETO_RISE_DB) {
                            spendBleProof(fresh)
                            Logx.w("prox", "$reason: cloud Lock request #${requestIndex + 1} vetoed - signal rose " +
                                "${(level - first).toInt()} dB since the first request (walking back)")
                            return@departure false
                        }
                        if (requestIndex == 0) levelAtFirstRequest = level
                        // Only the first request: a later one went out 20-30 s after the proof, while
                        // the user may be walking back (stress F32/F02: executed at 6-12 m).
                        val bleProofFresh = requestIndex == 0 && bleProofAtMs != 0L &&
                            clock.wallMs() - bleProofAtMs <= BLE_PROOF_CLOUD_MS && lastStrongNearAtMs < bleProofAtMs
                        if (bleProofFresh) Logx.d("prox", "$reason: cloud Lock backed by a fresh BLE departure proof")
                        bleProofFresh || confirmPhysicalDeparture()
                    },
                    command = {
                        if (requestIndex == 0) firstRequestSentAtMs = clock.wallMs()
                        try { cloudLock() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                    },
                ) ?: run {
                    Logx.w("prox", "$reason: departure unverified before cloud request " +
                        "#${requestIndex + 1}; NO LOCK SENT; manual Lock required")
                    cloudLockWithheldUnverified = true
                    return false
                }
                Logx.d("prox", "$reason: cloud lock request #${requestIndex + 1} " +
                    if (accepted) "accepted; checking vehicle status" else "rejected")
                if (!accepted) return@repeat
                if (requestIndex == 0) firstRequestAcceptedSentAtMs = firstRequestSentAtMs
                var consecutiveLocked = 0
                repeat(CLOUD_LOCK_STATUS_POLLS) {
                    delay(CLOUD_LOCK_STATUS_POLL_MS)
                    val state = runCatching { cloudIsLocked() }.getOrNull()
                    Logx.d("prox", "$reason: cloud lock vehicle state=" +
                        when (state) { true -> "LOCKED"; false -> "UNLOCKED"; null -> "unknown" })
                    if (state == true) {
                        if (++consecutiveLocked >= 2) return true
                    } else consecutiveLocked = 0
                }
            }
            Logx.w("prox", "$reason: cloud lock status not verified; physical lock state unknown")
            return false
        } finally {
            releaseSafetyWakelock(wl)
        }
    }

    /** A bounded partial wakelock for cloud checks or an unverified-lock warning after a BLE drop. */
    private fun acquireSafetyWakelock(timeoutMs: Long = CLOUD_NET_WAKELOCK_MS): Any? = wakeLocks.acquire(timeoutMs)

    private fun releaseSafetyWakelock(wl: Any?) = wakeLocks.release(wl)

    /** Tear the link down and re-establish it (reuse the KNOWN device, no rescan), for the unlock loop. */
    private suspend fun resetLink() {
        Logx.d("prox", "unlock: resetting the BLE link")
        runCatching { ble.disconnect() }
        awaitState(setOf(DkBleManager.State.IDLE, DkBleManager.State.ERROR), RESET_SETTLE_MS)
        if (!ble.reconnectLast()) runCatching { ble.connect(null) }
        awaitState(setOf(DkBleManager.State.SESSION_READY), RESET_RECONNECT_MS)
    }

    /** Suspend until [ble] state is one of [targets] or [timeoutMs] elapses; true if it reached one. */
    private suspend fun awaitState(targets: Set<DkBleManager.State>, timeoutMs: Long): Boolean {
        val deadline = clock.wallMs() + timeoutMs
        while (clock.wallMs() < deadline) {
            if (ble.state.value in targets) return true
            delay(120)
        }
        return ble.state.value in targets
    }

    /** Approach-only liveness ping: send a non-actuating 0x0110 (the car always acks with 0x0111 and
     *  rejects the RPA byte) at most once per PING_INTERVAL_MS. ANY reply clears the streak;
     *  PING_FAIL_STREAK silent pings in a row means the control path is wedged → reconnect. */
    private fun maybePing() {
        val now = clock.wallMs()
        if (pingInFlight || now - lastPingMs < PING_INTERVAL_MS) return
        lastPingMs = now; pingInFlight = true
        pingJob = scope.launch {
            try {
                val ok = runCatching { ble.session.ping(PING_TIMEOUT_MS) }.getOrDefault(false)
                if (ok) { pingFailStreak = 0; return@launch }
                pingFailStreak++
                Logx.w("prox", "liveness ping failed (streak=$pingFailStreak/$PING_FAIL_STREAK)")
                if (pingFailStreak >= PING_FAIL_STREAK) { pingFailStreak = 0; forceReconnect() }
            } finally {
                pingInFlight = false
                pingJob = null
            }
        }
    }

    /** Tear a wedged link down and re-establish it. We KNOW exactly which car we just dropped, so we
     *  reconnect straight to that device ([DkBleManager.reconnectLast], no scan); only if there's no
     *  cached device do we fall back to a scan. Skipped while an action is in flight so it can't fight
     *  an unlock's own reset retry, and guarded so only one reconnect runs at a time. */
    @Volatile private var reconnecting = false
    private fun forceReconnect() {
        if (actionInFlight || reconnecting) return
        reconnecting = true
        Logx.w("prox", "forcing reconnect to the known car (no rescan)")
        scope.launch {
            try {
                runCatching { ble.disconnect() }
                awaitState(setOf(DkBleManager.State.IDLE, DkBleManager.State.ERROR), RESET_SETTLE_MS)
                if (!ble.reconnectLast()) runCatching { ble.connect(null) }
            } finally { reconnecting = false }
        }
    }

    /** Log-distance path loss: d = 10^((txPower@1m − rssi)/(10·n)). Display/log only — calibrate. */
    private fun rssiToDistance(rssi: Int): Double =
        10.0.pow((TX_POWER_1M - rssi) / (10.0 * PATH_LOSS_N))

    /** FAR poll interval bounded by physics: dist / walking-speed, clamped so we neither hammer the link
     *  when nearly on top of the car nor doze past a BLE-range walk. */
    private fun blindInterval(dist: Double): Long =
        (dist / WALK_SPEED_MPS * 1000.0).toLong().coerceIn(MONITOR_BLIND_MIN_MS, MONITOR_STILL_NOSENSOR_MS)

    /** True while a FAR approach may keep the wakelock: we hold it for at most ~5× the walk-time estimate
     *  from the closest distance seen. Making inward progress (dist drops past the still band) re-arms the
     *  deadline from the new distance, so a genuine long walk isn't cut off; a stall beyond the window
     *  returns false → the caller treats it as still and sleeps. */
    // Start of the current linked watch without MOVING (elapsed); 0 = not watching.
    private var linkedWatchSinceElapsedMs = 0L
    // Fresh windows granted after a real link absence in the current lock period.
    private var linkedWatchRefills = 0

    /** A new lock period (or an unlock) starts the linked watch from scratch. */
    private fun resetLinkedWatch() { linkedWatchSinceElapsedMs = 0L; linkedWatchRefills = 0 }

    /** How long the linked watch has held the CPU without MOVING; counts only while linked and locked. */
    private fun linkedWatchForMs(): Long {
        val now = clock.elapsedMs()
        linkedWatchSinceElapsedMs = LinkedApproachWatch.watchStart(linkedWatchSinceElapsedMs, now,
            ble.state.value == DkBleManager.State.SESSION_READY, armedUnlocked,
            motion.state.value == MotionMonitor.Motion.MOVING)
        return if (linkedWatchSinceElapsedMs == 0L) 0L else now - linkedWatchSinceElapsedMs
    }

    /** True when the motion source itself wakes the CPU per step / on motion (no linked watch needed). */
    private fun motionWakesOnSteps(): Boolean =
        motion.source == MotionMonitor.Source.STEP || motion.source == MotionMonitor.Source.SENSORS

    private fun withinApproachBackstop(now: Long, dist: Double): Boolean {
        if (farApproachDeadline == 0L || dist < farApproachRefDist - NEAR_STILL_BAND_M) {
            farApproachRefDist = dist
            farApproachDeadline = now + (5.0 * dist / WALK_SPEED_MPS * 1000.0).toLong()
                .coerceIn(APPROACH_BACKSTOP_MIN_MS, APPROACH_BACKSTOP_MAX_MS)
        }
        return now < farApproachDeadline
    }

    // ---------------- action gate ----------------

    private fun trigger(label: String, action: suspend () -> Boolean) {
        if (actionInFlight) { _state.value = _state.value.copy(lastAction = "$label · busy"); return }
        actionInFlight = true
        lastTriggerMs = clock.wallMs()
        scope.launch {
            val result = runCatching { action() }
            actionInFlight = false
            val ok = result.getOrNull() == true
            _state.value = _state.value.copy(
                lastAction = label + (if (ok) " ✓" else " ✗ ${result.exceptionOrNull()?.message ?: "failed"}"),
            )
            Logx.d("prox", "$label result=${if (ok) "ok" else "FAIL ${result.exceptionOrNull()?.message ?: ""}"}")
        }
    }

    companion object {
        private const val ACTION_COOLDOWN_MS = 5_000L
        private const val NEAR_DIST_M = 6.0              // NEAR/FAR boundary for the state machine
        private const val MONITOR_MID_MS = 800L          // no-session fallback (onSessionDown)
        // NEAR (< 6 m): wakelock held, poll eases with hold-still time. "still" = distance within the band.
        private const val MONITOR_FAST_MS = 200L         // moving, or just entered NEAR
        private const val MONITOR_NEAR_MID_MS = 500L     // still ≥ NEAR_EASE_MID_MS
        private const val MONITOR_NEAR_SLOW_MS = 1_000L  // still ≥ NEAR_EASE_SLOW_MS
        private const val NEAR_STILL_BAND_M = 0.5        // distance change that counts as "moved" (near)
        private const val NEAR_EASE_MID_MS = 30_000L     // hold still this long → 500 ms
        private const val NEAR_EASE_SLOW_MS = 60_000L    // hold still this long → 1 s
        // FAR (≥ 6 m): poll interval = dist / WALK_SPEED_MPS, clamped; wakelock held only while a sensor
        // says MOVING (bounded by the 5× walk-time backstop), released to sleep otherwise.
        private const val WALK_SPEED_MPS = 1.4           // avg human walking speed
        private const val MONITOR_BLIND_MIN_MS = 2_000L  // clamp floor: don't hammer when almost at the car
        private const val MONITOR_STILL_NOSENSOR_MS = 30_000L // clamp ceiling / no-sensor idle
        private const val APPROACH_BACKSTOP_MIN_MS = 30_000L  // temp-wakelock min lifetime for an approach
        private const val APPROACH_BACKSTOP_MAX_MS = 300_000L // …and max, so a stall can't leak it
        private const val FAR_SLEEP_SAFETY_MS = 60_000L  // loose re-check if the sensor never fires
        // RSSI-steadiness fallback — only used on devices with NO motion sensor at all (can't sleep, so we
        // poll; steady RSSI just dials the no-sensor cadence back to the 30 s ceiling).
        private const val STEADY_BAND_DB = 4
        private const val STEADY_HOLD_MS = 12_000L
        // Liveness: consecutive null connected-RSSI reads on a READY session before we force a
        // reconnect. At the 200 ms approach cadence that's ~1 s of a wedged link.
        private const val RSSI_NULL_RECONNECT = 5
        // App-layer liveness ping (0x0110/0x0A) — ONLY while actively approaching (state 3), never
        // while parked/idle. The car always acks 0x0110 with 0x0111 and rejects the RPA byte, so it
        // never actuates. PING_FAIL_STREAK silent pings in a row = control path wedged → reconnect.
        private const val PING_INTERVAL_MS = 1_000L
        private const val PING_TIMEOUT_MS = 700L
        private const val PING_FAIL_STREAK = 2
        private const val JUMP_DB = 4
        private const val TREND_DEADBAND = 0.6      // dB of smoothed change to count as moving
        private const val ALPHA_FAST = 0.6
        private const val ALPHA_SLOW = 0.35
        // Cloud lock fallback: bound the CPU hold while status and retries finish.
        private const val CLOUD_NET_WAKELOCK_MS = 45_000L
        // This delay suppresses a warning for brief reconnects; it NEVER authorizes a lock.
        private const val UNVERIFIED_LOCK_ALERT_DELAY_MS = 10_000L
        private const val UNVERIFIED_LOCK_ALERT_WAKE_MS = 15_000L
        private const val UNVERIFIED_LOCK_ESCALATION_MS = 30 * 60_000L
        /** Out-of-range link loss with the phone still: check the cloud this long after the loss (0.2.16). */
        private const val STATIONARY_LINK_LOSS_CHECK_MS = 100_000L
        /** ... and this long after it when the step counter shows a walk away from the car (0.3.2). */
        private const val STATIONARY_LINK_LOSS_EARLY_CHECK_MS = 60_000L
        /** A failed link-departure (GNSS) check raises nothing: it may hold the link-loss alert this long (0.3.2). */
        private const val LINK_DEPARTURE_ALERT_HOLD_MS = 30_000L
        /** First steps after a loss beside the car: the link usually returns within this time (0.3.2). */
        private const val NEAR_LOSS_WALK_CONFIRM_MS = 10_000L
        /** While an alarm is up the cloud is asked this often, for at most this long, whether the car locked. */
        private const val ALARM_CLOUD_POLL_MS = 8_000L
        private const val ALARM_CLOUD_WATCH_MS = 10 * 60_000L
        /** Two cloud snapshots this far apart must agree before "locked since our unlock" counts. */
        private const val CLOUD_SNAPSHOT_RECHECK_MS = 400L
        private const val SHADOW_RECHECK_MS = 5_000L
        private const val DEPARTURE_ALARM_READS = 3
        private const val SHADOW_REARM_MS = 15_000L
        private const val STRONG_NEAR_DIAGNOSTIC_RSSI = -78
        private const val CLOUD_LOCK_MAX_REQUESTS = 2
        private const val CLOUD_LOCK_STATUS_POLLS = 3
        private const val CLOUD_LOCK_STATUS_POLL_MS = 2_000L
        private const val POST_UNLOCK_FAST_MONITOR_MS = 25_000L
        // Approach-unlock BT-reset retry: time to let a teardown settle to IDLE, and to wait for
        // the fresh session to come up before the second (final) unlock attempt.
        private const val RESET_SETTLE_MS = 1_500L
        private const val RESET_RECONNECT_MS = 15_000L
        // Confirmed-unlock retry loop.
        // Unlocked activity-watch: an inbound frame within this window = "active" (track full-speed);
        // silence longer than it = "settled" (idle, event-wait). And the safety re-check period while
        // idle — one RSSI read that catches a rare drift-away with no car pushes.
        private const val ARMED_ACTIVE_MS = 4_000L
        // Safety re-check period while armed+idle. Kept SHORT so a walk-away is caught by an RSSI read
        // while the link is still up - the only window a BLE lock can be sent (offline / no-LTE garage,
        // where the after-link-drop cloud fallback is useless). Walking away drops through the FAR
        // threshold over several seconds; a ~3s poll gets a valid far reading before the link dies. The
        // battery cost is one RSSI read/~3s while you sit in the car unlocked - negligible; reliable
        // walk-away lock is worth more (per the user). Was 30s, which missed the connected window.
        private const val ARMED_IDLE_MAX_MS = 3_000L
        private const val SELF_LOCK_SETTLE_MS = 1_500L
        /** Plan K7: a strong reading after this long without one is the user's return. */
        private const val RETURN_GAP_MS = 10_000L
        /** Plan K9: steps since the last strong reading at the car that mean "walked away" (~20 m;
         *  50 missed a 30 m walk the hub counter counted as 44, stress F20). */
        private const val WALKED_AWAY_STEPS = 30L
        private const val WALKED_AWAY_QUIET_MS = 45_000L
        /** Plan K8: steps after a near-car link loss that mean "walking away", watched for this long. */
        private const val NEAR_LOSS_WALK_STEPS = 20L
        private const val NEAR_LOSS_STEP_WATCH_MS = 5 * 60_000L
        /** Plan H8: longest wakelock for one Lock loop (BLE attempts, GNSS check, cloud, status). */
        private const val LOCK_LOOP_WAKE_MS = 3 * 60_000L
        /** A running Lock or departure check may hold the link-loss alert back at most this long. */
        private const val LINK_LOSS_ALERT_MAX_WAIT_MS = 120_000L
        /** Steps since the last reading after which a link loss is no longer "at the car", ... */
        private const val LOSS_NEAR_MAX_STEPS_SINCE_READING = 8L
        /** ... or any step once that reading is this old (the hub counter withholds the first steps). */
        private const val LOSS_NEAR_STALE_READING_MS = 10_000L
        /** Level assumed for a veto without any reading: a new BLE proof is then out of reach. */
        private const val VETO_UNKNOWN_LEVEL_RSSI = -100
        /** Battery: GNSS departure windows (25 s each) one visit may use. */
        private const val MAX_GNSS_WINDOWS_PER_EPOCH = 6
        /** Battery: an open car with the phone still beside it holds the CPU at most this long. */
        private const val ARMED_STILL_WAKE_MAX_MS = 10 * 60_000L
        /** A proven arrival up to this long before the unlock is this epoch's (a late unlock at the door). */
        private const val ARRIVAL_BEFORE_UNLOCK_MS = 30_000L
        /** Plan K1: fewer steps than this since the car veto an automatic Lock (~9 m of walking). */
        private const val LOCK_MIN_STEPS_SINCE_CAR = 12L
        /** Plan K1: a strong reading this recent vetoes an automatic Lock. */
        private const val LOCK_VETO_RECENT_STRONG_MS = 6_000L
        /** Plan K1: a rise of this much over the readings 4-8 s earlier = walking back to the car. */
        private const val LOCK_VETO_RISE_DB = 6
        private const val LOCK_VETO_RISE_FROM_MS = 4_000L
        private const val LOCK_VETO_RISE_TO_MS = 8_000L
        private const val RECENT_READINGS_MS = 15_000L
        /** Plan K6: an unlock loop gives up after this long (a retry needs fresh near evidence anyway). */
        private const val UNLOCK_LOOP_MAX_MS = 20_000L
        /** A persisted "car open" record older than this is not restored (the car may be anywhere). */
        private const val RESTORE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        private const val FAR_NO_MOTION_RETRY_MS = 60_000L
        private const val FAR_NO_MOTION_MAX_ATTEMPTS = 2
        private const val CORROBORATION_AFTER_LOSS_MS = 120_000L
        private const val DEPARTURE_ALARM_MAX_HOLDS = 2
        private const val MAX_UNLOCK_ATTEMPTS = 5          // bound so a walked-away/absent car can't spin
        private const val UNLOCK_SESSION_WAIT_MS = 8_000L  // wait for SESSION_READY before an attempt
        private const val UNLOCK_ACK_TIMEOUT_MS = 1_500L   // wait for the car's 0x0111 receipt
        private const val UNLOCK_RETRY_DELAY_MS = 400L     // pause between attempts after a reset
        // Confirmed-lock loop (walk-away). After MAX_LOCK_ATTEMPTS unconfirmed BLE tries, fall back to
        // cloud. Shorter session-wait than unlock: if BLE won't come up we want cloud sooner (car open).
        private const val MAX_LOCK_ATTEMPTS = 5
        /** Key Locks the car received but did not execute before the cloud is tried (field 05/10). */
        private const val MAX_NOT_EXECUTED_LOCKS = 2
        /** How long a BLE departure proof may back a cloud Lock after the key link failed. */
        private const val BLE_PROOF_CLOUD_MS = 30_000L
        /** A key Lock left unanswered at or below this fresh reading goes to the cloud at once. */
        private const val FAR_LINK_CLOUD_RSSI = -98
        /** An unanswered key Lock this far below the lock threshold goes to the cloud at once (0.3.2). */
        private const val EDGE_CLOUD_MARGIN_DB = 8
        /** The stop-in-the-step-stream rule for the departure proof applies this close to the car. */
        private const val STOP_RULE_MAX_STEPS_FROM_CAR = 15L
        /** "Counted steps rising now" for the walk-back return: this many within this window. */
        private const val STEPS_RISING_MIN = 2L
        private const val STEPS_RISING_WINDOW_MS = 3_000L
        /** A self-lock this soon after our approach unlock may be undone by walking up to the door. */
        private const val SELF_LOCK_REOPEN_AFTER_UNLOCK_MS = 180_000L
        /** A link gap after which the car's lock state counts as unknown. */
        private const val LOCK_STATE_GAP_MS = 60_000L
        /** Time for the car's own status push in a new session before asking the cloud. */
        private const val LOCK_STATE_STATUS_WAIT_MS = 10_000L
        private const val LOCK_SESSION_WAIT_MS = 3_000L    // brief wait for SESSION_READY per attempt
        private const val LOCK_ACK_TIMEOUT_MS = 1_500L     // wait for the car's lock receipt

        // Nominal RSSI-at-1m and path-loss exponent for the metre estimate (DISPLAY ONLY).
        private const val TX_POWER_1M = -59
        private const val PATH_LOSS_N = 2.5
    }
}
