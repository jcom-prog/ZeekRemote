package com.openzeekr.app.ble

/** Require fresh, continued separation after an RSSI candidate requests an automatic lock. */
internal class WalkAwayLockConfirmation(private val lockThreshold: Int) {
    enum class Decision { WAIT, CANCEL, LOCK }

    private var firstFar: Int? = null
    private var farCount = 0

    fun observe(rssi: Int): Decision {
        if (rssi > lockThreshold) return Decision.CANCEL
        if (firstFar == null) firstFar = rssi
        farCount++
        return if (farCount >= 4 && rssi <= firstFar!! - 2) Decision.LOCK else Decision.WAIT
    }
}
