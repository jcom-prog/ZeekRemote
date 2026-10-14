package com.openzeekr.app.ble

import android.app.NotificationManager
import com.openzeekr.app.ble.LockAlarmHealth.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan K5 (0.3.0): which settings keep the "car not locked" warning from the user. */
class LockAlarmHealthTest {
    private val high = NotificationManager.IMPORTANCE_HIGH

    @Test fun allOnIsHealthy() {
        assertEquals(emptyList<Problem>(), LockAlarmHealth.evaluate(true, high, high))
        assertEquals("channels not created yet", emptyList<Problem>(), LockAlarmHealth.evaluate(true, null, null))
    }

    @Test fun notificationsOffIsTheOneProblemToFixFirst() {
        assertEquals(listOf(Problem.NOTIFICATIONS_OFF), LockAlarmHealth.evaluate(false, high, NotificationManager.IMPORTANCE_NONE))
    }

    @Test fun mutedOrBlockedChannelsAreReported() {
        assertEquals(listOf(Problem.ALARM_CHANNEL_SILENT), LockAlarmHealth.evaluate(true, high, NotificationManager.IMPORTANCE_LOW))
        assertEquals(listOf(Problem.WARNING_CHANNEL_OFF, Problem.ALARM_CHANNEL_OFF),
            LockAlarmHealth.evaluate(true, NotificationManager.IMPORTANCE_NONE, NotificationManager.IMPORTANCE_NONE))
    }
}
