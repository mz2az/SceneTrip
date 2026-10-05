#!/usr/bin/env bash
# 편의시설 네이버 카드(poi_naver)를 CSV 한 편으로 뜬다.
# 사용법: poi-naver-backup.sh <파일.csv>
# 호출: just poi-naver-backup <파일.csv>
#
# 네이버 카드는 더 쓰지 않는다(docs/project/plans/poi-i18n-image.md). 표와 코드는 두고,
# 모은 것을 파일로 남겨 나중에 쓸 일이 생기면 되살린다.
#
# **source_id 를 맨 앞에 붙여 뜬다.** poi_naver 는 내부 poi.id 를 들고 있는데, 그 id 는
# `just seed-poi --prune` 으로 출처를 갈면 바뀐다. 되살릴 때 잇는 열쇠는 출처의 id 다.
#
# **저장소 안에는 쓰지 않는다.** 비공식 경로로 받은 자료다(ADR 0011, 데모 한정).
# 있는 파일도 덮지 않는다 — 백업을 백업으로 덮는 실수를 막는다.
#
# 붙는 길은 db-psql.sh 와 같다 — SCENETRIP_DB_HOST 가 있으면 직접, 없으면 kind 파드 안.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

DST="${1:-}"
[ -n "$DST" ] || die "백업할 파일 경로를 주세요. 예: just poi-naver-backup ~/SceneTrip-backup/poi_naver-$(date +%Y%m%d).csv"
[ -e "$DST" ] && die "$DST 가 이미 있습니다 — 덮지 않습니다. 다른 이름을 주세요"

DST_DIR="$(dirname "$DST")"
mkdir -p "$DST_DIR"
DST_ABS="$(cd "$DST_DIR" && pwd)/$(basename "$DST")"
case "$DST_ABS" in
  "$REPO_ROOT"/*) die "저장소 안에는 쓰지 않습니다 — 비공식 경로로 받은 자료입니다(ADR 0011). 저장소 밖 경로를 주세요" ;;
esac

# poi 와 INNER JOIN 이 아니라 LEFT JOIN 이다. poi_naver 는 FK CASCADE 라 짝 없는 행이
# 생길 수 없지만, 생겼다면 빠뜨리지 말고 source_id 가 빈 채로라도 남긴다.
COPY_SQL="COPY (
  SELECT p.source_id, n.*
  FROM poi_naver n
  LEFT JOIN poi p ON p.id = n.poi_id
  ORDER BY n.poi_id
) TO STDOUT WITH (FORMAT csv, HEADER)"

# 반쯤 쓴 파일이 백업처럼 남지 않도록 임시 파일에 쓰고 다 되면 옮긴다.
TMP="$DST_ABS.partial"
trap 'rm -f "$TMP"' EXIT

if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  have psql || die "psql 이 없습니다 (직접 접속 경로). SCENETRIP_DB_HOST 를 지우면 kind 파드 경로로 뜹니다"
  db_connect
  db_psql -c "$COPY_SQL" >"$TMP"
else
  require_kind_context
  kubectl exec -i statefulset/postgres -n "$NAMESPACE" -- \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "$COPY_SQL" >"$TMP"
fi

mv "$TMP" "$DST_ABS"
trap - EXIT

# CSV 안 값에 줄바꿈이 들 수 있어 wc -l 은 행 수가 아니다. 줄 수만 알린다.
log "백업 완료: $DST_ABS ($(wc -c <"$DST_ABS" | tr -d ' ') 바이트, $(($(wc -l <"$DST_ABS") - 1)) 줄 + 머리글)"
