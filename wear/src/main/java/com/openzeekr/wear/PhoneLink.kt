package com.openzeekr.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.openzeekr.app.wear.WearControl
import com.openzeekr.app.wear.WearKeyProtocol
import com.openzeekr.app.wear.WearVehicleSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "OZWearKey"

/**
 * Watch → phone BLE-link arbitration. The car allows only ONE BLE peer, so before the watch
 * touches the car it asks the phone what it's doing and, when needed, has the phone release
 * the link first. See [com.openzeekr.app.wear.WearLinkArbiter] (phone side).
 *
 * Request/reply over the Data Layer is modelled with a single-slot [CompletableDeferred] per
 * conversation: the waiter is armed *before* the outgoing message is sent, and the inbound
 * [KeySyncService] completes it whenever the reply lands — so there's no subscribe-race and a
 * late reply is never lost. Only one arbitration runs at a time (the UI's `busy` guard).
 */
object PhoneLink {
    /** [keyShared] (0.4.3, T7): the owner's "Watch key" switch as the phone reports it; false = wipe our copy. */
    data class Status(val present: Boolean, val connected: Boolean, val proximityEnabled: Boolean, val keyShared: Boolean = true) {
        companion object { val ABSENT = Status(present = false, connected = false, proximityEnabled = false) }
    }

    @Volatile private var statusWaiter: CompletableDeferred<Status>? = null
    @Volatile private var pauseWaiter: CompletableDeferred<String>? = null
    @Volatile private var snapshotWaiter: CompletableDeferred<WearVehicleSnapshot>? = null
    @Volatile private var controlWaiter: CompletableDeferred<String>? = null
    /** 0.4.3 (H4): the control whose ack we are waiting for; an ack naming another control is a late one and is dropped. */
    @Volatile private var pendingControl: String? = null
    /** 0.4.3 (T6): the id the phone gave the running hand-over (its "paused" ack); "" for an older phone or none. */
    @Volatile private var handOverId: String = ""
    /** 0.4.3 (H11): one conversation with the phone at a time - the 4 s poll and a tap never share a waiter. */
    private val conversation = Mutex()

    /** Latest cloud snapshot the phone has sent (SOC / charging / lock / reachability). Observed
     *  by the watch UI so the charge card + cloud-control greying stay current between queries. */
    val snapshot = MutableStateFlow<WearVehicleSnapshot?>(null)

    /** Called by [KeySyncService] when the phone answers a status query. */
    fun onStatus(connected: Boolean, prox: Boolean, keyShared: Boolean = true) {
        statusWaiter?.complete(Status(present = true, connected = connected, proximityEnabled = prox, keyShared = keyShared))
    }

    /** Called by [KeySyncService] when the phone acks that it released the car link; [payload] = the hand-over id (0.4.3). */
    fun onPaused(payload: String) { pauseWaiter?.complete(payload.trim()) }

    /** Called by [KeySyncService] when a vehicle snapshot arrives. */
    fun onSnapshot(json: String) {
        val snap = WearVehicleSnapshot.decode(json)
        snapshot.value = snap
        snapshotWaiter?.complete(snap)
    }

    /** Called by [KeySyncService] with a relayed-control result ("<CONTROL>|ok" / "…|err|…"). 0.4.3 (H4): only the ack
     *  of the control we are waiting for counts; a late ack of an earlier one would confirm the wrong chip. */
    fun onControlAck(text: String) {
        val name = text.substringBefore("|").trim()
        if (name == pendingControl) controlWaiter?.complete(text)
        else Log.w(TAG, "late or stray control ack for $name ignored (waiting for ${pendingControl ?: "none"})")
    }

    /** Ask the phone for the current vehicle snapshot. null if no phone or no reply. */
    suspend fun querySnapshot(ctx: Context): WearVehicleSnapshot? = conversation.withLock {
        val node = phoneNodeId(ctx) ?: return null
        val d = CompletableDeferred<WearVehicleSnapshot>()
        snapshotWaiter = d
        sendTo(ctx, node, WearKeyProtocol.PATH_SNAPSHOT_QUERY)
        withTimeoutOrNull(STATUS_TIMEOUT_MS) { d.await() }.also { snapshotWaiter = null }
    }

    /** Relay a cloud control to the phone. [arg] carries an extra value (e.g. the climate target
     *  temperature). Returns null on success, or a short error string (incl. "no phone" when
     *  nothing is paired — cloud controls need the phone). */
    suspend fun control(ctx: Context, c: WearControl, arg: String? = null): String? = conversation.withLock {
        val node = phoneNodeId(ctx) ?: return "no phone"
        val d = CompletableDeferred<String>()
        pendingControl = c.name
        controlWaiter = d
        val payload = if (arg != null) "${c.name}|$arg" else c.name
        sendTo(ctx, node, WearKeyProtocol.PATH_CONTROL, payload.toByteArray(Charsets.UTF_8))
        val ack = withTimeoutOrNull(CONTROL_TIMEOUT_MS) { d.await() }.also { controlWaiter = null; pendingControl = null }
            ?: return "no response"
        // ack = "<CONTROL>|ok" or "<CONTROL>|err|<reason>"
        val parts = ack.split("|")
        if (parts.getOrNull(1) == "ok") null else (parts.getOrNull(2) ?: "failed")
    }

