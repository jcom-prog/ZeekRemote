package com.openzeekr.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openzeekr.app.Deps
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.util.CarNotifier
import com.openzeekr.app.util.Logx
import com.openzeekr.core.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the digital key live once the phone is provisioned:
 *
 *  1. **Keep-alive** — holds the DK BLE session connected to the car (reconnecting
 *     whenever it drops) so manual and approach lock/unlock are instant.
 *  2. **Approach** — when the "lock/unlock on approach" setting is on, runs the
 *     RSSI proximity controller (approach-unlock / walk-away-lock).
 *
 * Started by the app once logged in + provisioned (see AppBootstrap); pair with a
 * battery-optimization exemption for reliability. Runs as a connectedDevice FGS.
 */
class ProximityService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loops: Job? = null

    // A FGS keeps the PROCESS alive but does NOT keep the CPU awake, and screen-off BLE work (RSSI
    // polling, GATT callbacks, the handshake) needs the CPU up. Ownership is centralised in
    // [manageWakeLock]: we hold it while BRINGING A SESSION UP (scanning/connecting/handshaking) and
    // while the proximity controller says it needs the CPU (NEAR, or a FAR approach burst). We RELEASE
    // it when parked-idle (offloaded presence scan wakes us CPU-asleep) and — crucially — while the car
    // is connected but FAR + still, where the step detector wakes us the instant you start walking.
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // One-shot probe for the "app started right next to the car" case before the offloaded listener
    // has seen its first advert. This runs once per service lifetime, never after every motion edge.
    private var didInitialProbe = false
    // When we last held a live/engaged link. Used to keep reconnecting aggressively (foreground) for a
    // short window after a drop while you're moving — the walk-up case — vs. the slow offloaded scan.
    private var lastEngagedMs = 0L
    private var lastWakeProbeMs = 0L
    // Protect the proven PendingIntent route briefly after a security-sleep wake. Without this,
    // keepConnected can classify the just-dropped session as "recent" and immediately replace the
    // offload listener with the screen-off callback scan that timed out in the 0.1.21 field trace.
    private var motionPresenceRecoveryUntilMs = 0L
    private var wakeReceiverRegistered = false
    /** Modern-key security mode: after two minutes without movement there is no BLE session and no
     * presence scan. Motion must be confirmed before the key is made discoverable/useful again. */
    @Volatile private var keySleeping = false
    private var sleepDisconnectGraceUntilMs = 0L

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val reason = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> "screen-on"
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON)
                        "bluetooth-on" else null
                else -> null
            } ?: return
            val deps = (application as? DepsHolder)?.deps ?: return
            scope.launch { recoveryProbe(deps, reason) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val deps = (application as? DepsHolder)?.deps ?: return START_STICKY
        // A wake-up sensor only guarantees CPU time for delivery of its callback. Hold a short,
        // bounded bridge across the 1.5 s motion-confirmation window; scan/connect owns the normal
        // wakelock after recovery begins. This is never a standing idle wakelock.
        deps.motion.onHardwareWake = { acquireWakeLock(HARDWARE_WAKE_BRIDGE_MS) }
        registerWakeReceiver()

        // Presence signals delivered by BleScanReceiver (offloaded scan woke us).
        when (intent?.action) {
            ACTION_PRESENT -> {
                if (keySleeping) {
                    Logx.d("svc", "presence ignored: stationary key is sleeping")
                    deps.ble.disarmPresenceScan()
                    return START_STICKY
                }
                val scanResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_SCAN_RESULT, android.bluetooth.le.ScanResult::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_SCAN_RESULT) as? android.bluetooth.le.ScanResult
                }
                val mac = scanResult?.device?.address ?: intent.getStringExtra(EXTRA_MAC)
                Logx.d("svc", "presence: car in range (saw $mac) — engaging from preserved scan result")
                deps.ble.disarmPresenceScan()
                // A ScanResult carries the BluetoothDevice's RANDOM/RPA address type. Never rebuild
                // that device with getRemoteDevice(mac): Android then treats it as PUBLIC and the
                // connect fails. If this particular split advert lacks broadcastRnd, the manager
                // safely falls back to the normal scan and combines only packets from the same MAC.
                runCatching {
                    if (scanResult == null || !deps.ble.connectFromPresence(scanResult)) deps.ble.connect(null)
                } // wakelock follows state via manageWakeLock
            }
            ACTION_ABSENT -> {
                // Legacy MATCH_LOST signal. Nothing to do: manageWakeLock releases
                // the wakelock once we're IDLE, and keepConnected keeps the offload armed. A live
                // session's walk-away lock is driven by the connected-RSSI controller, not this signal.
            }
        }

        if (loops?.isActive != true) {
            loops = scope.launch {
                launch { keepConnected(deps) }
                launch { runApproach(deps) }
                launch { onMotionEscalate(deps) }
                launch { manageWakeLock(deps) }
                launch { watchdog(deps) }
                launch { stationaryKeySecurity(deps) }
                launch { pollCarMessages(deps) }
            }
        }
        return START_STICKY
    }

    /**
     * Single owner of the keep-alive wakelock. Hold it while BRINGING A SESSION UP (scan → connect →
     * handshake all need the CPU) OR while the proximity controller needs it (NEAR, or a FAR approach
     * burst). Release it otherwise — parked-idle (offload scan wakes us) and, importantly, connected but
     * FAR + still, where the wake-up step detector is what breaks the sleep. [distinctUntilChanged] keeps
     * us from re-acquiring/re-releasing on every tick.
     */
    private suspend fun manageWakeLock(deps: Deps) {
        combine(deps.ble.state, deps.proximity.wakeLockNeeded) { st, controllerNeeds ->
            val establishing = st == DkBleManager.State.SCANNING ||
                st == DkBleManager.State.CONNECTING ||
                st == DkBleManager.State.CONNECTED   // not yet SESSION_READY: still handshaking
            establishing || controllerNeeds
        }.distinctUntilChanged().collect { hold ->
            if (hold) acquireWakeLock() else releaseWakeLock()
        }
    }

    override fun onDestroy() {
        loops?.cancel(); loops = null
        (application as? DepsHolder)?.deps?.let {
            it.motion.onHardwareWake = null
            it.proximity.stop()
            runCatching { it.ble.disarmPresenceScan() }
        }
        releaseWakeLock()
        if (wakeReceiverRegistered) runCatching { unregisterReceiver(wakeReceiver) }
        wakeReceiverRegistered = false
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock(timeoutMs: Long? = null) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { if (timeoutMs != null) acquire(timeoutMs) else acquire() }
        }
        Logx.d("svc", "wakelock acquired (CPU stays awake for screen-off keep-alive/proximity)")
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    /**
     * Hold the DK BLE session connected; reconnect whenever it goes idle/errored.
     *
     * This is the SOLE owner of the connection — it runs regardless of the proximity
     * setting, so the session stays up constantly (instant lock/unlock, a stable link for
     * RPA, and no churn). The [ProximityController] only reads RSSI off this live session;
     * it never connects or disconnects, so the two can't fight over the GATT.
     */
    private suspend fun keepConnected(deps: Deps) {
        while (scope.isActive) {
            if (keySleeping && !deps.ble.driveAuthorizationActive) {
                // During the short grace window the proximity controller may complete a pending
                // walk-away lock. After that, enforce a genuinely silent key.
                deps.ble.disarmPresenceScan()
                if (System.currentTimeMillis() >= sleepDisconnectGraceUntilMs &&
                    deps.ble.state.value !in setOf(DkBleManager.State.IDLE, DkBleManager.State.ERROR)) {
                    Logx.d("svc", "stationary key: dropping residual BLE session")
                    runCatching { deps.ble.disconnect() }
                }
                delay(KEY_SLEEP_POLL_MS)
                continue
            }
            // The watch is borrowing the car link (only one BLE peer allowed): stand down —
            // release our session and don't reconnect until it resumes us (or the fail-safe
            // deadline passes, in case the watch app died mid-handover).
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) {
                if (com.openzeekr.app.wear.WearLinkArbiter.expired()) {
                    Logx.d("svc", "keep-alive: watch link-borrow expired — reclaiming")
                    com.openzeekr.app.wear.WearLinkArbiter.resume()
                } else {
                    when (deps.ble.state.value) {
                        DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {}
                        else -> { Logx.d("svc", "keep-alive: releasing link for watch"); runCatching { deps.ble.disconnect() } }
                    }
                    delay(WATCH_YIELD_POLL_MS)
                    continue
                }
            }
            if (deps.ble.hasCredential && deps.ble.bluetoothAvailable) {
                val offload = deps.config.config.value.presenceOffloadEnabled
                when (deps.ble.state.value) {
                    DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {
                        // "Actively approaching" = moving AND we held a live link recently (the drop just
                        // happened at range while you walked up). The LOW_POWER/STICKY offloaded scan is
                        // meant for parked-still and is too slow to reconnect + handshake before you reach
                        // the door — so here we do a fast FOREGROUND scan-connect instead. Bounded to
                        // AGGRESSIVE_RECONNECT_MS since the last session so walking AWAY falls back to the
                        // low-power scan once you're clearly gone.
                        val moving = deps.motion.state.value == MotionMonitor.Motion.MOVING
                        val recentlyEngaged = System.currentTimeMillis() - lastEngagedMs < AGGRESSIVE_RECONNECT_MS
                        val normallyAggressive = (moving && recentlyEngaged) || deps.ble.driveAuthorizationActive
                        val presenceRecoveryActive = System.currentTimeMillis() < motionPresenceRecoveryUntilMs
                        val recoveryRoute = DeepSleepRecoveryPolicy.keepAliveRoute(
                            presenceRecoveryActive = presenceRecoveryActive,
                            presenceArmed = deps.ble.presenceArmed,
                            normallyAggressive = normallyAggressive,
                        )
                        val aggressive = recoveryRoute == DeepSleepRecoveryPolicy.Route.FOREGROUND_SCAN
                        if (offload && !aggressive) {
                            // Zero-CPU idle: the offloaded scan watches for the car and wakes us via
                            // BleScanReceiver. manageWakeLock releases the wakelock (nothing to hold for).
                            if (!didInitialProbe) {
                                // First idle tick: the car may already be in range (app launched next
                                // to it), before the offload has delivered — do one foreground probe.
                                didInitialProbe = true
                                Logx.d("svc", "keep-alive: initial presence probe (already-at-car case)")
                                runCatching { deps.ble.connect(null) }
                            } else {
                                // Long-approach path: BALANCED while walking, LOW_POWER while still.
                                // This filtered PendingIntent scan stays in the BT controller and holds
                                // no CPU wakelock, so it can listen all day without foreground scanning.
                                deps.ble.armPresenceScan(approachMode = moving)
                            }
                        } else {
                            // Legacy (offload off), OR aggressive reconnect while walking up: foreground
                            // scan-connect. manageWakeLock holds the wakelock across the connect + session.
                            if (aggressive) Logx.d("svc", "keep-alive: bounded approach recovery — aggressive scan-connect (skip low-power offload)")
                            else Logx.d("svc", "keep-alive: (re)connecting DK session")
                            runCatching { deps.ble.connect(null) }
                        }
                    }
                    // Engaged (scanning/connecting/connected/session): the offload scan is redundant while
                    // we hold a link, so drop it. The wakelock is owned by manageWakeLock (held while
                    // establishing, then handed to the proximity controller's need). We do NOT proactively
                    // cycle the link — a genuine stall is caught reactively by the controller's liveness.
                    else -> {
                        lastEngagedMs = System.currentTimeMillis()
                        if (deps.ble.presenceArmed) deps.ble.disarmPresenceScan()
                    }
                }
            }
            delay(RECONNECT_INTERVAL_MS)
        }
    }

    private fun registerWakeReceiver() {
        if (wakeReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(wakeReceiver, filter)
            wakeReceiverRegistered = true
        }.onFailure { Logx.w("svc", "wake receiver registration failed: ${it.message}") }
    }

    /** Recover an idle key after an explicit wake signal. A confirmed-motion wake from security sleep
     *  first uses the filtered PendingIntent scan: field traces prove Android delivers that route with
     *  the screen off while ordinary callback scans can time out twice. Other wake signals retain the
     *  bounded foreground probe. Neither route steals the BLE slot from Wear. */
    private suspend fun recoveryProbe(deps: Deps, reason: String, confirmedMotionWake: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastWakeProbeMs < WAKE_PROBE_DEBOUNCE_MS) return
        if (!deps.ble.hasCredential || !deps.ble.bluetoothAvailable) return
        if (keySleeping) {
            deps.proximity.updateDiagnostics("$reason · ignored while stationary")
            return
        }
        if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) {
            deps.proximity.updateDiagnostics("$reason · deferred to Watch")
            return
        }
        if (deps.ble.state.value !in setOf(DkBleManager.State.IDLE, DkBleManager.State.ERROR)) return
        lastWakeProbeMs = now
        val offloadEnabled = deps.config.config.value.presenceOffloadEnabled
        val presenceArmed = if (confirmedMotionWake && offloadEnabled) {
            runCatching { deps.ble.armPresenceScan(approachMode = true) }.getOrDefault(false)
        } else false
        when (DeepSleepRecoveryPolicy.route(offloadEnabled && confirmedMotionWake, presenceArmed)) {
            DeepSleepRecoveryPolicy.Route.OFFLOADED_PRESENCE -> {
                motionPresenceRecoveryUntilMs = now + MOTION_PRESENCE_RECOVERY_MS
                deps.proximity.updateDiagnostics("$reason · waiting for car presence")
                Logx.d("svc", "$reason: BALANCED offloaded presence armed (screen-off recovery)")
            }
            DeepSleepRecoveryPolicy.Route.FOREGROUND_SCAN -> {
                deps.proximity.updateDiagnostics("$reason · recovery scan")
                Logx.d("svc", "$reason: immediate bounded recovery scan")
                runCatching { deps.ble.disarmPresenceScan(); deps.ble.connect(null) }
            }
        }
    }

    /** Low-frequency state watchdog. This does not scan continuously: it only repairs an idle/error
     *  state while motion or the post-unlock drive window says the key is actively needed. */
    private suspend fun watchdog(deps: Deps) {
        while (scope.isActive) {
            val state = deps.ble.state.value
            val needed = deps.ble.driveAuthorizationActive
            deps.proximity.updateDiagnostics(
                "BLE ${state.name.lowercase()} · motion ${deps.motion.source.name.lowercase()}" +
                    if (deps.ble.driveAuthorizationActive) " · start-key guarded" else ""
            )
            if (needed && state in setOf(DkBleManager.State.IDLE, DkBleManager.State.ERROR)) {
                recoveryProbe(deps, "start-key watchdog")
            }
            delay(WATCHDOG_INTERVAL_MS)
        }
    }

    /** Start/stop the RSSI approach controller to follow the persisted setting. */
    private suspend fun runApproach(deps: Deps) {
        deps.config.config.collect { cfg ->
            val running = deps.proximity.state.value.running
            if (cfg.proximityEnabled && !running) runCatching { deps.proximity.start() }
            else if (!cfg.proximityEnabled && running) runCatching { deps.proximity.stop() }
        }
    }

    /**
     * Motion-triggered escalation (wakelock-free until it fires). The phone was still and just
     * started moving ([MotionMonitor] hardware trigger). Switch the hardware-filtered PendingIntent
     * presence scan to BALANCED duty while walking; the Bluetooth controller keeps listening with the
     * CPU asleep. Its next ALL_MATCHES advert wakes us into the proven foreground scan-connect path.
     * This works for a two-minute or all-day approach without starting a 20 s foreground scan after
     * every stop in every shop. If already engaged, the connected-RSSI controller owns the cadence.
     */
    private suspend fun onMotionEscalate(deps: Deps) {
        var last = MotionMonitor.Motion.UNKNOWN
        deps.motion.state.collect { m ->
            val became = m == MotionMonitor.Motion.MOVING && last != MotionMonitor.Motion.MOVING
            last = m
            if (!became || keySleeping || !deps.ble.hasCredential || !deps.ble.bluetoothAvailable) return@collect
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) return@collect
            when (deps.ble.state.value) {
                DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {
                    Logx.d("svc", "motion: phone started moving — arm BALANCED offloaded presence")
                    runCatching { deps.ble.armPresenceScan(approachMode = true) }
                }
                else -> {} // already engaged/connecting — nothing to do
            }
        }
    }

    /**
     * Motion-gated anti-relay mode, modelled after modern motion-sleeping key fobs.
     *
     * After two minutes of sensor-confirmed stillness the phone stops both the live GATT session and
     * the hardware-offloaded presence scan. Screen-on/Bluetooth-on cannot bypass this state. A newly
     * moving phone must remain moving for a short debounce window; it then performs one immediate,
     * bounded recovery scan. Drive authorization and Watch link borrowing always take precedence.
     */
    private suspend fun stationaryKeySecurity(deps: Deps) {
        while (scope.isActive) {
            val eligible = deps.ble.hasCredential &&
                deps.config.config.value.proximityEnabled &&
                !deps.ble.driveAuthorizationActive &&
                !com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value

            if (!keySleeping && eligible && deps.motion.isStillFor(KEY_SLEEP_AFTER_MS)) {
                keySleeping = true
                // Allow the existing controller a bounded window to finish a walk-away lock if the
                // car had just been auto-unlocked. No new presence event can start an unlock now.
                sleepDisconnectGraceUntilMs = System.currentTimeMillis() + KEY_SLEEP_LOCK_GRACE_MS
                deps.ble.disarmPresenceScan()
                runCatching { deps.ble.disconnect() }
                deps.proximity.updateDiagnostics("security sleep · stationary 2 min · BLE off")
                Logx.d("svc", "security sleep: stationary for 2 min — BLE session/presence disabled")
            } else if (keySleeping) {
                // A running/starting car must never lose its key just because the phone lies still.
                val driveOverride = deps.ble.driveAuthorizationActive
                val movementConfirmed = deps.motion.isMovingFor(KEY_WAKE_MOTION_CONFIRM_MS)
                if (driveOverride || movementConfirmed) {
                    keySleeping = false
                    sleepDisconnectGraceUntilMs = 0L
                    val reason = if (driveOverride) "start-key override" else "confirmed motion"
                    deps.proximity.updateDiagnostics("$reason · waking digital key")
                    Logx.d("svc", "$reason: waking stationary key; selecting screen-off recovery route")
                    recoveryProbe(deps, reason, confirmedMotionWake = !driveOverride)
                } else {
                    deps.ble.disarmPresenceScan()
                }
            }
            delay(KEY_SLEEP_POLL_MS)
        }
    }

    /**
     * Poll the car's message centre and raise a system notification (own channel) for anything new.
     * There is no server push, so this is the delivery mechanism. On the first run we only *seed* the
     * high-water mark (newest existing message) so we don't replay the whole history as alerts; after
     * that, every message newer than the mark and still unread is posted via [CarNotifier]. The mark
     * is persisted so a service restart doesn't re-notify. Guarded on being logged in/provisioned.
     */
    private suspend fun pollCarMessages(deps: Deps) {
        val prefs = getSharedPreferences(CAR_MSG_PREFS, Context.MODE_PRIVATE)
        var lastSeen = prefs.getLong(CAR_MSG_LAST_SEEN, 0L)
        var seeded = lastSeen > 0L
        while (scope.isActive) {
            runCatching {
                if (deps.config.config.value.overseasReady) {
                    val res = deps.inbox.messages()
                    if (res is com.openzeekr.app.remote.CallResult.Ok) {
                        val msgs = res.value.filter { it.timeMs != null }
                        val newest = msgs.maxOfOrNull { it.timeMs ?: 0L } ?: 0L
                        if (!seeded) {
                            seeded = true
                        } else {
                            msgs.filter { (it.timeMs ?: 0L) > lastSeen && !it.read }
                                .sortedBy { it.timeMs }
                                .forEach { CarNotifier.notify(this@ProximityService, it) }
                        }
                        if (newest > lastSeen) {
                            lastSeen = newest
                            prefs.edit().putLong(CAR_MSG_LAST_SEEN, lastSeen).apply()
                        }
                    }
                }
            }
            delay(CAR_MSG_POLL_MS)
        }
    }

    private fun startInForeground() {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZeekRemote digital key active")
            .setContentText("Keeping your key connected for lock/unlock")
            .setSmallIcon(R.drawable.ic_logo)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Digital key", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val WAKELOCK_TAG = "openzeekr:dk-keepalive"
        private const val CHANNEL_ID = "proximity"
        private const val NOTIF_ID = 42
        private const val RECONNECT_INTERVAL_MS = 8_000L
        // After a link drop, keep foreground-reconnecting (not the slow offloaded scan) while you're
        // moving, for this long since the last live session — covers a walk-up where the link dropped at
        // range; a genuine walk-away goes quiet (STILL) or ages out and falls back to the low-power scan.
        private const val AGGRESSIVE_RECONNECT_MS = 30_000L
        private const val WAKE_PROBE_DEBOUNCE_MS = 5_000L
        private const val MOTION_PRESENCE_RECOVERY_MS = 30_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val KEY_SLEEP_AFTER_MS = 2 * 60 * 1_000L
        private const val KEY_WAKE_MOTION_CONFIRM_MS = 1_500L
        private const val KEY_SLEEP_LOCK_GRACE_MS = 15_000L
        private const val KEY_SLEEP_POLL_MS = 500L
        private const val HARDWARE_WAKE_BRIDGE_MS = 5_000L
        /** While yielded to the watch, poll faster so we notice resume/expiry promptly. */
        private const val WATCH_YIELD_POLL_MS = 1_000L
        /** Car message-centre poll cadence (no server push, so we pull). */
        private const val CAR_MSG_POLL_MS = 5 * 60 * 1000L
        private const val CAR_MSG_PREFS = "car_notify"
        private const val CAR_MSG_LAST_SEEN = "last_seen_ms"

        /** BleScanReceiver → service: a matching car advert was delivered (ALL_MATCHES). */
        const val ACTION_PRESENT = "com.openzeekr.app.ble.PROX_PRESENT"
        /** BleScanReceiver → service: the car's advert left range (MATCH_LOST) or offload dropped. */
        const val ACTION_ABSENT = "com.openzeekr.app.ble.PROX_ABSENT"
        const val EXTRA_MAC = "mac"
        const val EXTRA_SCAN_RESULT = "scan_result"

        fun start(context: Context) {
            val i = Intent(context, ProximityService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProximityService::class.java))
        }

        /** Woken by the offloaded scan: car is nearby — engage (connect + approach). */
        fun notifyPresent(context: Context, result: android.bluetooth.le.ScanResult?) {
            val i = Intent(context, ProximityService::class.java)
                .setAction(ACTION_PRESENT)
                .putExtra(EXTRA_MAC, result?.device?.address)
                .putExtra(EXTRA_SCAN_RESULT, result)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        /** Woken by the offloaded scan: car left range (or the offload was dropped). */
        fun notifyPresenceLost(context: Context) {
            val i = Intent(context, ProximityService::class.java).setAction(ACTION_ABSENT)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }
    }
}
