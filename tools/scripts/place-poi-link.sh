#!/usr/bin/env bash
# 촬영지와 같은 곳인 편의시설을 연결하고, 편의시설 쪽 리뷰를 촬영지로 옮긴다 (MZ2AZ-371).
# 사용법: place-poi-link.sh [--dry-run]
# 호출: just place-poi-link [--dry-run]
#
# --dry-run 은 계산하고 목록을 보여 준 뒤 되돌린다 — 처음 판정할 때 후보 전부를 연결 전에 본다.
#
# 촬영지 적재(just seed)와 편의시설 갱신(just seed-poi --update) 뒤에 돌린다. 몇 번을 돌려도 같다 —
# 연결은 매번 처음부터 계산해 표를 맞춘다. 계획: docs/project/plans/place-poi-link.md.
#
# 사람의 판정은 services/scene-api/seed/place_poi_links.tsv 에 적는다(same/not). 끝에 나오는
# 「판정이 필요한 후보」 줄을 그 파일에 붙이고 ? 를 same 이나 not 으로 바꾼 뒤 다시 돌린다.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

TRANSFORM="services/scene-api/seed/place_poi_link.sql"
VERDICTS="services/scene-api/seed/place_poi_links.tsv"
POD="postgres-0"
# place_poi_link.sql 의 \copy 가 읽는 경로. psql 이 도는 기계 기준이다.
STAGED="/tmp/place-poi-links.tsv"

DRY=()
case "${1:-}" in
  "") ;;
  --dry-run) DRY=(-v dry_run=1) ;;
  *) die "모르는 인자: $1 — 쓸 수 있는 것은 --dry-run 뿐입니다" ;;
esac

[ -f "$TRANSFORM" ] || die "연결 SQL 이 없습니다: $TRANSFORM"
[ -f "$VERDICTS" ] || die "판정 파일이 없습니다: $VERDICTS"

# 붙는 길은 seed.sh 와 같다(ADR 0005) — SCENETRIP_DB_HOST 가 있으면 직접, 없으면 kind 파드 안에서.
if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  have psql || die "psql 이 없습니다 (직접 접속 경로). 맥이라면:  brew install libpq && brew link --force libpq"
  db_connect
  cp "$VERDICTS" "$STAGED" || die "판정 파일을 $STAGED 로 놓지 못했습니다"
  cleanup() { rm -f "$STAGED"; }
  trap cleanup EXIT
  log "연결 — $DB_HOST:$DB_PORT/$DB_NAME"
  db_psql ${DRY[@]+"${DRY[@]}"} -q -f "$TRANSFORM" || die "연결 실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
else
  require_kind_context
  kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"
  kubectl cp "$VERDICTS" "$NAMESPACE/$POD:$STAGED" || die "판정 파일 복사 실패"
  cleanup() { kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED" >/dev/null 2>&1 || true; }
  trap cleanup EXIT
  log "연결"
  kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 ${DRY[@]+"${DRY[@]}"} -q -f - <"$TRANSFORM" ||
    die "연결 실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
fi

log "연결 완료 — 판정이 필요한 후보가 있으면 $VERDICTS 에 적고 다시 돌리세요"
