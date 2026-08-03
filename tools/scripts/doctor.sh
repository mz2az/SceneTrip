#!/usr/bin/env bash
# SceneTrip 을 빌드하는 데 필요한 것이 워크스테이션에 다 있는지 확인한다.
# 호출: just doctor
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

status=0

check_required() {
  local tool="$1" hint="$2"
  if have "$tool"; then
    printf '  정상  %-10s %s\n' "$tool" "$($tool --version 2>&1 | head -1)"
  else
    printf '  없음  %-10s -> %s\n' "$tool" "$hint"
    status=1
  fi
}

check_optional() {
  local tool="$1" why="$2"
  if have "$tool"; then
    printf '  정상  %-10s %s\n' "$tool" "$($tool --version 2>&1 | head -1)"
  else
    printf '  미설치 %-10s (선택: %s)\n' "$tool" "$why"
  fi
}

log "필수 도구"
check_required bazel "bazelisk 를 설치하고 'bazel' 이름으로 심볼릭 링크"
check_required just  "https://github.com/casey/just"
check_required git   "git 설치"

log "선택 도구"
check_optional docker "로컬 클러스터, 이미지 적재"
check_optional gh     "풀 리퀘스트 자동화"

log "워크스페이스"
[ -f "$REPO_ROOT/MODULE.bazel" ] || { echo "  MODULE.bazel 없음"; status=1; }
[ -f "$REPO_ROOT/.bazelversion" ] && echo "  고정된 bazel 버전: $(cat "$REPO_ROOT/.bazelversion")"

if [ "$status" -eq 0 ]; then
  log "워크스테이션 준비 완료"
else
  die "필수 도구가 빠져 있습니다"
fi
