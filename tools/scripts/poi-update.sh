#!/usr/bin/env bash
# 편의시설 출처별 업데이트 — 묶음 폴더의 재료를 DB 에 반영한다 (MZ2AZ-492).
# 사용법: poi-update.sh <store|tour|transit> <묶음 폴더> [--dry-run]
# 호출: just poi-update <출처> <묶음 폴더> [--dry-run]
#
# 묶음 폴더 — 출처마다 넣는 파일:
#   store    poi_food.jsonl · poi_stay.jsonl          판 전체 (빠진 번호는 숨김 판단)
#   transit  poi_transit.jsonl                        판 전체
#   tour     poi_sight.jsonl · poi_tour_food.jsonl · poi_tour_stay.jsonl · tour_hidden.jsonl   바뀐 것만 (just poi-tour-sync 가 만든다)
#            tour_detail_ko.jsonl · tour_detail_en.jsonl 이 있으면 이어서 상세를 맞춘다(just seed-poi-detail 과 같은 SQL)
# .gz 도 받는다. 영어 칸(addr_en 등)이 붙은 파일이면 영어도 고친다 — 없으면 영어는 그대로 둔다.
# 규칙: services/scene-api/seed/poi_source_update.sql 머리말. 계획: docs/project/plans/poi-load-cleanup.md §5.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

SEED="services/scene-api/seed"
LOAD="$SEED/poi_update_load.sql"
POD="postgres-0"
STAGED_IN="/tmp/poi-update-input.jsonl"
STAGED_HIDDEN="/tmp/poi-update-hidden.jsonl"

SOURCE="${1:-}"
BUNDLE="${2:-}"
DRY=()
case "${3:-}" in
  "") ;;
  --dry-run) DRY=(-v dry_run=1) ;;
  *) die "모르는 인자: $3 — 쓸 수 있는 것은 --dry-run 뿐입니다" ;;
esac
case "$SOURCE" in
  store) FILES=(poi_food poi_stay); FULL=1 ;;
  transit) FILES=(poi_transit); FULL=1 ;;
  tour) FILES=(poi_sight poi_tour_food poi_tour_stay); FULL=0 ;;
  *) die "사용법: just poi-update <store|tour|transit> <묶음 폴더> [--dry-run]" ;;
esac
[ -n "$BUNDLE" ] && [ -d "$BUNDLE" ] || die "묶음 폴더가 없습니다: $BUNDLE"

# 묶음의 이름표가 있으면 출처가 맞는지 본다 — 관광공사 묶음을 store 로 넣으면 판 전체에서 빠진 번호 계산이 틀린다.
if [ -f "$BUNDLE/manifest.json" ]; then
  kind=$(sed -n 's/.*"kind": *"\([^"]*\)".*/\1/p' "$BUNDLE/manifest.json" | head -1)
  src=$(sed -n 's/.*"source": *"\([^"]*\)".*/\1/p' "$BUNDLE/manifest.json" | head -1)
  [ -z "$kind" ] || [ "$kind" = "update" ] || die "업데이트 재료가 아닙니다(kind=$kind)"
  [ -z "$src" ] || [ "$src" = "$SOURCE" ] || die "묶음의 출처($src)와 명령의 출처($SOURCE)가 다릅니다"
fi

TMP_IN="$(mktemp)"
TMP_HIDDEN="$(mktemp)"
cleanup_local() { rm -f "$TMP_IN" "$TMP_HIDDEN"; }
trap cleanup_local EXIT

found=0
for f in "${FILES[@]}"; do
  if [ -f "$BUNDLE/$f.jsonl.gz" ]; then gzip -dc "$BUNDLE/$f.jsonl.gz" >>"$TMP_IN"; found=1
  elif [ -f "$BUNDLE/$f.jsonl" ]; then cat "$BUNDLE/$f.jsonl" >>"$TMP_IN"; found=1
  fi
done
[ "$found" = 1 ] || die "$BUNDLE 에 $SOURCE 파일(${FILES[*]})이 없습니다"
if [ "$SOURCE" = tour ]; then
  if [ -f "$BUNDLE/tour_hidden.jsonl.gz" ]; then gzip -dc "$BUNDLE/tour_hidden.jsonl.gz" >"$TMP_HIDDEN"
  elif [ -f "$BUNDLE/tour_hidden.jsonl" ]; then cp "$BUNDLE/tour_hidden.jsonl" "$TMP_HIDDEN"
  fi
fi
log "$SOURCE 업데이트 — 줄 $(wc -l <"$TMP_IN" | tr -d ' ') · 표출 중단 $(wc -l <"$TMP_HIDDEN" | tr -d ' ')"

VARS=(-v source="$SOURCE" -v full="$FULL")
if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  have psql || die "psql 이 없습니다 (직접 접속 경로). 맥이라면:  brew install libpq && brew link --force libpq"
  db_connect
  cp "$TMP_IN" "$STAGED_IN" || die "입력을 /tmp 에 놓지 못했습니다"
  cp "$TMP_HIDDEN" "$STAGED_HIDDEN" || die "입력을 /tmp 에 놓지 못했습니다"
  trap 'rm -f "$TMP_IN" "$TMP_HIDDEN" "$STAGED_IN" "$STAGED_HIDDEN"' EXIT
  db_psql "${VARS[@]}" -v filter_sql="$SEED/poi_filter.sql" -v update_sql="$SEED/poi_source_update.sql" \
    ${DRY[@]+"${DRY[@]}"} -q -f "$LOAD" || die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
else
  require_kind_context
  kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"
  kubectl cp "$TMP_IN" "$NAMESPACE/$POD:$STAGED_IN" || die "입력 복사 실패"
  kubectl cp "$TMP_HIDDEN" "$NAMESPACE/$POD:$STAGED_HIDDEN" || die "입력 복사 실패"
  kubectl cp "$SEED/poi_filter.sql" "$NAMESPACE/$POD:/tmp/poi_filter.sql" || die "SQL 복사 실패"
  kubectl cp "$SEED/poi_source_update.sql" "$NAMESPACE/$POD:/tmp/poi_source_update.sql" || die "SQL 복사 실패"
  trap 'rm -f "$TMP_IN" "$TMP_HIDDEN"; kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED_IN" "$STAGED_HIDDEN" /tmp/poi_filter.sql /tmp/poi_source_update.sql >/dev/null 2>&1 || true' EXIT
  kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 "${VARS[@]}" \
    -v filter_sql=/tmp/poi_filter.sql -v update_sql=/tmp/poi_source_update.sql \
    ${DRY[@]+"${DRY[@]}"} -q -f - <"$LOAD" || die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
fi

# 관광공사 상세 — 편의시설 다음에. 미리 보기면 건너뛴다(상세 SQL 에는 되돌리기가 없다).
if [ "$SOURCE" = tour ] && [ ${#DRY[@]} -eq 0 ]; then
  for f in tour_detail_ko tour_detail_en; do
    if [ -s "$BUNDLE/$f.jsonl" ]; then
      log "상세 — $f"
      ./tools/scripts/seed-poi-detail.sh "$BUNDLE/$f.jsonl" || die "상세 적재 실패: $f"
    fi
  done
fi
log "완료"
