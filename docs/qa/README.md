# Quality documentation

| Document | Purpose |
| --- | --- |
| `test-strategy.md` | what we test at each layer and why |
| `coverage-policy.md` | the coverage bar and how exemptions are granted |
| `test-plans/<feature>.md` | per-feature test plans |
| `flaky-tests.md` | quarantined tests, owners, and deadlines |

## Standing rules

- New and changed code carries ≥80% coverage; critical paths are covered end to end.
- A test lives in the lane its tags declare — see AGENTS.md §4.2.
- A flaky test is a defect. Quarantine it with an owner and a deadline; never delete it
  to make the build green.
- Tests are fixed by fixing the implementation, unless the test itself encodes the wrong
  expectation.

```bash
just test / test-integration / test-e2e
just coverage
just test-flaky <target>
```
