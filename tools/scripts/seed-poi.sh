#!/usr/bin/env bash
# POI(편의시설) JSON Lines 를 poi 표에 적재한다.
# 사용법: seed-poi.sh [--prune] [파일.jsonl(.gz) ...]
# 호출: just seed-poi
#
# 인자가 없으면 저장소의 표본(services/scene-api/seed/poi-sample.jsonl)을 넣는다.
# 전량은 저장소에 없다 — 250 MB 다. 공공데이터 판(SceneTrip_POI_20260907/out, 여섯 파일)을
# 경로로 넘긴다. 여러 파일을 주면 이어 붙여 **한 번에** 넣는다 — 파일을 넘나드는 중복과
# 관광공사↔상가정보 겹침을 한 번의 적재 안에서 접기 위해서다.
#
#   just seed-poi ~/Downloads/SceneTrip_POI_20260907/out/poi_*.jsonl
#
# **다시 돌려도 안전하다.** seed.sh(성지)와 달리 기본으로는 지우지 않는다 — source_id 로
# UPSERT 한다. 있는 행은 갱신하고 없는 행은 더한다. course_item 이 poi 를 참조하게 되면
# (poi.md §4-2) TRUNCATE 는 사용자 코스를 지우는 일이 되기 때문이다.
#
# **출처를 통째로 바꿀 때는 --prune.** 이번 입력에 없는 source_id 를 지운다 — TMAP 판
# (숫자 id)에서 공공데이터 판(MA…·tour-…)으로 갈 때 옛 50만 행을 없애는 데 쓴다. 표본만
# 넣으면서 켜면 나머지 전부가 지워지므로 표본에는 막아 둔다. poi_naver 카드는 CASCADE.
#
#   just seed-poi --prune ~/Downloads/SceneTrip_POI_20260907/out/poi_*.jsonl
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

SAMPLE="services/scene-api/seed/poi-sample.jsonl"
TRANSFORM="services/scene-api/seed/poi.sql"
POD="postgres-0"
# poi.sql 의 \copy 가 읽는 경로. psql 이 도는 기계 기준이다 (seed.sh 와 같은 규칙).
STAGED="/tmp/seed-poi-input.jsonl"

PRUNE=0
FILES=()
for a in "$@"; do
  case "$a" in
    --prune) PRUNE=1 ;;
    *) FILES+=("$a") ;;
  esac
done
[ ${#FILES[@]} -eq 0 ] && FILES=("$SAMPLE")
if [ "$PRUNE" = 1 ] && [ ${#FILES[@]} -eq 1 ] && [ "${FILES[0]}" = "$SAMPLE" ]; then
  die "--prune 은 전량 적재에만 씁니다. 표본과 함께 켜면 표본 밖 전부가 지워집니다."
fi
for f in "${FILES[@]}"; do
  [ -f "$f" ] || die "파일을 찾을 수 없습니다: $f
       인자 없이 실행하면 저장소의 표본($SAMPLE)을 넣습니다."
done
[ -f "$TRANSFORM" ] || die "변환 SQL 이 없습니다: $TRANSFORM"

# 붙는 길이 둘이다 (ADR 0005) — seed.sh 와 같다.
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

# 입력을 gzip 한 덩어리로 모은다. gzip 은 이어 붙여도(멤버 여러 개) 하나로 풀리므로
# .gz 는 그대로 잇고 평문만 압축한다. 파드로는 압축본을 보낸다 — 190 MB 대신 25 MB.
TMP="$(mktemp -d)"
BUNDLE="$TMP/input.jsonl.gz"
cleanup_local() { rm -rf "$TMP"; }
trap cleanup_local EXIT
for f in "${FILES[@]}"; do
  case "$f" in
    *.gz) cat "$f" ;;
    *) gzip -c "$f" ;;
  esac
done >"$BUNDLE"
ROWS=$(gzip -dc "$BUNDLE" | wc -l | tr -d ' ')

if [ ${#FILES[@]} -eq 1 ] && [ "${FILES[0]}" = "$SAMPLE" ]; then
  log "저장소의 표본 $ROWS 행을 적재합니다 — 전량은 'just seed-poi <파일...>'"
else
  log "${#FILES[@]}개 파일, $ROWS 행을 적재합니다"
fi
if [ "$PRUNE" = 1 ]; then
  log "있는 행은 갱신하고 없는 행은 더합니다. --prune: 이번 입력에 없는 행은 지웁니다 (poi_naver 카드도)."
else
  log "있는 행은 갱신하고 없는 행은 더합니다 (source_id 기준). 지우지 않습니다."
fi

if [ -n "$DIRECT" ]; then
  db_connect
  gzip -dc "$BUNDLE" >"$STAGED" || die "입력을 $STAGED 로 놓지 못했습니다"
  cleanup() { rm -f "$STAGED"; cleanup_local; }
  trap cleanup EXIT

  log "변환 실행 — $DB_HOST:$DB_PORT/$DB_NAME"
  if ! db_psql -q -v prune="$PRUNE" -f "$TRANSFORM"; then
    die "적재 실패 — 트랜잭션이 롤백됐습니다. DB 는 적재 직전 상태입니다."
  fi
else
  log "입력을 $POD 로 복사"
  kubectl cp "$BUNDLE" "$NAMESPACE/$POD:$STAGED.gz" || die "복사 실패"
  cleanup() {
    kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED" "$STAGED.gz" >/dev/null 2>&1 || true
    cleanup_local
  }
  trap cleanup EXIT
  kubectl exec "$POD" -n "$NAMESPACE" -- sh -c "gzip -dc '$STAGED.gz' > '$STAGED'" || die "파드에서 압축 풀기 실패"

  log "변환 실행"
  if ! kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 -v prune="$PRUNE" -q -f - <"$TRANSFORM"; then
    die "적재 실패 — 트랜잭션이 롤백됐습니다. DB 는 적재 직전 상태입니다."
  fi
fi

log "적재 완료 — 확인:  just db-psql \"SELECT category_group, count(*) FROM poi GROUP BY 1;\""
