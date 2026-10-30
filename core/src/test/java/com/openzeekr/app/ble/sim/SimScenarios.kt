package com.openzeekr.app.ble.sim

import com.openzeekr.app.ble.sim.Leg.Bluetooth
import com.openzeekr.app.ble.sim.Leg.BluetoothOff
import com.openzeekr.app.ble.sim.Leg.CarUnlocksItself
import com.openzeekr.app.ble.sim.Leg.Fidget
import com.openzeekr.app.ble.sim.Leg.LockByOther
import com.openzeekr.app.ble.sim.Leg.Mark
import com.openzeekr.app.ble.sim.Leg.ProcessRestart
import com.openzeekr.app.ble.sim.Leg.ManualLock
import com.openzeekr.app.ble.sim.Leg.OpenDoor
import com.openzeekr.app.ble.sim.Leg.ReopenByOther
import com.openzeekr.app.ble.sim.Leg.PhoneInCar
import com.openzeekr.app.ble.sim.Leg.Shuffle
import com.openzeekr.app.ble.sim.Leg.Stand
import com.openzeekr.app.ble.sim.Leg.Walk
import com.openzeekr.app.ble.sim.Leg.WalkPeak

/*
 * The user's requirements (02/10-03/10), as expectations placed between Marks:
 *   expect-unlock          an approach opens the car before the door (ideally ~5-6 m)
 *   expect-lock            a real departure locks the car (~8 m; never while near)
 *   expect-stay-unlocked   no automatic Lock while the user stays near the car
 *   expect-stay-locked     after a Lock (manual or the car's own) no unlock while near / at 10 m
 *   expect-unlocked-by-end the car is open when the segment ends (user back at the car)
 *   expect-locked-by-end   the car is locked when the segment ends (user gone)
 *   expect-alarm           the car cannot be locked (key refused, cloud down): the user must be alarmed
 * Global invariants are checked over the whole run (SimOracle).
 */
internal data class Scenario(val family: String, val description: String, val startM: Double, val legs: List<Leg>,
                    val gnssAccuracyM: Float? = 4f, val carRefusesKeyLock: Boolean = false,
                    val keyEdgeRssi: Double? = null, val cloudDown: Boolean = false)

/** A field incident and the simulator families that must reproduce its situation (fase 0, 14/10). */
internal data class FieldCase(val id: String, val what: String, val families: List<String>, val note: String = "")

