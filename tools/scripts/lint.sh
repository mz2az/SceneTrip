#!/usr/bin/env bash
# 언어별 린터와 정적 분석. BUILD 파일 린트는 레시피 안의 buildifier 가 담당한다.
# 대상 언어는 확정 스택뿐이다: Java(Spring Boot) · Kotlin(Android) · Swift(iOS) · Python(AI).
# 호출: just lint
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

# 파일 탐색이 왜 find 인지는 format.sh 의 같은 함수 주석 참고
# (globstar 미적용 시 `**` 가 한 단계만 매칭 + pipefail 아래 SIGPIPE 오탐).
find_sources() {
  find . -type d \( -name 'bazel-*' -o -name '.git' -o -name '.venv' \) -prune \
    -o -type f -name "$1" -print 2>/dev/null
}
has_files() {
  local found
  found="$(find_sources "$1")"
  [ -n "$found" ]
}

ran=0

if have checkstyle && has_files '*.java'; then
  ran=1
  mapfile -t files < <(find_sources '*.java')
  checkstyle -c /google_checks.xml "${files[@]}"
fi
have ktlint     && has_files '*.kt'    && { ran=1; ktlint; }
have swiftlint  && has_files '*.swift' && { ran=1; swiftlint; }
have ruff       && has_files '*.py'    && { ran=1; ruff check .; }
have shellcheck && { ran=1; shellcheck -x tools/scripts/*.sh; }

[ "$ran" -eq 1 ] || pending "아직 적용할 린터가 없습니다 (소스 모듈 없음)"
