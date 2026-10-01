package com.openzeekr.app.ble

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class FreshRssiDepartureConfirmationTest {
    @Test fun fourIndependentRecedingReadsCanConfirmDeparture() = runBlocking {
        val reads = PendingRssiRead(); val gatt = Any()
        val confirmation = WalkAwayLockConfirmation(-82)
        var decision = WalkAwayLockConfirmation.Decision.WAIT
        for ((index, value) in listOf(-84, -85, -87, -88).withIndex()) {
            val now = index * 200L
            val request = reads.begin(gatt)!!
            reads.response(gatt, value, now)
            decision = confirmation.observe(request.reply.await()!!.valueAt(now)!!)
            if (index < 3) assertEquals(WalkAwayLockConfirmation.Decision.WAIT, decision)
        }
        assertEquals(WalkAwayLockConfirmation.Decision.LOCK, decision)
    }

    @Test fun oldConnectionWeakReplyCannotHideFreshNearRecovery() = runBlocking {
        val reads = PendingRssiRead(); val old = Any(); val current = Any()
        val confirmation = WalkAwayLockConfirmation(-82)
        assertEquals(WalkAwayLockConfirmation.Decision.WAIT, confirmation.observe(-84))
        reads.begin(old)!!
        val request = reads.begin(current)!!
        reads.response(old, -99, 200L)
        assertFalse(request.reply.isCompleted)
        reads.response(current, -65, 200L)
        assertEquals(WalkAwayLockConfirmation.Decision.CANCEL,
            confirmation.observe(request.reply.await()!!.valueAt(200L)!!))
    }

    @Test fun unansweredReadCannotReuseWeakValueForFourObservations() = runBlocking {
        val reads = PendingRssiRead(); val gatt = Any()
        val confirmation = WalkAwayLockConfirmation(-82)
        val first = reads.begin(gatt)!!; reads.response(gatt, -84, 0L)
        assertEquals(WalkAwayLockConfirmation.Decision.WAIT,
            confirmation.observe(first.reply.await()!!.valueAt(0L)!!))
        val lost = reads.begin(gatt)!!
        assertNull(withTimeoutOrNull(5) { lost.reply.await() })
        repeat(4) { assertNull(reads.begin(gatt)) }
        reads.response(gatt, -99, 200L) // drain; never supplied to confirmation
        val recovered = reads.begin(gatt)!!; reads.response(gatt, -65, 400L)
        assertEquals(WalkAwayLockConfirmation.Decision.CANCEL,
            confirmation.observe(recovered.reply.await()!!.valueAt(400L)!!))
    }
}
