package com.openzeekr.app.ble

/**
 * Never start a key connection from an advert heard at the edge of radio range.
 *
 * Field 13/10 19:41-20:06 (0.2.15, phone at home, car parked outside): 54 status-133 failures, all
 * GATT_CONN_FAILED_ESTABLISHMENT, all from adverts at -95 dBm or weaker (presence hits at -98..-100
 * and their immediate retries); across 03/10-13/10 more than half of all connects ended in 133 and
 * a connection that hit one was ready after ~20 s instead of ~5 s. A link cannot be established
 * that far out, so trying only costs time, wakelocks and a 133 recovery cycle.
 *
 * At [CONNECT_FLOOR_RSSI] (~25 m) a walking user still reaches the approach-unlock range (~10 m)
 * well after the ~4 s connect + handshake. A weak hit while MOVING still counts as arrival evidence
 * (the presence epoch) but starts the bounded foreground scan instead of a direct connect; that
 * scan connects as soon as an advert reaches the floor. A weak hit while still is ignored (the
 * offloaded scan stays armed). Pure Kotlin.
 */
internal object WeakConnectGate {
    const val CONNECT_FLOOR_RSSI = -94

    fun tooWeak(advertRssi: Int): Boolean = advertRssi < CONNECT_FLOOR_RSSI

    enum class PresenceAction { CONNECT, FOREGROUND_SCAN, IGNORE }

    fun onPresence(advertRssi: Int, moving: Boolean): PresenceAction = when {
        !tooWeak(advertRssi) -> PresenceAction.CONNECT
        moving -> PresenceAction.FOREGROUND_SCAN
        else -> PresenceAction.IGNORE
    }
}
