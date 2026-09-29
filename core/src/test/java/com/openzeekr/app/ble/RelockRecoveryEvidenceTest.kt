package com.openzeekr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelockRecoveryEvidenceTest {
    @Test fun confirmedVehicleRelockAfterUnlockMayRearmNewArrival() {
        assertTrue(RelockRecoveryEvidence.confirmsRelock(
            CloudLockSnapshot(true, 150_000), CloudLockSnapshot(true, 150_000),
            unlockedAtMs = 100_000, nowMs = 160_000))
        // The failed 19:13 return was more than an hour after the 18:06 unlock.
        assertTrue(RelockRecoveryEvidence.confirmsRelock(
            CloudLockSnapshot(true, 220_000), CloudLockSnapshot(true, 220_000),
            unlockedAtMs = 100_000, nowMs = 4_200_000))
    }

    @Test fun oldOrMissingCloudDataMustNotRearmNearCar() {
        for (timestamp in listOf(null, 90_000L, 100_000L)) {
            assertFalse(RelockRecoveryEvidence.confirmsRelock(
                CloudLockSnapshot(true, timestamp), CloudLockSnapshot(true, timestamp),
                unlockedAtMs = 100_000, nowMs = 160_000))
        }
        assertFalse(RelockRecoveryEvidence.confirmsRelock(
            CloudLockSnapshot(true, 150_000), CloudLockSnapshot(false, 151_000),
            unlockedAtMs = 100_000, nowMs = 160_000))
    }

    @Test fun outOfOrderOrExpiredLockStatusMustNotRearm() {
        assertFalse(RelockRecoveryEvidence.confirmsRelock(
            CloudLockSnapshot(true, 160_000), CloudLockSnapshot(true, 159_000),
            unlockedAtMs = 100_000, nowMs = 170_000))
        assertFalse(RelockRecoveryEvidence.confirmsRelock(
            CloudLockSnapshot(true, 150_000), CloudLockSnapshot(true, 150_000),
            unlockedAtMs = 100_000, nowMs = 7_350_001))
    }
}
