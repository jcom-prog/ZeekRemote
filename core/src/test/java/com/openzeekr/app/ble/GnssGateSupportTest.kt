package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.0c: the car's remembered position ([CarPositionMemory]) and the locked-side fix filter ([LockedFixFilter]). */
class GnssGateSupportTest {
    @Test fun theCarPositionIsTheMedianOfTheKeptAnchors() {
        val memory = CarPositionMemory()
        assertNull(memory.position)
        memory.remember(DepartureFix(52.00000, 4.00000, 5f, 1L))
        memory.remember(DepartureFix(52.00001, 4.00001, 6f, 2L))
        memory.remember(DepartureFix(52.00020, 4.00030, 4f, 3L))   // one anchor with a GNSS jump (~25 m off)
        val p = memory.position!!
        assertEquals("the jumped anchor does not move the car", 52.00001, p.latitude, 1e-9)
        assertEquals(4.00001, p.longitude, 1e-9)
        assertEquals(5f, p.accuracyM, 1e-6f)
        assertEquals(3, memory.count)
        // only the last five anchors are kept
        repeat(5) { memory.remember(DepartureFix(52.00002, 4.00002, 7f, 10L + it)) }
        assertEquals(CarPositionMemory.KEPT, memory.count)
        assertEquals(52.00002, memory.position!!.latitude, 1e-9)
        assertEquals(7f, memory.position!!.accuracyM, 1e-6f)
    }

    @Test fun theCarPositionSurvivesARestartThroughTheSavedListAndIsForgottenInTheCar() {
        val memory = CarPositionMemory()
        memory.remember(DepartureFix(52.0, 4.0, 5f, 1L))
        memory.remember(DepartureFix(52.00001, 4.00001, 6f, 2L))
        val saved = memory.toSaved()
        assertEquals(2, saved.size)
        assertEquals(3, saved[0].size)
        val restored = CarPositionMemory(saved)
        assertEquals(2, restored.count)
        assertEquals(memory.position!!.latitude, restored.position!!.latitude, 1e-12)
        assertEquals(memory.position!!.longitude, restored.position!!.longitude, 1e-12)
        assertEquals(memory.position!!.accuracyM, restored.position!!.accuracyM, 1e-6f)
        restored.forget()
        assertNull(restored.position)
        assertEquals(0, restored.count)
        assertTrue(restored.toSaved().isEmpty())
        // a damaged saved entry is skipped
        assertEquals(1, CarPositionMemory(listOf(doubleArrayOf(52.0), doubleArrayOf(52.0, 4.0, 5.0))).count)
    }

    @Test fun theFixFilterSetsAJumpAsideAndReadsTheMedianAndTheLatest() {
        val f = LockedFixFilter()
        assertTrue(f.offer(1_000L, 24.0, 5f))
        assertEquals("accepted 24 m acc 5", f.lastNote)
        assertTrue(f.offer(2_000L, 22.0, 6f))
        // 1 s later 40 m further: faster than a walk (3 m/s plus 3 m) - a jump, set aside
        assertFalse(f.offer(3_000L, 62.0, 4f))
        assertEquals("set aside 62 m (a jump from 22 m)", f.lastNote)
        assertTrue(f.offer(4_000L, 21.0, 8f))
        assertEquals(22.0, f.medianM(4_000L, 12f)!!, 1e-9)
        assertEquals(8f, f.worstAccM(4_000L, 12f)!!, 1e-6f)
        assertEquals(21.0, f.latestM(4_000L, 12f)!!, 1e-9)
        // the accuracy cut leaves the inaccurate fix out
        assertEquals(23.0, f.medianM(4_000L, 6f)!!, 1e-9)
        assertNull("the latest fix is too inaccurate", f.latestM(4_000L, 6f))
        // the latest fix ages out after 3 s, the median after 5 s
        assertNull(f.latestM(7_100L, 12f))
        assertNotNull(f.medianM(7_100L, 12f))
        assertNull(f.medianM(9_100L, 12f))
    }

    @Test fun afterFifteenSecondsWithoutAnAcceptedFixTheNextFixIsAcceptedWhateverItSays() {
        val f = LockedFixFilter()
        assertTrue(f.offer(1_000L, 10.0, 5f))
        assertFalse(f.offer(2_000L, 40.0, 5f))
        assertFalse("still measured against the accepted 10 m", f.offer(3_000L, 41.0, 5f))
        assertTrue("15 s without an accepted fix: a new start", f.offer(16_100L, 41.0, 5f))
        assertEquals(41.0, f.latestM(16_100L, 12f)!!, 1e-9)
        f.clear()
        assertNull(f.medianM(16_100L, 12f))
        assertNull(f.latestM(16_100L, 12f))
    }
}
