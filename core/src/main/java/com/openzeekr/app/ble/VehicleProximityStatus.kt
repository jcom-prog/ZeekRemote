package com.openzeekr.app.ble

/** Read-only diagnostics for an authenticated, decrypted 0x0121 body with its six-byte envelope.
 * Raw bits/codes only: they prove neither departure nor physical locking. No Lock authorization.
 * The caller must establish vehicle identity, current crypto session and freshness separately.
 */
internal data class VehicleProximityStatus(
    val approachSwitchBit: Int,
    val walkAwaySwitchBit: Int,
    val passiveEntryBit: Int,
    val passiveStartBit: Int,
    val centralLockCode: Int,
) {
    companion object {
        fun parse(body: ByteArray): VehicleProximityStatus? {
            if (body.size < 6 + 14) return null
            val switches = body[6 + 12].toInt() and 0xff
            return VehicleProximityStatus(
                approachSwitchBit = (switches ushr 7) and 1,
                walkAwaySwitchBit = (switches ushr 6) and 1,
                passiveEntryBit = (switches ushr 4) and 1,
                passiveStartBit = (switches ushr 5) and 1,
                centralLockCode = body[6 + 11].toInt() and 3,
            )
        }
    }
}
