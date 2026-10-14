package com.openzeekr.app.remote

/**
 * When may the app deactivate the active car locally because the cloud no longer lists it?
 * (plan K3, 0.3.0). Deactivating clears the VIN, which disables the digital key in this app, so it
 * needs strong evidence that a SHARE ended:
 *  - only a shared car: an owner's car is never deactivated from a vehicle list;
 *  - only a successful, non-empty list that names other cars but not this one: an empty or failed
 *    reply says nothing about the share (cloud hiccup, region or session problem);
 *  - twice, at least [minGapMs] apart: the old rule took two calls, which opening the Vehicle tab
 *    twice within seconds could produce from one transient reply.
 * The car reappearing in any later list resets the evidence. Pure Kotlin.
 */
internal class SharedVinRemovalPolicy(private val minGapMs: Long = MIN_GAP_MS) {
    enum class Decision { PRESENT, NO_EVIDENCE, MISSING_ONCE, MISSING_TOO_SOON, DEACTIVATE }

    private var firstMissingAtMs: Long? = null

    fun observe(activeVin: String, isOwner: Boolean, listSucceeded: Boolean, listedVins: List<String?>,
                nowMs: Long): Decision {
        if (activeVin.isBlank()) return Decision.NO_EVIDENCE
        if (listedVins.any { it == activeVin }) { firstMissingAtMs = null; return Decision.PRESENT }
        if (!listSucceeded || listedVins.none { !it.isNullOrBlank() } || isOwner) return Decision.NO_EVIDENCE
        val first = firstMissingAtMs
        if (first == null || nowMs < first) { firstMissingAtMs = nowMs; return Decision.MISSING_ONCE }
        if (nowMs - first < minGapMs) return Decision.MISSING_TOO_SOON
        firstMissingAtMs = null
        return Decision.DEACTIVATE
    }

    companion object {
        const val MIN_GAP_MS = 10 * 60_000L
    }
}
