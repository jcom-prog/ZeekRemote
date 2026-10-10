package com.openzeekr.app.net

import com.openzeekr.app.net.model.ElectricStatusVo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeKindTest {
    @Test fun twentyTwoKwPostIsThreePhase() {
        // Field 10/10, stock app: 21.3 kW · 31.5 A · 390.5 V at a 22 kW post (was labelled "1-phase").
        val e = ElectricStatusVo(chargerState = "2", chargeUAct = "390.5", chargeIAct = "31.5")
        assertEquals(21_305.0, e.chargePowerW!!, 5.0)
        assertEquals("3-fase", e.chargeKindLabel)
    }

    @Test fun highPowerAtPhaseVoltageIsThreePhase() {
        val e = ElectricStatusVo(chargerState = "2", chargeUAct = "230", chargeIAct = "40")
        assertEquals("3-fase", e.chargeKindLabel) // 9.2 kW cannot be one phase
    }

    @Test fun lowPowerAtPhaseVoltageNamesNoPhase() {
        assertNull(ElectricStatusVo(chargerState = "2", chargeUAct = "230", chargeIAct = "16").chargeKindLabel)
    }

    @Test fun dcSessionIsDc() {
        val e = ElectricStatusVo(chargerState = "2", dcChargePileUAct = "400", dcChargePileIAct = "120")
        assertEquals("DC", e.chargeKindLabel)
    }
}
