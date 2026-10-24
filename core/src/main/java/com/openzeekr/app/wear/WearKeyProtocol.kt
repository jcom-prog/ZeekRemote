package com.openzeekr.app.wear

/**
 * Message paths for the phone ⇄ watch digital-key clone over the Wear Data Layer.
 * Shared by :app (phone responder) and :wear (watch requester). Plain constants — no
 * Play Services dependency here.
 */
object WearKeyProtocol {
    /** Watch → phone: "send me your digital key". */
    const val PATH_REQUEST = "/openzeekr/request-key"

    /** Phone → watch: the DK credential blob (JSON of [com.openzeekr.app.ble.DkIdentity.exportCredentialBlob]). */
    const val PATH_KEY = "/openzeekr/key"

    /** Phone → watch: "the key was removed — purge your cached copy." (Sent on Remove key / sign-out.) */
    const val PATH_PURGE = "/openzeekr/purge-key"

    // ---- BLE-link arbitration (the car allows only ONE peer, so watch and phone can't both
    // hold the DK session; the watch checks with the phone before touching the car) ----

    /** Watch → phone: "are you present / holding the car link / is proximity on?" */
    const val PATH_STATUS_QUERY = "/openzeekr/status-query"

    /** Phone → watch: reply to a status query. JSON: {"connected":"0|1","prox":"0|1","key":"0|1"} - 0.4.3: "key" is
     *  the owner's "Watch key" switch; a provisioned watch that reads 0 wipes its copy (T7), "connected" means a
     *  ready session and "prox" a running engine (T10). */
    const val PATH_STATUS = "/openzeekr/status"

    /** Watch → phone: "release the car link and stand your keep-alive down, I need it." */
    const val PATH_PAUSE = "/openzeekr/pause-link"

    /** Phone → watch: "released — the car is free, go ahead." 0.4.3: payload = the hand-over id (decimal, UTF-8); the
     *  watch echoes it in DONE and RESUME. An empty payload is an older phone. */
    const val PATH_PAUSED = "/openzeekr/link-paused"

    /** Watch → phone: "I'm done — take the link back." 0.4.3: payload "<id>" or "<id>|LOCK|ok" (the outcome rides along
     *  so a RESUME that overtakes or replaces DONE still carries it); empty = older watch. */
    const val PATH_RESUME = "/openzeekr/resume-link"

    /** Watch → phone (W2): what the watch's own key just did, sent before RESUME. Payload "LOCK|ok", "LOCK|err",
     *  "UNLOCK|ok" or "UNLOCK|err" (UTF-8), 0.4.3: followed by "|<id>" inside a hand-over (none from the standalone
     *  branch). The phone's proximity engine takes a confirmed outcome as an external Lock/Unlock at once instead of
     *  inferring it from the car's next status - only for the hand-over it belongs to, or standalone while the phone
     *  holds no session (T8). */
    const val PATH_DONE = "/openzeekr/watch-done"

    // ---- cloud control relay (climate / charging / locate) ----
    // The watch has no account or TSP stack, so cloud-only actions are RELAYED to the phone,
    // which runs them (BLE-first via VehicleControl, else cloud). Lock/unlock do NOT come
    // through here — the watch actuates those itself over its cloned DK key.

    /** Watch → phone: run a cloud control. Payload = a [WearControl] name (UTF-8). */
    const val PATH_CONTROL = "/openzeekr/control"

    /** Phone → watch: result of a relayed control. Payload = "<CONTROL>|ok" or "<CONTROL>|err|<reason>". */
    const val PATH_CONTROL_ACK = "/openzeekr/control-ack"

    /** Watch → phone: "send me the current vehicle snapshot (SOC / charging / lock / reachability)." */
    const val PATH_SNAPSHOT_QUERY = "/openzeekr/snapshot-query"

    /** Phone → watch: a [WearVehicleSnapshot] as JSON. */
    const val PATH_SNAPSHOT = "/openzeekr/snapshot"
}
