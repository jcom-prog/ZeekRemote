# Fresh RSSI engineering checkpoint (0.1.51-work, code 89)

This is a partial correctness repair, not a completed automatic-lock solution. GPS departure authorization remains mandatory. No practice logs, derived traces or original manufacturer APK contents are included.

## Changes

Each RSSI poll waits for its own Bluetooth callback under a coroutine mutex. A request is registered before invoking Android, so an immediate callback cannot be lost. Failed callbacks or rejected requests yield no measurement. Callbacks from previous GATT instances cannot complete the current request. Timeouts and cancellations leave a pending request to drain; a late reply cannot be rebound to another read on the same connection. Disconnect invalidates pending readers. A received sample carries monotonic time and is rejected after one second or if its time is in the future. Advertising RSSI remains separate approach evidence and no longer seeds a GATT measurement.

Proximity departure confirmation rechecks motion and session state after awaiting a read. Poll scheduling accounts for the time spent awaiting callbacks rather than always adding that time to the existing interval. Remote parking awaits RSSI in its separate RSSI-stream coroutine; its movement command heartbeat is separate. The earlier sampling-continuity repair invalidates departure candidates after observation gaps, duplicates or backward clocks.

## Validation perspectives

1. Data quality: independent completed reads, equal values from separate valid reads, failed reads, expired/future samples.
2. Asynchronous lifecycle: immediate callbacks, old connections, rejection, timeout, cancellation, disconnect, reentrant waiters.
3. Safety composition: four fresh receding observations qualify the existing confirmation model; an old weak response cannot hide fresh near recovery; unanswered reads cannot fill the confirmation window. Existing core regression tests remain enabled.

214 tests passed in each debug/release unit-test variant. The complete local debug app build succeeded, and the final 0.1.51-work/code-89 checkpoint was rebuilt successfully after metadata changes. The unchanged unit-test sources/results were up to date for that final metadata-only build. A local debug signer is not the permanent CI signer; no local APK release is authorized.

## Limits and publication boundary

These tests cover source models and compilation, not physical vehicle behavior. Real callback latency, radio contention, approach distance and parking RSSI cadence still need device validation before claiming their field behavior. The 600 ms callback timeout is bounded; slower/unanswered reads return unknown and may cause existing recovery. No permanent scan, sleep bypass, native vehicle-mode activation or weaker GPS safety margin is introduced.

The existing GPS uncertainty gate can still prevent locking near the requested departure distance. This checkpoint must not be presented as fixing automatic walk-away locking or distributed as its practice-test solution. Public push requires explicit user permission. Preserve the 0.1.38 fallback. The next design decision remains how to authorize real departure without treating nearby body shadowing as departure.
