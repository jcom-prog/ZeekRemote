package com.openzeekr.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.util.Logx
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Proactively pushes this phone's provisioned DK key to any paired watch, so the watch is
 * armed the moment provisioning finishes — without the user having to open the watch app.
 *
 * The watch's [KeySyncService] (a WearableListenerService) receives it even if the watch app
 * isn't running (the Data Layer starts the service to deliver the message) and caches it in
 * the watch's encrypted store, so it survives the phone later being out of range.
 *
 * This is the push half; the watch also PULLs on open (KeySyncClient.requestKey) as a fallback.
 */
object PhoneKeyPush {
    private const val TAG = "OZWearKey"

    /** Tell any paired watch to purge its cached key (on Remove key / sign-out). Best-effort. */
    fun purgeWatches(context: Context) {
        val ctx = context.applicationContext
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                val mc = Wearable.getMessageClient(ctx)
                nodes.forEach { node ->
                    mc.sendMessage(node.id, WearKeyProtocol.PATH_PURGE, ByteArray(0))
                        .addOnSuccessListener { Log.i(TAG, "purge sent to ${node.id}"); Logx.d("prox", "watch: key purge sent") }
                        .addOnFailureListener { e -> Log.w(TAG, "purge to ${node.id} failed", e) }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "purge: connectedNodes failed", e) }
    }

    fun pushToWatches(context: Context) {
        val ctx = context.applicationContext
        // 0.4.3 (T7): the owner's "Watch key" switch gates the push as it gates the request path.
        if ((ctx as? DepsHolder)?.deps?.config?.config?.value?.watchKeyEnabled == false) {
            Log.i(TAG, "push skipped — key sharing is off"); Logx.d("prox", "watch: key refused (sharing off)"); return
        }
        val blob = DkIdentity.get(ctx).exportCredentialBlob() ?: run {
            Log.i(TAG, "push skipped — phone not provisioned yet"); return
        }
        val json = Json.encodeToString(blob).toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) { Log.i(TAG, "push: no watch connected"); return@addOnSuccessListener }
                val mc = Wearable.getMessageClient(ctx)
                nodes.forEach { node ->
                    mc.sendMessage(node.id, WearKeyProtocol.PATH_KEY, json)
                        .addOnSuccessListener { Log.i(TAG, "pushed key to ${node.id} (${json.size}B)"); Logx.d("prox", "watch: key pushed") }
                        .addOnFailureListener { e -> Log.w(TAG, "push to ${node.id} failed", e) }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "push: connectedNodes failed", e) }
    }
}
