# AGENTS.md — SceneTrip Monorepo Contract

> **Canonical source of truth** for how any agent (human or AI) works in this repository.
> Tool-specific operating procedure for Claude Code lives in [`CLAUDE.md`](./CLAUDE.md).
> Read this file **before** the first edit in a session.

---

## 1. What this repository is

SceneTrip is a **single monorepo holding the entire product lifecycle**: planning, design,
implementation, tests, infrastructure, CI/CD, and operational runbooks. Nothing about
SceneTrip lives outside this repo.

Two invariants govern everything:

| Invariant | Rule |
| --- | --- |
| **Bazel is the only build system** | Every build, test, run, package, and image step is a Bazel target. No language-native build invocation (`./gradlew build`, `xcodebuild`, `pytest`) is used as the source of truth. |
| **`just` is the only entry point** | Every command a human or agent runs is a `just` recipe. Raw `bazel …` lines never appear in docs, CI workflows, or scripts. If a command is worth running twice, it becomes a recipe. |

Consequence: **CI and local development execute identical commands.** A green `just ci` locally
means a green pipeline.

---

## 2. Repository map

```
SceneTrip/
├── AGENTS.md              # this file — the contract
├── CLAUDE.md              # Claude Code operating procedure
├── justfile               # command surface (imports tools/just/*.just)
├── MODULE.bazel           # bzlmod dependency graph
├── .bazelrc               # build flags + named configs
│
├── apps/                  # FRONTENDS (multiple). One directory per native iOS/Android app.
├── services/              # BACKENDS (multiple). One directory per deployable Spring Boot server.
├── agents/                # AI AGENTS (multiple). One directory per agent runtime.
├── libs/                  # shared libraries, grouped by language
│   ├── java/  kotlin/  swift/  python/  proto/
│
├── contracts/             # INTERFACE SOURCE OF TRUTH — hand-written, never generated
│   ├── proto/             # gRPC / protobuf service + message definitions
│   ├── openapi/           # REST specs
│   ├── asyncapi/          # event/stream specs
│   └── schemas/           # JSON Schema, Avro, agent tool schemas
│
├── platform/              # infrastructure as code
│   ├── terraform/  kubernetes/  helm/  environments/  docker/
│
├── tests/                 # CROSS-MODULE tests only (module-local tests live with the module)
│   ├── e2e/  integration/  contract/  load/
│
├── tools/                 # build & developer tooling
│   ├── bazel/             # custom macros, rules, toolchains
│   ├── just/              # justfile modules imported by the root justfile
│   ├── scripts/           # scripts invoked *by* just recipes
│   ├── templates/         # scaffolding templates for new modules
│   └── ci/                # CI helper logic
│
├── docs/                  # ALL documentation (see §8)
├── third_party/           # vendored code with provenance notes
└── .github/workflows/     # CI/CD — thin wrappers that call `just`
```

### Placement decision table

Before creating any file, resolve its home with this table. Do not invent new top-level directories.

| The thing you are creating | Goes in |
| --- | --- |
| A deployable Spring Boot HTTP/gRPC server | `services/<name>/` |
| A deployable native iOS or Android app | `apps/<name>/` |
| An LLM agent runtime, tool, or orchestration graph | `agents/<name>/` |
| Code imported by ≥2 modules | `libs/<lang>/<name>/` |
| A protobuf/OpenAPI/JSON-Schema definition | `contracts/<kind>/` |
| Generated client/server stubs | **nowhere** — generated at build time by Bazel |
| Terraform, Helm chart, K8s manifest | `platform/<kind>/` |
| A unit test | next to the code it tests, inside the module |
| A test spanning ≥2 deployables | `tests/<kind>/` |
| A Bazel macro used by ≥2 modules | `tools/bazel/defs/` |
| A new command | a recipe in `tools/just/<area>.just` |
| Any prose, spec, diagram, or decision | `docs/<area>/` (see §8) |

---

## 3. Module anatomy

Every module in `apps/`, `services/`, and `agents/` is self-contained and follows the same shape:

```
services/scene-api/
├── BUILD.bazel        # REQUIRED — declares all targets
├── README.md          # REQUIRED — purpose, ports, deps, runbook link
├── CLAUDE.md          # OPTIONAL — module-local rules that override the root
├── src/               # implementation
├── tests/             # module-local unit + integration tests
└── deploy/            # module-owned deploy config (see below)
```

`deploy/` means different things by module kind:

