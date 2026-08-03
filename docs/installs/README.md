# Setup guides

Step-by-step installation for the local development environment. Read them in order —
SigNoz assumes the Kubernetes setup is already done.

| Guide | Covers |
| --- | --- |
| [k8s_install.md](k8s_install.md) | Homebrew, Git, Docker Desktop, **kind**, kubectl, Helm, k9s, AWS CLI, cluster smoke test, troubleshooting |
| [signoz_install.md](signoz_install.md) | SigNoz via foundryctl, UI access, health checks, connecting apps over OpenTelemetry, log search |

## What the local environment looks like

```
Docker Desktop
└── kind cluster "scenetrip"          context: kind-scenetrip
    ├── namespace scenetrip           SceneTrip services / apps / agents
    └── namespace signoz              SigNoz (ClickHouse, Zookeeper, Postgres, collector)

host 8080 → NodePort 30080 → SigNoz UI
host 8081 → NodePort 30081 → application API
```

Host ports are fixed by `platform/kind/cluster.yaml` at cluster-creation time, so no
`port-forward` is needed for the UI or the API.

> **One kind cluster at a time per machine.** Ports 8080 and 8081 are bound by the node
> container. If another project's cluster is running, stop it before `just cluster-up`.

## Commands

The guides show the underlying `kubectl` and `helm` commands so you understand what is
happening. Day to day, use the recipes:

```bash
just cluster-up          # create the cluster and install SigNoz (idempotent)
just cluster-doctor      # tools, cluster, SigNoz, workloads — one screen
just cluster-test-drive  # prove the cluster works end to end
just signoz              # UI address and filter hint
just signoz-status       # helm release + pod status
just cluster-down        # delete everything (confirm-gated)
```

A guided walkthrough of the same material is in
[../education/](../education/README.md) — run `just slides`.
