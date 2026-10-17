#!/usr/bin/env bash
# 이미 남아 있는 같은 가게 중복을 정리한다 — 한쪽을 숨기고 리뷰·코스·글 항목·번호를 남는 쪽으로 옮긴다 (MZ2AZ-491).
# 사용법: poi-dedupe.sh [--dry-run]
# 호출: just poi-dedupe [--dry-run]
#
# --dry-run 은 계산하고 목록을 보여 준 뒤 되돌린다. 몇 번을 돌려도 같다 — 두 번째부터는 찾을 쌍이 없다.
# 규칙은 services/scene-api/seed/poi_dedupe.sql 머리말. 계획: docs/project/plans/poi-load-cleanup.md §8 2단계.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

TRANSFORM="services/scene-api/seed/poi_dedupe.sql"
POD="postgres-0"

DRY=()
case "${1:-}" in
  "") ;;
  --dry-run) DRY=(-v dry_run=1) ;;
  *) die "모르는 인자: $1 — 쓸 수 있는 것은 --dry-run 뿐입니다" ;;
esac
[ -f "$TRANSFORM" ] || die "SQL 이 없습니다: $TRANSFORM"

if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  have psql || die "psql 이 없습니다 (직접 접속 경로). 맥이라면:  brew install libpq && brew link --force libpq"
  db_connect
  log "같은 가게 정리 — $DB_HOST:$DB_PORT/$DB_NAME"
  db_psql ${DRY[@]+"${DRY[@]}"} -q -f "$TRANSFORM" || die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
else
  require_kind_context
  kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"
  log "같은 가게 정리"
  kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 ${DRY[@]+"${DRY[@]}"} -q -f - <"$TRANSFORM" ||
    die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
fi
