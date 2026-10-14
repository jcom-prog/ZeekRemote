package com.openzeekr.app.ble

/**
 * When a still phone may put the digital key to sleep (ProximityService security sleep).
 *
 * Field 10/10 16:44-16:51 (0.2.6): the phone lay in the car (-46..-49 dBm) while the user stood
 * beside it with the door open. After 2 min of stillness the key slept, the car no longer found it
 * (~16:48), and only picking the phone up woke it again. A phone at or in the car keeps the key
 * awake, bounded so a phone forgotten in a parked car still sleeps.
 */
object KeySleepPolicy {
    const val SLEEP_AFTER_STILL_MS = 2 * 60 * 1_000L
    const val AT_CAR_MAX_AWAKE_MS = 30 * 60 * 1_000L
    /** Smoothed RSSI of a phone at the car or in it, within ~2-4 m (field: -46..-49 in the car,
     *  -82 at ~6 m on approach; simulator: a pocket beside the car reads -71..-77). */
    const val AT_CAR_RSSI = -78

    fun atCar(sessionReady: Boolean, smoothedRssi: Int?): Boolean =
        sessionReady && smoothedRssi != null && smoothedRssi >= AT_CAR_RSSI

    /**
     * Whether the key may sleep now. [stillFor] answers "still for at least this long".
     * Leaving the car restarts the 2 min: the phone taken from the car and carried away while
     * Activity Recognition still says STILL (S24 screen off) must not lose its key at once - the
     * departure is watched over that very link. So does a counted step ([sinceStepMs], 0.3.2).
     */
    fun maySleep(stillFor: (Long) -> Boolean, atCar: Boolean, sinceAtCarMs: Long?, sinceStepMs: Long? = null): Boolean =
        if (atCar) stillFor(AT_CAR_MAX_AWAKE_MS)
        else stillFor(SLEEP_AFTER_STILL_MS) && (sinceAtCarMs == null || sinceAtCarMs >= SLEEP_AFTER_STILL_MS) &&
            // 0.3.2: the hardware step counter counted a step within the stillness window - the S24
            // with the screen off reports STILL during whole walks; a walking phone keeps its key.
            (sinceStepMs == null || sinceStepMs >= SLEEP_AFTER_STILL_MS)
}
