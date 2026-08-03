# Education material

Teaching material for onboarding engineers and students onto SceneTrip's local
Kubernetes and observability stack.

| Material | Format | Duration |
| --- | --- | --- |
| [k8s-observability-class.html](k8s-observability-class.html) | single-file HTML deck, 35 slides | ~3 hours with hands-on |

```bash
just slides    # open in a browser
```

## What it covers

| Part | Topic |
| --- | --- |
| 1 | Why containers and Kubernetes — the problem each one actually solves |
| 2 | kind: cluster as code, port mappings, namespaces, the traps |
| 3 | Images, manifests, deployment, automation, `just`, Bazel |
| 4 | Instrumentation: structured logs, metrics, traces, OpenTelemetry |
| 5 | SigNoz: searching logs, connecting traces, verifying ingestion |

The deck is **offline and self-contained** — no network, no CDN, no build step. Dark and
light themes both work. It is a normal HTML file, so it diffs in review like any other
source file.

## Keeping it honest

The slides name real paths, real recipes, and real failure modes from this repository.
When any of these change, the slide changes in the same commit:

- `just` recipe names and arguments
- paths under `platform/` and `tools/`
- the `service.name` convention (`scenetrip-<module>`)
- host port mappings

A slide that teaches a command which no longer exists is worse than no slide — students
lose trust in the whole deck. Run `just guides` and `just --list` against the deck before
teaching from it.
