# SceneTrip

A monorepo holding the entire SceneTrip product lifecycle — planning, design, implementation,
tests, infrastructure, CI/CD, and operations. Multiple backend services, multiple frontend apps,
and multiple AI agent modules live side by side.

## Two rules that shape everything

1. **Bazel builds and tests everything.** No language-native build command is authoritative.
2. **`just` is the only entry point.** Every command is a recipe; raw `bazel` calls never appear
   in docs, scripts, or CI.

## Start here

```bash
just setup      # one-time workstation bootstrap
just --list     # every available command, grouped
just build      # build the workspace
just test       # fast unit lane
just check      # pre-PR gate — must be green before you commit
```

## Where things live

| Directory | Contents |
| --- | --- |
| `services/` | backend servers (one directory per deployable) |
| `apps/` | frontend applications |
| `agents/` | AI agent modules |
| `libs/` | shared libraries by language |
| `contracts/` | proto / OpenAPI / AsyncAPI / JSON Schema — the interface source of truth |
| `platform/` | Terraform, Kubernetes, Helm, environments |
| `tests/` | cross-module e2e, integration, contract, and load tests |
| `tools/` | Bazel macros, justfile modules, scripts, templates |
| `docs/` | all product, architecture, engineering, QA, ops, and AI documentation |

Full placement rules: [AGENTS.md §2](./AGENTS.md#2-repository-map).

## Setup guides

Start here on a new machine. Read them in order — the SigNoz guide assumes the
Kubernetes setup is done.

| Guide | Covers |
| --- | --- |
| **[docs/installs/k8s_install.md](./docs/installs/k8s_install.md)** | Homebrew, Git, Docker Desktop, **kind**, kubectl, Helm, k9s, AWS CLI, cluster smoke test, troubleshooting, checklist |
| **[docs/installs/signoz_install.md](./docs/installs/signoz_install.md)** | SigNoz install via foundryctl, UI access, health checks, connecting apps over OpenTelemetry, log search |
| [docs/installs/](./docs/installs/README.md) | index and the local-environment diagram |

A guided version of the same material — 35 slides, offline, ~3 hours with hands-on:

| Material | Open with |
| --- | --- |
| **[docs/education/k8s-observability-class.html](./docs/education/k8s-observability-class.html)** | `just slides` |
| [docs/education/](./docs/education/README.md) | what the class covers, and how to keep it accurate |

## Local environment

```bash
just cluster-up          # create the kind cluster and install SigNoz (idempotent, 3-4 min)
just cluster-doctor      # tools, cluster, SigNoz, workloads — one screen
just cluster-test-drive  # prove the cluster works end to end
just signoz              # SigNoz UI address and filter hint
just cluster-down        # delete everything (confirm-gated)
```

| Address | Serves |
| --- | --- |
| `http://localhost:8080` | SigNoz UI |
| `http://localhost:8081` | application API |

No `port-forward` needed — the host ports are mapped by
[`platform/kind/cluster.yaml`](./platform/kind/README.md) when the cluster is created.

> Only one kind cluster at a time per machine: ports 8080 and 8081 are bound by the node
> container.

## Documentation

| Read this | For |
| --- | --- |
| [AGENTS.md](./AGENTS.md) | the repository contract — structure, Bazel, just, quality bars |
| [CLAUDE.md](./CLAUDE.md) | how AI agents operate in this repo |
| [docs/](./docs/README.md) | full documentation index |
| [docs/engineering/](./docs/engineering/README.md) | onboarding, Bazel guide, just guide, conventions |
| [docs/installs/](./docs/installs/README.md) | local environment setup |
| [docs/education/](./docs/education/README.md) | class material |
| [platform/](./platform/README.md) | infrastructure as code |

## Requirements

Needed to build and test:

| Tool | Purpose |
| --- | --- |
| [bazelisk](https://github.com/bazelbuild/bazelisk) (installed as `bazel`) | pins the Bazel version in `.bazelversion` |
| [just](https://github.com/casey/just) ≥ 1.34 | command runner |
| git | version control |

Compilers, interpreters, and SDKs are **not** in this list — Bazel provides them
hermetically. Run `just doctor` to check.

Needed additionally to run the local cluster (see
[the setup guide](./docs/installs/k8s_install.md)):

| Tool | Purpose |
| --- | --- |
| Docker Desktop | runs the kind node as a container |
| kind | the local Kubernetes cluster |
| kubectl · Helm | cluster control and package install |
| k9s | terminal UI for cluster inspection |

Run `just cluster-doctor` to check these.
