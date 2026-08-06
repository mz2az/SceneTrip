#!/usr/bin/env bash
# 통합 테스트 레인. 실제 PostgreSQL 에 대고 SQL 을 태운다.
# 호출: just test-integration
#
# 이 스크립트가 하는 일은 **테스트를 DB 에 이어 주는 것** 하나다. 어디에 붙을지는
# _lib.sh 의 db_connect 가 정한다 (ADR 0005).
#
#   노트북  kind 클러스터의 postgres 로 포트포워드를 세운다
#   CI      SCENETRIP_DB_HOST 로 서비스 컨테이너를 가리킨다
#
# 테스트 코드가 쿠버네티스를 알 필요가 없도록 여기서 막는다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

BAZEL_BIN="${BAZEL:-bazel}"
TARGETS="${1:-//...}"

db_connect

log "통합 테스트 실행 — 대상 $TARGETS"

# --test_env 로 접속 정보를 넘긴다. 테스트는 이 값이 없으면 건너뛰지 않고 실패한다
# (IntegrationDatabase). 조용히 0 개 실행되고 초록인 상태가 제일 나쁘다.
"$BAZEL_BIN" test "$TARGETS" \
  --test_tag_filters=integration \
  --test_output=errors \
  --test_env="SCENETRIP_TEST_JDBC_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
  --test_env="SCENETRIP_TEST_DB_USER=$DB_USER" \
  --test_env="SCENETRIP_TEST_DB_PASSWORD=$DB_PASSWORD"
