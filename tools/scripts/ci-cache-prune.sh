#!/usr/bin/env bash
# main 이 방금 저장한 캐시 하나만 남기고, 같은 종류(접두사)의 옛 main 캐시를 지운다.
# 호출: just ci-cache-prune <남길 키> <접두사>   (DRY_RUN=1 이면 지울 목록만 출력)
#
# 저장소 캐시 한도는 10 GB 다. main 한 번이 상자 셋(약 9 GB)을 새로 저장하는데 옛 상자도
# 남아 있어 한도를 넘고, GitHub 이 오래 안 쓴 것부터 지운다 — 아직 꺼내지 않은 macOS 캐시가
# 지워져 그 잡이 처음부터 지었다(CI #546, 40 분, MZ2AZ-421). 그래서 종류마다 최신 하나만 둔다.
#
# 종류별로만 지운다. 먼저 끝난 Linux 잡이 macOS 옛 상자까지 지우면 같은 일이 난다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

[[ $# -eq 2 ]] || die "사용법: just ci-cache-prune <남길 키> <접두사>"
keep=$1
prefix=$2
[[ -n $prefix ]] || die "접두사가 비었습니다 — 모든 캐시를 지우게 됩니다"
[[ $keep == "$prefix"* ]] || die "남길 키가 접두사로 시작하지 않습니다: $keep / $prefix"
have gh || die "gh 가 없습니다"

caches=$(gh cache list --ref refs/heads/main --key "$prefix" --limit 100 \
  --json id,key --jq '.[] | "\(.id)\t\(.key)"')

# 방금 저장한 것이 목록에 없으면(저장 실패 등) 지우지 않는다 — 다 지우면 다음 실행이 처음부터 짓는다.
if ! cut -f2 <<<"$caches" | grep -qxF -- "$keep"; then
  warn "남길 캐시가 목록에 없습니다 — 아무것도 지우지 않습니다: $keep"
  exit 0
fi

deleted=0
while IFS=$'\t' read -r id key; do
  [[ -z $id || $key == "$keep" ]] && continue
  if [[ ${DRY_RUN:-} == 1 ]]; then
    log "지울 것(DRY_RUN): $key"
  else
    log "지운다: $key"
    gh cache delete "$id" || warn "지우지 못했습니다(이미 지워졌을 수 있음): $key"
  fi
  deleted=$((deleted + 1))
done <<<"$caches"

log "남김: $keep · 옛 캐시 ${deleted} 개"
