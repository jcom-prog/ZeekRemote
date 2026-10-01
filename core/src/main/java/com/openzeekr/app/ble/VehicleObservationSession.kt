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

    @Synchronized fun authenticated() { generation = nextGeneration.incrementAndGet(); ready = true; previous = null }
    @Synchronized fun reset() { ready = false; previous = null }
    @Synchronized fun capture(): Long? = generation.takeIf { ready }

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
