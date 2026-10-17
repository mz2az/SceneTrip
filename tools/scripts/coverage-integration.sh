#!/usr/bin/env bash
# 단위 + 통합 레인을 함께 돌린 커버리지. 통합 테스트가 실제 PostgreSQL 에 붙어야 하므로
# integration-test.sh 와 같은 db_connect 로 DB 를 잇는다.
# 호출: just coverage-integration [타깃…]
#
# 커버리지는 「어떤 테스트로 쟀나」 에 따라 달라진다 — 빠른 레인만 재는 `just coverage` 는
# DB 를 쓰는 코드(…Store)를 거의 밟지 않아 낮게 나온다. 이 레시피는 두 레인을 합친 숫자를 낸다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

BAZEL_BIN="${BAZEL:-bazel}"
if [ "$#" -eq 0 ]; then
  set -- //...
fi

db_connect

# 실패한 테스트 타깃은 커버리지를 모으지 않는다. 로컬 DB 내용(표본 vs 전량)에 기대는 테스트가
# 떨어지면 타깃 전체 숫자가 사라지므로, 그 메서드만 빼고 잴 수 있게 한다(JUnit 콘솔 런처 정규식).
# 예: COVERAGE_EXCLUDE_METHODS='.*#(findsDetail|existingIds)\(.*' just coverage-integration //services/scene-api/...
extra=()
if [ -n "${COVERAGE_EXCLUDE_METHODS:-}" ]; then
  log "제외할 테스트 메서드: $COVERAGE_EXCLUDE_METHODS"
  extra+=("--test_arg=--exclude-methodname=$COVERAGE_EXCLUDE_METHODS")
fi

log "단위 + 통합 커버리지 — 대상 $*"
"$BAZEL_BIN" coverage "$@" ${extra[@]+"${extra[@]}"} \
  --test_tag_filters=-e2e,-slow,-manual \
  --test_output=errors \
  --combined_report=lcov \
  --test_env="SCENETRIP_TEST_JDBC_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
  --test_env="SCENETRIP_TEST_DB_USER=$DB_USER" \
  --test_env="SCENETRIP_TEST_DB_PASSWORD=$DB_PASSWORD"
