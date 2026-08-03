#!/usr/bin/env bash
# Language linters and static analysis. BUILD lint is buildifier, in the recipe.
# Invoked by: just lint
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

ran=0
have golangci-lint && compgen -G "**/*.go" >/dev/null 2>&1 && { ran=1; golangci-lint run ./...; }
have ruff          && compgen -G "**/*.py" >/dev/null 2>&1 && { ran=1; ruff check .; }
have shellcheck    && { ran=1; shellcheck -x tools/scripts/*.sh; }

[ "$ran" -eq 1 ] || pending "no linters applicable yet (no source modules)"
