package com.openzeekr.app.ble

/** Pure selection rule for split presence advertisements; kept Android-free for trace tests. */
internal object PresenceAdvertSelector {
    data class Candidate<T>(val value: T, val rssi: Int, val hasBroadcastRnd: Boolean)

    /** Prefer a connect-complete advert (broadcastRnd present), then the strongest RSSI. */
    fun <T> select(candidates: List<Candidate<T>>): T? = candidates
        .maxWithOrNull(compareBy<Candidate<T>> { it.hasBroadcastRnd }.thenBy { it.rssi })
        ?.value
}
