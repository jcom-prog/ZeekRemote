package com.openzeekr.app.ble.sim

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SimBaselineTest {
    @Test fun theWalkBackEntriesReadBackNextToTheSuites() {
        // 0.3.13: "return-f45" holds a digit; the suites around it must still read as before.
        val f = File.createTempFile("sim-baseline", ".json").apply { deleteOnExit() }
        f.writeText("{\n  \"seeds\": 5,\n  \"calibrated\": {\"runs\": 1980, \"APPROACH_UNLOCK_LATE\": 441},\n" +
            "  \"return-f45\": {\"runs\": 380, \"medianCm\": 180, \"meanCm\": 240, \"notBeforeDoor\": 70}\n}\n")
        val b = SimBaseline.read(f)
        assertEquals(5, SimBaseline.seeds(f))
        assertEquals(441, b.getValue("calibrated").getValue("APPROACH_UNLOCK_LATE"))
        assertEquals(mapOf("runs" to 380, "medianCm" to 180, "meanCm" to 240, "notBeforeDoor" to 70), b.getValue("return-f45"))
    }
}
