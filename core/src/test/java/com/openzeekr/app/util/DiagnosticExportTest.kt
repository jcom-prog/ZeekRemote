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