internal object SimScenarios {
    private val F20_PEAK_DB = System.getenv("SIM_F20_PEAK_DB")?.toDouble() ?: 15.0
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
        // Rain (user): the approach is brisk, ~2 m/s, straight to the door.
        Scenario("F15 fast approach (rain)", "approach at 2.0 m/s, open the door, later walk away", 30.0,
            approachAndOpen(2.0) + listOf(Mark("expect-lock"), Walk(30.0), Stand(150_000))),
        Scenario("F16 fast return after self-lock", "car relocks; away 25 m, back at 2.0 m/s", 30.0,
            listOf(Mark("expect-unlock"), Walk(1.0), Mark("-"), Stand(150_000), Walk(25.0), Stand(10_000),
                Mark("expect-unlock"), Walk(1.0, 2.0), Stand(10_000))),
        Scenario("F17 fast approach after long still", "phone still 7 min at 25 m (security sleep), then 2.0 m/s", 25.0,
            listOf(Stand(420_000)) + approachAndOpen(2.0)),
        // Field: manual Lock, 10 m and turning round (stay locked), then back to the car (open).
        Scenario("F18 manual lock, 10 m and back", "manual Lock, 30 s at the car, 10 m turning round 30 s, back to the door", 30.0,
            approachAndOpen() + listOf(ManualLock, Mark("expect-stay-locked"), Stand(30_000), Walk(10.0, 1.0),
                Shuffle(30_000, -6.0), Stand(3_000, Facing.TOWARD), Mark("-"), Walk(1.0),
                Mark("expect-unlocked-by-end"), Stand(10_000))),
        // Field: the same, standing still (not turning) at 10 m for 40 s, then back.
        Scenario("F19 manual lock, 10 m still, back", "manual Lock, 10 m standing still 40 s, back to the door", 30.0,
            approachAndOpen() + listOf(ManualLock, Mark("expect-stay-locked"), Stand(20_000), Walk(10.0, 1.2),
                Stand(40_000, Facing.AWAY), Mark("expect-unlock"), Walk(1.0), Stand(10_000))),
        // Field (0.2.1): auto Lock, walking on indoors a 1.5 s peak (to about
        // -80) reopened the car behind the user. Then a real return must still open.
        Scenario("F20 peak while walking away", "auto Lock, 30 m, walking on a 1.5 s signal peak (+$F20_PEAK_DB dB), later back", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(30.0), Stand(40_000, Facing.AWAY), Mark("expect-stay-locked"),
                Walk(32.0), WalkPeak(1_500, F20_PEAK_DB), Walk(36.0), Stand(20_000), Mark("expect-unlock"), Walk(1.0),
                Stand(10_000))),
        // Field (0.2.2): the car received the key Lock (0x0111) but stayed open,
        // while the app showed "Locked". The departure must end in an alarm, never in silence.
        // Field: out of the car, a short stop to lock a gate (~4.5 s), on, a
        // short stop at the back door (~1.5 s), then inside (walls). The car must lock.
        Scenario("F22 home: stops at gate and door", "walk 14 m, gate 4.5 s, 18 m, door 1.5 s, inside", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(14.0), Stand(4_500, Facing.AWAY), Walk(18.0),
                Stand(1_500, Facing.AWAY), Walk(22.0), Stand(150_000, Facing.AWAY, -10.0, indoor = true))),
        // Point 3 of 0.2.5: the key Lock fails and there is no GNSS (garage, indoors): only the cloud
        // Lock backed by a fresh BLE departure proof can lock the car.
        Scenario("F23 key Lock fails, no GNSS", "normal departure, car ignores key Locks, no GNSS", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(30.0), Stand(150_000)), gnssAccuracyM = null,
            carRefusesKeyLock = true),
        // Field: indoors 25 m away (walls), then locking a door ~20 s in
        // the open with the phone in hand (+6 dB line of sight), 20 m to a gate ~3 m from
        // the car, gate ~25 s, then the door. No unlock while indoors; open before the car door.
        Scenario("F24 house door, gate, car", "inside 25 m, front door 20 s (LOS), gate at 4 m 25 s, door", 25.0,
            listOf(Stand(150_000, Facing.SIDE, -12.0, indoor = true), Mark("expect-stay-locked"), Fidget(20_000, 6.0),
                Mark("expect-unlock"), Walk(4.0), Fidget(25_000), Walk(1.0), Stand(10_000))),
        // The same with a long stop at the gate (2.5 min): the car relocks itself; walking up to the
        // door must open it again (0.2.6 B).
        Scenario("F25 gate 2.5 min, car relocks", "approach to the gate at 4 m, 150 s there, then the door", 25.0,
            listOf(Mark("-"), Walk(4.0), Fidget(150_000), Walk(1.0), Mark("expect-unlocked-by-end"), Stand(10_000))),
        // Field: unlocked on approach, the phone laid in the car (-46..-49) while the
        // user worked beside it; the car relocked itself; the door was then opened by other means
        // (no key Unlock); finally the user took the phone and walked away. The key must stay awake
        // at the car, and the open car must be locked or alarmed after the walk away.
        Scenario("F26 phone in car, reopened, away", "phone still in the car 5 min, car relocks, opened by hand, walk away", 25.0,
            listOf(Mark("expect-unlock"), Walk(0.5), Mark("-"), PhoneInCar(300_000), ReopenByOther, PhoneInCar(60_000),
                Mark("expect-lock"), Walk(30.0), Stand(150_000))),
        // Field: walk away; the departure is proven at the edge of the link and the key
        // Lock gets no answer there - the cloud must take over promptly.
        Scenario("F27 key Lock unanswered at link edge", "normal departure; key Lock gets no answer below -97", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(60.0), Stand(150_000)), keyEdgeRssi = -97.0),
        Scenario("F21 car ignores key Lock", "normal departure; the car acknowledges but never executes a key Lock", 30.0,
            approachAndOpen() + listOf(Mark("-"), Walk(30.0), Stand(150_000)), carRefusesKeyLock = true),
        // Field: approach + open, walk away ~20 m so the car auto-locks and the link
        // drops far, then turn straight back. The reconnect only completed at the door while the
        // user stood still; the departure-ended latch kept the car shut and the user had to unlock
        // by hand. A proven away-and-back must reopen at the door (0.2.14 B).
        Scenario("F28 walk-away lock, straight back", "open, away 30 m (auto-lock, link drops far), back to the door", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(30.0), Stand(14_000, Facing.AWAY),
                Mark("expect-unlock"), Walk(1.0), Stand(20_000))),
        // Field: open by the handle, walk ~35 m away and come back to ~20 m, stand there.
        // With the screen off the step detector delivered nothing: the BLE route said too few steps
        // and the location proof needed 12 m clearance without steps, so the car stayed open until
        // the alarm and the user locked from the app. The hub step counter (0.2.17) must lock it.
        Scenario("F29 walk away, wait at 20 m", "open, away 35 m, back to 20 m, stand 2 min", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(35.0), Walk(20.0), Stand(120_000))),

        // ---------------- fase 0 (14/10): situations the field showed or will show ----------------
        Scenario("F30 errand 1 min", "open, walk 40 m, 1 min there, back to the car", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(40.0), Stand(60_000, Facing.AWAY),
                Mark("expect-unlock"), Walk(1.0), Stand(10_000))),
        Scenario("F31 wait 4 min at 10 m, then go", "car locked; wait 4 min at 10 m, then walk to the door", 30.0,
            listOf(Walk(10.0), Mark("expect-stay-locked"), Stand(240_000, Facing.SIDE), Mark("expect-unlock"),
                Walk(1.0), Stand(10_000))),
        Scenario("F32 back 5 s after reaching 25 m", "open, walk 25 m, turn straight back to the door", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(25.0), Stand(5_000, Facing.AWAY),
                Mark("expect-unlock"), Walk(1.0), Stand(15_000))),
        Scenario("F33 pass-by at 2 m", "car locked; walk past the car at 2 m without stopping", 30.0,
            listOf(Mark("-"), Walk(2.0), Walk(30.0), Mark("expect-locked-by-end"), Stand(120_000))),
        Scenario("F34 jog away", "open, jog 80 m away at 3.5 m/s", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(80.0, 3.5), Stand(120_000))),
        Scenario("F35 stairwell at 15 m", "open, walk 15 m into a stairwell/garage (deep shadow, no GNSS)", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(15.0), Fidget(60_000, -20.0),
                Stand(150_000, Facing.AWAY, -25.0, indoor = true))),
        Scenario("F36 loading the boot 3x", "open, three trips of 3 m with the body between phone and car", 30.0,
            approachAndOpen() + listOf(Mark("expect-stay-unlocked"),
                Walk(3.0, 0.8), Stand(15_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(5_000),
                Walk(3.0, 0.8), Stand(15_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(5_000),
                Walk(3.0, 0.8), Stand(15_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(10_000))),
        // Plan K2: the app process dies while the car is open (update, One UI, crash).
        Scenario("F37 app restart while open, then away", "open, app restarts at the car, walk away 30 m", 30.0,
            approachAndOpen() + listOf(ProcessRestart(4_000L), Stand(10_000), Mark("expect-lock"), Walk(30.0),
                Stand(150_000))),
        Scenario("F38 app restart while away, then approach", "car locked, app restarts at 30 m, approach", 30.0,
            listOf(Stand(10_000), ProcessRestart(4_000L), Stand(10_000), Mark("expect-unlock"), Walk(1.0), Stand(10_000))),
        Scenario("F39 Bluetooth off 60 s at the car, then away", "open, BT off 60 s at the car, on, walk away", 30.0,
            approachAndOpen() + listOf(BluetoothOff(60_000L), Stand(10_000), Mark("expect-lock"), Walk(30.0),
                Stand(150_000))),
        // Plan H1: locked by hand while the link was down; the app still believes "open".
        Scenario("F40 locked by hand while BT off, away, back", "open, BT off, lock by hand, 30 m away, BT on, back", 30.0,
            approachAndOpen() + listOf(Bluetooth(false), Mark("-"), LockByOther, Walk(30.0), Stand(60_000, Facing.AWAY),
                Bluetooth(true), Stand(20_000), Mark("expect-unlock"), Walk(1.0), Stand(10_000))),
        // The key Lock is refused and the cloud is down: the only acceptable end is an alarm.
        Scenario("F41 key Lock refused, cloud down", "normal departure; car refuses key Locks; cloud outage", 30.0,
            approachAndOpen() + listOf(Mark("expect-alarm"), Walk(30.0), Stand(150_000)),
            carRefusesKeyLock = true, cloudDown = true),
        // Field: arrive by car, the car unlocks itself when parked, walk away.
        Scenario("F42 drive in, car unlocks on parking, away", "phone in the car while driving; car unlocks itself; walk away", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(5_000), Mark("expect-lock"),
                Walk(30.0), Stand(150_000))),
        // Field 16/10 (measurement walks 3-5): away to the front door at ~25 m (the car locks), one minute
        // standing there, then straight back to the car (it opened only at the door).
        Scenario("F43 1 min at the front door (25 m), then back", "away 25 m (lock), stand 1 min facing the car, walk back", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(25.0), Stand(60_000, Facing.TOWARD)) + approachAndOpen()),
        // Measurement walk C (0.3.12): the car locked itself on the walk out; the user stood a minute, walked to and fro
        // for a minute at about that distance, stood briefly and walked back at an easy pace. Step odometry counts the
        // to-and-fro as distance out: study E-B (not adopted) opened 2.6 s later on such a walk. (F44 was a study walk.)
        Scenario("F45 lock, 1 min to and fro at 12-16 m, back", "away 15 m (lock), stand 1 min, to and fro 12-16 m 1 min, back", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(15.0, 1.0), Stand(60_000, Facing.AWAY)) +
                List(7) { listOf(Walk(16.0, 1.0), Walk(12.0, 1.0)) }.flatten() +
                listOf(Walk(15.0, 1.0), Stand(15_000, Facing.TOWARD)) + approachAndOpen(1.0)),
        // Quick walk 18/10 (rain): up from inside the house, a few seconds indoors to the door (walls ~10 dB), quickly
        // to the car, a short stop with the door opened, quickly back and on inside; no usable GNSS at the house. In
        // the field the car opened before the user reached it and locked 15-20 s after the user left, already inside.
        Scenario("F46 rain: quick to the car and back inside", "inside 1 min, 8 s to the door, 2.0 m/s to the car, ~6 s there, 2.0 m/s back 16 m, inside", 18.0,
            listOf(Stand(60_000, Facing.AWAY, -10.0, indoor = true), Mark("expect-unlock"), Fidget(8_000, -10.0),
                Walk(1.0, 2.0), Stand(2_000),
                OpenDoor, Stand(4_000, shadowDb = -6.0), Mark("expect-lock"), Walk(16.0, 2.0), Fidget(8_000, -10.0),
                Stand(150_000, Facing.AWAY, -10.0, indoor = true)), gnssAccuracyM = null),
        // 0.3.19 (field 18/10): open, a while busy at the car, then a short walk straight into a house; the wall takes
        // about 10 dB within seconds and the link may hold for minutes inside. The car must lock by itself.
        Scenario("F47 short walk into the house", "open, 30 s busy at the car, 15 m at 1.4 m/s into the house, stand inside", 30.0,
            approachAndOpen() + listOf(Shuffle(30_000), Mark("expect-lock"), Walk(15.0, 1.4),
                Stand(150_000, Facing.AWAY, -8.0, indoor = true))),
        // 0.3.19 (stress at the car): F36 and F06 with 30 s stands, the body between phone and car.
        Scenario("F48 loading the boot 3x, long stands", "open, three trips of 3 m, 30 s each with the body between phone and car", 30.0,
            approachAndOpen() + listOf(Mark("expect-stay-unlocked"),
                Walk(3.0, 0.8), Stand(30_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(5_000),
                Walk(3.0, 0.8), Stand(30_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(5_000),
                Walk(3.0, 0.8), Stand(30_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(10_000))),
        // 0.3.19 (study I): back to the car between two long stands, without a strong reading on the way back.
        Scenario("F49 around the car, long stands", "walk around the car within 4 m, 30 s stands", 30.0,
            approachAndOpen() + listOf(Mark("expect-stay-unlocked"), Walk(3.5, 0.8), Stand(30_000, Facing.AWAY, -6.0),
                Walk(1.0, 0.8), Walk(4.0, 0.8), Stand(30_000, Facing.SIDE, -10.0), Walk(1.5, 0.8), Stand(10_000))),
        // Study J (field 16/10 and 19/10): home by car. After the drive the phone held every step of the short walk into
        // the house and Activity Recognition still said MOVING; the car locked only with the user inside, after the alarm.
        Scenario("F50 drive home, short walk into the house", "drive; the car unlocks on parking; 10 s at the car; 15 m into the house", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(10_000), Mark("expect-lock"),
                Walk(15.0, 1.4), Stand(150_000, Facing.AWAY, -8.0, indoor = true))),
        // Study J: home by car, the boot unloaded first - two 30 s stands behind the car with the body between phone and
        // car while Activity Recognition still says MOVING. The car stays open until the user walks in.
        Scenario("F51 drive home, unload the boot, into the house", "drive; parked; 2x 30 s at the boot, body between; 15 m into the house", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Mark("expect-stay-unlocked"),
                Walk(3.0, 0.8), Stand(30_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(5_000),
                Walk(3.0, 0.8), Stand(30_000, Facing.AWAY, -12.0), Walk(1.0, 0.8), Stand(5_000),
                Mark("expect-lock"), Walk(15.0, 1.4), Stand(150_000, Facing.AWAY, -8.0, indoor = true))),
        // Study J (field 19/10): home by car, a while just inside the door (the signal hovers above the very-deep level),
        // then further in. The alarm sounded a second before the very-deep Lock.
        Scenario("F52 drive home, a while inside the door, further in", "drive; parked; 10 s at the car; 15 m to the door; 15 s inside it; 5 m further in", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(10_000), Mark("expect-lock"),
                Walk(15.0, 1.4), Stand(15_000, Facing.AWAY, -2.0, indoor = true), Walk(20.0, 1.0),
                Stand(150_000, Facing.AWAY, -8.0, indoor = true))),
        // 0.3.21 (field 20/10): the key asleep in the house, then out of the door (15 m) to the car. The phone heard the
        // car indoors at about -100, two connects at the edge ended in status 133, and the re-armed presence scan was
        // stopped for a mode change the scan-start ration then refused: 24 s without a scan, opened by hand.
        Scenario("F53 key asleep in the house, out to the car", "inside 2.5 min (the key sleeps), 8 s to the back door, 15 m to the car", 15.0,
            listOf(Stand(150_000, Facing.AWAY, -10.0, indoor = true), Mark("expect-unlock"), Fidget(8_000, -10.0),
                Walk(1.0), Stand(4_000), OpenDoor, Stand(20_000, shadowDb = -6.0))),
        // 0.3.28 (field 22/10, 0.3.24): the walk away locked the car; the user turned back a few metres and stood
        // facing it for two minutes while the signal crept up to the return level; two readings at -82 reopened
        // the car behind a motionless phone (Activity Recognition STILL, no counted step). Then the user leaves.
        Scenario("F54 auto-Lock, then standing in the return band", "open; away 22 m (auto-Lock), 15 s there; back to 10 m; 20 s turned away, 130 s facing the car at a favourable spot; leave", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(22.0), Stand(15_000, Facing.AWAY), Mark("-"), Walk(10.0, 1.0),
                Mark("expect-stay-locked"), Stand(20_000, Facing.AWAY, -4.0), Stand(130_000, Facing.TOWARD, 5.0),
                Mark("expect-locked-by-end"), Walk(40.0), Stand(60_000, Facing.AWAY))),
        // 0.4.11 (study A, field 29/10): home by car; out; a few metres to the garden gate; standing there unlocking it (body
        // between phone and car); through it; the gate closes between phone and car (15-20 dB); locking it; to the back door;
        // inside. The car locked well behind the gate (idle sampling, the 12 s very-deep window, two key Locks the car did
        // not execute) and the departure alarm sounded a second before the proof confirmed.
        Scenario("G2a drive home, gate 6 m 25 s, behind the gate 12 s, 20 m, inside", "drive; parked; 6 m; 25 s at the gate; gate closes (10 dB); 12 s; 20 m; inside", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(5_000), Mark("expect-lock"),
                Walk(6.0, 0.8), Stand(25000, Facing.AWAY, -2.0), Leg.Obstacle(10.0), Walk(7.0, 0.8),
                Stand(12000, Facing.AWAY), Walk(27.0, 1.2), Stand(150_000, Facing.AWAY, -4.0, indoor = true))),
        Scenario("G2b gate, the car ignores key Locks", "G2a; every key Lock 'received, not executed' (field 29/10: all of them)", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(5_000), Mark("expect-lock"),
                Walk(6.0, 0.8), Stand(25000, Facing.AWAY, -2.0), Leg.Obstacle(10.0), Walk(7.0, 0.8),
                Stand(12000, Facing.AWAY), Walk(27.0, 1.2), Stand(150_000, Facing.AWAY, -4.0, indoor = true)), carRefusesKeyLock = true),
        Scenario("G2c heavy gate 16 dB", "G2a with a heavier gate (the link at its edge behind it)", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(5_000), Mark("expect-lock"),
                Walk(6.0, 0.8), Stand(25000, Facing.AWAY, -2.0), Leg.Obstacle(16.0), Walk(7.0, 0.8),
                Stand(12000, Facing.AWAY), Walk(27.0, 1.2), Stand(150_000, Facing.AWAY, -4.0, indoor = true))),
        Scenario("G2d no gate: 6 m, 25 s, 20 m, inside", "control: the same walk and stops without a gate", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(5_000), Mark("expect-lock"),
                Walk(6.0, 0.8), Stand(25000, Facing.AWAY, -2.0), Walk(27.0, 1.2), Stand(150_000, Facing.AWAY, -4.0, indoor = true))),
        Scenario("G2e gate at 9 m", "G2a with the gate at 9 m (the signal in the band while standing)", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(5_000), Mark("expect-lock"),
                Walk(9.0, 0.8), Stand(25000, Facing.AWAY, -2.0), Leg.Obstacle(10.0), Walk(10.0, 0.8),
                Stand(12000, Facing.AWAY), Walk(30.0, 1.2), Stand(150_000, Facing.AWAY, -4.0, indoor = true))),
        Scenario("G2f gate 40 s, behind it 25 s, car ignores key Locks", "the owner's sketch at its longest; key Locks not executed", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(5_000), Mark("expect-lock"),
                Walk(6.0, 0.8), Stand(40000, Facing.AWAY, -2.0), Leg.Obstacle(10.0), Walk(7.0, 0.8),
                Stand(25000, Facing.AWAY), Walk(27.0, 1.2), Stand(150_000, Facing.AWAY, -4.0, indoor = true)), carRefusesKeyLock = true),
        // 0.4.12 (study D): home by car, a while inside, then back to the car - does the car's position survive the parking (the
        // drive's lingering IN_VEHICLE flag wiped it in the field, 29/10) and does the next approach still open in time?
        Scenario("G3 drive home, 25 m inside 3 min, back to the car", "drive; parked; 5 s; 25 m inside; 3 min; back to the car; open", 2.0,
            listOf(Mark("-"), PhoneInCar(60_000), CarUnlocksItself, OpenDoor, Stand(3_000), Shuffle(5_000), Mark("expect-lock"),
                Walk(25.0, 1.2), Stand(180_000, Facing.AWAY, -4.0, indoor = true), Mark("expect-unlock"), Walk(1.0, 1.3),
                Stand(4_000), OpenDoor, Stand(20_000, shadowDb = -6.0))),
    )

    /** W2 (24/10): the watch companion's hand-overs, on the phone side (the watch's own key acts; the phone stands down
     *  and learns the outcome). Not in [families]: WearLinkArbiter is process-global, so these run one world at a time
     *  (WatchHandOverSimulationTest) and the gate's scenario lines stay what they were. Front pocket only. */
    val watchFamilies: List<Scenario> = listOf(
        // Gap 1: a watch Lock right after the approach unlock must not be undone at the door (self-lock reopen rule).
        Scenario("W-1 watch Lock at the car, then to the door", "open (phone); 5 s; Lock with the watch; 6 m to the door; stand", 30.0,
            approachAndOpen() + listOf(Stand(5_000), Leg.WatchLock(), Mark("expect-stay-locked"), Walk(6.0, 1.0), Stand(60_000, Facing.AWAY, -4.0))),
        // Gap 2: an Unlock with the watch while the phone's key sleeps beside the car; the owner leaves without locking.
        Scenario("W-2 watch Unlock, key asleep, walk away", "35 min still at 4 m (the key sleeps); Unlock with the watch; door; 5 s; away 30 m", 4.0,
            listOf(Mark("-"), Stand(2_100_000, Facing.SIDE), Leg.WatchUnlock(), OpenDoor, Stand(5_000), Mark("expect-lock"), Walk(30.0), Stand(150_000, Facing.AWAY))),
        // A Lock with the watch while already walking away from the open car.
        Scenario("W-3 watch Lock while walking away", "open (phone); away to 8 m; Lock with the watch; on to 30 m", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Walk(8.0), Leg.WatchLock(), Walk(30.0), Stand(150_000, Facing.AWAY))),
        // As W-2, but the watch app dies after acting: no RESUME; the phone's fail-safe reclaims the link after 45 s.
        Scenario("W-4 watch Unlock, resume lost, walk away", "as W-2 without the watch's resume", 4.0,
            listOf(Mark("-"), Stand(2_100_000, Facing.SIDE), Leg.WatchUnlock(resume = false), OpenDoor, Stand(5_000), Mark("expect-lock"), Walk(30.0), Stand(150_000, Facing.AWAY))),
        // An Unlock with the watch from inside (30 m, the key asleep); ten minutes later the owner walks to the car.
        Scenario("W-5 watch Unlock from inside, later to the car", "20 min inside at 30 m (the key sleeps); Unlock with the watch; 10 min; to the car; in", 30.0,
            listOf(Mark("-"), Stand(1_200_000, Facing.AWAY, -10.0, indoor = true), Leg.WatchUnlock(), Stand(600_000, Facing.AWAY, -10.0, indoor = true),
                Mark("expect-unlock"), Walk(1.0), Stand(4_000), OpenDoor, Stand(20_000, shadowDb = -6.0))),
        // 0.4.3 (review T1): the owner walks away with the car open, the phone's Lock loop starts, and right then the watch is
        // tapped to Unlock (he turns back): the loop must stand down - no reconnect fight, no cloud Lock behind the watch's back.
        Scenario("W-6 watch Unlock while the phone's Lock loop runs, back to the car", "open (phone); away; tap Unlock the moment the Lock loop runs; back to the door; in", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock-loop"), Leg.WatchUnlock(whenLooping = true, walkToM = 30.0), Mark("expect-open"),
                Walk(1.0), Stand(4_000), OpenDoor, Stand(30_000, shadowDb = -6.0))),
        // The mirror case: a watch Lock the moment the phone's Lock loop runs (both want the car locked - once, by the watch).
        Scenario("W-7 watch Lock while the phone's Lock loop runs", "open (phone); away; tap Lock the moment the Lock loop runs; on to 30 m", 30.0,
            approachAndOpen() + listOf(Mark("expect-lock"), Leg.WatchLock(whenLooping = true, walkToM = 30.0), Walk(30.0), Stand(150_000, Facing.AWAY))),
        // 0.4.3 (H5/T5): the watch asks for the link and gives up (no usable ack): the link comes back at once, nothing else happens.
        Scenario("W-8 watch asks for the link and gives up", "car locked, 4 m, the key asleep; pause; no action; resume after 10 s; stand", 4.0,
            listOf(Mark("-"), Stand(2_100_000, Facing.SIDE), Leg.WatchAbort(10_000L), Stand(120_000, Facing.SIDE))),
        // 0.4.3 (T6): a hand-over that outruns the 45 s fail-safe: the phone takes the link back first, the watch's Unlock lands after it.
        Scenario("W-9 watch Unlock after the fail-safe reclaimed the link", "as W-2, the watch needs 50 s", 4.0,
            listOf(Mark("-"), Stand(2_100_000, Facing.SIDE), Leg.WatchUnlock(actMs = 50_000L), OpenDoor, Stand(5_000), Mark("expect-lock"), Walk(30.0), Stand(150_000, Facing.AWAY))),
    )

    /** Every field incident and the families that reproduce it (incident history, 14/10). */
    val fieldCases: List<FieldCase> = listOf(
        FieldCase("#1 28/09 0.1.38", "auto-Lock with the user beside the car", listOf("F05", "F06", "F36")),
        FieldCase("#2 28-30/09 0.1.40-49", "departure never locks / locks 94 s late", listOf("F01", "F04", "F09")),
        FieldCase("#3 30/09 0.1.46", "no unlock on approach after a relock", listOf("F02", "F16")),
        FieldCase("#4 30/09-02/10", "first unlock only at the door (AR lag)", listOf("F07", "F17", "F01")),
        FieldCase("#5 02/10 0.1.52", "tile showed Locked while open", listOf(), "UI - unit test LockView"),
        FieldCase("#6 02/10 0.1.53", "walked away, no alarm", listOf("F21", "F23", "F41")),
        FieldCase("#7 02/10 0.1.54", "stop-and-go approach, no unlock", listOf("F12", "F24")),
        FieldCase("#8 02/10 0.1.55", "no reopen on return after a manual Lock", listOf("F12", "F18")),
        FieldCase("#9 03/10 0.1.57", "cloud token expired", listOf(), "cloud - unit test SessionRenewal"),
        FieldCase("#10 03/10 0.1.59", "false alarm; the car's own lock ignored", listOf("F02", "F16", "F25")),
        FieldCase("#11 03/10 0.1.60-63", "return after a Lock opens late or not", listOf("F02", "F16", "F12", "F19")),
        FieldCase("#12 04/10 0.1.64", "reopened at 10 m after a manual Lock", listOf("F03", "F18", "F13")),
        FieldCase("#13 04/10 0.1.65-66", "stays locked at the door / opens late", listOf("F19")),
        FieldCase("#14 04/10 0.2.0", "garage: app thought locked; re-unlock from damping", listOf("F42", "F35")),
        FieldCase("#15 04/10 0.2.1", "auto-Lock not executed; a peak reopened the car", listOf("F20", "F21")),
        FieldCase("#16 05/10 0.2.2", "no auto-Lock (2 steps, GNSS clearance)", listOf("F29", "F04", "F22")),
        FieldCase("#17 05/10 0.2.2", "key Lock acknowledged but not executed", listOf("F21", "F23", "F41")),
        FieldCase("#18 05/10 0.2.3", "pass-by unlock at ~10 m, then no Lock", listOf("F11", "F33")),
        FieldCase("#19 05/10 0.2.4", "gate and door stops: no Lock", listOf("F22")),
        FieldCase("#20 06/10 0.2.5", "alarm storm, security sleep while open", listOf("F26", "F39", "F35")),
        FieldCase("#21 04/10 0.2.0", "re-unlock after auto-Lock; sleep while open", listOf("F20", "F28")),
        FieldCase("#22 09/10 0.2.5", "opened at ~25 m from the house door", listOf("F24")),
        FieldCase("#23 09/10 0.2.5", "motion STILL 40 s: no proof, alarm", listOf("F22", "F29")),
        FieldCase("#24 10/10 0.2.6", "phone in the car, key slept, car open", listOf("F26")),
        FieldCase("#25 12/10 0.2.12", "passive start fails with phone in pocket", listOf(), "car side - needs snooplog"),
        FieldCase("#26 12/10 0.2.12", "~35 s handshaking with another Zeekr", listOf(), "unit test OwnKeyNameFilter; multi-car sim planned"),
        FieldCase("#27 12/10 0.2.12", "Lock 38 s late at the link edge", listOf("F27")),
        FieldCase("#28 13/10 0.2.13", "no unlock on return (latch)", listOf("F28", "F32", "F30")),
        FieldCase("#29 13/10 0.2.15", "status-133 storms at the edge of range", listOf("F07", "F17"), "stress: SIM_PRESENCE_EDGE=1"),
        FieldCase("#30 14/10 0.2.16", "no auto-Lock (step detector silent)", listOf("F29", "F22", "F04")),
        FieldCase("#31 18/10 0.3.13", "quick walk (rain): steps late after a short stop at the car; open just in time", listOf("F46", "F15", "F16")),
        FieldCase("#32 16/10+19/10 0.3.18", "home by car: no steps after the drive; alarm just before the very-deep Lock", listOf("F52", "F50", "F42")),
        FieldCase("#33 20/10 0.3.20", "no open on approach: 133 at the edge, then 24 s without a scan (scan-start ration)", listOf("F53", "F07", "F17"),
            "world edge-133"),
        FieldCase("#34 22/10 0.3.24", "auto-Lock, then standing 8-10 m away: the signal return reopened the car behind a motionless phone", listOf("F54", "F14", "F03")),
        FieldCase("#35 29/10 0.4.10", "home by car; standing at the garden gate; the gate closes between phone and car: a late Lock behind it, the alarm just before the proof", listOf("G2b", "G2a", "G2d")),
    )

    /**
     * The learning period before a run (0.3.1 self-calibration): [visits] ordinary days at the car -
     * walk up from 30 m, a short stop at the door, away again - each with the phone carried another
     * way (hand, front or back pocket at random), in the same world as the run. The scenario then
     * starts with what the app learned.
     */
    /**
     * Owner's decision (20/10, confirmed 22/10): the phone rides in the front trouser pocket. The matrix and the
     * learning visits simulate that carry only. SIM_CARRY (FRONT_POCKET, BACK_POCKET, HAND, a list, or ALL) and
     * SIM_LEARN_CARRY exist for an explicitly requested diagnostic run and nothing else; the pinned tests of rules
     * built earlier pass their own carry to [matrix].
     */
    val CARRIES: List<Carry> = System.getenv("SIM_CARRY")?.let { v ->
        if (v.trim().equals("ALL", ignoreCase = true)) Carry.values().toList() else v.split(',').map { Carry.valueOf(it.trim()) }
    } ?: listOf(Carry.FRONT_POCKET)
    private val LEARN_CARRY: Carry = System.getenv("SIM_LEARN_CARRY")?.let { Carry.valueOf(it.trim()) } ?: Carry.FRONT_POCKET
    /** [carry] null = a random carry per visit, as the worlds of the pinned tests of earlier rules were built. */
    fun learningVisits(r: SimRandom, visits: Int, carry: Carry? = LEARN_CARRY): List<Leg> = buildList {
        repeat(visits) {
            val drawn = Carry.values()[(r.nextDouble() * 3).toInt().coerceAtMost(2)]   // the draw stays: same streams as the studies
            add(Leg.Carrying(carry ?: drawn))
            add(Walk(1.0, r.uniform(1.1, 1.5)))
            add(Stand(r.uniform(4_000.0, 10_000.0).toLong()))
            add(OpenDoor)
            add(Stand(r.uniform(5_000.0, 15_000.0).toLong(), shadowDb = -6.0))
            add(Walk(30.0, r.uniform(1.1, 1.5)))
            add(Stand(60_000))
            add(LockByOther)
        }
        add(Leg.Carrying(null))
    }

    /** families x carry x motion profile x seeds; [carries] is the front pocket unless a pinned test asks otherwise. */
    fun matrix(seeds: Int = 3, carries: List<Carry> = CARRIES): List<Triple<Scenario, Carry, Pair<MotionProfile, Long>>> = buildList {
        for (f in families) for (c in carries) for (m in MotionProfile.ALL) for (s in 1..seeds)
            add(Triple(f, c, m to (s * 7919L + f.family.hashCode() * 31L + c.ordinal * 101L + m.name.hashCode())))
    }
}

