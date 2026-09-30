package com.openzeekr.app.ble

import org.junit.Assert.*
import org.junit.Test

class DepartureForegroundPolicyTest {
    @Test fun backgroundStartWithAllPrerequisitesMayUseLocationType() {
        assertTrue(DepartureForegroundPolicy.allowLocation(true, true, true))
    }
    @Test fun missingFineOrBackgroundPermissionCannotUseLocationType() {
        assertFalse(DepartureForegroundPolicy.allowLocation(false, true, true))
        assertFalse(DepartureForegroundPolicy.allowLocation(true, false, true))
    }
    @Test fun disabledLocationCannotUseLocationTypeEvenWithPermissions() {
        assertFalse(DepartureForegroundPolicy.allowLocation(true, true, false))
    }
}