- `services/<name>/deploy/` — k8s/helm overlays (env-specific values live in `platform/`).
- `apps/<name>/deploy/` — store-submission config: fastlane lanes, signing/provisioning
  references, `Info.plist`/`AndroidManifest.xml` build variants. Never a k8s manifest — mobile
  apps ship to the App Store / Play Store, not a cluster.

Rules:

1. **A module never reaches into another module's source tree.** Cross-module code goes through
   `libs/` (compile-time) or `contracts/` (runtime/wire).
2. **A module owns its `BUILD.bazel`.** If you add a file, update the target's `srcs` in the same
   change.
3. **A module declares its own dependencies explicitly.** No transitive-dependency squatting.
4. **A module is independently buildable**: `just build //services/scene-api/...` must pass alone.

---

## 4. Bazel conventions

### 4.1 Target naming (mandatory)

Consistent names make targets machine-predictable — an agent can construct a target label without
reading the `BUILD.bazel` first.

| Target | Meaning |
| --- | --- |
| `:<module-name>` | the primary library/binary of the module (matches directory name) |
| `:bin` | the executable entry point, when distinct from `:<module-name>` |
| `:unit_test` | fast, hermetic, no network, no external services |
| `:integration_test` | needs containers/fixtures; tagged `integration` |
| `:image` | OCI container image — `services/` only |
| `:push` | image push (always `tags = ["manual"]`) — `services/` only |
| `:ipa` | signed iOS install artifact — `apps/<name>-ios/` only, always `tags = ["manual"]` |
| `:aab` / `:apk` | Android install artifact (App Bundle / APK) — `apps/<name>-android/` only |
| `:lint` | module-specific lint target |
| `:<name>_proto` / `:<name>_<lang>_proto` | proto library and language bindings |

`apps/` (iOS/Android) modules never define `:image`/`:push` — they are not containerized.

### 4.2 Required tags

Tags are how `just` slices the graph. Apply them or your test will run in the wrong lane.

| Tag | Applied to | Effect |
| --- | --- | --- |
| `unit` | fast hermetic tests | runs in `just test` |
| `integration` | tests needing external processes | excluded from `just test`, runs in `just test-integration` |
| `e2e` | full-stack tests | runs only in `just test-e2e` |
| `slow` | >30s runtime | excluded from the fast lane |
| `manual` | never in `//...` wildcards | deploys, pushes, destructive ops |
| `requires-network` | non-hermetic | excluded from sandboxed/remote execution |

### 4.3 Hermeticity rules

- **No host toolchains.** Compilers, interpreters, and SDKs are declared in `MODULE.bazel` and
  registered in `tools/bazel/toolchains/`. Never depend on what happens to be installed.
  - **Deliberate exception: Xcode.** Apple's license forbids redistributing Xcode, so Bazel
    cannot fetch it — every machine (dev laptop or CI runner) building an `apps/<name>-ios/`
    module must have the exact pinned Xcode version installed locally, selected via
    `xcode_version` in `tools/bazel/toolchains/`. This is the one accepted breach of this rule.
    It also means iOS build/test targets only run on macOS executors — no Linux remote
    execution for them.
- **No network at build time.** All external dependencies are pinned in `MODULE.bazel` /
  lockfiles. If a build needs the network, it is wrong.
- **No absolute paths.** No `/Users/...`, no `$HOME`, no machine-specific paths in any BUILD file
  or script.
- **No timestamps or randomness in outputs.** Builds are reproducible; identical inputs produce
  identical outputs.

### 4.4 Dependency management

- External deps are added **only** to `MODULE.bazel` (bzlmod). Legacy `WORKSPACE` is not used.
- Language dependency manifests (`build.gradle(.kts)`/`maven_install.json` for JVM services and
  Kotlin/Android, `requirements.txt` for Python agents, `Package.swift`/`Package.resolved` for
  Swift iOS libs) exist to feed the Bazel extensions and to keep IDEs working — they are **inputs
  to Bazel**, never a parallel build path.
- After changing any dependency declaration: `just deps-update`, then `just build`. Commit the
  resulting `MODULE.bazel.lock` diff in the same commit.
- Apple rules (`rules_apple`, `rules_swift`, `apple_support`) stay commented out until the first
  iOS module lands. Declaring them on a machine without the full Xcode app crashes the entire
  build, not just the iOS targets.

### 4.5 BUILD files are hand-written

There is no BUILD file generator in this repo. Gazelle is a Go tool and covers none of our four
languages — Java, Kotlin, and Swift are unsupported outright — so it was removed rather than kept
as dead weight that drags in a full Go toolchain.

Write `BUILD.bazel` by hand: when you add a source file, add it to the target's `srcs` in the same
edit. Follow the target naming in §4.1 so labels stay predictable without reading the file first.
`just gen` covers only contract-derived artifacts (proto stubs, API clients, mocks) — never BUILD
files.

