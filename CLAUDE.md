# CLAUDE.md — SceneTrip Operating Procedure

> **[`AGENTS.md`](./AGENTS.md) is the contract. This file is the procedure.**
> AGENTS.md defines *what the rules are* (structure, Bazel, just, quality bars).
> CLAUDE.md defines *how you operate* inside those rules. Read AGENTS.md once per session
> before your first edit; keep this file as the working checklist.

---

## 0. The two laws

1. **Bazel builds everything.** Never invoke a language-native build/test tool as the source of
   truth. If Bazel cannot build it, it is not done.
2. **`just` runs everything.** Never emit a bare `bazel …` command in a doc, script, CI file, or
   your own message to the user. Wrap it in a recipe, then reference the recipe.

If a task seems to require breaking either law, stop and say so instead of working around it.

---

## 1. Session start protocol

Do this before the first edit of a session — it is cheap and prevents most wasted work.

```
1. Read AGENTS.md §2 (repo map) and §4 (Bazel conventions).
2. `just --list`                      → learn the current command surface
3. Read the README.md of every module you will touch.
4. Read the module's CLAUDE.md if one exists — it overrides this file for that module.
5. For a non-trivial change, read the relevant docs/architecture/ and docs/product/ page.
```

**Precedence when instructions conflict:**

```
user's explicit instruction  >  <module>/CLAUDE.md  >  CLAUDE.md  >  AGENTS.md  >  your defaults
```

---

## 2. Command quick reference

`just --list` prints the current surface with a description per recipe. Read it at session
start (§1) instead of trusting a copy here — a copy goes stale silently and sends you to a
recipe that no longer exists.

**If the command you need does not exist, add the recipe** to the right file in `tools/just/`
rather than running the underlying tool ad hoc. Adding the recipe *is* part of the task.

---

## 3. Task loop

Follow this loop for every code change. Steps are ordered by cost — do the cheap ones first.

```
LOCATE   → Use the placement table (AGENTS.md §2) to decide where code goes.
           Never create a new top-level directory.

CONTRACT → Does this change a wire format (API, event, proto, tool schema)?
           YES → edit contracts/ first, `just gen`, then implement against the stubs.
           NO  → continue.

TEST     → Write the failing test in the correct lane (unit / integration / e2e).
           `just test <target>` — confirm it fails for the intended reason.

BUILD    → Implement. Update BUILD.bazel in the SAME edit that adds the source file.
           A source file without a Bazel target is an incomplete change.

VERIFY   → `just check`. Do not report success on a red gate.
           If `just gen` produces a diff, commit it.

DOCUMENT → Update the module README and any affected docs/ page.
           Architectural decision? Add an ADR in docs/architecture/adr/.
```

### When to plan first

Write a plan before coding when **any** of these is true:

- the change touches more than one module
- the change alters a contract in `contracts/`
- the change adds a dependency to `MODULE.bazel`
- the change is expected to exceed ~100 lines

Plan artifacts: feature plans → `docs/project/`; decisions with lasting consequences →
`docs/architecture/adr/`. A plan is a document in the repo, not a message that scrolls away.

---

## 4. Bazel working rules

These are the mistakes that actually happen. Check yourself against them.

| Situation | Correct action |
| --- | --- |
| Added a source file | Add it to `srcs` of the owning target in the same edit |
| Added a cross-module import | Add the `deps` entry; if the dep is not in `libs/` or `contracts/`, the import is illegal |
| Added an external dependency | Edit `MODULE.bazel` only, then `just deps-update`, then commit the `MODULE.bazel.lock` diff |
| Added a BUILD target | Write it by hand — no generator is wired up yet (see AGENTS.md §4.5) |
| Test needs a database/container | Tag it `integration`, put it in the integration lane, never in `:unit_test` |
| Test needs the network | Tag `requires-network` — and first ask whether it can be faked instead |
| Build fails with a missing file | Check `srcs`/`data` before touching the code — it is usually a BUILD file gap |
| Target label is unknown | Derive it from the naming convention (AGENTS.md §4.1) before grepping BUILD files |
| Need a repeated build pattern | Write a macro in `tools/bazel/defs/`, do not copy-paste rules |

Hermeticity, restated because it is violated silently: **no host toolchains, no network at build
time, no absolute paths, no non-deterministic output.**

---

## 5. Multi-module awareness

