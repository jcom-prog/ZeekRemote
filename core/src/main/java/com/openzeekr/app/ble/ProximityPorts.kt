package com.openzeekr.app.ble

import kotlinx.coroutines.flow.StateFlow

/*
 * The platform edges of [ProximityController]. Production wires the Android implementations; the
 * whole-app simulator (core/src/test/.../sim) wires a simulated phone, car and walker so the real
 * controller code runs on virtual time. No behaviour lives here.
 */

/** Monotonic (elapsed, includes deep sleep) and wall-clock time. */
interface ProximityClock {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

object SystemProximityClock : ProximityClock {
    override fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime()
    /** Never jumps when the phone's clock is set (plan H7): see [com.openzeekr.app.util.SteadyClock]. */
    override fun wallMs(): Long = com.openzeekr.app.util.SteadyClock.wallMs()
}

/** The digital-key link as the proximity loop sees it (implemented by [DkBleManager]). */
interface ProximityLink {
    val state: StateFlow<DkBleManager.State>
    val session: DkSession
    var onInboundActivity: (() -> Unit)?
    val lastInboundMs: Long
    suspend fun pollRemoteRssi(): Int?
    fun reconnectLast(): Boolean
    fun connect(deviceMac: String?)
    fun disconnect()
    fun noteUnlockConfirmed()
}

/** Still/moving signal (implemented by [MotionMonitor]). */
interface ProximityMotion {
    val state: StateFlow<MotionMonitor.Motion>
    val source: MotionMonitor.Source
    val hasSource: Boolean
    val hasStepAssist: Boolean
    val observedSteps: Long?
    /** Diagnostic: the step sources behind [observedSteps] (no positions, no identifiers). */
    val stepSummary: String get() = ""
    /** The hub step counter's own running total (null without one); [observedSteps] is the larger of
     *  it and the detector, which hides counter steps once the detector is ahead. */
    val counterSteps: Long? get() = null
    var onMovingEdge: (() -> Unit)?
    /** Measurement version (doorbraak C): the app's own step counter window - opened while the car is
     *  believed unlocked, re-extended by every strong at-the-car reading, logs only. Default: nothing. */
    fun ownWindow(active: Boolean) {}
    /** 0.3.27: Measurement mode opens the same window without the car (a walk during the garage week measures the
     *  own counter, the turn and the battery per window); the controller closes it unless the car's window runs. */
    fun ownMeasurementWindow(active: Boolean) {}
    /** 0.3.20: MOVING only because Activity Recognition reported a drive (IN_VEHICLE) and nothing has shown walking
     *  since - no walking transition, no step, no STILL (field 16/10 and 19/10: MOVING for minutes after the drive). */
    val vehicleMotion: Boolean get() = false
    fun start()
    fun stop()
}

/** Lock warnings and alarms (implemented by [UnverifiedLockNotifier]). */
internal interface ProximityAlerts {
    fun show(event: LockAlertPolicy.Event, bleDepartureRouteActive: Boolean = false): Boolean
    fun clear()
    fun silenceAlarm()
    /**
     * 0.3.10: a new alarm episode - the next audible event may sound at once (the reminder interval
     * starts again), while an alarm that is sounding keeps sounding.
     */
    fun newAlarmEpisode()
    /** The car itself reported locked: the audible "Auto gesloten" notification (user request 05/10). */
    fun carLocked()
}

internal class AndroidProximityAlerts(private val context: android.content.Context) : ProximityAlerts {
    override fun show(event: LockAlertPolicy.Event, bleDepartureRouteActive: Boolean): Boolean =
        UnverifiedLockNotifier.show(context, event, bleDepartureRouteActive)
    override fun clear() = UnverifiedLockNotifier.clear(context)
    override fun silenceAlarm() = UnverifiedLockNotifier.silenceAlarm(context)
    override fun newAlarmEpisode() = UnverifiedLockNotifier.newAlarmEpisode()
    override fun carLocked() = CarLockedNotifier.show(context)
}

/** GNSS departure evidence (implemented by [DepartureLocationSource]). */
internal interface DepartureLocator {
    suspend fun nearAnchor(policy: NearDepartureAnchor, enabled: () -> Boolean): DepartureFix?
    suspend fun confirmsDeparture(anchor: DepartureFix, steps: () -> Long?, enabled: () -> Boolean,
                                  bleCorroborated: () -> Boolean = { false },
                                  /** 0.3.25: what this visit's earlier windows learnt, and when the phone last moved (fix clock). */
                                  memory: DepartureFixMemory? = null,
                                  lastMotionAtMs: () -> Long = { Long.MAX_VALUE }): Boolean
}

/**
 * Survives a process restart (plan K2): the app's belief that the car is open after an unlock.
 * Field + simulator F37: an update, One UI or a crash restarted the app while the car was open, the
 * new process believed "locked" and neither locked nor alarmed when the user walked away.
 */
interface ProximityStateStore {
    data class Saved(val armedUnlocked: Boolean, val atWallMs: Long, val source: String)
    fun load(): Saved?
    fun save(saved: Saved)

    object None : ProximityStateStore {
        override fun load(): Saved? = null
        override fun save(saved: Saved) {}
    }
}

/** Plain app-private preferences: no secrets, written synchronously so a kill right after keeps it. */
internal class SharedPrefsProximityStateStore(context: android.content.Context) : ProximityStateStore {
    private val prefs = context.getSharedPreferences("proximity_state", android.content.Context.MODE_PRIVATE)
    override fun load(): ProximityStateStore.Saved? = if (!prefs.contains("armed")) null else
        ProximityStateStore.Saved(prefs.getBoolean("armed", false), prefs.getLong("at", 0L), prefs.getString("source", "") ?: "")
    override fun save(saved: ProximityStateStore.Saved) {
        prefs.edit().putBoolean("armed", saved.armedUnlocked).putLong("at", saved.atWallMs)
            .putString("source", saved.source).commit()
    }
}

/** Bounded partial wakelocks for safety checks. */
interface SafetyWakeLocks {
    fun acquire(timeoutMs: Long): Any?
    fun release(token: Any?)
}

internal class AndroidSafetyWakeLocks(private val context: android.content.Context) : SafetyWakeLocks {
    override fun acquire(timeoutMs: Long): Any? = runCatching {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "openzeekr:lock-check").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }.getOrNull()

    override fun release(token: Any?) {
        runCatching { (token as? android.os.PowerManager.WakeLock)?.let { if (it.isHeld) it.release() } }
    }
}
