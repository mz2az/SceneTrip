# AI documentation

Design and evaluation of the modules in [`agents/`](../../agents/README.md).

| Document | Purpose |
| --- | --- |
| `agent-catalog.md` | every agent, its capability, and its guardrails |
| `prompt-library.md` | shared prompt patterns and the rationale behind them |
| `eval-methodology.md` | how agent quality is measured |
| `evals/<agent>-<date>.md` | recorded evaluation results |
| `model-policy.md` | approved models, cost limits, fallback behavior |
| `safety.md` | injection defenses, output validation, escalation paths |

## Standing rules

- **Prompts are versioned files**, not string literals in code.
- **Every prompt or model change ships with an eval run**, and the result is recorded here.
- **Model output is untrusted input**: validated against a schema in `contracts/schemas/`
  before use, never `eval`'d, never interpolated into a shell command, SQL query, or path.
- **Offline evals gate CI**; live-model evals cost money and run deliberately.
- **Cost and latency are quality metrics**, tracked alongside accuracy.

```bash
just agent-eval <name>
just agent-eval-diff <name> <baseline>
just agent-lint-prompts
```