/** Checks one finished run against the requirements. */
internal object SimOracle {
    data class Finding(val code: String, val atMs: Long, val distanceM: Double, val detail: String)

    /** Safety: the car opens or locks wrongly, or is open with the user away and nobody knows. */
    val SAFETY = setOf("LOCK_WHILE_NEAR", "LOCK_WHILE_RETURNING", "UNWANTED_LOCK_NEAR", "UNLOCK_WHILE_FAR",
        "UNWANTED_UNLOCK", "UNLOCK_WHILE_LOITERING", "DEPARTURE_NO_LOCK_SILENT", "OPEN_FAR_NO_ALARM", "GONE_BUT_UNLOCKED",
        "ALARM_SILENCED_AWAY")
    fun tier(code: String) = if (code in SAFETY) "SAFETY" else "COMFORT"
    /** After a stop mark, a key command this soon still belongs to the walk up (see expect-stay-locked). */
    const val STOP_GRACE_MS = 2_500L
    /** A departure lock beyond 30 m is late only this long after the user passed 8 m (see expect-lock). */
    const val LOCK_EVIDENCE_MS = 12_000L

    fun check(w: SimWorld): List<Finding> {
        val out = mutableListOf<Finding>()
        val ev = w.events
        val samples = w.samples
        fun f(code: String, e: SimWorld.Event, detail: String = e.detail) = Finding(code, e.atMs, e.distanceM, detail)
        fun sampleAt(t: Long) = samples.lastOrNull { it.atMs <= t }
        fun belief(t: Long) = sampleAt(t)?.let { if (!it.appAlive) "app dead" else if (it.appBelievesUnlocked) "app: open" else "app: locked" } ?: "?"

        // Which key Locks were the user's own (manual)?
        val manualLockAt = ev.filter { it.kind == "USER_MANUAL_LOCK" }.map { it.atMs }
        fun automatic(e: SimWorld.Event) = (e.kind == "KEY_LOCK_CMD" || e.kind == "CLOUD_LOCK_CMD") &&
            manualLockAt.none { e.atMs - it in 0..2_000 }
        for (e in ev) {
            if (automatic(e) && e.distanceM <= 5.0)
                out += f("LOCK_WHILE_NEAR", e, "automatic Lock at %.1f m".format(e.distanceM))
            else if (automatic(e) && e.distanceM <= 15.0) {
                // The user turned back: >= 3 m closer than 6 s earlier (plan K1).
                val before = sampleAt(e.atMs - 6_000)
                if (before != null && before.distanceM - e.distanceM >= 3.0)
                    out += f("LOCK_WHILE_RETURNING", e, "automatic Lock at %.1f m while walking back (%.1f m 6 s earlier)".format(e.distanceM, before.distanceM))
            }
            if (e.kind == "KEY_UNLOCK_CMD" && e.distanceM > 15.0)
                out += f("UNLOCK_WHILE_FAR", e, "unlock at %.1f m".format(e.distanceM))
            else if (e.kind == "KEY_UNLOCK_CMD" && e.distanceM >= 8.0) {
                // Waiting nearby (>= 5 s without walking at 8-15 m) must never open the car.
                val win = samples.filter { it.atMs in (e.atMs - 5_000)..e.atMs }
                if (win.size >= 4 && win.none { it.moving } && win.all { it.distanceM >= 8.0 })
                    out += f("UNLOCK_WHILE_LOITERING", e, "unlock at %.1f m while standing there".format(e.distanceM))
            }
            // The key went to sleep with the phone at the car (field: the car then no
            // longer recognised the phone lying in it).
            if (e.kind == "SECURITY_SLEEP" && e.distanceM <= 2.0 &&
                samples.filter { it.atMs in (e.atMs - 30 * 60_000L)..e.atMs }.any { it.distanceM > 2.0 })
                out += f("KEY_ASLEEP_AT_CAR", e, "security sleep at %.1f m".format(e.distanceM))
            if (e.kind == "ALARM" && e.detail.contains("car LOCKED"))
                out += f("FALSE_ALARM_CAR_LOCKED", e)
            if (e.kind == "ALARM" && !e.detail.contains("car LOCKED")) {
                // The user stayed at the car for the next 30 s: alarm for nothing.
                val near = samples.filter { it.atMs in e.atMs..(e.atMs + 30_000) }.all { it.distanceM <= 4.0 }
                if (near && e.distanceM <= 4.0) out += f("ALARM_WHILE_AT_CAR", e)
                // Study J (field 16/10 and 19/10): the alarm sounded and the app locked the car moments later.
                ev.firstOrNull { it.kind == "CAR_LOCKED" && (it.detail == "key" || it.detail == "cloud") && it.atMs in e.atMs..(e.atMs + 20_000) }
                    ?.let { out += f("ALARM_THEN_LOCK", e, "alarm, then the app locked the car %.1f s later".format((it.atMs - e.atMs) / 1000.0)) }
            }
        }
        // 0.3.10: a sounding alarm silenced while the car is still open (its own lock events, not the 1 s
        // samples: a confirmed Lock silences in the same tick) and the user is >= 10 m away, with >= 10 s of
        // the alarm left, no Lock within 1 s, no new alarm within 30 s and the user not back at the car
        // (<= 4 m) within 30 s (stress F15: silenced at 30 m by "2 s at the unlock level", 70 s without an
        // alarm). The end of an alarm followed by a new one (F41) is no gap; a silence in the last 30 s of
        // a run cannot be judged.
        run {
            val runEnd = samples.lastOrNull()?.atMs ?: 0L
            var carLocked = samples.firstOrNull()?.carLocked ?: true
            var alarmAt = -1L
            for (e in ev) {
                when (e.kind) {
                    "CAR_LOCKED" -> carLocked = true
                    "CAR_UNLOCKED" -> carLocked = false
                    "ALARM" -> alarmAt = e.atMs
                }
                if (e.kind != "ALARM_STOP" || e.detail != "silence" || e.distanceM < 10.0 || carLocked || alarmAt < 0) continue
                if (runEnd < e.atMs + 30_000) continue
                val left = alarmAt + com.openzeekr.app.ble.LockAlertPolicy.ALARM_EPISODE_MS - e.atMs
                val lockedNow = ev.any { it.kind == "CAR_LOCKED" && it.atMs in e.atMs..(e.atMs + 1_000) }
                val realarm = ev.any { it.kind == "ALARM" && it.atMs in e.atMs..(e.atMs + 30_000) }
                val back = samples.any { it.atMs in e.atMs..(e.atMs + 30_000) && it.distanceM <= 4.0 }
                if (left >= 10_000 && !lockedNow && !realarm && !back)
                    out += f("ALARM_SILENCED_AWAY", e, "alarm silenced at %.1f m with the car open, %d s early".format(e.distanceM, left / 1000))
            }
        }
        // Alarm hygiene: one alarm per episode, and it stops once the car is locked or the user is back.
        val alarms = ev.filter { it.kind == "ALARM" }
        // More often than every ~3 min (field: 12 alarms in 17 min).
        alarms.firstOrNull { a -> alarms.count { it.atMs in a.atMs until a.atMs + 600_000 } > 3 }?.let { a ->
            out += f("ALARM_SPAM", a, "${alarms.count { it.atMs in a.atMs until a.atMs + 600_000 }} alarms within 10 min") }
        for (a in alarms) {
            val stop = ev.firstOrNull { it.kind == "ALARM_STOP" && it.atMs >= a.atMs }?.atMs ?: Long.MAX_VALUE
            val soundingUntil = minOf(stop, a.atMs + com.openzeekr.app.ble.LockAlertPolicy.ALARM_EPISODE_MS)
            val lockedAt = ev.firstOrNull { it.kind == "CAR_LOCKED" && it.atMs in a.atMs until soundingUntil }?.atMs
            val backAt = samples.firstOrNull { it.atMs in a.atMs until soundingUntil && it.distanceM <= 2.0 }?.atMs
            val resolvedAt = listOfNotNull(lockedAt, backAt).minOrNull()
            if (resolvedAt != null && soundingUntil > resolvedAt + 10_000) {
                out += f("ALARM_NOT_SILENCED", a, "alarm kept sounding after the car was ${if (resolvedAt == lockedAt) "locked" else "reached"}")
                break
            }
        }
        // Car open and the user away: an alarm (or a Lock) is due. 15 m for 90 s = late; 20 m for 2 min = none.
        var awaySince = -1L; var lateDone = false
        var farOpenSince = -1L
        for (s in samples) {
            val open = !s.carLocked
            if (open && s.distanceM >= 15.0) {
                if (awaySince < 0) awaySince = s.atMs
                if (!lateDone && s.atMs - awaySince >= 90_000L) {
                    if (ev.none { it.kind == "ALARM" && it.atMs in (awaySince - 60_000)..s.atMs })
                        out += Finding("ALARM_LATE", s.atMs, s.distanceM, "open, user >= 15 m for 90 s, no alarm yet (${belief(s.atMs)})")
                    lateDone = true
                }
            } else { awaySince = -1L; lateDone = false }
            if (open && s.distanceM >= 20.0) {
                if (farOpenSince < 0) farOpenSince = s.atMs
                if (s.atMs - farOpenSince >= 120_000L) {
                    val alarmed = ev.any { it.kind == "ALARM" && it.atMs in (farOpenSince - 60_000)..s.atMs }
                    if (!alarmed) out += Finding("OPEN_FAR_NO_ALARM", s.atMs, s.distanceM, "car open, user >= 20 m for 2 min, no alarm (${belief(s.atMs)})")
                    farOpenSince = Long.MAX_VALUE / 2
                }
            } else if (farOpenSince != Long.MAX_VALUE / 2) farOpenSince = -1L
        }
        // Lock - unlock - lock by the app within a minute.
        val changes = ev.filter { (it.kind == "CAR_LOCKED" || it.kind == "CAR_UNLOCKED") && (it.detail == "key" || it.detail == "cloud") }
        for (i in 0 until changes.size - 2) {
            val a = changes[i]; val b = changes[i + 1]; val c = changes[i + 2]
            if (a.kind == "CAR_LOCKED" && b.kind == "CAR_UNLOCKED" && c.kind == "CAR_LOCKED" && c.atMs - a.atMs <= 60_000) {
                out += f("LOCK_UNLOCK_THRASH", a, "locked, opened and locked again within %.0f s".format((c.atMs - a.atMs) / 1000.0)); break
            }
        }

        // Segment expectations.
        val marks = ev.filter { it.kind == "MARK" } + SimWorld.Event(Long.MAX_VALUE, "MARK", 0.0, "end")
        fun plus(t: Long, ms: Long) = if (t > Long.MAX_VALUE - ms) Long.MAX_VALUE else t + ms
        for (i in 0 until marks.size - 1) {
            val m = marks[i]; val endAt = marks[i + 1].atMs
            val everUnlockedBefore = ev.any { it.kind == "CAR_UNLOCKED" && it.atMs < endAt }
            val unlockedAtMark = samples.lastOrNull { it.atMs <= m.atMs }?.carLocked == false
            val seg = ev.filter { it.atMs >= m.atMs && it.atMs < endAt }
            val segSamples = samples.filter { it.atMs >= m.atMs && it.atMs < endAt }
            when (m.detail) {
                // The car is already open at the mark and stays so: nothing to open. The finding that
                // left it open is counted where it happened (simulation audit B: 30 calibrated and 92
                // stress NO_UNLOCK segments were this).
                "expect-unlock" -> if (!(unlockedAtMark && seg.none { it.kind == "CAR_LOCKED" })) {
                    // An unlock just after the segment (user already at the door) is late, not missing.
                    val u = seg.firstOrNull { it.kind == "CAR_UNLOCKED" }
                        ?: ev.firstOrNull { it.kind == "CAR_UNLOCKED" && it.atMs in endAt..plus(endAt, 5_000) && it.distanceM <= 2.0 }
                    val arrival = segSamples.firstOrNull { it.distanceM <= 1.05 }
                    when {
                        u == null -> out += Finding("APPROACH_NO_UNLOCK", m.atMs, m.distanceM, "no unlock on approach (${belief(endAt.coerceAtMost(samples.last().atMs))})")
                        arrival != null && u.atMs > arrival.atMs + 1_000 ->
                            out += f("APPROACH_UNLOCK_AFTER_DOOR", u, "unlocked %.1f s after reaching the door".format((u.atMs - arrival.atMs) / 1000.0))
                        u.distanceM < 2.5 -> out += f("APPROACH_UNLOCK_LATE", u, "unlocked at %.1f m".format(u.distanceM))
                        u.distanceM > 12.0 -> out += f("APPROACH_UNLOCK_EARLY", u, "unlocked at %.1f m".format(u.distanceM))
                    }
                }
                "expect-lock" -> if (unlockedAtMark || seg.any { it.kind == "CAR_UNLOCKED" }) {
                    val l = seg.firstOrNull { it.kind == "CAR_LOCKED" }
                    // An alarm a few seconds into the next segment still counts as an alarm.
                    val alarmed = ev.any { it.kind == "ALARM" && it.atMs >= m.atMs && it.atMs < plus(endAt, 30_000) }
                    // The user came back within a minute: not locking in time is slow (comfort), not silent.
                    val shortTrip = endAt != Long.MAX_VALUE && endAt - m.atMs < 60_000L
                    when {
                        l == null && !alarmed && shortTrip -> out += Finding("NOT_LOCKED_BEFORE_RETURN", m.atMs, m.distanceM,
                            "no Lock in the %.0f s before the user turned back".format((endAt - m.atMs) / 1000.0))
                        l == null -> out += Finding(if (alarmed) "DEPARTURE_NO_LOCK_ALARMED" else "DEPARTURE_NO_LOCK_SILENT",
                            m.atMs, m.distanceM, "no Lock after the departure (${belief(minOf(endAt, samples.last().atMs))})")
                        l.detail == "self-lock" -> Unit
                        // Late = beyond 30 m and more than 12 s after passing 8 m: the proof needs ~12 s of
                        // evidence, which a jogger (F34, 3.5 m/s) turns into 40 m (simulation audit C: 80 of the
                        // 84 calibrated findings were this).
                        l.distanceM > 30.0 && (segSamples.firstOrNull { it.distanceM >= 8.0 }?.let { l.atMs - it.atMs > LOCK_EVIDENCE_MS } ?: true) ->
                            out += f("DEPARTURE_LOCK_LATE", l, "locked at %.1f m".format(l.distanceM))
                    }
                }
                // Only an open car needs the alarm (stress F41: the approach unlock failed, the car
                // stayed locked the whole time and was counted as a silent departure).
                "expect-alarm" -> if ((unlockedAtMark || seg.any { it.kind == "CAR_UNLOCKED" }) &&
                    ev.none { it.kind == "ALARM" && it.atMs >= m.atMs && it.atMs < plus(endAt, 30_000) } &&
                    seg.none { it.kind == "CAR_LOCKED" })
                    out += Finding("DEPARTURE_NO_LOCK_SILENT", m.atMs, m.distanceM, "car could not be locked and no alarm")
                "expect-stay-unlocked" -> seg.firstOrNull { it.kind == "KEY_LOCK_CMD" || it.kind == "CLOUD_LOCK_CMD" }?.let {
                    out += f("UNWANTED_LOCK_NEAR", it, "Lock at %.1f m while staying near".format(it.distanceM)) }
                "expect-stay-locked" -> {
                    // A walk that stops at the mark (F13, F31): a command decided while still walking up,
                    // or in the first seconds after the stop, is part of the approach - no sensor shows a
                    // stop that soon (simulation audit A, C0/C1).
                    val walkingAtMark = samples.lastOrNull { it.atMs <= m.atMs }?.moving == true
                    val from = if (walkingAtMark) plus(m.atMs, STOP_GRACE_MS) else m.atMs
                    seg.firstOrNull { it.kind == "KEY_UNLOCK_CMD" && it.atMs >= from }?.let {
                        out += f("UNWANTED_UNLOCK", it, "unlock at %.1f m where the car should stay locked".format(it.distanceM)) }
                }
                "expect-unlocked-by-end" -> segSamples.lastOrNull()?.let { if (it.carLocked && everUnlockedBefore)
                    out += Finding("BACK_AT_CAR_BUT_LOCKED", it.atMs, it.distanceM, "user back at the car, car locked") }
                "expect-locked-by-end" -> segSamples.lastOrNull()?.let { if (!it.carLocked)
                    out += Finding("GONE_BUT_UNLOCKED", it.atMs, it.distanceM, "user gone, car still open (${belief(it.atMs)})") }
            }
        }
        return out
    }
}
