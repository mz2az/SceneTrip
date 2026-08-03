#!/usr/bin/env bash
# Shared helpers for SceneTrip scripts. Source this, do not execute it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export REPO_ROOT

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# Print a notice for tooling that is not wired up yet, without failing a gate.
pending() { printf '\033[1;33mpending:\033[0m %s\n' "$*"; }

# --- kubernetes safety -------------------------------------------------------

CLUSTER_NAME="${CLUSTER_NAME:-scenetrip}"
KIND_CONTEXT="kind-${CLUSTER_NAME}"
NAMESPACE="${NAMESPACE:-scenetrip}"
SIGNOZ_NS="${SIGNOZ_NS:-signoz}"

# Refuse to run against anything but the local kind cluster.
#
# This is a block, not a reminder. The whole point is that a rule a person has to
# remember every time is not a rule — one distracted `kubectl apply` against an
# EKS context is a production incident.
require_kind_context() {
  local current
  current="$(kubectl config current-context 2>/dev/null || echo none)"
  if [ "$current" != "$KIND_CONTEXT" ]; then
    die "current context is '$current' — this command only runs on '$KIND_CONTEXT'.
       switch with:  just cluster-context"
  fi
}
