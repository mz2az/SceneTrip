#!/usr/bin/env bash
# 언어별 린터와 정적 분석. BUILD 파일 린트는 레시피 안의 buildifier 가 담당한다.
# 호출: just lint
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

ran=0
have checkstyle && compgen -G "**/*.java" >/dev/null 2>&1 && { ran=1; checkstyle -c /google_checks.xml $(find . -name "*.java" -not -path "./bazel-*"); }
have ktlint     && compgen -G "**/*.kt" >/dev/null 2>&1   && { ran=1; ktlint; }
have swiftlint  && compgen -G "**/*.swift" >/dev/null 2>&1 && { ran=1; swiftlint; }
have ruff       && compgen -G "**/*.py" >/dev/null 2>&1   && { ran=1; ruff check .; }
have shellcheck && { ran=1; shellcheck -x tools/scripts/*.sh; }

[ "$ran" -eq 1 ] || pending "아직 적용할 린터가 없습니다 (소스 모듈 없음)"
