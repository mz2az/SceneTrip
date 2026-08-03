# tools/scripts/ — script library

Scripts invoked by just recipes. Never called directly by contributors.

## Conventions

- `source _lib.sh` for `log`, `warn`, `die`, `have`, `pending`, and `REPO_ROOT`.
- `set -euo pipefail` (inherited from `_lib.sh`).
- Idempotent: safe to run twice.
- No absolute paths; resolve everything from `REPO_ROOT`.
- Scripts that gate CI must exit non-zero on failure and print why.

Scripts printing `pending: …` are deliberate placeholders for tooling that has not been
chosen yet. They exit 0 so gates stay green, and each names what must be wired up.
