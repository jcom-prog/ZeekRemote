package com.openzeekr.app.ble.sim

import com.openzeekr.app.ble.sim.Leg.Mark
import com.openzeekr.app.ble.sim.Leg.ManualLock
import com.openzeekr.app.ble.sim.Leg.OpenDoor
import com.openzeekr.app.ble.sim.Leg.Shuffle
import com.openzeekr.app.ble.sim.Leg.Stand
import com.openzeekr.app.ble.sim.Leg.Walk

/*
 * The user's requirements (02/10-03/10), as expectations placed between Marks:
 *   expect-unlock          an approach opens the car before the door (ideally ~5-6 m)
 *   expect-lock            a real departure locks the car (~8 m; never while near)
 *   expect-stay-unlocked   no automatic Lock while the user stays near the car
 *   expect-stay-locked     after a Lock (manual or the car's own) no unlock while near / at 10 m
 *   expect-unlocked-by-end the car is open when the segment ends (user back at the car)
 *   expect-locked-by-end   the car is locked when the segment ends (user gone)
 * Global invariants are checked over the whole run (SimOracle).
 */
internal data class Scenario(val family: String, val description: String, val startM: Double, val legs: List<Leg>,
                    val gnssAccuracyM: Float? = 4f)

internal object SimScenarios {
    private fun approachAndOpen(speed: Double = 1.3) = listOf(
        Mark("expect-unlock"), Walk(1.0, speed), Stand(4_000), OpenDoor, Stand(20_000, shadowDb = -6.0))

    val families: List<Scenario> = listOf(
        Scenario("F01 normal", "approach, open the door, later walk away 30 m", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(30.0), Stand(150_000))),
        Scenario("F02 self-lock+return", "approach, stand 2.5 min without opening (car relocks), away 20 m, back", 30.0,
            listOf(Mark("expect-unlock"), Walk(1.0), Mark("-"), Stand(150_000), Mark("expect-stay-locked"),
                Stand(20_000), Mark("-"), Walk(20.0), Stand(10_000), Mark("expect-unlock"), Walk(1.0), Stand(10_000))),
        Scenario("F03 manual lock near/10m", "open, manual Lock at the car, stay, 10 m stand + shuffle, then 25 m and back", 30.0,
            approachAndOpen() + listOf(ManualLock, Mark("expect-stay-locked"), Shuffle(60_000, -10.0), Walk(10.0, 1.0),
                Stand(40_000, Facing.TOWARD), Shuffle(30_000, -6.0), Mark("-"), Walk(25.0), Stand(15_000),
                Mark("expect-unlock"), Walk(1.0), Stand(10_000))),
        Scenario("F04 slow walk-away", "slow walk away with stops (0.6 m/s)", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(5.0, 0.6), Stand(8_000, Facing.AWAY), Walk(10.0, 0.6),
                Stand(10_000, Facing.AWAY), Walk(20.0, 0.6), Stand(150_000, Facing.AWAY))),
        Scenario("F05 long stay at car", "unlocked at the car for 5 min, body shadow, turning", 30.0,
            approachAndOpen() + listOf(Mark("expect-stay-unlocked"), Shuffle(300_000, -14.0))),
        Scenario("F06 around the car", "walk around the car within 4 m (loading the boot)", 30.0,
            approachAndOpen() + listOf(Mark("expect-stay-unlocked"), Walk(3.5, 0.8), Stand(15_000, Facing.AWAY, -6.0),
                Walk(1.0, 0.8), Walk(4.0, 0.8), Stand(20_000, Facing.SIDE, -10.0), Walk(1.5, 0.8), Stand(10_000))),
        Scenario("F07 approach after long still", "phone still for 3 min at 40 m (security sleep), then approach", 40.0,
            listOf(Stand(180_000)) + approachAndOpen() + listOf(Mark("expect-lock"), Walk(30.0), Stand(150_000))),
        Scenario("F08 turn back at 9 m", "walk away 9 m, turn back to the car", 30.0,
            approachAndOpen() + listOf(Mark("-"), Walk(9.0), Stand(3_000, Facing.AWAY), Walk(1.0),
                Mark("expect-unlocked-by-end"), Stand(20_000))),
        Scenario("F09 departure no GNSS", "normal departure without usable GNSS (garage)", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(30.0), Stand(150_000)), gnssAccuracyM = null),
        Scenario("F10 quick in-out", "open the door, leave after 5 s", 30.0,
            listOf(Mark("expect-unlock"), Walk(1.0), OpenDoor, Stand(5_000), Mark("expect-lock"), Walk(30.0), Stand(150_000))),
        Scenario("F11 pass-by", "walk past the car at 4 m without stopping", 30.0,
            listOf(Mark("-"), Walk(4.0), Walk(30.0), Mark("expect-locked-by-end"), Stand(150_000))),
        Scenario("F12 manual lock, away+stop at 10 m, back", "manual Lock, walk 25 m, return with a 10 s stop at 10 m", 30.0,
            approachAndOpen() + listOf(ManualLock, Mark("-"), Walk(25.0), Stand(10_000), Mark("expect-unlock"), Walk(10.0),
                Stand(10_000), Walk(1.0), Stand(10_000))),
        Scenario("F13 waiting at 12 m", "car locked; the user waits 3 min at 12 m, turning around", 30.0,
            listOf(Walk(12.0), Mark("expect-stay-locked"), Shuffle(180_000, -6.0), Stand(5_000, Facing.TOWARD))),
        Scenario("F14 waiting at 8 m", "car locked; the user waits 2 min at 8 m facing the car, then leaves", 30.0,
            listOf(Walk(8.0), Mark("-"), Stand(120_000, Facing.TOWARD), Walk(30.0), Stand(30_000))),
    )

    /** families x carry x motion profile x seeds. */
    fun matrix(seeds: Int = 3): List<Triple<Scenario, Carry, Pair<MotionProfile, Long>>> = buildList {
        for (f in families) for (c in Carry.values()) for (m in MotionProfile.ALL) for (s in 1..seeds)
            add(Triple(f, c, m to (s * 7919L + f.family.hashCode() * 31L + c.ordinal * 101L + m.name.hashCode())))
    }
}

