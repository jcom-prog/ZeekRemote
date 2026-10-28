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

/** 0.4.5: the guard automaton as pure logic - the car, the cloud and the clock are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardAutomatonTest {

    private class FakeIo : GuardIo {
        var flag: Boolean? = false
        var car: GuardCar? = GuardCar(locked = true, doorsClosed = true, hoodClosed = true, trunkClosed = true, socPct = 60, charging = false)
        var accept = true
        /** The car follows our command after this many flag reads (0 = at once). */
        var confirmAfterReads = 0
        private var pendingWant: Boolean? = null
        private var readsSincePending = 0
        val sends = mutableListOf<Boolean>()
        val notes = mutableListOf<String>()
        var held = false
        override suspend fun readFlag(): Boolean? {
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
        override fun notify(title: String, text: String) { notes += title }
        override fun hold(active: Boolean) { held = active }
    }

    private val log = mutableListOf<String>()

    private fun TestScope.automaton(io: FakeIo, mode: GuardMode = GuardMode.AUTO, threshold: Int = 20, settings: () -> GuardSettings = { GuardSettings(mode, threshold) }) =
        GuardAutomaton(backgroundScope, io, settings, clock = { testScheduler.currentTime }, log = { log += it })

    @Test fun armsTwoMinutesAfterTheLockAndWaitsForTheCarToConfirm() = runTest {
        val io = FakeIo(); io.confirmAfterReads = 1          // the first poll still reads off, the second reads on
        val a = automaton(io)
        a.onLockConfirmed("walk-away ble-departure")
        advanceTimeBy(119_000); runCurrent()
        assertTrue("nothing sent before the delay", io.sends.isEmpty())
        assertTrue("CPU held while waiting", io.held)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertTrue(a.view.value.pending)
        advanceTimeBy(61_000); runCurrent()
        assertFalse(a.view.value.pending)
        assertEquals(true, a.view.value.flag)
        assertTrue(a.view.value.status, a.view.value.status.startsWith("Auto · on since"))
        assertFalse("CPU released after the arm", io.held)
        assertTrue(log.any { it.startsWith("guard: on confirmed after 60 s") })
        assertTrue(log.contains("guard: episode started (walk-away ble-departure)"))
    }

    @Test fun postponesOnceForAnOpenDoorThenSkips() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(doorsClosed = false)
        val a = automaton(io)
        a.onLockConfirmed("manual key control")
        advanceTimeBy(121_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(a.view.value.status.contains("open"))
        advanceTimeBy(121_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: postponed (door hood or tailgate open)"))
        assertTrue(log.contains("guard: skipped (door hood or tailgate open)"))
    }

    @Test fun skipsBelowTheThresholdAndArmsOnceTheBatteryRecovers() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(socPct = 15)
        val a = automaton(io, threshold = 20)
        a.onLockConfirmed("car self-lock")
        advanceTimeBy(121_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(log.contains("guard: skipped (low soc 15%)"))
        io.car = io.car!!.copy(socPct = 24)                   // above the threshold but inside the hysteresis
        advanceTimeBy(3_600_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        io.car = io.car!!.copy(socPct = 25)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertTrue(log.contains("guard: battery recovered (25%)"))
    }

    @Test fun switchesOffBelowTheThresholdWhileOnAndTellsTheOwner() = runTest {
        val io = FakeIo()
        val a = automaton(io, threshold = 20)
        a.onLockConfirmed("walk-away")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        io.car = io.car!!.copy(socPct = 14)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(listOf(true, false), io.sends)
        assertEquals(listOf("Sentry guard switched off"), io.notes)
        assertEquals(false, a.view.value.flag)
        assertTrue(log.contains("guard: off sent (low soc 14%)"))
        io.car = io.car!!.copy(socPct = 30)
        advanceTimeBy(3_600_000 + 31_000); runCurrent()
        assertEquals(listOf(true, false, true), io.sends)
    }

    @Test fun chargingOverridesTheThreshold() = runTest {
        val io = FakeIo(); io.car = io.car!!.copy(socPct = 10, charging = true)
        val a = automaton(io, threshold = 20)
        a.onLockConfirmed("walk-away")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
    }

    @Test fun theOwnersOffWinsUntilTheNextLock() = runTest {
        val io = FakeIo()
        val a = automaton(io)
        a.onLockConfirmed("walk-away")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        io.flag = false                                        // switched off on the car's screen
        a.onFlagRead(false)
        assertTrue(a.view.value.status.contains("until the next lock"))
        io.car = io.car!!.copy(socPct = 60)
        advanceTimeBy(2 * 3_600_000); runCurrent()
        assertEquals("no re-arm in this episode", listOf(true), io.sends)
        assertTrue(log.contains("guard: off detected"))
        a.onUnlockConfirmed("approach")
        a.onLockConfirmed("walk-away")
        advanceTimeBy(121_000 + 31_000); runCurrent()
        assertEquals(listOf(true, true), io.sends)
    }

    @Test fun theOwnersSwitchOffDuringTheDelayBlocksTheArm() = runTest {
        val io = FakeIo()
        val a = automaton(io)
        a.onLockConfirmed("walk-away")
        advanceTimeBy(30_000); runCurrent()
        a.onManualSwitch(false)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(listOf(false), io.sends)
        assertTrue(a.view.value.status, a.view.value.status.startsWith("Off by hand"))
        advanceTimeBy(300_000); runCurrent()
        assertEquals(listOf(false), io.sends)
    }

    @Test fun anUnlockCancelsThePendingArm() = runTest {
        val io = FakeIo()
        val a = automaton(io)
        a.onLockConfirmed("walk-away")
        advanceTimeBy(60_000); runCurrent()
        a.onUnlockConfirmed("approach")
        assertFalse(io.held)
        advanceTimeBy(300_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        assertTrue(a.view.value.status.startsWith("Car open"))
    }

    @Test fun retriesOnceThenNotifiesWhenTheCarNeverConfirms() = runTest {
        val io = FakeIo(); io.confirmAfterReads = 99
        val a = automaton(io)
        a.onLockConfirmed("walk-away")
        advanceTimeBy(121_000 + 6 * 31_000); runCurrent()
        assertEquals(listOf(true, true), io.sends)
        assertEquals(listOf("Sentry guard not confirmed"), io.notes)
        assertFalse(a.view.value.pending)
        assertTrue(a.view.value.status.contains("not confirmed"))
    }

    @Test fun manualAndOffModesNeverSendByThemselves() = runTest {
        for (mode in listOf(GuardMode.MANUAL, GuardMode.OFF)) {
            val io = FakeIo()
            val a = automaton(io, mode = mode)
            a.onLockConfirmed("walk-away")
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

    @Test fun switchingToAutoWhileParkedArmsAtOnce() = runTest {
        val io = FakeIo()
        var mode = GuardMode.MANUAL
        val a = automaton(io, settings = { GuardSettings(mode, 20) })
        a.onLockConfirmed("walk-away")
        advanceTimeBy(300_000); runCurrent()
        assertTrue(io.sends.isEmpty())
        mode = GuardMode.AUTO; a.onSettingsChanged()
        advanceTimeBy(31_000); runCurrent()
        assertEquals(listOf(true), io.sends)
        assertEquals(GuardMode.AUTO, a.view.value.mode)
    }

    @Test fun modeParsingIsForgiving() {
        assertEquals(GuardMode.AUTO, GuardMode.parse("auto"))
        assertEquals(GuardMode.OFF, GuardMode.parse("OFF"))
        assertEquals(GuardMode.MANUAL, GuardMode.parse(null))
        assertEquals(GuardMode.MANUAL, GuardMode.parse("nonsense"))
        assertNull(GuardView().flag)
    }
}
