#!/usr/bin/env bash
# 편의시설 사진(관광공사 대표 이미지)을 poi_image 에 맞춘다.
# 사용법: seed-poi-images.sh <tour_images.jsonl(.gz)>
# 호출: just seed-poi-images
#
# 입력은 `just poi-tour-images` 가 만든 파일이다(저장소 밖). 입력에 든 POI 의 tour_api 사진만 파일과 같게 하고
# 다른 출처 사진·입력에 없는 POI 는 건드리지 않는다 — 몇 번을 돌려도 같다. POI 를 못 찾은 줄은 세어서 보여 준다.
# 규칙은 services/scene-api/seed/poi_image.sql, 근거는 docs/project/plans/poi-source.md §6-1.
#
#   just seed-poi-images ~/SceneTrip-data/SceneTrip_POI/out/tour_images.jsonl
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

LOAD="services/scene-api/seed/poi_image_load.sql"
IMAGE_SQL="services/scene-api/seed/poi_image.sql"
POD="postgres-0"
# poi_image_load.sql 의 \copy 가 읽는 경로. psql 이 도는 기계 기준이다 (seed-poi.sh 와 같은 규칙).
STAGED="/tmp/seed-poi-images.jsonl"
STAGED_SQL="/tmp/seed-poi-image.sql"

[ $# -eq 1 ] || die "사용법: just seed-poi-images <tour_images.jsonl(.gz)> — 만드는 법은 just poi-tour-images"
INPUT="$1"
[ -f "$INPUT" ] || die "파일을 찾을 수 없습니다: $INPUT"
[ -f "$LOAD" ] || die "적재 SQL 이 없습니다: $LOAD"
[ -f "$IMAGE_SQL" ] || die "사진 SQL 이 없습니다: $IMAGE_SQL"

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
log "$(gzip -dc "$BUNDLE" | wc -l | tr -d ' ') 곳의 사진을 맞춥니다 (tour_api 만, 입력에 든 POI 만)"

if [ -n "$DIRECT" ]; then
  db_connect
  gzip -dc "$BUNDLE" >"$STAGED" || die "입력을 $STAGED 로 놓지 못했습니다"
  cleanup() { rm -f "$STAGED"; cleanup_local; }
  trap cleanup EXIT
  db_psql -q -v image_sql="$IMAGE_SQL" -f "$LOAD" || die "적재 실패 — 트랜잭션이 롤백됐습니다."
else
  kubectl cp "$BUNDLE" "$NAMESPACE/$POD:$STAGED.gz" || die "복사 실패"
  cleanup() {
    kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED" "$STAGED.gz" "$STAGED_SQL" >/dev/null 2>&1 || true
    cleanup_local
  }
  trap cleanup EXIT
  kubectl exec "$POD" -n "$NAMESPACE" -- sh -c "gzip -dc '$STAGED.gz' > '$STAGED'" || die "파드에서 압축 풀기 실패"
  # 적재 SQL 은 표준 입력으로 들어가 \i 의 상대 경로가 파드에서 풀리지 않는다 — 사진 SQL 은 파일로 옮겨 둔다.
  kubectl cp "$IMAGE_SQL" "$NAMESPACE/$POD:$STAGED_SQL" || die "사진 SQL 복사 실패"
  kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 -v image_sql="$STAGED_SQL" -q -f - <"$LOAD" ||
    die "적재 실패 — 트랜잭션이 롤백됐습니다."
fi

log "사진 적재 완료 — 확인:  just db-psql \"SELECT source, count(*) FROM poi_image GROUP BY 1;\""
