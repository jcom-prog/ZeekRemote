package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkAwayLockConfirmationTest {
    @Test fun `close-range rebound prevents idle lock`() {
        val confirmation = WalkAwayLockConfirmation(-82)
        assertEquals(WalkAwayLockConfirmation.Decision.WAIT, confirmation.observe(-92))
        assertEquals(WalkAwayLockConfirmation.Decision.CANCEL, confirmation.observe(-79))
    }

    @Test fun `continued separation permits idle lock`() {
        val confirmation = WalkAwayLockConfirmation(-82)
        listOf(-88, -86, -87).forEach {
            assertEquals(WalkAwayLockConfirmation.Decision.WAIT, confirmation.observe(it))
        }
        assertEquals(WalkAwayLockConfirmation.Decision.LOCK, confirmation.observe(-91))
    }

    @Test fun `flat weak signal alone does not prove departure`() {
        val confirmation = WalkAwayLockConfirmation(-82)
        repeat(9) {
            assertEquals(WalkAwayLockConfirmation.Decision.WAIT, confirmation.observe(-92))
        }
    }

    @Test fun `minor noise during a real departure does not prevent confirmation`() {
        val confirmation = WalkAwayLockConfirmation(-82)
        listOf(-89, -90, -88).forEach {
            assertEquals(WalkAwayLockConfirmation.Decision.WAIT, confirmation.observe(it))
        }
        assertEquals(WalkAwayLockConfirmation.Decision.LOCK, confirmation.observe(-93))
    }

    @Test fun `first read near the threshold cannot approve one later far dip`() {
        val confirmation = WalkAwayLockConfirmation(-82)
        listOf(-83, -84, -85).forEach {
            assertEquals(WalkAwayLockConfirmation.Decision.WAIT, confirmation.observe(it))
        }
        assertEquals(WalkAwayLockConfirmation.Decision.CANCEL, confirmation.observe(-80))
    }
}
