#!/usr/bin/env bash
# 통합 테스트 레인. 클러스터의 실제 PostgreSQL 에 대고 SQL 을 태운다.
# 호출: just test-integration
#
# 이 스크립트가 하는 일은 **테스트를 DB 에 이어 주는 것** 하나다.
#
# 클러스터 안의 postgres 는 ClusterIP Service 라 맥에서 바로 닿지 않는다. 포트포워드를
# 세우고, 주소를 환경변수로 넘기고, 끝나면 치운다. 테스트 코드가 kubectl 을 알 필요가
# 없도록 여기서 막는다 — 나중에 DB 를 다른 방식으로 띄우게 되면 이 파일만 고치면 된다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

BAZEL_BIN="${BAZEL:-bazel}"
TARGETS="${1:-//...}"

# 로컬 개발용 값이다. 진짜 비밀번호가 아니라 kind 클러스터에서만 쓰는 고정값이고,
# platform/kubernetes/postgres/configmap.yaml 에 이미 평문으로 들어 있다.
DB_USER="${SCENETRIP_TEST_DB_USER:-scenetrip}"
DB_NAME="${SCENETRIP_TEST_DB_NAME:-scenetrip}"
DB_PASSWORD="${SCENETRIP_TEST_DB_PASSWORD:-scenetrip-local}"

# 5432 를 그대로 쓰지 않는 이유: 맥에 로컬 PostgreSQL 이 깔려 있으면 그쪽으로 붙어
# 버린다. 빈 DB 를 상대로 테스트가 돌면 무엇을 검사했는지 알 수 없게 된다.
LOCAL_PORT="${SCENETRIP_TEST_DB_PORT:-15432}"

have kubectl || die "kubectl 이 없습니다 — docs/installs/k8s_install.md 를 보세요"
require_kind_context

if ! kubectl get statefulset/postgres -n "$NAMESPACE" >/dev/null 2>&1; then
  die "네임스페이스 $NAMESPACE 에 postgres 가 없습니다 — 'just cluster-up' 을 먼저 실행하세요"
fi

PORT_FORWARD_PID=""
cleanup() {
  if [ -n "$PORT_FORWARD_PID" ]; then
    kill "$PORT_FORWARD_PID" 2>/dev/null || true
    wait "$PORT_FORWARD_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

log "postgres 로 포트포워드 (localhost:$LOCAL_PORT)"
kubectl port-forward -n "$NAMESPACE" statefulset/postgres "$LOCAL_PORT:5432" >/dev/null 2>&1 &
PORT_FORWARD_PID=$!

# 포트가 열릴 때까지 기다린다. 바로 테스트를 돌리면 "연결 거부" 로 실패하는데,
# 그것이 DB 문제인지 코드 문제인지 로그만 보고는 구분되지 않는다.
ready=""
for _ in $(seq 1 30); do
  if nc -z localhost "$LOCAL_PORT" 2>/dev/null; then
    ready="yes"
    break
  fi
  sleep 1
done
[ -n "$ready" ] || die "포트포워드가 열리지 않았습니다 — 'just cluster-doctor' 로 클러스터 상태를 확인하세요"

log "통합 테스트 실행 — 대상 $TARGETS"

# --test_env 로 접속 정보를 넘긴다. 테스트는 이 값이 없으면 건너뛰지 않고 실패한다
# (IntegrationDatabase). 조용히 0 개 실행되고 초록인 상태가 제일 나쁘다.
"$BAZEL_BIN" test "$TARGETS" \
  --test_tag_filters=integration \
  --test_output=errors \
  --test_env="SCENETRIP_TEST_JDBC_URL=jdbc:postgresql://localhost:$LOCAL_PORT/$DB_NAME" \
  --test_env="SCENETRIP_TEST_DB_USER=$DB_USER" \
  --test_env="SCENETRIP_TEST_DB_PASSWORD=$DB_PASSWORD"
