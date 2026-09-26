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

        // Prefer a result that already carries broadcastRnd over a slightly stronger UUID-only
        // primary packet. The old strongest-only choice could discard the one split advert that
        // makes an immediate direct connection possible. Identity is still proven by the DK
        // handshake; the random is never combined across MAC addresses.
        val candidates = results.map { result ->
            val rec = result.scanRecord
            val hasRnd = DkAdvertParser.parseBroadcastRnd(
                rawRecord = rec?.bytes,
                manufacturerData = rec?.manufacturerSpecificData?.get(DK_MFR_COMPANY_ID),
            ) != null
            PresenceAdvertSelector.Candidate(result, result.rssi, hasRnd)
        }
        val best = PresenceAdvertSelector.select(candidates)
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
                // Preserve the complete ScanResult. The BluetoothDevice inside it retains the
                // RANDOM/RPA address type and the record may already contain the broadcastRnd.
                // Passing only the MAC forced the service to throw this useful result away and
                // start a second 20 s scan (captured by the 0.1.20 deep-sleep trace).
                ProximityService.notifyPresent(context, best)
            }
        }
    }

    companion object {
        const val ACTION_SCAN_RESULT = "com.openzeekr.app.ble.PRESENCE_SCAN_RESULT"
        private const val DK_MFR_COMPANY_ID = 0x06FE
    }
}
