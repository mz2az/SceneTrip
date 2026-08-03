#!/usr/bin/env bash
# SceneTrip 스크립트 공용 헬퍼. 실행하지 말고 source 해서 쓴다.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export REPO_ROOT

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m경고:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m오류:\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# 아직 연결하지 않은 도구를 알리되, 게이트를 실패시키지는 않는다.
pending() { printf '\033[1;33m미구현:\033[0m %s\n' "$*"; }

# --- Kubernetes 안전장치 -------------------------------------------------------

CLUSTER_NAME="${CLUSTER_NAME:-scenetrip}"
KIND_CONTEXT="kind-${CLUSTER_NAME}"
NAMESPACE="${NAMESPACE:-scenetrip}"
SIGNOZ_NS="${SIGNOZ_NS:-signoz}"

# 로컬 kind 클러스터가 아니면 실행을 거부한다.
#
# 알림이 아니라 차단이다. 사람이 매번 기억해야 하는 규칙은 규칙이 아니고,
# 한눈판 사이의 `kubectl apply` 한 번이 EKS 컨텍스트로 나가면 그대로 운영 장애다.
require_kind_context() {
  local current
  current="$(kubectl config current-context 2>/dev/null || echo 없음)"
  if [ "$current" != "$KIND_CONTEXT" ]; then
    die "현재 컨텍스트가 '$current' 입니다 — 이 명령은 '$KIND_CONTEXT' 에서만 동작합니다.
       전환:  just cluster-context"
  fi
}
