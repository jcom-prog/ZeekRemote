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

The source logs remain external because they total roughly 48 MB and contain unrelated device logcat.
The hashes make the provenance independently checkable when a fixture is reviewed or regenerated.
