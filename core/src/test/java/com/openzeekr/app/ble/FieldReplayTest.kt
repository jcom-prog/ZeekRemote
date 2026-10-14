package com.openzeekr.app.ble

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Field replay, step 2 (0.3.2, local only - see tools/field-replay/README.md): feeds the owner's
 * recorded locked-car approaches through the real unlock policy in every walking-approach mode and
 * writes when each mode would have opened. Skipped unless REPLAY_IN and REPLAY_OUT are set; the
 * field data never enters the repository.
 */
class FieldReplayTest {
    @Test fun replayRecordedApproaches() {
        val input = System.getenv("REPLAY_IN"); val output = System.getenv("REPLAY_OUT")
        assumeTrue("set REPLAY_IN and REPLAY_OUT to replay local field episodes", input != null && output != null)
        val byEpisode = File(input!!).readLines().filter { it.isNotBlank() }.map { it.split(",") }.groupBy { it[0].toInt() }
        File(output!!).printWriter().use { w ->
            w.println("episode,mode,t,route,rssi")
            for ((id, events) in byEpisode) for (mode in ProximityDecisionPolicy.ApproachConfirm.values()) {
                val policy = ProximityDecisionPolicy(mode)
                var hit: String? = null
                for (e in events) {
                    val t = e[1].toLong(); val rssi = e[3].toInt(); val moving = e[4] == "1"; val threshold = e[5].toInt()
                    if (e[2] == "P") policy.onPresenceMatch(t, rssi, moving, threshold)
                    else if (policy.shouldUnlock(t, rssi, moving, threshold)) { hit = "$t,${policy.unlockRoute},$rssi"; break }
                }
                w.println("$id,$mode,${hit ?: ",,"}")
            }
        }
    }
}
