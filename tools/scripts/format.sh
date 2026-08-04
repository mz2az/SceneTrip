#!/usr/bin/env bash
# 언어별 포매터. BUILD 파일은 레시피 안의 buildifier 가 담당한다.
# 대상 언어는 확정 스택뿐이다: Java(Spring) · Python(AI) · Swift(iOS) · Kotlin(Android).
# 호출: just fmt [--check]
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
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

# --- Java (services/, libs/java/) ---------------------------------------------
if have google-java-format && has_files '*.java'; then
  ran=1
  mapfile -t java_files < <(find . -type d \( -name 'bazel-*' -o -name '.git' \) -prune \
    -o -type f -name '*.java' -print)
  if [ "$CHECK" -eq 1 ]; then
    google-java-format --dry-run --set-exit-if-changed "${java_files[@]}"
  else
    google-java-format --replace "${java_files[@]}"
  fi
fi

# --- Python (agents/, libs/python/) -------------------------------------------
if have ruff && has_files '*.py'; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ruff format --check .; else ruff format .; fi
fi

# --- Swift (apps/ iOS, libs/swift/) -------------------------------------------
if has_files '*.swift'; then
  if have swiftformat; then
    ran=1
    if [ "$CHECK" -eq 1 ]; then swiftformat --lint .; else swiftformat .; fi
  elif have swift-format; then
    ran=1
    if [ "$CHECK" -eq 1 ]; then swift-format lint -r .; else swift-format format -i -r .; fi
  fi
fi

# --- Kotlin (apps/ Android, libs/kotlin/) -------------------------------------
if have ktlint && has_files '*.kt'; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ktlint; else ktlint -F; fi
fi

[ "$ran" -eq 1 ] || pending "아직 적용할 언어 포매터가 없습니다 (소스 모듈 없음)"
