package com.openzeekr.app.ble

/**
 * 0.4.0c (study round 4f, owner decision 24/10): the two pure pieces of the GNSS gate on the locked side.
 *
 * [CarPositionMemory] keeps the car's last known positions - the departure anchors of the latest visits (the fix at the
 * car right after the unlock) - and gives their component-wise median, so one anchor with a GNSS jump does not move
 * the car. It is kept on disk through [ProximityStateStore] and forgotten when the phone rides in the car.
 *
 * [LockedFixFilter] takes the fixes of the locked-side watch. A fix whose distance to the car moved faster than a walk
 * since the last accepted fix (3 m/s plus 3 m) is a jump, not a position, and is set aside; without an accepted fix in
 * the last 5 s the gate is undecided. The gate reads the median distance of the last three accepted fixes and the
 * latest one: "far" needs both, "near" needs the latest alone (the median lags a walker by a few metres).
 */
internal class CarPositionMemory(saved: List<DoubleArray> = emptyList()) {
    private val fixes: MutableList<DepartureFix> =
        saved.filter { it.size >= 3 }.map { DepartureFix(it[0], it[1], it[2].toFloat(), 0L) }.toMutableList()

    /** The car's position: the median of the kept anchors; null without one. */
    var position: DepartureFix? = median(fixes)
        private set
    val count: Int get() = fixes.size

    fun remember(anchor: DepartureFix) {
        fixes += anchor
        while (fixes.size > KEPT) fixes.removeAt(0)
        position = median(fixes)
    }
    fun forget() { fixes.clear(); position = null }
    /** What goes to disk: [latitude, longitude, accuracy m] per anchor, oldest first. */
    fun toSaved(): List<DoubleArray> = fixes.map { doubleArrayOf(it.latitude, it.longitude, it.accuracyM.toDouble()) }

    private fun median(list: List<DepartureFix>): DepartureFix? {
        if (list.isEmpty()) return null
        fun med(v: List<Double>): Double { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        return DepartureFix(med(list.map { it.latitude }), med(list.map { it.longitude }),
            med(list.map { it.accuracyM.toDouble() }).toFloat(), 0L)
    }

    companion object { const val KEPT = 5 }
}

internal class LockedFixFilter {
    private val accepted = ArrayDeque<Pair<Long, Double>>()   // wall ms, distance from the car (m)
    private val acceptedAcc = ArrayDeque<Float>()
    private val rejected = ArrayDeque<Pair<Long, Double>>()
    /** The latest decision, for the log: "accepted 23 m acc 4", "set aside 37 m (a jump from 9 m)". */
    var lastNote: String = ""
        private set

    /** Takes a fix; returns true when it was accepted. */
    fun offer(nowMs: Long, distanceM: Double, accuracyM: Float): Boolean {
        val last = accepted.lastOrNull()
        val plausible = last == null || nowMs - last.first > RESET_MS ||
            kotlin.math.abs(distanceM - last.second) <= WALK_MPS * ((nowMs - last.first) / 1000.0) + SLACK_M
        if (plausible) {
            accepted.addLast(nowMs to distanceM); acceptedAcc.addLast(accuracyM)
            while (accepted.size > KEEP) { accepted.removeFirst(); acceptedAcc.removeFirst() }
            rejected.clear()
            lastNote = "accepted %.0f m acc %.0f".format(distanceM, accuracyM)
            return true
        }
        rejected.addLast(nowMs to distanceM); while (rejected.size > KEEP) rejected.removeFirst()
        lastNote = "set aside %.0f m (a jump from %.0f m)".format(distanceM, last!!.second)
        return false
    }
    fun clear() { accepted.clear(); acceptedAcc.clear(); rejected.clear() }

    /** The median distance of the accepted fixes of the last [FRESH_MS] whose accuracy is at most [accMax]; null without one. */
    fun medianM(nowMs: Long, accMax: Float): Double? {
        val ds = accepted.indices.filter { nowMs - accepted[it].first <= FRESH_MS && acceptedAcc[it] <= accMax }
            .map { accepted[it].second }.sorted()
        if (ds.isEmpty()) return null
        return if (ds.size % 2 == 1) ds[ds.size / 2] else (ds[ds.size / 2 - 1] + ds[ds.size / 2]) / 2
    }
    /** The worst accuracy among the fixes [medianM] used; null without one. */
    fun worstAccM(nowMs: Long, accMax: Float): Float? =
        accepted.indices.filter { nowMs - accepted[it].first <= FRESH_MS && acceptedAcc[it] <= accMax }.maxOfOrNull { acceptedAcc[it] }
    /** 0.4.0d: the fall of the distance over the accepted fixes of the last [FRESH_MS] that are accurate enough (the
     *  oldest minus the latest; positive = approaching); null with fewer than two such fixes. */
    fun trendM(nowMs: Long, accMax: Float): Double? {
        val idx = accepted.indices.filter { nowMs - accepted[it].first <= FRESH_MS && acceptedAcc[it] <= accMax }
        if (idx.size < 2) return null
        return accepted[idx.first()].second - accepted[idx.last()].second
    }
    /** 0.4.0d: how many accepted fixes of the last [FRESH_MS] are accurate enough. */
    fun freshCount(nowMs: Long, accMax: Float): Int =
        accepted.indices.count { nowMs - accepted[it].first <= FRESH_MS && acceptedAcc[it] <= accMax }
    /** The latest accepted fix's distance when it is at most [LATEST_MS] old and accurate enough; null otherwise. */
    fun latestM(nowMs: Long, accMax: Float): Double? {
        val i = accepted.lastIndex
        if (i < 0 || nowMs - accepted[i].first > LATEST_MS || acceptedAcc[i] > accMax) return null
        return accepted[i].second
    }

    companion object {
        const val KEEP = 3
        const val FRESH_MS = 5_000L
        const val LATEST_MS = 3_000L
        /** No accepted fix for this long: the next fix is accepted whatever it says. */
        const val RESET_MS = 15_000L
        const val WALK_MPS = 3.0
        const val SLACK_M = 3.0
    }
}
