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
    override fun wallMs(): Long = System.currentTimeMillis()
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
    var onMovingEdge: (() -> Unit)?
    fun start()
    fun stop()
}

/** Lock warnings and alarms (implemented by [UnverifiedLockNotifier]). */
internal interface ProximityAlerts {
    fun show(event: LockAlertPolicy.Event, bleDepartureRouteActive: Boolean = false): Boolean
    fun clear()
    fun silenceAlarm()
}

internal class AndroidProximityAlerts(private val context: android.content.Context) : ProximityAlerts {
    override fun show(event: LockAlertPolicy.Event, bleDepartureRouteActive: Boolean): Boolean =
        UnverifiedLockNotifier.show(context, event, bleDepartureRouteActive)
    override fun clear() = UnverifiedLockNotifier.clear(context)
    override fun silenceAlarm() = UnverifiedLockNotifier.silenceAlarm(context)
}

/** GNSS departure evidence (implemented by [DepartureLocationSource]). */
internal interface DepartureLocator {
    suspend fun nearAnchor(policy: NearDepartureAnchor, enabled: () -> Boolean): DepartureFix?
    suspend fun confirmsDeparture(anchor: DepartureFix, steps: () -> Long?, enabled: () -> Boolean,
                                  bleCorroborated: () -> Boolean = { false }): Boolean
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
