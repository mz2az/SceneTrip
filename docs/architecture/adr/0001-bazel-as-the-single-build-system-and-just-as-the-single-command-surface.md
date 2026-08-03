---
number: 0001
title: Bazel as the single build system and just as the single command surface
status: accepted
date: 2026-08-04
supersedes:
superseded-by:
---

# ADR 0001: Bazel as the single build system and just as the single command surface

## Context

SceneTrip is a monorepo holding multiple backend services, multiple frontend
applications, and multiple AI agent modules, alongside all planning, infrastructure, and
operational artifacts. Much of the development will be carried out by AI agents rather
than by a person typing commands.

That combination creates two specific pressures:

1. **Polyglot growth.** Go, TypeScript, and Python modules will coexist. Per-language
   build tooling multiplies: each new module would otherwise bring its own build command,
   its own test runner, its own caching story, and its own CI job.
2. **Machine-operable commands.** An AI agent cannot reliably infer that this service
   uses `make test` while that app uses `pnpm test:ci`. Every variation is a chance to run
   the wrong thing, or to report success from a command that verified nothing.

## Decision

We will use **Bazel as the only build system** and **`just` as the only command surface**.

- Every build, test, run, package, and image step is a Bazel target. No language-native
  build invocation is authoritative.
- Every command a human or agent runs is a `just` recipe. Raw `bazel` invocations do not
  appear in documentation or CI workflows.
- CI workflows are thin wrappers that install `just` and `bazelisk` and call a recipe, so
  `just ci` reproduces the pipeline locally.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Per-language native tooling with a task runner on top | No shared cache, no cross-language dependency graph, no way to compute what a change actually affects. Cost grows with every language added. |
| Nx or Turborepo | Strong for JS/TS monorepos, weak for Go and Python in the same tree. We expect all three from the start. |
| Make instead of just | Make's target model fights non-file tasks, and its variable and shell semantics are a persistent source of subtle bugs. `just` has argument passing, recipe grouping, and `--list` discovery — which is what makes the surface self-describing to an agent. |
| Bazel with no command wrapper | Bazel's flag surface is large and easy to get subtly wrong. Wrapping it means the correct flags for a lane are defined once and used identically by every developer and by CI. |

## Consequences

**Positive**

- One dependency graph across all languages; correct incremental builds and remote
  caching become available to every module at once.
- `just --list` is a self-describing command inventory — an agent discovers what it can do
  without reading build files.
- Local and CI runs are identical by construction, so "green locally, red in CI" becomes a
  bug in a recipe rather than a fact of life.
- Test lanes are defined by Bazel tags, so a mis-tagged test is a reviewable defect rather
  than an invisible one.

**Negative / accepted costs**

- Bazel has a real learning curve, and adding a new language means writing rules rather
  than running an installer.
- Tooling that assumes native layouts (some IDE integrations, some third-party CLIs) needs
  adapter work.
- Contributors must add a `just` recipe rather than running a tool ad hoc — deliberate
  friction, and the point of the decision.

**Follow-up work**

- Enable a shared remote cache; the config block is stubbed in `.bazelrc`.
- Add language rule sets to `MODULE.bazel` as the first module of each language lands.
- Replace the placeholder scripts in `tools/scripts/` (those printing `pending:`) as the
  corresponding tooling is chosen.

## Verification

This decision is working if a new module can be built and tested by someone who knows only
`just build` and `just test`; if CI and local runs disagree only when a recipe is wrong;
and if adding the third language does not require a second build system.

We revisit if Bazel's cost of onboarding a new language exceeds the cost of running a
separate build system for it — measured concretely, not felt.
