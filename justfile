# SceneTrip — single command surface for the monorepo.
#
# LAW: every command a human or an agent runs is a recipe here. Raw `bazel` calls
#      never appear in docs, scripts, or CI. See AGENTS.md §5.
#
# Requires: just >= 1.34, bazelisk installed as `bazel`.
# Run `just` (no args) to list every recipe grouped by area.

set shell := ["bash", "-euo", "pipefail", "-c"]
set dotenv-load := true
set positional-arguments := true

# --- shared variables --------------------------------------------------------

BAZEL       := env_var_or_default("BAZEL", "bazel")
ALL         := "//..."
REPO_ROOT   := justfile_directory()

# --- command modules ---------------------------------------------------------

import 'tools/just/bazel.just'
import 'tools/just/dev.just'
import 'tools/just/test.just'
import 'tools/just/docs.just'
import 'tools/just/infra.just'
import 'tools/just/k8s.just'
import 'tools/just/ci.just'
import 'tools/just/agent.just'
import 'tools/just/scaffold.just'

# --- entry points ------------------------------------------------------------

# List every available recipe (default).
default:
    @just --list --list-heading $'SceneTrip commands\n'

# Pre-PR gate. Run this before handing off any change. Must be green.
[group('gate')]
check: fmt-check lint build test
    @echo "check passed — safe to commit"

# Everything CI runs, in CI order. Reproduces the pipeline locally.
[group('gate')]
ci: gen-check fmt-check lint build test test-integration
    @echo "ci passed"

# Print the toolchain versions this workspace resolves to.
[group('gate')]
versions:
    @echo "just   : $(just --version)"
    @echo "bazel  : $({{BAZEL}} --version 2>/dev/null || echo 'NOT FOUND')"
    @echo "pinned : $(cat .bazelversion 2>/dev/null || echo 'no .bazelversion')"
    @echo "git    : $(git --version)"
