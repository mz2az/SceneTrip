#!/usr/bin/env bash
# 언어별 포매터. BUILD 파일은 레시피 안의 buildifier 가 담당한다.
# 대상 언어는 확정 스택뿐이다: Java(Spring Boot) · Kotlin(Android) · Swift(iOS) · Python(AI).
# 호출: just fmt [--check]
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

# 빌드 산출물·가상환경을 제외하고 소스 파일을 찾는다.
#
# `compgen -G "**/*.java"` 를 쓰지 않는 이유: globstar 를 켜지 않으면 bash 에서 `**` 는
# `*` 와 같아서 딱 한 단계 깊이만 매칭한다. services/x/src/main/java/... 처럼 깊은
# 경로에 있는 실제 소스를 놓치고 포매터가 조용히 건너뛴다.
#
# 파이프도 쓰지 않는다 — `set -o pipefail` 아래에서 find 가 SIGPIPE 를 받으면
# 파일이 있는데도 실패로 잡힌다.
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

# --- Java (services/, libs/java/) ---------------------------------------------
if have google-java-format && has_files '*.java'; then
  ran=1
  mapfile -t files < <(find_sources '*.java')
  if [ "$CHECK" -eq 1 ]; then
    out="$(google-java-format --dry-run --set-exit-if-changed "${files[@]}" 2>&1 || true)"
    [ -z "$out" ] || die "포맷이 어긋난 Java 파일:\n$out"
  else
    google-java-format -i "${files[@]}"
  fi
fi

# --- Kotlin (apps/ Android, libs/kotlin/) -------------------------------------
if have ktlint && has_files '*.kt'; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ktlint; else ktlint -F; fi
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

# --- Python (agents/, libs/python/) -------------------------------------------
if have ruff && has_files '*.py'; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ruff format --check .; else ruff format .; fi
fi

[ "$ran" -eq 1 ] || pending "아직 적용할 언어 포매터가 없습니다 (소스 모듈 없음)"
