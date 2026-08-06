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

# --- 소스 파일 탐색 -------------------------------------------------------------
#
# format.sh · lint.sh · doctor.sh 가 같이 쓴다.
#
# `compgen -G "**/*.java"` 를 쓰지 않는 이유: globstar 를 켜지 않으면 bash 에서 `**` 는
# `*` 와 같아서 딱 한 단계 깊이만 매칭한다. services/x/src/main/java/... 처럼 깊은
# 경로에 있는 실제 소스를 놓치고 포매터가 조용히 건너뛴다.
#
# 파이프도 쓰지 않는다 — `set -o pipefail` 아래에서 find 가 SIGPIPE 를 받으면
# 파일이 있는데도 실패로 잡힌다.
find_sources() {
  find "$REPO_ROOT" -type d \( -name 'bazel-*' -o -name '.git' -o -name '.venv' \) -prune \
    -o -type f -name "$1" -print 2>/dev/null
}

has_files() {
  local found
  found="$(find_sources "$1")"
  [ -n "$found" ]
}

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

# --- 데이터베이스 접속 ----------------------------------------------------------
#
# db-migrate.sh · seed.sh · integration-test.sh 가 같이 쓴다. 셋 다 "psql 에 닿는다" 는
# 같은 필요를 갖는데, 닿는 경로가 환경마다 다르다 (ADR 0005).
#
#   노트북  kind 클러스터의 ClusterIP Service → 포트포워드를 세워야 닿는다
#   CI      Actions 서비스 컨테이너 → localhost 로 바로 닿는다
#
# 그 차이를 여기서 흡수한다. 부르는 쪽은 DB_HOST·DB_PORT 만 보면 되고 쿠버네티스를
# 알 필요가 없다.

DB_NAME="${SCENETRIP_DB_NAME:-scenetrip}"
DB_USER="${SCENETRIP_DB_USER:-scenetrip}"
# 로컬 개발용 고정값이다. 진짜 비밀번호가 아니라 kind 에서만 쓰며,
# platform/kubernetes/postgres/configmap.yaml 에 이미 평문으로 들어 있다.
DB_PASSWORD="${SCENETRIP_DB_PASSWORD:-scenetrip-local}"

# 5432 를 기본으로 쓰지 않는 이유: 맥에 로컬 PostgreSQL 이 깔려 있으면 그쪽으로 붙는다.
# 빈 DB 를 상대로 적재나 테스트가 돌면 무엇을 했는지 알 수 없게 된다.
DB_FORWARD_PORT="${SCENETRIP_DB_PORT:-15432}"

_db_forward_pid=""

_db_forward_cleanup() {
  if [ -n "$_db_forward_pid" ]; then
    kill "$_db_forward_pid" 2>/dev/null || true
    wait "$_db_forward_pid" 2>/dev/null || true
    _db_forward_pid=""
  fi
}

# DB_HOST·DB_PORT 를 정한다. 필요하면 포트포워드를 세우고 스크립트가 끝날 때 치운다.
db_connect() {
  if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
    DB_HOST="$SCENETRIP_DB_HOST"
    DB_PORT="${SCENETRIP_DB_PORT:-5432}"
    log "DB: $DB_HOST:$DB_PORT (환경변수로 지정됨)"
    return
  fi

  have kubectl || die "kubectl 이 없습니다.
       클러스터 없이 쓰려면 SCENETRIP_DB_HOST 로 접속 주소를 알려 주세요."
  require_kind_context

  kubectl get statefulset/postgres -n "$NAMESPACE" >/dev/null 2>&1 ||
    die "네임스페이스 $NAMESPACE 에 postgres 가 없습니다 — 'just cluster-up' 을 먼저 실행하세요"

  DB_HOST="localhost"
  DB_PORT="$DB_FORWARD_PORT"

  log "postgres 로 포트포워드 ($DB_HOST:$DB_PORT)"
  kubectl port-forward -n "$NAMESPACE" statefulset/postgres "$DB_PORT:5432" >/dev/null 2>&1 &
  _db_forward_pid=$!
  trap _db_forward_cleanup EXIT

  # 포트가 열릴 때까지 기다린다. 바로 쓰면 "연결 거부" 로 실패하는데, 그것이 DB 문제인지
  # 코드 문제인지 로그만 보고는 구분되지 않는다.
  local waited=0
  while [ "$waited" -lt 30 ]; do
    if nc -z "$DB_HOST" "$DB_PORT" 2>/dev/null; then
      return
    fi
    sleep 1
    waited=$((waited + 1))
  done
  die "포트포워드가 열리지 않았습니다 — 'just cluster-doctor' 로 클러스터 상태를 확인하세요"
}

# 접속 정보를 붙인 psql. db_connect 를 먼저 불러야 한다.
db_psql() {
  PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 "$@"
}
