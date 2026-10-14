package com.openzeekr.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportTest {
    private fun l(area: String, msg: String) = "16:51:30.260 D/$area  $msg"

    @Test fun keepsDecisionLinesAndDropsEverythingIdentifying() {
        val out = DiagnosticExport.filterAppLog(listOf(
            l("prox", "car locked itself (key status central=3) -> treated as Lock"),
            l("prox", "lock confirmed (car self-lock) -> departure latched"),
            l("svc", "security sleep: stationary for 2 min — BLE session/presence disabled"),
            l("dk", "vehicle status observed session=1 approach=1 walkAway=1 pe=1 ps=1 central=3"),
            l("dk", "handshake diag: vin=LTESTVIN000000001 dkId=8000aaaa phoneId=0011223344556677"),
            l("dk", "handshake 0/5 connect-confirm (0x0101) rnd=1e81bee26d04f4b1 …"),
            l("dk", "our leaf cert: subj='DC=Connected Car'"),
            l("ble", "<- frame cmd=0x121 VSTATUS_SYNC body=38B hex=1a74e2ee32a166570de8"),
            l("ble", "presence MATCH mac=60:27:1B:42:45:C6 rssi=-78 — waking to connect"),
            l("login", "step 5/6 bearer_login loginDeviceId=abc"),
            l("status", "keys=basicVehicleStatus{position{latitude}}"),
            l("prox", "departure anchor at 51.412345,5.401234"),
        ))
        assertEquals(listOf(
            l("prox", "car locked itself (key status central=3) -> treated as Lock"),
            l("prox", "lock confirmed (car self-lock) -> departure latched"),
            l("svc", "security sleep: stationary for 2 min — BLE session/presence disabled"),
            l("dk", "vehicle status observed session=1 approach=1 walkAway=1 pe=1 ps=1 central=3"),
            l("ble", "presence MATCH mac=XX:XX:XX:XX:XX:XX rssi=-78 — waking to connect"),
            l("prox", "departure anchor at <num>,<num>"),
        ), out)
    }

    @Test fun ordinaryDecisionLinesSurviveTheIdentifierFilter() {
        // Plan H10 (0.3.0): "vin" and "cert" as bare substrings dropped these whole.
        val decision = listOf(
            l("prox", "rssi=-91 ema=-90 ~17.4m trend=-0.5 zone=FAR armed=true motion=MOVING next=200ms"),
            l("motion", "-> MOVING"),
            l("svc", "status 133: hardware presence re-armed; preserving arrival epoch"),
            l("prox", "departure position unverified; NO LOCK SENT; manual Lock required"),
            l("prox", "pending departure corroborated by position; sending Lock"),
            l("prox", "lock state uncertain after reconnect -> cloud check"),
            l("svc", "driving: IN_VEHICLE -> keep-alive paused"),
        )
        assertEquals(decision, DiagnosticExport.filterAppLog(decision))
        // ...while identifiers in any spelling still drop the line.
        val ids = listOf(
            l("prox", "vin=LTESTVIN000000001"), l("prox", "carVin=X"), l("prox", "vinCode=X"),
            l("prox", "VIN LTESTVIN000000001"), l("prox", "cert chain ok"), l("prox", "certificate expired"),
            l("prox", "broadcastRnd=1e81"), l("prox", "rnd=1e81"), l("prox", "macAddress=60:27"),
            l("prox", "token refresh failed"),
        )
        assertEquals(emptyList<String>(), DiagnosticExport.filterAppLog(ids))
        // Coordinates with four decimals (~10 m) are masked too.
        assertEquals(listOf(l("prox", "anchor <num>,<num>")),
            DiagnosticExport.filterAppLog(listOf(l("prox", "anchor 51.4123,5.4012"))))
    }

    @Test fun reportHasHeaderJournalAndFilteredLog() {
        val text = DiagnosticExport.build("0.2.12-work", "2026-10-10 20:30",
            "2026-10-10 16:44:02.713 UNLOCK_STARTED approach -82\n",
            listOf(l("prox", "link restored; proximity monitoring resumed"), l("http", "GET /x token=abc")))
        assertTrue(text.contains("== Dagboek ==\n2026-10-10 16:44:02.713 UNLOCK_STARTED approach -82"))
        assertTrue(text.contains("App-log (gefilterd, 1 regels)"))
        assertFalse(text.contains("token=abc"))
    }

    @Test fun readsTheAppsOwnLogcatAndDropsOtherLines() {
        val out = DiagnosticExport.fromLogcat(listOf(
            "10-10 16:47:04.143 28667 28667 D openzeekr: [svc] security sleep: stationary for 2 min",
            "10-10 16:47:04.778  1318  1585 I power_partial_wake_state: [DIS,1,openzeekr:dk-keepalive]",
            "10-10 16:47:04.776 28667 28667 W openzeekr: [prox] link down (lastRssi=-49)",
        ))
        assertEquals(listOf("16:47:04.143 D/svc  security sleep: stationary for 2 min",
            "16:47:04.776 W/prox  link down (lastRssi=-49)"), out)
    }
}
