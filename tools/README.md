# tools/ — build and developer tooling

| Directory | Contents |
| --- | --- |
| `bazel/defs/` | reusable Starlark macros and rules |
| `bazel/toolchains/` | hermetic toolchain registrations |
| `just/` | justfile modules imported by the root `justfile` |
| `scripts/` | shell scripts invoked *by* just recipes |
| `templates/` | scaffolding templates rendered by `just new-*` |
| `ci/` | CI helper logic |

## Rules

- **Justfiles hold commands; scripts hold logic.** More than ~5 lines of shell in a
  recipe means it belongs in `scripts/`.
- **Three copies of a Bazel pattern is a macro.** Put it in `bazel/defs/`.
- Scripts source `scripts/_lib.sh` for logging helpers and `REPO_ROOT`.
- Every script is `set -euo pipefail` and safe to re-run.
