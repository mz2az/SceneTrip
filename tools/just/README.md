# tools/just/ — command modules

The root `justfile` imports every file here. One file per area, so the command surface
stays navigable as the repo grows.

| File | Group | Contents |
| --- | --- | --- |
| `bazel.just` | `build` | build, run, query, clean |
| `dev.just` | `dev` | setup, doctor, fmt, lint, gen |
| `test.just` | `test` | test lanes, coverage, flake hunting |
| `docs.just` | `docs` | docs lint, ADR creation, docs site |
| `infra.just` | `infra` | images, terraform, kubernetes, deploy |
| `ci.just` | `ci` | pipeline entry points, release |
| `agent.just` | `agent` | AI agent runs and evaluations |
| `scaffold.just` | `scaffold` | new service / app / agent / lib |

## Adding a recipe

1. Put it in the file matching its area.
2. Give it a `[group('…')]` attribute and a `#` doc comment — both appear in
   `just --list`, which is how contributors and AI agents discover commands.
3. Mark anything that mutates shared state `[confirm]` and echo the target environment.
4. Keep the body short; push logic into `tools/scripts/`.
