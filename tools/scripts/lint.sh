#!/usr/bin/env bash
# 언어별 린터와 정적 분석. BUILD 파일 린트는 레시피 안의 buildifier 가 담당한다.
# 대상 언어는 확정 스택뿐이다: Java(Spring) · Python(AI) · Swift(iOS) · Kotlin(Android).
# 호출: just lint
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

# 해당 확장자의 소스가 실제로 있는지 확인한다. 빌드 산출물과 가상환경은 제외.
# 파이프를 쓰지 않는다 — `set -o pipefail` 아래에서 find 가 SIGPIPE 를 받으면
# 파일이 있는데도 실패로 잡힌다.
has_files() {
  local found
  found="$(find . -type d \( -name 'bazel-*' -o -name '.git' -o -name '.venv' \) -prune \
    -o -type f -name "$1" -print 2>/dev/null)"
  [ -n "$found" ]
}

ran=0

have ruff       && has_files '*.py'    && { ran=1; ruff check .; }
have swiftlint  && has_files '*.swift' && { ran=1; swiftlint lint --quiet; }
have ktlint     && has_files '*.kt'    && { ran=1; ktlint; }
have shellcheck && { ran=1; shellcheck -x tools/scripts/*.sh; }

# Java 정적 분석은 아직 붙이지 않았다. 첫 Spring 서비스가 생길 때
# tools/bazel/defs/ 에 ErrorProne 또는 Checkstyle 을 Bazel 애스펙트로 연결한다.
if has_files '*.java'; then
  pending "Java 린터 미연결 — 첫 Spring 서비스와 함께 ErrorProne/Checkstyle 을 붙이세요"
fi

[ "$ran" -eq 1 ] || pending "아직 적용할 린터가 없습니다 (소스 모듈 없음)"
