# platform/kind

Definition of the local Kubernetes cluster, created by `just cluster-up`.

`cluster.yaml` is the reason this project uses kind rather than Docker Desktop's built-in
Kubernetes: the cluster is **code**. Node count and host port mappings are identical for
everyone, and a broken cluster is repaired by deleting and recreating it in one command
instead of clicking through a GUI.

## Host port mappings

| Host | NodePort | Purpose |
| --- | --- | --- |
| `localhost:8080` | 30080 | SigNoz UI |
| `localhost:8081` | 30081 | application API |

`extraPortMappings` can only be set **when the cluster is created**. Adding a port means
recreating the cluster — and that discards all collected telemetry and database data.

Because the node container binds these host ports, do not open a `port-forward` on the
same port. Two listeners on one port makes the responder depend on OS binding order, which
looks exactly like "my config change had no effect".

```bash
just cluster-up      # create (idempotent)
just cluster-down    # delete, confirm-gated
```
