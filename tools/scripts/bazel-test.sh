#!/usr/bin/env bash
# `bazel test` 를 실행하되, 종료 코드 4("테스트 대상 없음")를 실패가 아니라 경고로 바꾼다.
# 호출: tools/just/test.just 의 테스트 레인
#
# 모듈을 만들어 가는 중에 레인이 비어 있는 것은 정상 상태다.
# 실제 테스트 실패는 그대로 게이트를 막는다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

set +e
"${BAZEL:-bazel}" test "$@"
code=$?
set -e

if [ "$code" -eq 4 ]; then
  warn "일치하는 테스트 대상이 없습니다: $*"
  exit 0
fi
exit "$code"