---

## 5. The `just` command surface

The root `justfile` imports modules from `tools/just/`. Run `just` with no arguments to list every
recipe grouped by area.

### 5.1 Core recipes (memorize these)

| Recipe | Purpose |
| --- | --- |
| `just setup` | one-time workstation bootstrap; verifies toolchain versions |
| `just build [target]` | build (default `//...`) |
| `just test [target]` | fast unit lane |
| `just test-integration` | integration lane |
| `just test-e2e` | end-to-end lane |
| `just run <target> [args]` | run a binary target |
| `just fmt` | format everything (code, BUILD files, docs) |
| `just lint` | all linters + static analysis |
| `just gen` | regenerate contract-derived code (proto stubs, clients, mocks) |
| `just deps-update` | refresh `MODULE.bazel.lock` after editing `MODULE.bazel` |
| `just check` | **pre-PR gate**: fmt-check + lint + build + test |
| `just ci` | exactly what CI runs |
| `just clean` | drop build outputs |
| `just new-service <name>` | scaffold a Spring Boot backend service (Java) |
| `just new-app-ios <name>` | scaffold a native iOS app (Swift) |
| `just new-app-android <name>` | scaffold a native Android app (Kotlin) |
| `just new-agent <name>` | scaffold an AI agent module (Python) |

### 5.2 Rules for adding commands

1. **Never document a raw `bazel` invocation.** Wrap it in a recipe first, then reference the
   recipe.
2. Put the recipe in the right module file: `tools/just/{bazel,dev,test,docs,infra,ci,agent,scaffold}.just`.
3. Every recipe carries a `[group('…')]` attribute and a `#` doc comment — these render in
   `just --list` and are how agents discover the command surface.
4. Recipes that mutate cloud state or push artifacts must be marked `[confirm]` **and** print the
   target environment before acting.
5. Keep logic out of the justfile. More than ~5 lines of shell → move it to `tools/scripts/` and
   call the script.

---

## 6. Development workflow

```
 1. UNDERSTAND   Read docs/product + docs/architecture for the area. Read the module README.
 2. PLAN         For anything touching >1 module or >~100 lines, write the plan first
                 (docs/project/ for a feature plan, docs/architecture/adr/ for a decision).
 3. CONTRACT     If a wire interface changes, edit contracts/ FIRST, run `just gen`,
                 and let the generated stubs drive the implementation.
 4. TEST FIRST   Write the failing test. Run `just test` — confirm it fails for the right reason.
 5. IMPLEMENT    Minimal code to pass. Update BUILD.bazel in the same edit.
 6. VERIFY       `just check` must pass. Never hand off red.
 7. DOCUMENT     Update the module README, docs/, and any ADR affected by the change.
 8. COMMIT       Branch and commit per the JIRA ticket key, on a branch off `main` (see §7).
```

### Definition of Done

A change is done only when **all** of these hold:

- [ ] `just check` passes locally
- [ ] New/changed behavior is covered by a test in the correct lane
- [ ] `BUILD.bazel` files hand-updated for every added/removed source file
- [ ] Contracts updated before implementation when the wire format changed
- [ ] Module `README.md` reflects reality
- [ ] No secrets, no absolute paths, no debug prints, no `TODO` without a tracking link
- [ ] Docs updated for any behavior a user or operator can observe

---

## 7. Git workflow, commit & PR conventions

### 7.1 Branching model — GitHub Flow

- `main` is always deployable.
- All work happens on a branch cut from `main` — never commit directly to `main`.
- Merges to `main` happen **only** through a reviewed PR.
- A merge to `main` triggers deployment automatically.
  *(Target policy. The pipeline that performs this auto-deploy is not implemented yet — see
  `.github/workflows/`. Treat `main` as if it deploys the moment you merge even before the
  pipeline exists: never merge broken or half-finished work "just for now.")*

### 7.2 Branch naming

Branch name = the JIRA ticket key, optionally followed by a short kebab-case description.

```
MZ2AZ-91-브랜드-네이밍
```

### 7.3 Commit messages

Every commit message includes its JIRA ticket key, so JIRA/GitHub integrations can link the
commit back to the ticket automatically. Keep the key's case and hyphenation exact — the
integration matches on it literally.

```
<type>: <imperative summary> (<JIRA-KEY>)
```

- **type**: `feat` `fix` `docs` `refactor` `test` `chore` — `chore` also covers
  infra/CI/config-only changes (Terraform, Kubernetes manifests, `.env`, workflow files).
