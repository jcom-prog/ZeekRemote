# Field replay (local only)

Replays the owner's own recorded walks to the car through the real unlock policy
(`ProximityDecisionPolicy`), for every walking-approach confirmation mode, before a rule change is
built. Added in 0.3.2 after a late opening on 14/10 that the simulator did not reproduce.

**Privacy:** field logs and every file derived from them (episodes, results) stay on the local
machine. This directory ignores everything except the scripts; never commit or publish the data.

    python3 -I tools/field-replay/extract_episodes.py <log files or dirs ...> /tmp/replay/episodes.jsonl
    REPLAY_IN=/tmp/replay/episodes.csv REPLAY_OUT=/tmp/replay/result.csv \
      ./gradlew :core:testDebugUnitTest --rerun --tests "com.openzeekr.app.ble.FieldReplayTest"
    python3 -I tools/field-replay/compare.py /tmp/replay/episodes.jsonl /tmp/replay/result.csv

Input: logcat dumps (`-v threadtime`, also UTF-16 from PowerShell) and "Deel diagnose" exports. An
episode is one locked-car approach on one link (from the first locked reading to the unlock, a link
loss or a 60 s gap); approaches right after a Lock on the same link are skipped (the departure latch
disables the walking route there). The replay starts each episode with a fresh policy and feeds the
offloaded presence hits and the smoothed readings with their motion state and threshold.
