#!/usr/bin/env bash
# 처음 적재 때 다른 편의시설에 합쳐진 줄의 번호를 번호 기록(poi_source)에 넣는다 (MZ2AZ-491).
# 사용법: poi-source-merged.sh
# 호출: just poi-source-merged
#
# 입력은 services/scene-api/seed/poi_merged_2026-06.tsv(22,818 줄). 몇 번을 돌려도 같다. 넣은 뒤 관광공사 상세 적재
# (just seed-poi-detail)를 다시 돌리면 합쳐진 곳에도 상세가 붙는다. 계획: docs/project/plans/poi-load-cleanup.md §3.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

TRANSFORM="services/scene-api/seed/poi_source_merged.sql"
INPUT="services/scene-api/seed/poi_merged_2026-06.tsv"
POD="postgres-0"
# poi_source_merged.sql 의 \copy 가 읽는 경로. psql 이 도는 기계 기준이다.
STAGED="/tmp/poi-merged.tsv"

[ "$#" -eq 0 ] || die "인자를 받지 않습니다"
[ -f "$TRANSFORM" ] || die "SQL 이 없습니다: $TRANSFORM"
[ -f "$INPUT" ] || die "입력이 없습니다: $INPUT"

# 붙는 길은 seed.sh 와 같다(ADR 0005) — SCENETRIP_DB_HOST 가 있으면 직접, 없으면 kind 파드 안에서.
if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  have psql || die "psql 이 없습니다 (직접 접속 경로). 맥이라면:  brew install libpq && brew link --force libpq"
  db_connect
  cp "$INPUT" "$STAGED" || die "입력을 $STAGED 로 놓지 못했습니다"
  cleanup() { rm -f "$STAGED"; }
  trap cleanup EXIT
  log "합쳐진 번호 기록 — $DB_HOST:$DB_PORT/$DB_NAME"
  db_psql -q -f "$TRANSFORM" || die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
else
  require_kind_context
  kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"
  kubectl cp "$INPUT" "$NAMESPACE/$POD:$STAGED" || die "입력 복사 실패"
  cleanup() { kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED" >/dev/null 2>&1 || true; }
  trap cleanup EXIT
  log "합쳐진 번호 기록"
  kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 -q -f - <"$TRANSFORM" ||
    die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
fi

log "완료 — 관광공사 상세를 다시 맞추려면 just seed-poi-detail <국문 상세 파일>"
