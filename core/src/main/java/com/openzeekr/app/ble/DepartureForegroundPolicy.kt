package com.openzeekr.app.ble

/** Background entry is allowed only with all prerequisites; never degrade precise to coarse. */
internal object DepartureForegroundPolicy {
    fun allowLocation(fineGranted: Boolean, backgroundGranted: Boolean, locationEnabled: Boolean) =
        fineGranted && backgroundGranted && locationEnabled
}
