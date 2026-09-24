package com.openzeekr.app.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openzeekr.app.util.Logx

/**
 * Receives the HARDWARE-OFFLOADED presence scan (see [DkBleManager.armPresenceScan]). The Bluetooth
 * controller fires this PendingIntent — with the CPU otherwise asleep — when the car's advert
 * delivers a matching car advertisement (ALL_MATCHES; unlike FIRST_MATCH it cannot remain latched
 * across a long parked sleep). We hand the signal to [ProximityService],
 * which acquires a short wakelock and connects (approach) or locks + re-idles (walk-away).
 *
 * This is the zero-CPU replacement for the always-on wakelock + continuous foreground scan: idle
 * costs nothing, we only spend power once the car is actually nearby.
 */
class BleScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCAN_RESULT) return
        val callbackType = intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, 0)
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (errorCode != -1) {
            Logx.e("ble", "presence scan error via PendingIntent: $errorCode (controller dropped the offload)")
            // The offloaded scan is gone; let the service notice (state stays IDLE) and re-arm.
            ProximityService.notifyPresenceLost(context)
            return
        }

        @Suppress("UNCHECKED_CAST")
        val results: List<ScanResult> =
            (intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
                ?: emptyList())

        // Pick the strongest advertiser (only our car should realistically match the 0xFDFD/0x06FE
        // filter near us; identity is verified by the DK handshake regardless).
        val best = results.maxByOrNull { it.rssi }
        val mac = best?.device?.address
        val rssi = best?.rssi

        when (callbackType) {
            ScanSettings.CALLBACK_TYPE_MATCH_LOST -> {
                Logx.d("ble", "presence MATCH_LOST${if (mac != null) " $mac" else ""} — car out of range")
                ProximityService.notifyPresenceLost(context)
            }
            else -> {
                // ALL_MATCHES: the next matching car advert wakes the app; service disarms immediately.
                Logx.d("ble", "presence MATCH mac=${mac ?: "?"} rssi=${rssi ?: "?"} — waking to connect")
                ProximityService.notifyPresent(context, mac)
            }
        }
    }

    companion object {
        const val ACTION_SCAN_RESULT = "com.openzeekr.app.ble.PRESENCE_SCAN_RESULT"
    }
}
