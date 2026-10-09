#!/usr/bin/env bash
# POI(편의시설) JSON Lines 를 poi 표에 적재한다.
# 사용법: seed-poi.sh [--update] [파일.jsonl(.gz) ...]
# 호출: just seed-poi
#
# 인자가 없으면 저장소의 표본(services/scene-api/seed/poi-sample.jsonl)을 넣는다.
# 전량은 저장소에 없다 — 250 MB 다. 공공데이터 판(SceneTrip_POI_20260907/out, 여섯 파일)을
# 경로로 넘긴다. 여러 파일을 주면 이어 붙여 **한 번에** 넣는다 — 파일을 넘나드는 중복과
# 관광공사↔상가정보 겹침을 한 번의 적재 안에서 접기 위해서다.
#
#   just seed-poi ~/Downloads/SceneTrip_POI_20260907/out/poi_*.jsonl
#
# **다시 돌려도 안전하다.** seed.sh(성지)와 달리 지우지 않는다 — source_id 로 UPSERT 한다.
# 있는 행은 갱신하고 없는 행은 더한다. **POI 는 어떤 경우에도 지우지 않는다**(ADR 0022) — 리뷰·코스
# 항목·번역·사진이 poi 를 ON DELETE CASCADE 로 참조해 함께 사라진다. 보이지 않아야 할 POI 는 closed_at.
#
# 예전의 --prune(이번 입력에 없는 POI 를 지움)은 TMAP → 공공데이터 출처 교체(2026-09-09)에 한 번 쓰고
# 없앴다. 주면 멈춘다.
#
# **분기 갱신은 --update.** 입력에 없는 가게를 지우지 않는다. 좌표·이름으로 새 번호와 같은 가게면
# 번호만 갈아 잇고(poi.id 와 붙은 번역·사진이 남는다), 이어지지 않으면 폐업 표시(closed_at)만 한다.
# 같은 출처끼리만 본다 — 상가정보 파일만 넣어도 관광지는 그대로다. 규칙은 seed/poi_update.sql,
# 근거는 docs/project/plans/poi-i18n-image.md §8. 표본에는 막아 둔다.
#
#   just seed-poi --update ~/Downloads/SceneTrip_POI_<새 판>/out/poi_*.jsonl
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

SAMPLE="services/scene-api/seed/poi-sample.jsonl"
TRANSFORM="services/scene-api/seed/poi.sql"
UPDATE_SQL="services/scene-api/seed/poi_update.sql"
POD="postgres-0"
# poi.sql 의 \copy 가 읽는 경로. psql 이 도는 기계 기준이다 (seed.sh 와 같은 규칙).
STAGED="/tmp/seed-poi-input.jsonl"
# --update 일 때 poi.sql 이 \i 로 읽는 갱신 SQL 의 파드 쪽 경로.
STAGED_UPDATE="/tmp/seed-poi-update.sql"

UPDATE=0
FILES=()
for a in "$@"; do
  case "$a" in
    --prune)
      die "--prune 은 없앴습니다 — POI 를 지우면 그 POI 의 리뷰·코스 항목·번역·사진이 함께 지워집니다(ADR 0022).
       분기 갱신은 --update(사라진 가게는 폐업 표시만), 그냥 넣기는 인자 없이 파일만."
      ;;
    --update) UPDATE=1 ;;
    *) FILES+=("$a") ;;
  esac
done
[ ${#FILES[@]} -eq 0 ] && FILES=("$SAMPLE")
if [ "$UPDATE" = 1 ] && [ ${#FILES[@]} -eq 1 ] && [ "${FILES[0]}" = "$SAMPLE" ]; then
  die "--update 는 새 판 전량에만 씁니다. 표본과 함께 켜면 표본에 없는 가게가 전부 폐업으로 표시됩니다."
fi
[ -f "$UPDATE_SQL" ] || die "갱신 SQL 이 없습니다: $UPDATE_SQL"
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
if [ "$UPDATE" = 1 ]; then
  log "--update: 사라진 가게는 같은 가게로 보이는 새 번호에 잇고, 이어지지 않으면 폐업 표시만 합니다 (지우지 않음)."
else
  log "있는 행은 갱신하고 없는 행은 더합니다 (source_id 기준). 지우지 않습니다."
fi

if [ -n "$DIRECT" ]; then
  db_connect
  gzip -dc "$BUNDLE" >"$STAGED" || die "입력을 $STAGED 로 놓지 못했습니다"
  cleanup() { rm -f "$STAGED"; cleanup_local; }
  trap cleanup EXIT

  log "변환 실행 — $DB_HOST:$DB_PORT/$DB_NAME"
  if ! db_psql -q -v update="$UPDATE" -v update_sql="$UPDATE_SQL" -f "$TRANSFORM"; then
    die "적재 실패 — 트랜잭션이 롤백됐습니다. DB 는 적재 직전 상태입니다."
  fi
else
  log "입력을 $POD 로 복사"
  kubectl cp "$BUNDLE" "$NAMESPACE/$POD:$STAGED.gz" || die "복사 실패"
  cleanup() {
    kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED" "$STAGED.gz" "$STAGED_UPDATE" >/dev/null 2>&1 || true
    cleanup_local
  }
  trap cleanup EXIT
  kubectl exec "$POD" -n "$NAMESPACE" -- sh -c "gzip -dc '$STAGED.gz' > '$STAGED'" || die "파드에서 압축 풀기 실패"
  # poi.sql 은 표준 입력으로 들어가 \i 의 상대 경로가 파드에서 풀리지 않는다 — 갱신 SQL 은 파일로 옮겨 둔다.
  kubectl cp "$UPDATE_SQL" "$NAMESPACE/$POD:$STAGED_UPDATE" || die "갱신 SQL 복사 실패"

  log "변환 실행"
  if ! kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 -v update="$UPDATE" \
      -v update_sql="$STAGED_UPDATE" -q -f - <"$TRANSFORM"; then
    die "적재 실패 — 트랜잭션이 롤백됐습니다. DB 는 적재 직전 상태입니다."
  fi
fi

log "적재 완료 — 확인:  just db-psql \"SELECT category_group, count(*) FROM poi GROUP BY 1;\""
