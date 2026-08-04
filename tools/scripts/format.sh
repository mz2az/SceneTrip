#!/usr/bin/env bash
# 언어별 포매터. BUILD 파일은 레시피 안의 buildifier 가 담당한다.
# 호출: just fmt [--check]
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

ran=0

if have google-java-format && compgen -G "**/*.java" >/dev/null 2>&1; then
  ran=1
  mapfile -t files < <(find . -name "*.java" -not -path "./bazel-*")
  if [ "$CHECK" -eq 1 ]; then
    out="$(google-java-format --dry-run --set-exit-if-changed "${files[@]}" 2>&1 || true)"
    [ -z "$out" ] || die "포맷이 어긋난 Java 파일:\n$out"
  else
    google-java-format -i "${files[@]}"
  fi
fi

if have ktlint && compgen -G "**/*.kt" >/dev/null 2>&1; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ktlint; else ktlint -F; fi
fi

if have swiftformat && compgen -G "**/*.swift" >/dev/null 2>&1; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then swiftformat --lint .; else swiftformat .; fi
fi

if have ruff && compgen -G "**/*.py" >/dev/null 2>&1; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ruff format --check .; else ruff format .; fi
fi

[ "$ran" -eq 1 ] || pending "아직 적용할 언어 포매터가 없습니다 (소스 모듈 없음)"
