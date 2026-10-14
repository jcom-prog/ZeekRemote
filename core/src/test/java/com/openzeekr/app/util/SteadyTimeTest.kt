package com.openzeekr.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SteadyTimeTest {
    @Test fun intervalsSurviveTheWallClockBeingSet() {
        var wall = 1_790_000_000_000L
        var elapsed = 5_000L
        val t = SteadyTime({ wall }, { elapsed })
        val start = t.wallMs()
        assertEquals(1_790_000_000_000L, start)
        elapsed += 6_000; wall -= 3_600_000      // 6 s pass, the clock is set back one hour
        assertEquals(6_000L, t.wallMs() - start)
        elapsed += 30_000; wall += 7_200_000     // 30 s more, the clock jumps two hours ahead
        assertEquals(36_000L, t.wallMs() - start)
    }
}
