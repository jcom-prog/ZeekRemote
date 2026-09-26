package com.openzeekr.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DkAdvertParserTest {
    @Test
    fun `extracts random from Android manufacturer data without company id`() {
        val manufacturerData = byteArrayOf(
            0x01, 0x30, 0x21, 0x43, 0x03, 0x7a, 0x24, 0xc3.toByte(), 0xd1.toByte(), 0x62,
            0x13, 0x73, 0xf2.toByte(), 0x9c.toByte(), 0x35, 0x9a.toByte(), 0x68, 0x40,
        )

        assertArrayEquals(
            byteArrayOf(0x13, 0x73, 0xf2.toByte(), 0x9c.toByte(), 0x35, 0x9a.toByte(), 0x68, 0x40),
            DkAdvertParser.parseBroadcastRnd(rawRecord = null, manufacturerData = manufacturerData),
        )
    }

    @Test
    fun `falls back to complete manufacturer AD structure from field log`() {
        val rawRecord = byteArrayOf(
            0x02, 0x01, 0x06,
            0x03, 0x03, 0xfd.toByte(), 0xfd.toByte(),
            0x15, 0xff.toByte(), 0xfe.toByte(), 0x06,
            0x01, 0x30, 0x21, 0x43, 0x03, 0x7a, 0x24, 0xc3.toByte(), 0xd1.toByte(), 0x62,
            0x13, 0x73, 0xf2.toByte(), 0x9c.toByte(), 0x35, 0x9a.toByte(), 0x68, 0x40,
            0x00,
        )

        assertArrayEquals(
            byteArrayOf(0x13, 0x73, 0xf2.toByte(), 0x9c.toByte(), 0x35, 0x9a.toByte(), 0x68, 0x40),
            DkAdvertParser.parseBroadcastRnd(rawRecord = rawRecord, manufacturerData = null),
        )
    }

    @Test
    fun `extracts random from the exact deep-sleep car advert shape`() {
        // Redacted-value reconstruction of the 0x06FE packet shape captured in
        // 0.1.20-deep-sleep-only.log. This is the packet delivered by the offloaded presence scan;
        // it must be sufficient for a direct connection without a second foreground scan.
        val random = byteArrayOf(0x13, 0x73, 0xf2.toByte(), 0x9c.toByte(), 0x35, 0x9a.toByte(), 0x68, 0x40)
        val manufacturerData = byteArrayOf(
            0x01, 0x30, 0x21, 0x43, 0x03, 0x7a, 0x24, 0xc3.toByte(), 0xd1.toByte(), 0x62,
            *random,
        )

        assertArrayEquals(
            random,
            DkAdvertParser.parseBroadcastRnd(rawRecord = null, manufacturerData = manufacturerData),
        )
    }

    @Test
    fun `does not manufacture a random from primary uuid-only split advert`() {
        val primaryOnly = byteArrayOf(
            0x02, 0x01, 0x06,
            0x03, 0x03, 0xfd.toByte(), 0xfd.toByte(),
            0x00,
        )

        org.junit.Assert.assertNull(
            DkAdvertParser.parseBroadcastRnd(rawRecord = primaryOnly, manufacturerData = null),
        )
    }
}
