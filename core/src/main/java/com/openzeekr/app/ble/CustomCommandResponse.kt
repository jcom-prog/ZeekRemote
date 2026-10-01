package com.openzeekr.app.ble

/** Decodes an authenticated, decrypted 0x0152 body including its six-byte envelope.
 * Acceptance means that the car accepted the setting, never that it physically locked.
 * Transaction/session correlation must be enforced by the caller before parsing.
 */
internal data class CustomCommandResponse(val type: Int, val resultCode: Int) {
    val settingAccepted: Boolean get() = resultCode == 0x1000

    companion object {
        fun parse(body: ByteArray, expectedType: Int): CustomCommandResponse? {
            if (expectedType !in 1..2 || body.size < 9) return null
            val type = body[6].toInt() and 0xff
            if (type != expectedType) return null
            val code = ((body[7].toInt() and 0xff) shl 8) or
                (body[8].toInt() and 0xff)
            return CustomCommandResponse(type, code)
        }
    }
}
