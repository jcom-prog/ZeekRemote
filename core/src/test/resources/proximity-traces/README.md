# Field-incident regression traces (synthetic)

These compact series follow the course of field incidents seen with versions 0.1.24 to 0.1.36: the
order of the phases, the motion state and the direction of the signal. They contain no recorded
values: each series is rebuilt as a curve through a few anchor points of that course, with small
fixed noise in between, and the time axis starts at 0. There are no times of day, dates, places or
references to log files. `FieldTraceRegressionTest` replays them.

| Fixture | Required invariant |
|---|---|
| `0.1.24-wrong-direction.csv` | Departure is never classified as arrival. |
| `0.1.25-arrival-and-walk-away.csv` | An approach unlocks and a later departure locks. |
| `0.1.26-close-stationary.csv` | A stationary arrival at door range reaches the wire decision. |
| `0.1.27-still-dip-then-stronger.csv` | A transient signal dip or STILL state cannot erase a qualified arrival. |
| `0.1.28-arrival-late-10s.csv` | Presence at range must qualify before the user reaches the door. |
| `0.1.28-status133-arrival.csv` | GATT 133 may not lose the arrival or defer the unlock until departure. |
| `0.1.29-post-lock-rebound.csv` | A same-session signal rebound after a confirmed lock never unlocks. |
| `0.1.29-clean-post-lock.csv` | A clean fast departure stays locked without rearming arrival. |
| `0.1.30-pending-unlock-dip.csv` | A short arrival dip cannot cancel an unlock in flight. |
| `0.1.35-manual-lock-wrong-direction.csv` | After a manual Lock, walking the wrong way never unlocks; the return does. |
| `0.1.35-manual-lock-far-sleep-return.csv` | After a FAR/STILL pause the return unlocks before the door. |
| `0.1.36-manual-lock-return-failed.csv` | After sleeping near the car the return unlocks before the door. |