- One logical change per commit. Never mix a refactor with a behavior change.

Example:

```
feat: 지도 SDK 적용 (MZ2AZ-10)
```

### 7.4 PR titles

```
[<JIRA-KEY>] <type>: <imperative summary>
```

Example:

```
[MZ2AZ-10] feat: 지도 SDK 적용
```

PR description states: what changed, why, blast radius, how it was verified, rollback plan.

---

## 8. Documentation layout

Documentation is a first-class artifact, not an afterthought. Every doc has exactly one home.

| Directory | Contents |
| --- | --- |
| `docs/product/` | vision, PRDs, requirements, personas, roadmap, feature specs |
| `docs/architecture/` | system design, C4/sequence diagrams, tech-stack rationale, data models |
| `docs/architecture/adr/` | Architecture Decision Records — immutable once accepted |
| `docs/api/` | API usage guides and versioning policy (the *specs* live in `contracts/`) |
| `docs/engineering/` | conventions, Bazel guide, just guide, onboarding, git workflow |
| `docs/installs/` | local environment setup guides (Kubernetes/kind, SigNoz) |
| `docs/education/` | class material and slide decks |
| `docs/qa/` | test strategy, test plans, coverage policy, QA reports |
| `docs/ops/` | runbooks, SLO/SLI, on-call, incident postmortems, deployment procedures |
| `docs/ai/` | agent designs, prompt library, tool schemas, eval methodology + results |
| `docs/project/` | plans, status, decision log, retrospectives, meeting notes |

### Language policy

**Korean is the default language for this repository.** Documentation, `justfile` comments
and recipe descriptions, script messages, and README files are written in Korean, because
that is the language the team works in.

**`AGENTS.md` and `CLAUDE.md` are the sole exception and stay in English.** They are read
directly by AI coding tools as operating instructions, and English keeps them unambiguous
for every model and tool that consumes them. When you edit either file, keep it English.

Code identifiers — target names, recipe names, file paths, variables — are always English
regardless of the surrounding prose.

Rules:

- Filenames are `kebab-case.md`. Prefix with a number only when reading order matters.
- Every directory has a `README.md` acting as its index.
- An ADR is **append-only**: to change a decision, write a new ADR that supersedes the old one and
  update the old one's status. Never rewrite history.
- Diagrams are Mermaid inside Markdown wherever possible, so they are diffable.

---

## 9. Security & safety rules

Non-negotiable:

- **No secrets in the repo.** No API keys, tokens, passwords, certificates, or connection strings —
  including in tests, fixtures, and docs. Use env vars sourced from the secret manager; commit only
  `.env.example` files with placeholder values.
- **Validate at every boundary.** All external input (HTTP, queue, LLM output, file, third-party
  API) is schema-validated before use.
- **Treat LLM output as untrusted input.** Never `eval` it, never pass it unescaped to a shell, a
  SQL query, or a file path.
- **Parameterized queries only.** String-concatenated SQL is a blocking defect.
- **Never commit generated build outputs**, `bazel-*` symlinks, or `.env` files.

Destructive operations — `terraform apply`, image push, DB migration against a shared env, force
push, branch deletion — require an explicit `[confirm]` recipe and are never run implicitly as part
of another task.

---

## 10. Quality bars

| Dimension | Bar |
| --- | --- |
| Test coverage | ≥80% for new/changed code; critical paths covered end-to-end |
| File size | 200–400 lines typical, 800 hard maximum |
| Function size | <50 lines |
| Nesting | ≤4 levels — prefer early returns |
| Error handling | explicit at every level; never silently swallowed |
| Naming | `camelCase` vars/functions, `PascalCase` types/components, `UPPER_SNAKE_CASE` constants, `kebab-case` directories and files (language idiom wins where it conflicts) |
| Mutation | prefer immutable transformations; return new values instead of mutating inputs |
| Magic values | named constants only |

---

## 11. Anti-patterns — do not do these

- Running `./gradlew build` / `xcodebuild` / `pytest` as the authoritative build or test step.
- Adding a command to CI or docs without a corresponding `just` recipe.
- Adding a source file without updating `BUILD.bazel`.
- Hand-editing generated code (proto stubs, API clients) instead of the contract it came from.
- Importing across module boundaries instead of via `libs/` or `contracts/`.
- Creating a new top-level directory instead of using the placement table in §2.
- Writing implementation before the contract when the wire format changes.
- Marking work complete while `just check` is red.
- Copy-pasting a shared utility into a second module instead of promoting it to `libs/`.
- Leaving an empty directory with no `README.md` explaining what belongs in it.
