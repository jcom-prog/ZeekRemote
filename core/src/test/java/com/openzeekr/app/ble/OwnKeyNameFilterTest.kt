package com.openzeekr.app.ble

import com.openzeekr.app.ble.OwnKeyNameFilter.Decision.ACCEPT
import com.openzeekr.app.ble.OwnKeyNameFilter.Decision.SKIP_OTHER_CAR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnKeyNameFilterTest {
    private class MemStore(var value: String? = null) : OwnKeyNameFilter.Store {
        var saves = 0
        override fun load() = value
        override fun save(name: String?) { value = name; saves++ }
    }

    private val own = "Zeekr111"
    private val other = "Zeekr222"

    /** First advert the scan would connect to: (seconds into the scan, name). */
    private fun firstConnect(filter: OwnKeyNameFilter, adverts: List<Pair<Int, String?>>): Pair<Int, String?>? {
        filter.onScanStart()
        return adverts.firstOrNull { filter.decide(it.second) == ACCEPT }
    }

    @Test fun `nothing learned yet - every Zeekr is accepted, as before`() {
        val f = OwnKeyNameFilter(MemStore())
        assertEquals(ACCEPT, f.decide(other))
        assertEquals(ACCEPT, f.decide(own))
        assertEquals(ACCEPT, f.decide(null))
    }

    @Test fun `the name of a ready session is learned and persisted`() {
        val store = MemStore()
        val f = OwnKeyNameFilter(store)
        f.onSessionReady(own)
        assertEquals(own, store.value)
        assertEquals(own, OwnKeyNameFilter(store).learnedName)
    }

    @Test fun `another car nearby is skipped, our module is connected (field 12-10 shape)`() {
        val f = OwnKeyNameFilter(MemStore(own))
        // Another Zeekr is heard first for ~50 s, ours only later.
        val adverts = listOf(0 to other, 12 to other, 35 to other, 54 to own)
        assertEquals(54 to own, firstConnect(f, adverts))
        // Before 0.2.15 the scan took the first Zeekr: the other car at 0 s.
        assertEquals(0 to other, firstConnect(OwnKeyNameFilter(MemStore()), adverts))
    }

    @Test fun `case and spacing do not matter`() {
        val f = OwnKeyNameFilter(MemStore(own))
        assertEquals(ACCEPT, f.decide(" zeekr111 "))
    }

    @Test fun `an advert without a name is never skipped (screen-off scans drop the name)`() {
        val f = OwnKeyNameFilter(MemStore(own))
        assertEquals(ACCEPT, f.decide(null))
        assertEquals(ACCEPT, f.decide("  "))
    }

    @Test fun `one scan with only another car does not forget our name`() {
        val store = MemStore(own)
        val f = OwnKeyNameFilter(store)
        f.onScanStart(); assertEquals(SKIP_OTHER_CAR, f.decide(other))
        assertFalse(f.onScanTimeout())
        assertEquals(own, f.learnedName)
    }

    @Test fun `a renamed own module is accepted again after three scans (no lock-out)`() {
        val store = MemStore(own)
        val f = OwnKeyNameFilter(store)
        repeat(OwnKeyNameFilter.FORGET_AFTER_TIMEOUTS - 1) {
            f.onScanStart(); assertEquals(SKIP_OTHER_CAR, f.decide("Zeekr333"))
            assertFalse(f.onScanTimeout())
        }
        f.onScanStart(); assertEquals(SKIP_OTHER_CAR, f.decide("Zeekr333"))
        assertTrue(f.onScanTimeout())
        assertNull(store.value)
        f.onScanStart(); assertEquals(ACCEPT, f.decide("Zeekr333"))
        f.onSessionReady("Zeekr333")
        assertEquals("Zeekr333", store.value)
    }

    @Test fun `a successful session or an empty scan resets the forget count`() {
        val f = OwnKeyNameFilter(MemStore(own))
        f.onScanStart(); f.decide(other); f.onScanTimeout()
        f.onScanStart(); f.decide(other); f.onScanTimeout()
        f.onSessionReady(own)
        f.onScanStart(); f.decide(other); assertFalse(f.onScanTimeout())
        f.onScanStart(); f.decide(other); assertFalse(f.onScanTimeout())
        f.onScanStart(); assertFalse(f.onScanTimeout()) // nothing heard at all
        f.onScanStart(); f.decide(other); assertFalse(f.onScanTimeout())
        assertEquals(own, f.learnedName)
    }

    @Test fun `the same name is not re-saved on every session`() {
        val store = MemStore(own)
        val f = OwnKeyNameFilter(store)
        f.onSessionReady(own); f.onSessionReady(own); f.onSessionReady(null)
        assertEquals(0, store.saves)
    }
}
