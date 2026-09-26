# Physical-car regression traces

These compact traces are test fixtures derived from the user's complete field logs. They retain the
observed ordering, RSSI direction and motion state needed by `FieldTraceRegressionTest`; they are not
synthetic tuning curves.

| Fixture | Source log SHA-256 | Required invariant |
|---|---|---|
| `0.1.24-wrong-direction.csv` | `a92162254c9b305f43585143bb647a0ec71f47beb72a70328b1efcb953398c44` | Departure is never classified as arrival. |
| `0.1.25-arrival-and-walk-away.csv` | `0f09b860ca905bfafa9956115055673db8d859debe78cd1a0f7dc922fc75f685` | Real approach unlocks and later departure locks. |
| `0.1.26-close-stationary.csv` | `90e2512867e395ee7b7a711ba029b5f21ef6036ac5d8caa6ac1f9cd94a711839` | Door-range stationary arrival reaches the wire decision. |
| `0.1.27-still-dip-then-stronger.csv` | `873cd9ebc83d6646600c91f9d6676a86aa18dc6ac307fe144d110943928487ff` | A transient RSSI dip/STILL state cannot erase qualified arrival. |
| `0.1.28-arrival-late-10s.csv` | `7daa2baea64eb8c5ebc63123ca1d71cc42272ef1d5f7e68f1c446bd651ed73ac` | Presence at range must qualify before the user reaches the door. |
| `0.1.28-status133-arrival.csv` | `cfc70a4a26248462a0ad3a7df8c317f092b32d6bf1dd1c02d1b18217c72b07dc` | GATT 133 may not lose arrival context or defer unlock until departure. |
| `0.1.29-post-lock-rebound.csv` | `6b3c0c03c3f4171ccc8dc486c55d3264d94d64fa6540f149b3f6aa0168369e58` | A same-session RSSI rebound after confirmed lock must never emit unlock. |
| `0.1.29-clean-post-lock.csv` | `aab6f2771ab8478607aafe73486391b664a0961d0adfefa930352b5fb608f9c8` | A clean fast departure remains locked without rearming arrival. |

The source logs remain external because they total roughly 48 MB and contain unrelated device logcat.
The hashes make the provenance independently checkable when a fixture is reviewed or regenerated.
