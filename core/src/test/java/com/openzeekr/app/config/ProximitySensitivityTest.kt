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
}
