# tests/ — cross-module tests

**Only tests that span two or more deployables belong here.** A test that exercises a
single module lives inside that module, next to the code.

| Directory | Scope | Lane | Command |
| --- | --- | --- | --- |
| `contract/` | producer/consumer agreement with `contracts/` | `just test-contract` | fast |
| `integration/` | several modules against real dependencies | `just test-integration` | medium |
| `e2e/` | full stack through the user-facing surface | `just test-e2e` | slow |
| `load/` | throughput and latency under load | `just test-load` | manual |

## Lane discipline

Bazel tags decide which lane a test runs in — see AGENTS.md §4.2. A mis-tagged test
either slows the fast lane or silently never runs. Tag deliberately:

- `unit` — hermetic, no network, no containers
- `integration` — external processes required
- `e2e` — full stack
- `slow` — over 30 seconds
- `manual` — never selected by a wildcard
