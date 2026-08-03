#!/usr/bin/env bash
# Language formatters. BUILD files are handled by buildifier in the just recipe.
# Invoked by: just fmt [--check]
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

ran=0

if have gofmt && compgen -G "**/*.go" >/dev/null 2>&1; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then
    out="$(gofmt -l . || true)"
    [ -z "$out" ] || die "unformatted Go files:\n$out"
  else
    gofmt -w .
  fi
fi

if have ruff && compgen -G "**/*.py" >/dev/null 2>&1; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ruff format --check .; else ruff format .; fi
fi

if have prettier && [ -f package.json ]; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then prettier --check .; else prettier --write .; fi
fi

[ "$ran" -eq 1 ] || pending "no language formatters applicable yet (no source modules)"
