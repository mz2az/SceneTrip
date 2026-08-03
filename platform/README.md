# platform/ — infrastructure as code

| Directory | Contents |
| --- | --- |
| `kind/` | local Kubernetes cluster definition (`cluster.yaml`) |
| `terraform/` | cloud resources: networking, databases, clusters, IAM |
| `kubernetes/` | base manifests and overlays |
| `helm/` | charts owned by this repo |
| `environments/` | per-environment values: `dev`, `staging`, `prod` |
| `docker/` | local development stack composition |

## Rules

- **No secrets in this tree.** Values come from the secret manager; only
  `*.tfvars.example` placeholders are committed.
- Every environment is described by the same code, differing only in
  `environments/<env>/` values. No environment-only branches.
- Changes are reviewed as a plan (`just tf-plan`) before they are applied.
- All state-mutating commands are `[confirm]`-gated and print the target environment.

The **local** cluster is driven from `tools/just/k8s.just` — see
[docs/installs/](../docs/installs/README.md).

```bash
just cluster-up        # create the local kind cluster + SigNoz
just tf-check <env>     # fmt + validate, read-only
just tf-plan  <env>     # read-only
just tf-apply <env>     # MUTATES INFRASTRUCTURE, confirm-gated
just k8s-diff <env>     # read-only
just deploy <module> <env>
```
