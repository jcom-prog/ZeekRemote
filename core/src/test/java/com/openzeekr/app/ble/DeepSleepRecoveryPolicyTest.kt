package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSleepRecoveryPolicyTest {
    @Test fun `wake-capable hardware motion starts recovery immediately`() {
        assertEquals(
            DeepSleepRecoveryPolicy.WakeTiming.IMMEDIATE,
            DeepSleepRecoveryPolicy.wakeTiming(wakeCapableHardwareSignal = true),
        )
    }

    @Test fun `software activity estimate retains motion confirmation`() {
        assertEquals(
            DeepSleepRecoveryPolicy.WakeTiming.CONFIRM_MOTION,
            DeepSleepRecoveryPolicy.wakeTiming(wakeCapableHardwareSignal = false),
        )
    }

    @Test fun `confirmed-motion wake keeps the working offloaded presence route`() {
        assertEquals(
            DeepSleepRecoveryPolicy.Route.OFFLOADED_PRESENCE,
            DeepSleepRecoveryPolicy.route(offloadEnabled = true, presenceArmSucceeded = true),
        )
    }

    @Test fun `failed offload registration falls back to foreground scan`() {
        assertEquals(
            DeepSleepRecoveryPolicy.Route.FOREGROUND_SCAN,
            DeepSleepRecoveryPolicy.route(offloadEnabled = true, presenceArmSucceeded = false),
        )
    }

    @Test fun `disabled offload uses foreground scan`() {
        assertEquals(
            DeepSleepRecoveryPolicy.Route.FOREGROUND_SCAN,
            DeepSleepRecoveryPolicy.route(offloadEnabled = false, presenceArmSucceeded = true),
        )
    }

    @Test fun `recent link cannot override active deep-sleep presence recovery`() {
        assertEquals(
            DeepSleepRecoveryPolicy.Route.OFFLOADED_PRESENCE,
            DeepSleepRecoveryPolicy.keepAliveRoute(
                presenceRecoveryActive = true,
                presenceArmed = true,
                normallyAggressive = true,
            ),
        )
    }

    @Test fun `normal recent-link recovery remains aggressive outside presence window`() {
        assertEquals(
            DeepSleepRecoveryPolicy.Route.FOREGROUND_SCAN,
            DeepSleepRecoveryPolicy.keepAliveRoute(
                presenceRecoveryActive = false,
                presenceArmed = false,
                normallyAggressive = true,
            ),
        )
    }
}