/** Checks one finished run against the requirements. */
internal object SimOracle {
    data class Finding(val code: String, val atMs: Long, val distanceM: Double, val detail: String)

    fun check(w: SimWorld): List<Finding> {
        val out = mutableListOf<Finding>()
        val ev = w.events
        fun f(code: String, e: SimWorld.Event, detail: String = e.detail) = Finding(code, e.atMs, e.distanceM, detail)

        // Which key Locks were the user's own (manual)?
        val manualLockAt = ev.filter { it.kind == "USER_MANUAL_LOCK" }.map { it.atMs }
        for (e in ev) {
            if (e.kind == "KEY_LOCK_CMD" && manualLockAt.none { e.atMs - it in 0..2_000 } && e.distanceM <= 3.0)
                out += f("LOCK_WHILE_NEAR", e, "automatic Lock at %.1f m".format(e.distanceM))
            if (e.kind == "KEY_UNLOCK_CMD" && e.distanceM > 15.0)
                out += f("UNLOCK_WHILE_FAR", e, "unlock at %.1f m".format(e.distanceM))
            if (e.kind == "ALARM" && e.detail.contains("car LOCKED"))
                out += f("FALSE_ALARM_CAR_LOCKED", e)
            if (e.kind == "ALARM" && !e.detail.contains("car LOCKED")) {
                // The user stayed at the car for the next 30 s: alarm for nothing.
                val near = w.samples.filter { it.atMs in e.atMs..(e.atMs + 30_000) }.all { it.distanceM <= 4.0 }
                if (near && e.distanceM <= 4.0) out += f("ALARM_WHILE_AT_CAR", e)
            }
        }
        // Car open and the user >= 20 m away for 2 min without any alarm.
        var farOpenSince = -1L
        for (s in w.samples) {
            if (!s.carLocked && s.distanceM >= 20.0) {
                if (farOpenSince < 0) farOpenSince = s.atMs
                if (s.atMs - farOpenSince >= 120_000L) {
                    val alarmed = ev.any { it.kind == "ALARM" && it.atMs in (farOpenSince - 60_000)..s.atMs }
                    if (!alarmed) out += Finding("OPEN_FAR_NO_ALARM", s.atMs, s.distanceM, "car open, user >= 20 m for 2 min, no alarm")
                    farOpenSince = Long.MAX_VALUE / 2
                }
            } else if (farOpenSince != Long.MAX_VALUE / 2) farOpenSince = -1L
        }

        // Segment expectations.
        val marks = ev.filter { it.kind == "MARK" } + SimWorld.Event(Long.MAX_VALUE, "MARK", 0.0, "end")
        for (i in 0 until marks.size - 1) {
            val m = marks[i]; val endAt = marks[i + 1].atMs
            // A missed approach is reported once (APPROACH_NO_UNLOCK), not again as a missing Lock.
            val everUnlockedBefore = ev.any { it.kind == "CAR_UNLOCKED" && it.atMs < endAt }
            val unlockedAtMark = w.samples.lastOrNull { it.atMs <= m.atMs }?.carLocked == false
            val seg = ev.filter { it.atMs >= m.atMs && it.atMs < endAt }
            val segSamples = w.samples.filter { it.atMs >= m.atMs && it.atMs < endAt }
            when (m.detail) {
                "expect-unlock" -> {
                    val u = seg.firstOrNull { it.kind == "CAR_UNLOCKED" }
                    val arrival = segSamples.firstOrNull { it.distanceM <= 1.05 }
                    when {
                        u == null -> out += Finding("APPROACH_NO_UNLOCK", m.atMs, m.distanceM, "no unlock on approach")
                        arrival != null && u.atMs > arrival.atMs + 1_000 ->
                            out += f("APPROACH_UNLOCK_AFTER_DOOR", u, "unlocked %.1f s after reaching the door".format((u.atMs - arrival.atMs) / 1000.0))
                        u.distanceM < 2.5 -> out += f("APPROACH_UNLOCK_LATE", u, "unlocked at %.1f m".format(u.distanceM))
                        u.distanceM > 12.0 -> out += f("APPROACH_UNLOCK_EARLY", u, "unlocked at %.1f m".format(u.distanceM))
                    }
                }
                "expect-lock" -> if (unlockedAtMark || seg.any { it.kind == "CAR_UNLOCKED" }) {
                    val l = seg.firstOrNull { it.kind == "CAR_LOCKED" }
                    val alarmed = seg.any { it.kind == "ALARM" }
                    when {
                        l == null -> out += Finding(if (alarmed) "DEPARTURE_NO_LOCK_ALARMED" else "DEPARTURE_NO_LOCK_SILENT",
                            m.atMs, m.distanceM, "no Lock after the departure")
                        l.detail == "self-lock" -> Unit
                        l.distanceM > 30.0 -> out += f("DEPARTURE_LOCK_LATE", l, "locked at %.1f m".format(l.distanceM))
                    }
                }
                "expect-stay-unlocked" -> seg.firstOrNull { it.kind == "KEY_LOCK_CMD" }?.let {
                    out += f("UNWANTED_LOCK_NEAR", it, "Lock at %.1f m while staying near".format(it.distanceM)) }
                "expect-stay-locked" -> seg.firstOrNull { it.kind == "KEY_UNLOCK_CMD" }?.let {
                    out += f("UNWANTED_UNLOCK", it, "unlock at %.1f m where the car should stay locked".format(it.distanceM)) }
                "expect-unlocked-by-end" -> segSamples.lastOrNull()?.let { if (it.carLocked && everUnlockedBefore)
                    out += Finding("BACK_AT_CAR_BUT_LOCKED", it.atMs, it.distanceM, "user back at the car, car locked") }
                "expect-locked-by-end" -> segSamples.lastOrNull()?.let { if (!it.carLocked)
                    out += Finding("GONE_BUT_UNLOCKED", it.atMs, it.distanceM, "user gone, car still open") }
            }
        }
        return out
    }
}
