package com.openzeekr.app.ble

/** Extracts the per-advert Digital-Key random from the Zeekr manufacturer packet. */
internal object DkAdvertParser {
    private const val DK_PACKET_SIZE = 20
    private const val COMPANY_ID_SIZE = 2
    private const val RANDOM_OFFSET_IN_PACKET = 12
    private const val RANDOM_SIZE = 8

    /**
     * Android normally exposes manufacturer data without its two-byte company id. Some Samsung
     * scan paths, however, only leave the complete AD structure in [rawRecord]. Accept both forms:
     * the structured value first, then the raw advertisement as a compatibility fallback.
     */
    fun parseBroadcastRnd(rawRecord: ByteArray?, manufacturerData: ByteArray?): ByteArray? {
        parseManufacturerData(manufacturerData)?.let { return it }
        return parseRawRecord(rawRecord)
    }

    private fun parseManufacturerData(data: ByteArray?): ByteArray? {
        if (data == null) return null
        // ScanRecord.manufacturerSpecificData strips the two-byte company id. In the complete
        // 20-byte stock-app packet the random occupies [12,20), hence [10,18) in this value.
        val offset = RANDOM_OFFSET_IN_PACKET - COMPANY_ID_SIZE
        if (data.size < offset + RANDOM_SIZE) return null
        return data.copyOfRange(offset, offset + RANDOM_SIZE)
    }

    private fun parseRawRecord(record: ByteArray?): ByteArray? {
        if (record == null) return null
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 1 + len > record.size) break
            val type = record[i + 1].toInt() and 0xFF
            if (type == 0xFF && len - 1 >= DK_PACKET_SIZE) {
                val packetStart = i + 2
                return record.copyOfRange(
                    packetStart + RANDOM_OFFSET_IN_PACKET,
                    packetStart + RANDOM_OFFSET_IN_PACKET + RANDOM_SIZE,
                )
            }
            i += len + 1
        }
        return null
    }
}
