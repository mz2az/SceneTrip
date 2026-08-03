#!/usr/bin/env bash
# 기준 ref 와의 차이가 영향을 준 Bazel 테스트 대상을 출력한다.
# 사용법: affected-targets.sh [기준-ref]
# 호출: just ci-affected
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

BASE="${1:-origin/main}"
files="$(git diff --name-only "$BASE"...HEAD -- . | grep -v '^bazel-' || true)"
[ -z "$files" ] && { echo "" ; exit 0; }

# 바뀐 파일에 의존하는 테스트를 찾아낸다.
targets="$(bazel query \
  "kind('.*_test', rdeps(//..., set($(echo "$files" | sed 's|^|//|' | tr '\n' ' '))))" \
  --keep_going --output=label 2>/dev/null || true)"

echo "$targets"
