package com.openzeekr.app.guard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 0.4.5/0.4.6: the guard automaton as pure logic - the car, the cloud and the clock are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardAutomatonTest {

    private class FakeIo : GuardIo {
        var flag: Boolean? = false
        var car: GuardCar? = GuardCar(locked = true, doorsClosed = true, bonnetClosed = true, trunkClosed = true, socPct = 60, charging = false)
        var accept = true
        /** The car follows our command after this many flag reads (0 = at once). */
        var confirmAfterReads = 0
        private var pendingWant: Boolean? = null
        private var readsSincePending = 0
        val sends = mutableListOf<Boolean>()
        val notes = mutableListOf<String>()
        val actions = mutableListOf<GuardAction?>()
        var closes = 0
        var held = false
        /** 0.4.8 */
        var atCar: Boolean? = false
        var unreadable = false
        var cancels = 0
        override suspend fun readFlag(): Boolean? {
            if (unreadable) return null
            pendingWant?.let { want ->
                readsSincePending++
                if (readsSincePending > confirmAfterReads) { flag = want; pendingWant = null }
            }
            return flag
        }
        override suspend fun readCar(): GuardCar? = car
        override suspend fun send(on: Boolean): Boolean {
            sends += on
            if (accept) { pendingWant = on; readsSincePending = 0 }
            return accept
        }
        override suspend fun closeWindows(): Boolean { closes++; return true }
        override fun atCar(): Boolean? = atCar
        override fun cancelNotice() { cancels++ }
        override fun notify(title: String, text: String, action: GuardAction?) { notes += title; actions += action }
        override fun hold(active: Boolean) { held = active }
    }

    private val log = mutableListOf<String>()
    private val day = 86_400_000L
    private val home = GuardZone(12.0, 65.0, 50, "home")

    private fun TestScope.automaton(
        io: FakeIo, mode: GuardMode = GuardMode.AUTO, threshold: Int = 20,
        settings: () -> GuardSettings = { GuardSettings(mode, threshold, dailyCheckHour = -1) },
    ) = GuardAutomaton(backgroundScope, io, settings, clock = { testScheduler.currentTime }, log = { log += it },
        // The daily check, when a test switches it on, is due at the next whole (fake) day.
        nextDaily = { now, _ -> (now / day + 1) * day })

    private fun carAt(lat: Double?, lon: Double?, trusted: Boolean? = true) =
        GuardCar(locked = true, doorsClosed = true, bonnetClosed = true, trunkClosed = true, socPct = 60, charging = false,
            lat = lat, lon = lon, positionTrusted = trusted)

    @Test fun armsTwoMinutesAfterTheLockAndWaitsForTheCarToConfirm() = runTest {
        val io = FakeIo(); io.confirmAfterReads = 1          // the first poll (10 s) still reads off, the second (30 s) reads on
        val a = automaton(io)
        a.onLockConfirmed("walk-away ble-departure")
        advanceTimeBy(119_000); runCurrent()
        assertTrue("nothing sent before the delay", io.sends.isEmpty())
        assertTrue("CPU held while waiting", io.held)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertTrue(a.view.value.pending)
        advanceTimeBy(31_000); runCurrent()
        assertFalse(a.view.value.pending)
        assertEquals(true, a.view.value.flag)
        assertTrue(a.view.value.status, a.view.value.status.startsWith("Auto · on since"))
        assertTrue(log.contains("guard: on confirmed after 30 s"))
        assertFalse("CPU released after the episode's work", io.held)
        assertTrue(io.notes.isEmpty())
    }

    @Test fun postponesOnceForAnOpenDoorThenSkips() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(doorsClosed = false)
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(121_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: postponed (door bonnet or tailgate open)"))
        advanceTimeBy(121_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: skipped (door bonnet or tailgate open)"))
        assertTrue(a.view.value.status, a.view.value.status.startsWith("Auto · skipped: a door, the bonnet"))
    }

    @Test fun skipsBelowTheThresholdAndArmsOnceTheBatteryRecovers() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(socPct = 15)
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(121_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: skipped (low soc 15%)"))
        io.car = io.car!!.copy(socPct = 24)                    // above 20 but not above 20 + 5: stays off
        advanceTimeBy(3_600_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        io.car = io.car!!.copy(socPct = 26)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertTrue(log.contains("guard: battery recovered (26%)"))
    }

    @Test fun switchesOffBelowTheThresholdWhileOnAndTellsTheOwner() = runTest {
        val io = FakeIo()
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        io.car = io.car!!.copy(socPct = 19)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(listOf(true, false), io.sends)
        assertEquals(listOf("Sentry guard switched off"), io.notes)
        assertTrue(log.contains("guard: off sent (low soc 19%)"))
        io.car = io.car!!.copy(socPct = 30)
        advanceTimeBy(3_600_000 + 31_000); runCurrent()
        assertEquals(listOf(true, false, true), io.sends)
    }

    @Test fun chargingOverridesTheThreshold() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(socPct = 5, charging = true)
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
    }

    @Test fun theOwnersOffWinsUntilTheNextLock() = runTest {
        val io = FakeIo()
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        // The owner switched it off on the car's screen: the next read sees the flag off.
        io.flag = false; a.onFlagRead(false); runCurrent()
        assertTrue(log.contains("guard: off detected"))
        advanceTimeBy(2 * 3_600_000); runCurrent()
        assertEquals("no re-arm in this episode", listOf(true), io.sends)
        // The next lock starts afresh.
        a.onUnlockConfirmed("proximity"); a.onLockConfirmed("walk-away")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true, true), io.sends)
    }

    @Test fun theOwnersSwitchOffDuringTheDelayBlocksTheArm() = runTest {
        val io = FakeIo(); io.flag = true
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(30_000); runCurrent()
        a.onManualSwitch(false)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(listOf(false), io.sends)
        assertEquals("Off by hand until the next lock", a.view.value.status)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(listOf(false), io.sends)
    }

    @Test fun anUnlockCancelsThePendingArm() = runTest {
        val io = FakeIo()
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(60_000); runCurrent()
        a.onUnlockConfirmed("proximity")
        assertFalse(io.held)
        advanceTimeBy(300_000); runCurrent()
        assertTrue(io.sends.isEmpty())
    }

    @Test fun retriesOnceThenNotifiesWhenTheCarNeverConfirms() = runTest {
        val io = FakeIo(); io.confirmAfterReads = 99
        val a = automaton(io)
        a.onLockConfirmed("key")
        // 0.4.8: 2 min delay, 5 min of polls, a second attempt with 5 min of minute-polls, then the notice.
        advanceTimeBy(121_000 + 290_000); runCurrent()
        assertEquals(listOf(true, true), io.sends)
        assertTrue(io.notes.isEmpty())
        advanceTimeBy(301_000); runCurrent()
        assertEquals(listOf("Sentry guard not confirmed"), io.notes)
        assertTrue(log.contains("guard: not confirmed -> notified"))
        assertFalse(a.view.value.pending)
        // The car follows after all: the notice is withdrawn and the row says on.
        io.flag = true
        advanceTimeBy(61_000); runCurrent()
        assertEquals(1, io.cancels)
        assertTrue(log.contains("guard: on confirmed late"))
        assertTrue(a.view.value.status, a.view.value.status.startsWith("Auto · on since"))
    }

    @Test fun aReadFailureIsToldApartFromACarThatSaysOff() = runTest {
        val io = FakeIo(); io.unreadable = true
        val a = automaton(io)
        a.onLockConfirmed("key")
        advanceTimeBy(121_000 + 290_000 + 301_000); runCurrent()
        assertEquals(listOf("Sentry guard: state not readable"), io.notes)
        assertTrue(log.contains("guard: flag unreadable -> notified"))
    }

    @Test fun aLockWithThePhoneStillInTheCarWaitsUntilTheOwnerHasLeft() = runTest {
        // 0.4.8 (field 29/10): the doors lock at drive-off; the automaton must not ask for the guard while driving.
        val io = FakeIo(); io.atCar = true
        val a = automaton(io)
        a.onLockConfirmed("car self-lock")
        advanceTimeBy(10 * 60_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: waiting (phone at the car)"))
        assertTrue(a.view.value.status, a.view.value.status.contains("waiting until the car is parked"))
        assertFalse("no wakelock while riding", io.held)
        // Arrival: the door opens (the episode ends); nothing is sent afterwards either.
        a.onUnlockConfirmed("car reopened"); io.atCar = false
        advanceTimeBy(10 * 60_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        // The walk-away lock with the phone away arms as before; a lock at the car that is then left arms 2 min after leaving.
        io.atCar = true; a.onLockConfirmed("key")
        advanceTimeBy(5 * 60_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        io.atCar = false
        advanceTimeBy(31_000 + 121_000 + 11_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertTrue(log.contains("guard: phone away -> arm delay"))
    }

    @Test fun manualAndOffModesNeverSendByThemselves() = runTest {
        for (mode in listOf(GuardMode.MANUAL, GuardMode.OFF)) {
            val io = FakeIo()
            val a = automaton(io, mode = mode)
            a.onLockConfirmed("key")
            advanceTimeBy(600_000); runCurrent()
            assertTrue(io.sends.isEmpty())
            assertEquals(GuardAutomaton.initialStatus(mode), a.view.value.status)
        }
    }

    @Test fun theManualSwitchSendsAndFollowsTheCar() = runTest {
        val io = FakeIo(); io.confirmAfterReads = 1
        val a = automaton(io, mode = GuardMode.MANUAL)
        a.onManualSwitch(true)
        runCurrent()
        assertEquals(listOf(true), io.sends)
        assertTrue(a.view.value.pending)
        advanceTimeBy(61_000); runCurrent()
        assertFalse(a.view.value.pending)
        assertEquals(true, a.view.value.flag)
        // The confirmation time is a wall-clock stamp (time zone of the device), so only the shape is checked.
        val status = a.view.value.status
        assertTrue(status, status.startsWith("on since ") && status.endsWith(" (by hand)"))
    }

    @Test fun theSwitchFollowsTheHandWhileTheCarIsAsked() = runTest {
        // 0.4.6: the switch shows the owner's hand while the command is pending, not the stale flag.
        val io = FakeIo(); io.confirmAfterReads = 1
        val a = automaton(io, mode = GuardMode.MANUAL)
        a.onManualSwitch(true); runCurrent()
        assertEquals(true, a.view.value.wanted); assertTrue(a.view.value.pending)
        advanceTimeBy(11_000); runCurrent()
        assertEquals("the first poll (10 s) still reads off: the hand stays", true, a.view.value.wanted)
        assertEquals(false, a.view.value.flag)
        advanceTimeBy(20_000); runCurrent()
        assertNull(a.view.value.wanted); assertFalse(a.view.value.pending); assertEquals(true, a.view.value.flag)
        assertTrue(log.contains("guard: on confirmed after 30 s"))
        // Not confirmed within the five polls: the hand is dropped and the owner told.
        val io2 = FakeIo(); io2.confirmAfterReads = 99
        val b = automaton(io2, mode = GuardMode.MANUAL)
        b.onManualSwitch(true); advanceTimeBy(91_000); runCurrent()
        assertNull(b.view.value.wanted); assertFalse(b.view.value.pending)
        assertTrue(b.view.value.status, b.view.value.status.startsWith("Not confirmed by the car within 90 s"))
    }

    @Test fun switchingToAutoWhileParkedArmsAtOnce() = runTest {
        val io = FakeIo()
        var mode = GuardMode.MANUAL
        val a = automaton(io, settings = { GuardSettings(mode, 20, dailyCheckHour = -1) })
        a.onLockConfirmed("key")
        advanceTimeBy(300_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        mode = GuardMode.AUTO; a.onSettingsChanged()
        advanceTimeBy(31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
    }

    @Test fun insideAQuietZoneTheAutomatonStandsDownButTheSwitchStillWorks() = runTest {
        // 0.4.6: ~22 m from the zone's centre, radius 50 m.
        val io = FakeIo(); io.car = carAt(12.0002, 65.0)
        val a = automaton(io, settings = { GuardSettings(GuardMode.AUTO, 20, zones = listOf(home), dailyCheckHour = -1) })
        a.onLockConfirmed("key")
        advanceTimeBy(121_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: skipped (zone 1)"))
        assertTrue(a.view.value.status, a.view.value.status.contains("quiet zone home"))
        assertTrue("never a position or a zone label in the log", log.none { it.contains("12.0") || it.contains("65.0") || it.contains("home") })
        // Override a: the owner's switch arms it anyway, for this parking.
        a.onManualSwitch(true); advanceTimeBy(11_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertTrue(a.view.value.status, a.view.value.status.startsWith("Auto · on since"))
        advanceTimeBy(2 * 3_600_000); runCurrent()
        assertEquals("the hourly battery check runs, nothing else is sent", listOf(true), io.sends)
    }

    @Test fun outsideTheZonesAndWithAnUntrustedPositionTheArmProceeds() = runTest {
        val io = FakeIo(); io.car = carAt(12.01, 65.0)         // ~1.1 km away
        val a = automaton(io, settings = { GuardSettings(GuardMode.AUTO, 20, zones = listOf(home), dailyCheckHour = -1) })
        a.onLockConfirmed("key"); advanceTimeBy(121_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        // Right in the zone, but the fix is not to be trusted (or missing): the safe side is to arm.
        for (car in listOf(carAt(12.0, 65.0, trusted = false), carAt(null, null))) {
            val io2 = FakeIo(); io2.car = car
            val b = automaton(io2, settings = { GuardSettings(GuardMode.AUTO, 20, zones = listOf(home), dailyCheckHour = -1) })
            b.onLockConfirmed("key"); advanceTimeBy(121_000); runCurrent()
            assertEquals(listOf(true), io2.sends)
        }
        assertTrue(log.contains("guard: position unknown -> arming"))
    }

    @Test fun theZoneRuleCanBeSwitchedOff() = runTest {
        val io = FakeIo(); io.car = carAt(12.0, 65.0)
        val a = automaton(io, settings = { GuardSettings(GuardMode.AUTO, 20, zones = listOf(home), skipInZones = false, dailyCheckHour = -1) })
        a.onLockConfirmed("key"); advanceTimeBy(121_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertFalse(log.any { it.startsWith("guard: skipped") })
    }

    @Test fun anOpenWindowAtArmTimeIsReportedWithACloseButtonAndTheArmGoesOn() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(windowsOpen = true)
        val a = automaton(io)
        a.onLockConfirmed("key"); advanceTimeBy(121_000); runCurrent()
        assertEquals(listOf("Sentry guard: a window is open"), io.notes)
        assertEquals(listOf(GuardAction.CLOSE_WINDOWS), io.actions)
        assertEquals("the guard is still switched on", listOf(true), io.sends)
        assertTrue(log.contains("guard: windows open -> notified"))
        // The button: close, then read back and tell the owner.
        a.closeWindowsFromNotification(); runCurrent()
        assertEquals(1, io.closes)
        io.car = io.car!!.copy(windowsOpen = false)
        advanceTimeBy(21_000); runCurrent()
        assertEquals("Windows", io.notes.last())
        assertTrue(log.contains("guard: windows closed"))
        // The warning is not disabled: a car with the setting off is never bothered.
        val io2 = FakeIo(); io2.car = io2.car!!.copy(windowsOpen = true)
        val b = automaton(io2, settings = { GuardSettings(GuardMode.AUTO, 20, windowWarning = false, dailyCheckHour = -1) })
        b.onLockConfirmed("key"); advanceTimeBy(121_000); runCurrent()
        assertTrue(io2.notes.isEmpty())
    }

    @Test fun theDailyCheckArmsAGuardThatIsOffAndIsSilentWhenAllIsWell() = runTest {
        val io = FakeIo()
        val a = automaton(io, settings = { GuardSettings(GuardMode.AUTO, 20, dailyCheckHour = 8) })
        // No lock event (the phone restarted while the car stood locked): the check finds the guard off and arms it.
        a.onKeepAliveRound(); runCurrent()
        advanceTimeBy(day); a.onKeepAliveRound(); advanceTimeBy(11_000); runCurrent()
        assertTrue(log.contains("guard: daily check -> arming"))
        assertEquals(listOf(true), io.sends)
        assertTrue(io.notes.isEmpty())
        // The next day all is well: one log line, no notification, nothing sent.
        advanceTimeBy(day); a.onKeepAliveRound(); advanceTimeBy(1_000); runCurrent()
        assertTrue(log.contains("guard: daily check ok (on)"))
        assertEquals(listOf(true), io.sends)
        assertTrue(io.notes.isEmpty())
    }

    @Test fun theDailyCheckReportsAnOpenDoorAndALowBatteryAndDoesNotArm() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(doorsClosed = false, socPct = 12)
        val a = automaton(io, settings = { GuardSettings(GuardMode.AUTO, 20, dailyCheckHour = 8) })
        a.onKeepAliveRound(); advanceTimeBy(day); a.onKeepAliveRound(); runCurrent()
        assertEquals(listOf("Sentry guard daily check"), io.notes)
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: daily check issues 2"))
        // In MANUAL the check only reports; it never arms.
        val io2 = FakeIo()
        val b = automaton(io2, settings = { GuardSettings(GuardMode.MANUAL, 20, dailyCheckHour = 8) })
        b.onKeepAliveRound(); advanceTimeBy(day); b.onKeepAliveRound(); runCurrent()
        assertTrue(io2.sends.isEmpty()); assertTrue(io2.notes.isEmpty())
        assertTrue(log.contains("guard: daily check ok (off)"))
    }

    @Test fun distancesAndTheDailyHourAreComputedSoundly() {
        assertEquals(0.0, GuardAutomaton.distanceM(12.0, 65.0, 12.0, 65.0), 0.01)
        assertEquals(111.2, GuardAutomaton.distanceM(12.0, 65.0, 12.001, 65.0), 1.0)    // 0.001° of latitude ≈ 111 m
        val now = System.currentTimeMillis()
        val next = GuardAutomaton.nextLocalHour(now, 8)
        assertTrue(next > now && next - now <= 86_400_000L)
        assertEquals(8, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun modeParsingIsForgiving() {
        assertEquals(GuardMode.AUTO, GuardMode.parse("auto"))
        assertEquals(GuardMode.AUTO, GuardMode.parse("AUTO"))
        assertEquals(GuardMode.OFF, GuardMode.parse("off"))
        assertEquals(GuardMode.MANUAL, GuardMode.parse(null))
        assertEquals(GuardMode.MANUAL, GuardMode.parse("nonsense"))
        assertEquals("auto", GuardMode.AUTO.key)
    }
}
