package com.openzeekr.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximitySensitivityTest {
    @Test
    fun farStartsEarlierButKeepsProvenWalkAwayThreshold() {
        val config = SecretsConfig(proximitySensitivity = "far")

        assertEquals(-86, config.sensitivityUnlockRssi)
        assertEquals(-82, config.sensitivityLockRssi)
        assertTrue(config.sensitivityUnlockRssi < config.sensitivityLockRssi)
    }

    @Test
    fun everyStoredPresetUsesTheCalibratedFarThresholds() {
        // 0.3.4: the selector is gone; a config stored with an old preset (or the old default
        // "close") gets the thresholds the BLE departure route and the simulator were calibrated on.
        for (preset in listOf("veryclose", "close", "far", "")) {
            val config = SecretsConfig(proximitySensitivity = preset)
            assertEquals(preset, -86, config.sensitivityUnlockRssi)
            assertEquals(preset, -82, config.sensitivityLockRssi)
            assertEquals(preset, com.openzeekr.app.ble.BleDepartureRoute.CALIBRATED_LOCK_RSSI, config.sensitivityLockRssi)
        }
        assertEquals("far", SecretsConfig().proximitySensitivity)
    }
}
