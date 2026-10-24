package com.openzeekr.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.wear.WearKeyProtocol
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val TAG = "OZWearKey"

/**
 * Receives the phone's cloned DK credential over the Wear Data Layer and stores it locally,
 * then arms the BLE session ([WearKeyState.refresh]). Runs in the app process, so the
 * [WearKeyState] flow it updates is the same one the UI observes.
 */
class KeySyncService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearKeyProtocol.PATH_KEY -> importKey(event)
            WearKeyProtocol.PATH_PURGE -> purgeLocal("Key removed on phone")
            WearKeyProtocol.PATH_STATUS -> {
                val m = runCatching { Json.decodeFromString<Map<String, String>>(String(event.data, Charsets.UTF_8)) }.getOrNull()
                val keyShared = m?.get("key") != "0"
                PhoneLink.onStatus(connected = m?.get("connected") == "1", prox = m?.get("prox") == "1", keyShared = keyShared)
                // 0.4.3 (T7): the owner's "Watch key" switch reaches a watch that was out of range when it was flipped.
                if (!keyShared && WearKeyState.provisioned.value) purgeLocal("Key sharing is off on the phone")
            }
            WearKeyProtocol.PATH_PAUSED -> PhoneLink.onPaused(String(event.data, Charsets.UTF_8))
            WearKeyProtocol.PATH_SNAPSHOT -> PhoneLink.onSnapshot(String(event.data, Charsets.UTF_8))
            WearKeyProtocol.PATH_CONTROL_ACK -> PhoneLink.onControlAck(String(event.data, Charsets.UTF_8))
        }
    }

    /** Wipe the key on disk AND in memory (0.4.3 H9), drop any session, tell the UI why. */
    private fun purgeLocal(reason: String) {
        Log.i(TAG, "watch purging cached key: $reason")
        runCatching { DkIdentity.get(this).wipeAll() }
        val ble = com.openzeekr.app.ble.DkBleManager.get(this)
        ble.clearCredential()
        runCatching { ble.disconnect() }
        WearKeyState.status.value = reason
        WearKeyState.refresh(this)
    }

    private fun importKey(event: MessageEvent) {
        val raw = String(event.data, Charsets.UTF_8)
        Log.i(TAG, "watch received key reply, ${event.data.size} bytes")
        val blob = runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
        }.getOrNull()
        if (blob.isNullOrEmpty()) {
            Log.w(TAG, "reply had no key — phone is not provisioned")
            WearKeyState.status.value = "Phone has no key yet — provision it first"
            return
        }
        // 0.4.3 (H14): a malformed blob must not take the watch process down on the binder thread.
        val imported = runCatching {
            DkIdentity.get(this).importCredentialBlob(blob)
            WearKeyState.refresh(this)
        }
        if (imported.isFailure || !WearKeyState.provisioned.value) {
            Log.w(TAG, "key import failed", imported.exceptionOrNull())
            WearKeyState.status.value = "Key data from the phone was not usable — retry"
            return
        }
        WearKeyState.status.value = null
        Log.i(TAG, "watch imported key (${blob.size} fields), provisioned=${WearKeyState.provisioned.value}")
    }
}

/** Asks the paired phone (running OpenZeekr) to send its digital key. */
object KeySyncClient {
    /** 0.4.3 (H8): one request in flight at a time - the UI's lifecycle effect is the only caller; a reply that does not
     *  come within [ANSWER_TIMEOUT_MS] is said so (the phone app missing or old), instead of "asking" forever. */
    @Volatile private var inFlightUntilMs = 0L
    private const val IN_FLIGHT_MS = 5_000L
    private const val ANSWER_TIMEOUT_MS = 8_000L
    private const val ASKING = "Asking phone for the key…"

    fun requestKey(context: Context) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        if (now < inFlightUntilMs) return
        inFlightUntilMs = now + IN_FLIGHT_MS
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                Log.i(TAG, "connectedNodes=${nodes.size} nearby=${nodes.count { it.isNearby }}")
                if (nodes.isEmpty()) {
                    WearKeyState.status.value = "Phone not connected — open Galaxy Wearable"
                    return@addOnSuccessListener
                }
                WearKeyState.status.value = ASKING
                val mc = Wearable.getMessageClient(ctx)
                nodes.forEach { node ->
                    mc.sendMessage(node.id, WearKeyProtocol.PATH_REQUEST, ByteArray(0))
                        .addOnSuccessListener { Log.i(TAG, "request sent to ${node.id}") }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "send to ${node.id} failed", e)
                            WearKeyState.status.value = "Couldn't reach phone app"
                        }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!WearKeyState.provisioned.value && WearKeyState.status.value == ASKING)
                        WearKeyState.status.value = "No answer from the phone — retry"
                }, ANSWER_TIMEOUT_MS)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "connectedNodes failed", e)
                WearKeyState.status.value = "Can't reach phone (Play Services?)"
            }
    }
}
