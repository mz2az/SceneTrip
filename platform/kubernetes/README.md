# platform/kubernetes

Kubernetes manifests, one directory per deployable module plus shared platform
components.

| Directory | Contents |
| --- | --- |
| `<module>/` | a module's deployment, service, and configmap — applied by `just deploy <module> local` |
| `signoz/` | our NodePort service exposing the SigNoz UI on host port 8080 |

A module directory is what makes `just deploy` work: the recipe applies
`platform/kubernetes/<module>/` and waits for the rollout. Without it the command
stops with a clear message rather than deploying nothing.

See [platform/README.md](../README.md) for the rules that govern this tree, including the
no-secrets rule and the confirm-gated command policy.
