#!/usr/bin/env bash
# Code generation beyond Gazelle: protobuf stubs, API clients, mocks.
# Invoked by: just gen (after `bazel run //:gazelle`)
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

# Generated stubs are Bazel outputs, not checked-in files. This script exists for
# generation that Bazel cannot own (e.g. OpenAPI clients for external consumers).

if compgen -G "contracts/proto/**/*.proto" >/dev/null 2>&1; then
  log "proto definitions found — stubs are produced by Bazel proto rules at build time"
fi

if compgen -G "contracts/openapi/*.yaml" >/dev/null 2>&1; then
  pending "OpenAPI client generation not wired yet — add the rule in tools/bazel/defs/"
fi

log "generation complete"