This repo holds many Spring backends, two native mobile apps, and many Python agents. Before adding
code, ask which category the work belongs to:

| Category | Directory | Language | Owns | Talks to others via |
| --- | --- | --- | --- | --- |
| Backend service | `services/<name>/` | Java (Spring Boot) | its data, its API surface | `contracts/proto`, `contracts/openapi` |
| iOS app | `apps/<name>/` | Swift | its UI and view state | generated API clients from `contracts/` |
| Android app | `apps/<name>/` | Kotlin | its UI and view state | generated API clients from `contracts/` |
| AI agent | `agents/<name>/` | Python | its prompts, tools, orchestration | `contracts/schemas` for tool I/O; services for data |
| Shared library | `libs/{java,python,swift,kotlin}/<name>/` | — | reusable logic only | direct Bazel `deps` |

Rules that keep the graph clean:

- **Duplication across two modules is a signal, not a solution** — promote it to `libs/`.
- **An app never hand-writes an API client** — it consumes generated clients.
- **iOS and Android share no code.** They are separate native modules; what they share is the
  contract, enforced by `tests/contract/`. Do not invent a cross-platform layer.
- **An agent never calls a database directly** — it goes through a service.
- **A service never imports another service's internals** — it calls its contract.

---

## 6. AI agent modules (`agents/`)

Agent code is application code and gets the same rigor, plus:

- **Prompts are versioned files**, not inline string literals. Store them under the module and
  reference them as Bazel `data`.
- **Tool I/O is schema-defined** in `contracts/schemas/` and validated at runtime on both sides.
- **Model IDs and parameters are configuration**, never hardcoded in logic.
- **Evaluations are tests.** An agent change ships with an eval; results go to `docs/ai/`.
- **LLM output is untrusted input.** Validate before use; never `eval`, never interpolate into a
  shell command, SQL query, or filesystem path.
- **Deterministic tests must not call a live model.** Record fixtures or fake the client; live-model
  tests are tagged `integration` + `requires-network`.

---

## 7. Subagent delegation

Delegate when it saves context, not by reflex.

| Delegate to a subagent | Do it inline |
| --- | --- |
| Broad search across many modules ("where is X handled?") | You already know the file |
| Independent parallel work (review 3 modules at once) | Sequential, dependent edits |
| Reading a large surface to answer one question | A single-fact lookup |
| Language-specific review after a change | Trivial mechanical edits |

Rules: run independent subagents in **one message** so they execute concurrently; never fabricate
a pending subagent's result; relay only the conclusion, not the file dumps.

---

## 8. Verification and honesty

- `just check` is the gate. **Run it before reporting completion**, not after being asked.
- If tests fail, say so and paste the relevant output. A failing gate reported as success is the
  worst possible outcome.
- If you skipped part of the scope, state exactly what and why.
- Do not claim a build passes because it "should" — run it.
- If a command is unavailable in the environment, say that plainly instead of substituting a
  weaker check silently.

---

## 9. Guardrails

Stop and ask before:

- deleting or overwriting files you have not read
- running anything that mutates cloud/shared state (`terraform apply`, image push, shared-env
  migration)
- force-pushing, rewriting history, or deleting branches
- adding a heavyweight dependency to `MODULE.bazel`
- introducing a new language or toolchain to the repo
- creating a new top-level directory

Never, under any circumstance:

- commit secrets, tokens, keys, or real credentials — including in tests and docs
- commit `bazel-*` symlinks, build outputs, or `.env` files
- weaken or delete a failing test to make the gate green
- disable a lint rule repo-wide to fix one call site

---

## 10. Writing documentation

You will produce a lot of docs in this repo. Keep them useful:

- One home per doc — use the table in AGENTS.md §8. No orphan Markdown at the repo root.
- Every directory keeps a `README.md` index that reflects its actual contents.
- ADRs are append-only: supersede, never rewrite.
- Diagrams as Mermaid in Markdown so they diff.
- State facts, not intentions. "The service exposes port 8080" — not "the service will expose".
- When code and docs disagree, fix both in the same commit.

---

## 11. Response style with the user

- Lead with what changed and whether the gate passed.
- Reference code as `path/to/file.ext:42` — it is clickable.
- Show commands as `just` recipes, never raw `bazel`.
- Be concise; skip the option survey and state the recommendation.
- Flag risk in one or two sentences, then continue the work — do not stall on it.