    /** Ask the phone for its link status. [Status.ABSENT] if there's no phone node or it doesn't reply. */
    suspend fun queryStatus(ctx: Context): Status = conversation.withLock {
        val node = phoneNodeId(ctx) ?: return Status.ABSENT
        val d = CompletableDeferred<Status>()
        statusWaiter = d
        sendTo(ctx, node, WearKeyProtocol.PATH_STATUS_QUERY)
        (withTimeoutOrNull(STATUS_TIMEOUT_MS) { d.await() } ?: Status.ABSENT).also { statusWaiter = null }
    }

    /** Ask the phone to release the car link; true once it acks (false on timeout). The ack's hand-over id is kept for
     *  [doneAndResume]. 0.4.3 (H5): on a timeout the caller must still [doneAndResume] - the phone may have released the
     *  car already and would otherwise stay muted for its whole 45 s lease. */
    suspend fun pauseAndAwait(ctx: Context): Boolean = conversation.withLock {
        val node = phoneNodeId(ctx) ?: return true // no phone → nothing holds the link
        val d = CompletableDeferred<String>()
        pauseWaiter = d
        sendTo(ctx, node, WearKeyProtocol.PATH_PAUSE)
        val id = withTimeoutOrNull(PAUSE_TIMEOUT_MS) { d.await() }.also { pauseWaiter = null }
        handOverId = id ?: ""
        id != null
    }

    /** W2 (R1): tell the phone what the watch's own key just did from the STANDALONE branch (no hand-over, no id) -
     *  best effort; the phone applies it only while it holds no link itself. */
    fun done(ctx: Context, lockIt: Boolean, ok: Boolean) {
        val payload = (if (lockIt) "LOCK" else "UNLOCK") + "|" + (if (ok) "ok" else "err")
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { sendTo(ctx, it.id, WearKeyProtocol.PATH_DONE, payload.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** 0.4.3 (H12/T4): end the hand-over in order - the outcome (DONE "<what>|<ok>|<id>", when there is one) goes out and
     *  its delivery is awaited, then RESUME "<id>[|<what>|<ok>]" so a lost DONE still reaches the phone once. [lockIt] null:
     *  nothing was done (a pause timeout, H5) - the phone just takes the link back. */
    suspend fun doneAndResume(ctx: Context, lockIt: Boolean?, ok: Boolean) {
        val id = handOverId
        val outcome = lockIt?.let { (if (it) "LOCK" else "UNLOCK") + "|" + (if (ok) "ok" else "err") }
        val node = phoneNodeId(ctx)
        if (node != null) {
            if (outcome != null) sendAwait(ctx, node, WearKeyProtocol.PATH_DONE, "$outcome|$id".toByteArray(Charsets.UTF_8))
            sendTo(ctx, node, WearKeyProtocol.PATH_RESUME, (id + (outcome?.let { "|$it" } ?: "")).toByteArray(Charsets.UTF_8))
        }
        handOverId = ""
    }

    /** The paired phone's node id - 0.4.3 (H6/H10): only a node that is directly connected ([com.google.android.gms.wearable.Node.isNearby]);
     *  a phone reachable through the cloud only is not at the car. Bounded, and a cancelled lookup resolves too. */
    private suspend fun phoneNodeId(ctx: Context): String? = withTimeoutOrNull(NODE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            Wearable.getNodeClient(ctx).connectedNodes
                .addOnSuccessListener { nodes -> if (cont.isActive) cont.resume(nodes.firstOrNull { it.isNearby }?.id) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        }
    }

    private fun sendTo(ctx: Context, nodeId: String, path: String, data: ByteArray = ByteArray(0)) {
        Wearable.getMessageClient(ctx).sendMessage(nodeId, path, data)
            .addOnFailureListener { e -> Log.w(TAG, "send $path failed", e) }
    }

    /** Send and wait (bounded) until the Data Layer accepted the message - ordering for DONE before RESUME. */
    private suspend fun sendAwait(ctx: Context, nodeId: String, path: String, data: ByteArray): Boolean =
        withTimeoutOrNull(NODE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                Wearable.getMessageClient(ctx).sendMessage(nodeId, path, data)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                    .addOnFailureListener { e -> Log.w(TAG, "send $path failed", e); if (cont.isActive) cont.resume(false) }
                    .addOnCanceledListener { if (cont.isActive) cont.resume(false) }
            }
        } ?: false

    private const val STATUS_TIMEOUT_MS = 3_000L
    /** 0.4.3 (H5): the phone needs up to ~4 s to release the car, plus two Data Layer hops and a possible cold start. */
    private const val PAUSE_TIMEOUT_MS = 10_000L
    private const val CONTROL_TIMEOUT_MS = 12_000L
    private const val NODE_TIMEOUT_MS = 3_000L
}
