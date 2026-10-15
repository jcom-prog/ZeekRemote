package com.openzeekr.app.ble

/**
 * Steps walked since the motion monitor started, from two sources.
 *
 * The step DETECTOR fires per step but, on the Galaxy S24+ with the screen off, delivers late or
 * not at all (field: a ~30 m walk away gave too few steps for both departure proofs,
 * the car stayed open until the alarm). The step COUNTER is the sensor hub's own pedometer: it keeps
 * counting while the application processor sleeps and hands over its running total on request
 * (SensorManager.flush). The larger of the two is used, so neither source can lower the count.
 * Pure Kotlin.
 */
internal class StepTally {
    private var detectorSteps = 0L
    private var detectorSeen = false
    private var counterFirst: Long? = null
    private var counterLast: Long? = null

    @Synchronized fun onDetectorStep() { detectorSteps++; detectorSeen = true }

    /** A step-counter event: the hub's running total since boot (monotonic, may reset on reboot). */
    @Synchronized fun onCounter(totalSinceBoot: Long) {
        val first = counterFirst
        if (first == null || totalSinceBoot < (counterLast ?: first)) {
            // First reading, or the hub total went backwards (reboot): restart the counter baseline,
            // carrying over what was already counted so the tally never decreases.
            counterFirst = totalSinceBoot - (counterSteps() ?: 0L)
        }
        counterLast = totalSinceBoot
    }

    @Synchronized fun counterSteps(): Long? {
        val first = counterFirst ?: return null
        val last = counterLast ?: return null
        return (last - first).coerceAtLeast(0L)
    }

    /** Null when neither source has reported (no step sensor at all). */
    @Synchronized fun total(detectorAvailable: Boolean): Long? {
        val counter = counterSteps()
        val detector = if (detectorAvailable || detectorSeen) detectorSteps else null
        return when {
            counter == null -> detector
            detector == null -> counter
            else -> maxOf(counter, detector)
        }
    }

    @Synchronized fun summary(): String = "detector=$detectorSteps counter=${counterSteps() ?: "none"}"
}
