package com.openzeekr.app.ble

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class PendingRssiReadTest {
    @Test fun clearingBeforeResumingWaiterPreservesItsNextRequest() = runBlocking {
        val reads = PendingRssiRead(); val old = Any(); val current = Any()
        val first = reads.begin(old)!!
        var next: PendingRssiRead.Request? = null
        val waiter = launch(Dispatchers.Unconfined) {
            assertNull(first.reply.await())
            next = reads.begin(current)
        }
        reads.clear()
        waiter.join()
        assertNotNull(next)
        assertNull(reads.begin(current))
        reads.response(current, -60, 0L)
        assertEquals(-60, next!!.reply.await()?.rssi)
    }
    @Test fun expiredOrFutureCallbackCannotSupplyDepartureEvidence() {
        val sample = PendingRssiRead.Sample(-90, 1_000L)
        assertEquals(-90, sample.valueAt(2_000L))
        assertNull(sample.valueAt(2_001L))
        assertNull(sample.valueAt(999L))
    }

    @Test fun synchronousCallbackCannotBeLostAndNextReadNeedsItsOwnReply() = runBlocking {
        val reads = PendingRssiRead(); val gatt = Any()
        val first = reads.begin(gatt)!!
        reads.response(gatt, -65, 0L) // callback before platform initiation returns
        assertEquals(-65, first.reply.await()?.rssi)
        val second = reads.begin(gatt)!!
        assertFalse(second.reply.isCompleted)
        reads.response(gatt, -65, 0L) // equal RSSI is valid when independently measured
        assertEquals(-65, second.reply.await()?.rssi)
    }

    @Test fun failedCallbackCannotReusePreviousGoodValue() = runBlocking {
        val reads = PendingRssiRead(); val gatt = Any()
        val first = reads.begin(gatt)!!; reads.response(gatt, -90, 0L)
        assertEquals(-90, first.reply.await()?.rssi)
        val second = reads.begin(gatt)!!; reads.response(gatt, null, 0L)
        assertNull(second.reply.await())
    }

    @Test fun oldConnectionReplyCannotSatisfyNewConnectionRead() = runBlocking {
        val reads = PendingRssiRead(); val old = Any(); val current = Any()
        val first = reads.begin(old)!!; val second = reads.begin(current)!!
        assertNull(first.reply.await())
        reads.response(old, -95, 0L)
        assertFalse(second.reply.isCompleted)
        reads.response(current, -60, 0L)
        assertEquals(-60, second.reply.await()?.rssi)
    }

    @Test fun timeoutMustDrainBeforeAnotherSameConnectionRequest() = runBlocking {
        val reads = PendingRssiRead(); val gatt = Any()
        val first = reads.begin(gatt)!!
        assertNull(withTimeoutOrNull(5) { first.reply.await() })
        assertNull(reads.begin(gatt))
        reads.response(gatt, -95, 0L) // late callback drains only the abandoned request
        val second = reads.begin(gatt)!!
        assertFalse(second.reply.isCompleted)
        reads.response(gatt, -60, 0L)
        assertEquals(-60, second.reply.await()?.rssi)
    }

    @Test fun cancelledReadCannotDonateItsReplyToAnotherRequest() = runBlocking {
        val reads = PendingRssiRead(); val gatt = Any()
        val first = reads.begin(gatt)!!; first.reply.cancel()
        assertNull(reads.begin(gatt))
        reads.response(gatt, -95, 0L)
        assertFalse(reads.begin(gatt)!!.reply.isCompleted)
    }

    @Test fun disconnectInvalidatesWaiterAndUnsolicitedReply() = runBlocking {
        val reads = PendingRssiRead(); val gatt = Any()
        val first = reads.begin(gatt)!!; reads.clear()
        assertNull(first.reply.await())
        reads.response(gatt, -95, 0L)
        assertFalse(reads.begin(gatt)!!.reply.isCompleted)
    }

    @Test fun rejectedOldRequestCannotCancelNewConnectionRead() = runBlocking {
        val reads = PendingRssiRead()
        val first = reads.begin(Any())!!; val second = reads.begin(Any())!!
        reads.rejected(first)
        assertFalse(second.reply.isCompleted)
        reads.rejected(second)
        assertNull(second.reply.await())
    }
}
