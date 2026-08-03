#!/usr/bin/env bash
# 언어별 포매터. BUILD 파일은 레시피 안의 buildifier 가 담당한다.
# 호출: just fmt [--check]
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

ran=0

if have gofmt && compgen -G "**/*.go" >/dev/null 2>&1; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then
    out="$(gofmt -l . || true)"
    [ -z "$out" ] || die "포맷이 어긋난 Go 파일:\n$out"
  else
    gofmt -w .
  fi
fi

if have ruff && compgen -G "**/*.py" >/dev/null 2>&1; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then ruff format --check .; else ruff format .; fi
fi

if have prettier && [ -f package.json ]; then
  ran=1
  if [ "$CHECK" -eq 1 ]; then prettier --check .; else prettier --write .; fi
fi

[ "$ran" -eq 1 ] || pending "아직 적용할 언어 포매터가 없습니다 (소스 모듈 없음)"
