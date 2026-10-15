package com.openzeekr.app.ble

import java.util.concurrent.atomic.AtomicLong

/** Diagnostic scope only. The caller passes successfully authenticated/decrypted frames.
 * Capturing before decryption and checking afterwards prevents a reset from relabelling old data.
 * Never supplies departure or command-success authorization.
 */
internal class VehicleObservationSession {
    private var generation = 0L
    private var ready = false
    private var previous: VehicleProximityStatus? = null

    /** A status decrypted under the new session keys before authentication completed. */
    private var held: ByteArray? = null
    private var statusSinceAuth = false

    /**
     * Starts a new observation scope and returns the status the car pushed during the handshake,
     * if any, to apply to it. The car pushes its status once at the start of a session, before
     * "established", and afterwards only on a change (field test): dropping
     * it left a stale "locked" while the car had been opened with the key asleep.
     */
    @Synchronized fun authenticated(): ByteArray? {
        generation = nextGeneration.incrementAndGet(); ready = true; previous = null
        statusSinceAuth = false
        return held.also { held = null }
    }
    @Synchronized fun reset() { ready = false; previous = null; held = null }
    /** A new handshake: anything held belongs to the old keys. */
    @Synchronized fun handshakeStarted() { held = null }
    @Synchronized fun capture(): Long? = generation.takeIf { ready }

    /**
     * A status frame decrypted under the current session keys, captured at [captured]. Returns the
     * generation to apply it to now, or null when it is held for [authenticated] (or superseded).
     */
    @Synchronized fun offer(captured: Long?, decryptedBody: ByteArray): Long? {
        val current = generation.takeIf { ready }
        return when {
            captured != null -> { statusSinceAuth = true; captured }
            current == null -> { held = decryptedBody; null }
            // A handshake-time frame landing just after authentication is still the newest one.
            !statusSinceAuth -> { statusSinceAuth = true; current }
            else -> null
        }
    }


    @Synchronized fun changedStatus(captured: Long?, decryptedBody: ByteArray): String? {
        if (!ready || captured == null || captured != generation) return null
        val status = VehicleProximityStatus.parse(decryptedBody) ?: return null
        if (status == previous) return null
        previous = status
        return "vehicle status observed session=$generation approach=${status.approachSwitchBit} " +
            "walkAway=${status.walkAwaySwitchBit} pe=${status.passiveEntryBit} " +
            "ps=${status.passiveStartBit} central=${status.centralLockCode}"
    }

    companion object { private val nextGeneration = AtomicLong(0L) }
}
