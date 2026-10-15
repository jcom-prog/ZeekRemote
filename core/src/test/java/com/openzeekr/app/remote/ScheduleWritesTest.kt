package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.CycleTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWritesTest {

    private var now = 1_000_000L
    private val writes = ScheduleWrites { now }

    private val sent = Departures.newPlan(DepartureDraft("Plan B", "06:40:00", setOf(7)), btId = 2)

    /** The car's read-back: slot 1 in use, slot 2 empty, unless [stored] puts the sent plan in slot 2. */
    private fun readBack(stored: Boolean): List<BookingTravelSetting> = listOf(
        BookingTravelSetting(btId = 1, name = "Plan A", sts = 0, displaySts = 1, bookingType = 1,
            temporaryTime = Departures.PLACEHOLDER_TIME, preHeatSts = 0, cycleTimes = (1..7).map { CycleTime(it, if (it == 2) 1 else 0, "07:30:00") }),
        if (stored) sent else BookingTravelSetting(btId = 2, name = Departures.EMPTY_SLOT_NAME, sts = 0, displaySts = 0, bookingType = 1,
            temporaryTime = Departures.PLACEHOLDER_TIME, cycleTimes = emptyList(), preHeatSts = 0),
    )

    @Test fun aNewDepartureWaitingForTheCarRefusesASecondCreateUntilTheCarShowsIt() {
        assertTrue(writes.canCreate())
        writes.createSent(sent)
        assertFalse(writes.canCreate())
        assertFalse("not on the car yet", writes.readBack(readBack(stored = false)))
        assertFalse(writes.canCreate())
        assertTrue("stored: reported once", writes.readBack(readBack(stored = true)))
        assertTrue(writes.canCreate())
        assertFalse("nothing waits any more", writes.readBack(readBack(stored = true)))
    }

    @Test fun theWaitEndsAfterTenMinutesWhenTheCarNeverShowsIt() {
        writes.createSent(sent)
        now += ScheduleWrites.HOLD_MS - 1
        assertFalse(writes.canCreate())
        now += 1
        assertTrue("the cloud may have dropped it", writes.canCreate())
        assertNull(writes.awaiting.value)
    }

    @Test fun onlyOneWriteIsInFlightAndItAlwaysEnds() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = async { writes.exclusive(ScheduleWrites.Kind.EDIT) { release.await(); "first" } }
        yield()
        assertEquals(ScheduleWrites.Kind.EDIT, writes.busy.value)
        assertFalse("no Create while a write waits for the car", writes.canCreate())
        assertNull("a second write does not run", writes.exclusive(ScheduleWrites.Kind.DELETE) { "second" })
        release.complete(Unit)
        assertEquals("first", first.await())
        assertNull(writes.busy.value)
        // A write that fails still frees the slot for the next one.
        runCatching { writes.exclusive<Unit>(ScheduleWrites.Kind.CHARGE) { error("network") } }
        assertNull(writes.busy.value)
        assertEquals("next", writes.exclusive(ScheduleWrites.Kind.SWITCH) { "next" })
    }
}
