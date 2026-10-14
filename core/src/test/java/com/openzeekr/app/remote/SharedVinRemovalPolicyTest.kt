package com.openzeekr.app.remote

import com.openzeekr.app.remote.SharedVinRemovalPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan K3 (0.3.0): when may a missing car be deactivated locally? Synthetic VINs only. */
class SharedVinRemovalPolicyTest {
    private val vin = "TESTVIN0000000001"
    private val other = listOf("TESTVIN0000000002")
    private val min = 60_000L

    @Test fun anEmptyOrFailedListIsNoEvidenceHoweverOftenItComes() {
        val p = SharedVinRemovalPolicy()
        repeat(5) { i ->
            assertEquals(Decision.NO_EVIDENCE, p.observe(vin, false, true, emptyList(), i * 20 * min))
            assertEquals(Decision.NO_EVIDENCE, p.observe(vin, false, false, other, i * 20 * min + 1))
        }
    }

    @Test fun anOwnersCarIsNeverDeactivatedFromAList() {
        val p = SharedVinRemovalPolicy()
        repeat(3) { i -> assertEquals(Decision.NO_EVIDENCE, p.observe(vin, true, true, other, i * 20 * min)) }
    }

    @Test fun aSharedCarMissingTwiceTenMinutesApartIsDeactivated() {
        val p = SharedVinRemovalPolicy()
        assertEquals(Decision.MISSING_ONCE, p.observe(vin, false, true, other, 0L))
        // Opening the Vehicle tab again seconds later is no second, independent observation.
        assertEquals(Decision.MISSING_TOO_SOON, p.observe(vin, false, true, other, 5_000L))
        assertEquals(Decision.MISSING_TOO_SOON, p.observe(vin, false, true, other, 9 * min))
        assertEquals(Decision.DEACTIVATE, p.observe(vin, false, true, other, 10 * min))
    }

    @Test fun theCarReappearingResetsTheEvidence() {
        val p = SharedVinRemovalPolicy()
        assertEquals(Decision.MISSING_ONCE, p.observe(vin, false, true, other, 0L))
        assertEquals(Decision.PRESENT, p.observe(vin, false, true, other + vin, 5 * min))
        assertEquals(Decision.MISSING_ONCE, p.observe(vin, false, true, other, 11 * min))
        assertEquals(Decision.MISSING_TOO_SOON, p.observe(vin, false, true, other, 15 * min))
    }

    @Test fun noActiveCarNothingToDo() {
        assertEquals(Decision.NO_EVIDENCE, SharedVinRemovalPolicy().observe("", false, true, other, 0L))
    }
}
