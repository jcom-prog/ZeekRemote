package com.openzeekr.app.ble

import org.junit.Assert.*
import org.junit.Test

class VehicleProximityStatusTest {
    @Test fun exhaustiveByteValuesPreserveIndependentFlags() {
        for (value in 0..255) {
            val body = ByteArray(20).also { it[18] = value.toByte() }
            val s = VehicleProximityStatus.parse(body)!!
            assertEquals(if (value and 128 != 0) 1 else 0, s.approachSwitchBit)
            assertEquals(if (value and 64 != 0) 1 else 0, s.walkAwaySwitchBit)
            assertEquals(if (value and 16 != 0) 1 else 0, s.passiveEntryBit)
            assertEquals(if (value and 32 != 0) 1 else 0, s.passiveStartBit)
        }
    }
    @Test fun allTruncatedFramesRemainUnknown() {
        for (size in 0..19) assertNull(VehicleProximityStatus.parse(ByteArray(size)))
    }
    @Test fun centralLockCodesRemainRawWithoutClosedInterpretation() {
        for (value in 0..255) {
            val body = ByteArray(20).also { it[17] = value.toByte() }
            assertEquals(value and 3, VehicleProximityStatus.parse(body)!!.centralLockCode)
        }
    }
    @Test fun envelopeAndExtraBytesDoNotBecomeStatus() {
        val body = ByteArray(28) { -1 }.also { it[17] = 0; it[18] = 0 }
        assertEquals(VehicleProximityStatus(0, 0, 0, 0, 0), VehicleProximityStatus.parse(body))
    }
    @Test fun parsingDoesNotMutateOrRetainInput() {
        val body = ByteArray(20).also { it[18] = 64 }
        val copy = body.copyOf()
        val s = VehicleProximityStatus.parse(body)!!
        assertArrayEquals(copy, body)
        body[18] = 0
        assertEquals(1, s.walkAwaySwitchBit)
        assertEquals(0, VehicleProximityStatus.parse(body)!!.walkAwaySwitchBit)
        assertNull(VehicleProximityStatus.parse(ByteArray(19)))
    }
}
