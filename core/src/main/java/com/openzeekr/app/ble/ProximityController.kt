package com.openzeekr.app.ble

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Approach-unlock / walk-away-lock driven by BLE RSSI, with distance + trend hysteresis.
 *
 * Rides the live keep-alive session (see [ProximityService]); it reads the connected-GATT RSSI
 * and never owns the connection. Connected RSSI is the accurate near-field ranging source.
 *
 * Decision model (the tuning the user asked for):
 *  - **Latch**: once we auto-unlock we set [armedUnlocked] and won't unlock again until a lock
 *    happens — so no repeated unlock spam while you stand at the car.
 *  - **Trend**: unlock only while *approaching* (RSSI rising), lock only while *receding* — a flat
 *    signal (you're parked next to it, or the app just launched at the car) does nothing.
 *  - **Hysteresis**: unlock at/above [ConfigStore.sensitivityUnlockRssi] (≈ near), lock at/below
 *    [ConfigStore.sensitivityLockRssi] (≈ farther). The gap between them stops flapping.
 *  - **Cooldown**: after any action, ignore new triggers for [ACTION_COOLDOWN_MS].
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
class ProximityController(
    private val appContext: android.content.Context,
    private val store: ConfigStore,
    private val lock: DkLockController,
    private val ble: DkBleManager,
    private val motion: MotionMonitor,
    private val scope: CoroutineScope,
    /** Cloud lock fallback (POST Command.LOCK). Returns true if the cloud accepted it. Injected by Deps
     *  so :core stays decoupled from the remote-control catalog; defaults to a no-op for tests. */
    private val cloudLock: suspend () -> Boolean = { false },
    /** Cloud lock-state probe (GET vehicle status → centralLockingStatus). true=locked, false=unlocked,
     *  null=unknown/failed. Used to verify an explicitly confirmed departure's cloud fallback. */
    private val cloudIsLocked: suspend () -> Boolean? = { null },
) {
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

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

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
    private var armedUnlocked = false
    // A new unlock can be followed by a quiet walk-away before the car emits another status frame.
    private var postUnlockFastUntilElapsedMs = 0L
    // Time-based evidence gate. This owns arrival/departure confirmation so neither the fast RSSI
    // path nor the periodic idle check can actuate from one noisy threshold crossing.
    private val decisionPolicy = ProximityDecisionPolicy()
    // Confirmed-unlock retry loop: true while actively trying to unlock; the job is the loop itself.
    @Volatile private var needToUnlock = false
    private var unlockJob: Job? = null
    // Confirmed-lock loop (walk-away). Locking matters more than unlocking — never leave the car open —
    // so this is at least as persistent as unlock and falls back to a cloud lock if BLE won't confirm.
    private var lockJob: Job? = null
    private var lockConfirmationJob: Job? = null
    // Set while the unlocked "activity watch" is idling; the car's next frame completes it (instant wake).
    @Volatile private var activityWake: CompletableDeferred<Unit>? = null

    private var monitorJob: Job? = null

    // ---- action gate ----
    private var lastTriggerMs = 0L
    @Volatile private var actionInFlight = false

    // ---- link-loss handling ----
    private var linkLostAtMs = 0L
    private var lostRssi: Int? = null
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
        linkLostAtMs = 0L; lostRssi = null
        pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
        unverifiedLockAlertRaised = false
        // Keep armedUnlocked as-is across start/stop toggles within a session isn't meaningful;
        // reset so a fresh monitor starts from a known state.
        armedUnlocked = false
        postUnlockFastUntilElapsedMs = 0L
        decisionPolicy.resetLocked()
        needToUnlock = false; unlockJob?.cancel(); unlockJob = null
        farAsleep = false; nearRefDist = null; nearStillSinceMs = 0L
        farApproachDeadline = 0L; farApproachRefDist = Double.MAX_VALUE
        _wakeLockNeeded.value = true   // hold until the first sample decides (bring-up needs the CPU)
        _state.value = State(running = true, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
        ble.onInboundActivity = { activityWake?.complete(Unit) } // wake the unlocked idle-wait instantly
        motion.onMovingEdge = {
            motionWake?.complete(Unit)   // wake the FAR-still approach sleep
            activityWake?.complete(Unit) // resume armed RSSI tracking on the first motion edge
        }
        motion.start()
        Logx.d("prox", "monitor start (distance+trend hysteresis; rides keep-alive session)")
        monitorJob = scope.launch {
            while (isActive) {
                farAsleep = false   // onSample sets it true only for FAR + still; cleared each tick
                when (ble.state.value) {
                    DkBleManager.State.SESSION_READY, DkBleManager.State.CONNECTED -> {
                        if (linkLostAtMs != 0L) {
                            linkLostAtMs = 0L
                            pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
                            if (unverifiedLockAlertRaised) {
                                _state.value = _state.value.copy(lastAction =
                                    "BLE restored · vehicle lock still unverified")
                            }
                            Logx.d("prox", "link restored; proximity monitoring resumed")
                        }
                        // Unlocked at the car (NEAR): don't burn battery polling — idle until the car
                        // pushes a frame (activity = you moving/leaving) or the link drops. Hold the
                        // wakelock: this is the at-the-car case and the inbound-frame wake needs the CPU.
                        if (armedUnlocked) { _wakeLockNeeded.value = true; armedWatch(); continue }
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
                if (farAsleep) sleepUntilMotion() else delay(nextIntervalMs)
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
        val woke = try { withTimeoutOrNull(FAR_SLEEP_SAFETY_MS) { wake.await() } != null } finally { motionWake = null }
        _wakeLockNeeded.value = true
        farAsleep = false
        if (woke) Logx.d("prox", "far-idle -> woke on motion (re-poll)")
    }

    fun stop() {
        monitorJob?.cancel(); monitorJob = null
        needToUnlock = false; unlockJob?.cancel(); unlockJob = null
        lockJob?.cancel(); lockJob = null
        lockConfirmationJob?.cancel(); lockConfirmationJob = null
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
    fun onPresenceMatch(rssi: Int) {
        // A PendingIntent can recreate the service/process. Start before recording evidence so the
        // normal runApproach loop cannot subsequently reset the just-created arrival epoch.
        if (!_state.value.running) start()
        val cfg = store.current()
        decisionPolicy.onPresenceMatch(
            nowMs = System.currentTimeMillis(),
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
            pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
            unverifiedLockAlertRaised = false
            UnverifiedLockNotifier.clear(appContext)
            if (_state.value.running && store.current().proximityEnabled) {
                recordUnlockConfirmed(source)
            } else {
                // Keep drive authorization alive, but respect the user's disabled proximity toggle.
                ble.noteUnlockConfirmed()
                Logx.d("prox", "unlock confirmed ($source); proximity disabled/stopped -> walk-away not armed")
            }
        }
    }

    /** Only the BLE receipt verifies a manually requested lock. */
    fun onExternalLockConfirmed(source: String) = onManualLockAcknowledged(source, bleConfirmed = true)

    /** The cloud acknowledged the command, but physical actuation remains unknown. */
    fun onExternalCloudLockAccepted() = onManualLockAcknowledged("manual cloud control", bleConfirmed = false)

    private fun onManualLockAcknowledged(source: String, bleConfirmed: Boolean) {
        scope.launch {
            pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
            if (bleConfirmed) {
                unverifiedLockAlertRaised = false
                UnverifiedLockNotifier.clear(appContext)
            } else {
                // Cloud Ok acknowledges receipt of a command, not physical actuation.
                val posted = UnverifiedLockNotifier.show(appContext)
                unverifiedLockAlertRaised = true
                _state.value = _state.value.copy(lastAction =
                    "Cloud Lock accepted · vehicle lock unverified; check car")
                Logx.w("prox", "manual cloud Lock accepted; physical lock unverified (notification posted=$posted)")
            }
            if (_state.value.running && store.current().proximityEnabled) {
                needToUnlock = false
                unlockJob?.cancel()
                unlockJob = null
                armedUnlocked = false
                decisionPolicy.onManualLockConfirmed()
                lastTriggerMs = System.currentTimeMillis()
                _wakeLockNeeded.value = false
                activityWake?.complete(Unit)
                Logx.d("prox", "lock ${if (bleConfirmed) "confirmed" else "requested"} ($source) -> departure latched")
            } else {
                Logx.d("prox", "lock ${if (bleConfirmed) "confirmed" else "requested"} ($source); proximity disabled/stopped")
            }
        }
    }

    private fun recordUnlockConfirmed(source: String) {
        val now = System.currentTimeMillis()
        pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
        unverifiedLockAlertRaised = false
        UnverifiedLockNotifier.clear(appContext)
        ble.noteUnlockConfirmed()
        armedUnlocked = true
        postUnlockFastUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + POST_UNLOCK_FAST_MONITOR_MS
        decisionPolicy.onUnlockConfirmed(now)
        lastTriggerMs = now
        _wakeLockNeeded.value = true
        activityWake?.complete(Unit)
        Logx.d("prox", "unlock confirmed ($source) -> walk-away armed; fast RSSI for ${POST_UNLOCK_FAST_MONITOR_MS}ms")
        if (ble.state.value !in setOf(DkBleManager.State.SESSION_READY, DkBleManager.State.CONNECTED)) {
            if (linkLostAtMs == 0L) linkLostAtMs = now
            queueUnverifiedLockAlert()
        }
    }

    // ---------------- no live session ----------------

    private fun onSessionDown() {
        if (linkLostAtMs == 0L) {
            decisionPolicy.onLinkEnded()
            linkLostAtMs = System.currentTimeMillis()
            // RSSI/body shielding and a real departure can both tear down the link. Neither a timer
            // nor the last (possibly stale) RSSI can tell them apart. Only a confirmed departure while
            // connected is allowed to initiate an automatic lock.
            Logx.d("prox", "link down (lastRssi=$lostRssi armed=$armedUnlocked) " +
                "-> reconnect only; no unverified automatic lock")
            queueUnverifiedLockAlert()
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

    /** Warn after a sustained loss, without interpreting elapsed time as evidence to lock. */
    private fun queueUnverifiedLockAlert() {
        if (!armedUnlocked || pendingUnverifiedLockAlert?.isActive == true) return
        pendingUnverifiedLockAlert = scope.launch {
            val wl = acquireSafetyWakelock(UNVERIFIED_LOCK_ALERT_WAKE_MS)
            try {
                delay(UNVERIFIED_LOCK_ALERT_DELAY_MS)
                if (linkLostAtMs == 0L || !armedUnlocked || !_state.value.running) return@launch
                val posted = UnverifiedLockNotifier.show(appContext)
                unverifiedLockAlertRaised = true
                _state.value = _state.value.copy(lastAction =
                    if (posted) "BLE lost · vehicle lock unverified; check notification"
                    else "BLE lost · vehicle lock unverified; notifications unavailable")
                Logx.w("prox", "BLE still down; vehicle lock unverified (notification posted=$posted)")
            } finally {
                releaseSafetyWakelock(wl)
            }
        }
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
            delay(MONITOR_FAST_MS)
            return
        }
        val quietMs = System.currentTimeMillis() - ble.lastInboundMs
        if (quietMs >= ARMED_ACTIVE_MS &&
            android.os.SystemClock.elapsedRealtime() >= postUnlockFastUntilElapsedMs &&
            motion.state.value != MotionMonitor.Motion.MOVING) {
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
            val idleDecision = if (rssi == null) ProximityDecisionPolicy.ArmedDecision.NONE else
                decisionPolicy.onUnlockedSample(
                    System.currentTimeMillis(), rssi,
                    motion.state.value == MotionMonitor.Motion.MOVING,
                    store.current().sensitivityLockRssi,
                )
            if (idleDecision == ProximityDecisionPolicy.ArmedDecision.LOCK) {
                Logx.d("prox", "armed idle safety-check rssi=$rssi -> verifying fresh separation")
                requestVerifiedWalkAwayLock("idle-far-lock")
            }
            return
        }
        // Recent car activity, the initial unlock window, or observed phone movement keeps RSSI
        // sampling fast. A silent car must not force a three-second cadence while walking away.
        val rssi = ble.pollRemoteRssi()
        if (rssi != null) { rssiNullStreak = 0; onSample(rssi) }
        else if (++rssiNullStreak >= RSSI_NULL_RECONNECT) { rssiNullStreak = 0; forceReconnect() }
        delay(MONITOR_FAST_MS)
    }

    /** A GATT poll returns the preceding asynchronous RSSI reading, which may be stale. */
    private suspend fun confirmWalkAway(): Boolean {
        val confirmation = WalkAwayLockConfirmation(store.current().sensitivityLockRssi)
        // Discard the first poll: it can still contain the sample that triggered the candidate.
        ble.pollRemoteRssi()
        repeat(9) {
            delay(MONITOR_FAST_MS)
            if (ble.state.value != DkBleManager.State.SESSION_READY ||
                motion.state.value != MotionMonitor.Motion.MOVING) return false
            val rssi = ble.pollRemoteRssi() ?: return@repeat
            when (confirmation.observe(rssi)) {
                WalkAwayLockConfirmation.Decision.LOCK -> {
                    Logx.d("prox", "walk-away: fresh sustained departure confirmed")
                    return true
                }
                WalkAwayLockConfirmation.Decision.CANCEL -> {
                    Logx.d("prox", "walk-away: signal recovered ($rssi), retaining unlock")
                    return false
                }
                WalkAwayLockConfirmation.Decision.WAIT -> Unit
            }
        }
        Logx.d("prox", "walk-away: separation not confirmed, retaining unlock")
        return false
    }

    private fun requestVerifiedWalkAwayLock(reason: String) {
        if (lockConfirmationJob?.isActive == true) return
        lockConfirmationJob = scope.launch {
            if (confirmWalkAway() && armedUnlocked && _state.value.running &&
                store.current().proximityEnabled) {
                armedUnlocked = false
                startLockLoop(reason)
            } else {
                decisionPolicy.onWalkAwayVerificationFailed()
            }
            lockConfirmationJob = null
        }
    }

    // ---------------- RSSI → distance → decision ----------------

    private fun onSample(rssi: Int) {
        val cfg = store.current()
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

        // Trend from the smoothed value vs the previous smoothed value. (Unlock no longer needs a
        // rising trend — arrival is detected from the zone crossing — but `receding` still gates lock.)
        val trend = if (prev != null) smoothedD - prev else 0.0
        val receding = trend < -TREND_DEADBAND
        lostRssi = smoothed    // diagnostic only; never sufficient to lock after link loss

        val now = System.currentTimeMillis()
        val armedDecision = if (armedUnlocked) decisionPolicy.onUnlockedSample(
            now, smoothed, motion.state.value == MotionMonitor.Motion.MOVING, lockThresh,
        ) else ProximityDecisionPolicy.ArmedDecision.NONE
        // Observe locked RSSI even while CONNECTED is still handshaking. The deep-sleep field trace
        // reached the door at -61 dBm, but SESSION_READY arrived ~3.4 s later after the signal had
        // weakened; gating observation on READY discarded the only reliable arrival evidence.
        val unlockQualified = !armedUnlocked && !needToUnlock && decisionPolicy.shouldUnlock(
            now, smoothed, motion.state.value == MotionMonitor.Motion.MOVING, unlockThresh,
        )
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
                    _wakeLockNeeded.value = false; farAsleep = true
                    farApproachDeadline = 0L; farApproachRefDist = Double.MAX_VALUE
                }
            }
        }
        // Liveness ping only while actively closing in on foot (fast NEAR cadence).
        val approachState = near && nextIntervalMs == MONITOR_FAST_MS

        val cadence = when {
            near     -> "near-${nextIntervalMs}ms"
            farAsleep -> "far-sleep"
            else     -> "far-approach-${nextIntervalMs}ms"
        }
        if (cadence != lastCadence) {
            lastCadence = cadence
            Logx.d("prox", "cadence -> $cadence (~${"%.1f".format(dist)}m rssi=$smoothed motion=${motion.state.value} wl=${_wakeLockNeeded.value})")
        }

        Logx.d("prox", "rssi=$rssi ema=$smoothed ~${"%.1f".format(dist)}m " +
            "trend=${"%+.1f".format(trend)} zone=$zone armed=$armedUnlocked " +
            "thr(u/l)=$unlockThresh/$lockThresh motion=${motion.state.value} next=${nextIntervalMs}ms")

        val cooling = lastTriggerMs != 0L && System.currentTimeMillis() - lastTriggerMs < ACTION_COOLDOWN_MS
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
            Logx.d("prox", "approach-unlock ARM (rssi=$smoothed ~${"%.1f".format(dist)}m prevZone=$prevZone) — confirmed-unlock loop")
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
        // A lock now requires time-based, moving-and-receding evidence from the shared policy. The same
        // rule is used by armedWatch, so its periodic safety sample cannot bypass this guard.
        if (armedUnlocked && armedDecision == ProximityDecisionPolicy.ArmedDecision.LOCK) {
            Logx.d("prox", "walk-away candidate (rssi=$smoothed ~${"%.1f".format(dist)}m) -> verifying")
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
            while (isActive && needToUnlock && attempt < MAX_UNLOCK_ATTEMPTS) {
                attempt++
                if (ble.state.value != DkBleManager.State.SESSION_READY) {
                    if (!awaitState(setOf(DkBleManager.State.SESSION_READY), UNLOCK_SESSION_WAIT_MS)) {
                        if (!needToUnlock) break
                        resetLink(); continue
                    }
                }
                if (!needToUnlock) break
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
        if (lockJob?.isActive == true) return
        // A pending unlock loop is now moot (we've decided you're leaving) — stop it fighting us.
        needToUnlock = false; unlockJob?.cancel(); unlockJob = null
        // Terminal for this BLE session: a post-lock RSSI rebound must never emit CTRL_UNLOCK.
        // A genuine later arrival is rearmed only by link-down + a fresh hardware presence hit.
        decisionPolicy.onDepartureLockStarted()
        lockJob = scope.launch {
            lastTriggerMs = System.currentTimeMillis()   // start the action cooldown
            var confirmed = false
            var attempt = 0
            while (isActive && attempt < MAX_LOCK_ATTEMPTS) {
                attempt++
                if (ble.state.value != DkBleManager.State.SESSION_READY &&
                    !awaitState(setOf(DkBleManager.State.SESSION_READY), LOCK_SESSION_WAIT_MS)) {
                    // No live session this round — kick a reconnect to the known car and try the next attempt.
                    if (!ble.reconnectLast()) runCatching { ble.connect(null) }
                    continue
                }
                val r = runCatching { ble.session.control(DkProtocol.CTRL_LOCK, LOCK_ACK_TIMEOUT_MS) }
                    .getOrDefault(ControlResult.WRITE_FAILED)
                Logx.d("prox", "$reason: BLE lock attempt #$attempt -> $r")
                if (r == ControlResult.CONFIRMED) { confirmed = true; break }
                resetLink()   // write-fail / no-response / reject → clear the wedge and retry
                delay(UNLOCK_RETRY_DELAY_MS)
            }
            if (!confirmed) {
                Logx.w("prox", "$reason: BLE lock unconfirmed after $attempt attempts — falling back to CLOUD lock")
                val cloudStatusLocked = verifyCloudLock(reason)
                // Repeated cloud status can be cached; it cannot replace the missing BLE receipt.
                val posted = UnverifiedLockNotifier.show(appContext)
                unverifiedLockAlertRaised = true
                Logx.w("prox", "$reason: BLE lock unconfirmed; cloud status locked=$cloudStatusLocked; notification posted=$posted")
                _state.value = _state.value.copy(lastAction =
                    "$reason · ${if (cloudStatusLocked) "cloud reports LOCKED (2×); physical lock unverified" else "LOCK NOT VERIFIED ✗"}; check car")
            } else {
                pendingUnverifiedLockAlert?.cancel(); pendingUnverifiedLockAlert = null
                unverifiedLockAlertRaised = false
                UnverifiedLockNotifier.clear(appContext)
                _state.value = _state.value.copy(lastAction = "$reason · locked ✓")
            }
            lockJob = null
        }
    }

    /** A successful HTTP response acknowledges the request, not a physical door lock. */
    private suspend fun verifyCloudLock(reason: String): Boolean {
        val wl = acquireSafetyWakelock()
        try {
            repeat(CLOUD_LOCK_MAX_REQUESTS) { requestIndex ->
                val accepted = runCatching { cloudLock() }.getOrDefault(false)
                Logx.d("prox", "$reason: cloud lock request #${requestIndex + 1} " +
                    if (accepted) "accepted; checking vehicle status" else "rejected")
                if (!accepted) return@repeat
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
    private fun acquireSafetyWakelock(timeoutMs: Long = CLOUD_NET_WAKELOCK_MS): android.os.PowerManager.WakeLock? = runCatching {
        val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "openzeekr:lock-check").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }.getOrNull()

    private fun releaseSafetyWakelock(wl: android.os.PowerManager.WakeLock?) {
        runCatching { if (wl?.isHeld == true) wl.release() }
    }

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
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ble.state.value in targets) return true
            delay(120)
        }
        return ble.state.value in targets
    }

    /** Approach-only liveness ping: send a non-actuating 0x0110 (the car always acks with 0x0111 and
     *  rejects the RPA byte) at most once per PING_INTERVAL_MS. ANY reply clears the streak;
     *  PING_FAIL_STREAK silent pings in a row means the control path is wedged → reconnect. */
    private fun maybePing() {
        val now = System.currentTimeMillis()
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
        lastTriggerMs = System.currentTimeMillis()
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
        private const val MAX_UNLOCK_ATTEMPTS = 5          // bound so a walked-away/absent car can't spin
        private const val UNLOCK_SESSION_WAIT_MS = 8_000L  // wait for SESSION_READY before an attempt
        private const val UNLOCK_ACK_TIMEOUT_MS = 1_500L   // wait for the car's 0x0111 receipt
        private const val UNLOCK_RETRY_DELAY_MS = 400L     // pause between attempts after a reset
        // Confirmed-lock loop (walk-away). After MAX_LOCK_ATTEMPTS unconfirmed BLE tries, fall back to
        // cloud. Shorter session-wait than unlock: if BLE won't come up we want cloud sooner (car open).
        private const val MAX_LOCK_ATTEMPTS = 5
        private const val LOCK_SESSION_WAIT_MS = 3_000L    // brief wait for SESSION_READY per attempt
        private const val LOCK_ACK_TIMEOUT_MS = 1_500L     // wait for the car's lock receipt

        // Nominal RSSI-at-1m and path-loss exponent for the metre estimate (DISPLAY ONLY).
        private const val TX_POWER_1M = -59
        private const val PATH_LOSS_N = 2.5
    }
}
