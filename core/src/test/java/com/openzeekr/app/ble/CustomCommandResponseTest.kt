package com.openzeekr.app.ble

import org.junit.Assert.*
import org.junit.Test

class CustomCommandResponseTest {
    private fun body(type: Int, code: Int) = ByteArray(9).also {
        it[6] = type.toByte(); it[7] = (code ushr 8).toByte(); it[8] = code.toByte()
    }
    @Test fun bothSupportedTypesRequireExplicitAcceptedResult() {
        for (type in 1..2) {
            assertTrue(CustomCommandResponse.parse(body(type, 0x1000), type)!!.settingAccepted)
            assertFalse(CustomCommandResponse.parse(body(type, 0), type)!!.settingAccepted)
        }
    }
    @Test fun reversedBytesAreNotSuccess() {
        assertFalse(CustomCommandResponse.parse(body(2, 0x0010), 2)!!.settingAccepted)
    }
    @Test fun everyTruncatedBodyFailsClosed() {
        for (size in 0..8) assertNull(CustomCommandResponse.parse(body(2, 0x1000).copyOf(size), 2))
    }
    @Test fun unrelatedAndUnknownTypesFailClosed() {
        assertNull(CustomCommandResponse.parse(body(1, 0x1000), 2))
        for (type in listOf(-1, 0, 3, 255, 256))
            assertNull(CustomCommandResponse.parse(body(type, 0x1000), type))
    }
    @Test fun errorCodeIsUnsignedAndPreserved() {
        val response = CustomCommandResponse.parse(body(2, 0xffff), 2)!!
        assertEquals(65535, response.resultCode)
        assertFalse(response.settingAccepted)
    }
    @Test fun optionalDescriptionDoesNotChangeResultOrMutateInput() {
        val input = body(2, 0x1000) + byteArrayOf(0, -1, 65)
        val copy = input.copyOf()
        assertTrue(CustomCommandResponse.parse(input, 2)!!.settingAccepted)
        assertArrayEquals(copy, input)
    }
}
