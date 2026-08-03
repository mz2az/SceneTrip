# agents/ — AI agent modules

One directory per agent runtime: an LLM-driven component with prompts, tools, and an
orchestration graph. Multiple agents are expected.

```
agents/<name>/
├── BUILD.bazel   required
├── README.md     required — capability, tools, models, guardrails
├── CLAUDE.md     module-local rules for AI contributors
├── prompts/      versioned prompt files (never inline strings)
├── src/
├── evals/        evaluation datasets and scenarios
└── tests/
```

## Rules

- **Prompts are files**, versioned and referenced as Bazel `data`.
- **Tool I/O is schema-defined** in `contracts/schemas/` and validated on both sides.
- **Model IDs and parameters are configuration**, never hardcoded in logic.
- **LLM output is untrusted input.** Validate before use. Never `eval` it; never
  interpolate it into a shell command, SQL query, or file path.
- **An agent never touches a database directly** — it calls a service.
- **Evals are tests.** Offline evals are deterministic and gate CI; live-model evals are
  tagged `requires-network` and run deliberately.

## Commands

```bash
just new-agent <name> [lang]
just agent-run  <name> -- <args>
just agent-eval <name>            # offline, deterministic
just agent-eval-live <name>       # live model, costs money, [confirm]-gated
just agent-lint-prompts
```
