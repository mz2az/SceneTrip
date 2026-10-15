#!/usr/bin/env bash
# 편의시설 상세(관광공사 개요·이용시간·쉬는 날·주차·대표 메뉴·입장료·홈페이지)와 관광공사 사진을 맞춘다.
# 사용법: seed-poi-detail.sh <tour_detail.jsonl(.gz)>
# 호출: just seed-poi-detail
#
# 입력은 `just poi-tour-detail` 이 만든 파일이다(저장소 밖). 입력에 든 POI·언어의 상세와, 그 POI 에 이번 관광공사 번호로
# 들어온 사진만 파일과 같게 한다 — 몇 번을 돌려도 같다. 수집이 이어지는 동안 언제 다시 돌려도 된다. POI 를 못 찾은 줄은
# 세어서 보여 준다. 규칙은 services/scene-api/seed/poi_detail.sql, 근거는 docs/project/plans/poi-source.md §6-1.
#
#   just poi-tour-detail ~/SceneTrip-data/SceneTrip_POI/raw/tourapi/Kor/detail.jsonl ~/SceneTrip-data/SceneTrip_POI/out/tour_detail.jsonl
#   just seed-poi-detail ~/SceneTrip-data/SceneTrip_POI/out/tour_detail.jsonl
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

LOAD="services/scene-api/seed/poi_detail_load.sql"
DETAIL_SQL="services/scene-api/seed/poi_detail.sql"
POD="postgres-0"
# poi_detail_load.sql 의 \copy 가 읽는 경로. psql 이 도는 기계 기준이다 (seed-poi.sh 와 같은 규칙).
STAGED="/tmp/seed-poi-detail.jsonl"
STAGED_SQL="/tmp/seed-poi-detail.sql"

[ $# -eq 1 ] || die "사용법: just seed-poi-detail <tour_detail.jsonl(.gz)> — 만드는 법은 just poi-tour-detail"
INPUT="$1"
[ -f "$INPUT" ] || die "파일을 찾을 수 없습니다: $INPUT"
[ -f "$LOAD" ] || die "적재 SQL 이 없습니다: $LOAD"
[ -f "$DETAIL_SQL" ] || die "상세 SQL 이 없습니다: $DETAIL_SQL"

# 붙는 길이 둘이다 (ADR 0005) — seed-poi.sh 와 같다.
DIRECT=""
if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  DIRECT="yes"
  have psql || die "psql 이 없습니다 (직접 접속 경로).
       맥이라면:  brew install libpq && brew link --force libpq
       또는 SCENETRIP_DB_HOST 를 지우고 kind 파드 경로로 실행하세요."
else
  require_kind_context
  kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"
fi

TMP="$(mktemp -d)"
BUNDLE="$TMP/input.jsonl.gz"
cleanup_local() { rm -rf "$TMP"; }
trap cleanup_local EXIT
case "$INPUT" in
  *.gz) cp "$INPUT" "$BUNDLE" ;;
  *) gzip -c "$INPUT" >"$BUNDLE" ;;
esac
log "$(gzip -dc "$BUNDLE" | wc -l | tr -d ' ') 줄의 상세·사진을 맞춥니다 (입력에 든 POI 만)"

if [ -n "$DIRECT" ]; then
  db_connect
  gzip -dc "$BUNDLE" >"$STAGED" || die "입력을 $STAGED 로 놓지 못했습니다"
  cleanup() { rm -f "$STAGED"; cleanup_local; }
  trap cleanup EXIT
  db_psql -q -v detail_sql="$DETAIL_SQL" -f "$LOAD" || die "적재 실패 — 트랜잭션이 롤백됐습니다."
else
  kubectl cp "$BUNDLE" "$NAMESPACE/$POD:$STAGED.gz" || die "복사 실패"
  cleanup() {
    kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED" "$STAGED.gz" "$STAGED_SQL" >/dev/null 2>&1 || true
    cleanup_local
  }
  trap cleanup EXIT
  kubectl exec "$POD" -n "$NAMESPACE" -- sh -c "gzip -dc '$STAGED.gz' > '$STAGED'" || die "파드에서 압축 풀기 실패"
  # 적재 SQL 은 표준 입력으로 들어가 \i 의 상대 경로가 파드에서 풀리지 않는다 — 상세 SQL 은 파일로 옮겨 둔다.
  kubectl cp "$DETAIL_SQL" "$NAMESPACE/$POD:$STAGED_SQL" || die "상세 SQL 복사 실패"
  kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 -v detail_sql="$STAGED_SQL" -q -f - <"$LOAD" ||
    die "적재 실패 — 트랜잭션이 롤백됐습니다."
fi

log "상세 적재 완료 — 확인:  just db-psql \"SELECT lang, count(*) FROM poi_detail GROUP BY 1;\""
