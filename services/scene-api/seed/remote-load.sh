#!/usr/bin/env bash
# 원격 데이터 적재 — 적재 이미지(:data_load_image) 안에서 Job 이 돌린다 (MZ2AZ-414, docs/project/plans/remote-data-load.md).
#
# 로컬 스크립트(seed.sh · seed-poi.sh · place-poi-link.sh)의 「직접 경로」 와 **같은 SQL 파일을 같은 방식으로** 부른다 —
# 입력을 /tmp 의 정해진 이름에 놓고 psql -f. 두 벌이 되면 어긋난다.
#
# 접속은 표준 PG 환경변수로 받는다: PGHOST · PGUSER(app_migrate — 소유자, 적재 SQL 이 임시 표·TRUNCATE·MV 갱신을 한다) ·
# PGPASSWORD · PGDATABASE · PGSSLMODE. 그 밖에 SCENETRIP_COMMIT(배포 커밋, data_load 기록용).
#
# 순서(계획 §3): ① 촬영지 ② 편의시설(표가 비면 처음, 아니면 --update) ②-2 합쳐진 번호 기록 ②-3 같은 가게 정리(MZ2AZ-491)
# ③ 같은 곳 연결 ③-2 관광공사 상세(국문 → 영문, MZ2AZ-376 — 번호 기록으로 찾는다)
# ④ 인기도 ⑤ 검색 색인 ⑥ 건수 검사·기록.
# 몇 번을 돌려도 같다. **사용자 데이터를 지우는 seed-reset 은 여기 없다.**
#
# 마지막 줄에 `DATA_LOAD_SUMMARY {json}` 을 찍는다 — 배포기가 kubectl logs 로 이 줄만 읽는다(비밀값이 없다).
set -euo pipefail

DATA=/data
TMP=${TMPDIR:-/tmp}

# 단계 줄에 시작부터 지난 초를 붙인다 — 원격에서 어느 단계가 느린지 로그만으로 보이게(2026-10-13 DEV 갱신 적재가 30 분을 넘겼는데
# 어느 단계인지 몰랐다).
say() { printf '==> [+%ss] %s\n' "$(($(date +%s) - started))" "$*"; }
fail() {
  printf 'DATA_LOAD_FAILED %s\n' "$*"
  exit 1
}
q() { psql -X -v ON_ERROR_STOP=1 -At "$@"; }
run() { psql -X -v ON_ERROR_STOP=1 -q "$@"; }

: "${PGHOST:?PGHOST 가 없다}" "${PGUSER:?PGUSER 가 없다}" "${PGDATABASE:?PGDATABASE 가 없다}"
started=$(date +%s)

# 판 이름은 manifest 에서 — 이미지에 든 것이 무엇인지 그대로 적는다.
poi_edition=$(sed -n 's/.*"edition": *"\([^"]*\)".*/\1/p' "$DATA/poi/manifest.json" | head -1)
csv_sha=$(sha256sum "$DATA/seed/candidates.csv" | cut -d' ' -f1)

say "① 촬영지 — candidates.csv"
cp "$DATA/seed/candidates.csv" "$TMP/seed-input.csv"
run -f "$DATA/seed/candidates.sql" || fail "촬영지 적재"
rm -f "$TMP/seed-input.csv"

existing=$(q -c "SELECT count(*) FROM poi")
update=0
[ "$existing" -gt 0 ] && update=1
# 표가 비었을 때만 판 전체를 처음 적재한다. 이미 있으면 그 판은 들어가 있다 — 바뀐 것은 아래 업데이트 묶음으로만(MZ2AZ-492).
if [ "$update" = 0 ]; then
  say "② 편의시설 — 판 $poi_edition (처음 적재)"
  # 여섯 파일을 하나로 — seed-poi.sh 와 같이 한 번의 적재 안에서 파일을 넘나드는 중복을 접는다.
  cat "$DATA"/poi/poi_*.jsonl.gz | gzip -dc >"$TMP/seed-poi-input.jsonl"
  run -v update=0 -v update_sql="$DATA/seed/poi_update.sql" -f "$DATA/seed/poi.sql" || fail "편의시설 적재"
  rm -f "$TMP/seed-poi-input.jsonl"
else
  say "② 편의시설 — 판 $poi_edition 은 이미 들어 있다(처음 적재 건너뜀)"
fi

# 처음 적재 때 합쳐진 줄의 번호(poi_source) — 관광공사 상세가 합쳐진 곳에도 붙게. 로컬 `just poi-source-merged` 와 같은 SQL.
say "②-2 합쳐진 번호 기록"
cp "$DATA/seed/poi_merged_2026-06.tsv" "$TMP/poi-merged.tsv"
run -f "$DATA/seed/poi_source_merged.sql" || fail "합쳐진 번호 기록"
rm -f "$TMP/poi-merged.tsv"

# 남아 있던 같은 가게 중복(반올림 경계로 갈린 쌍) — 로컬 `just poi-dedupe` 와 같은 SQL. 두 번째부터는 할 일이 없다.
say "②-3 같은 가게 정리"
run -f "$DATA/seed/poi_dedupe.sql" || fail "같은 가게 정리"

# 업데이트 묶음(poi-update-<출처>-<이름>.tar.gz, 안에 manifest.json 이름표) — 이름 순서대로, 이미 넣은 것은 건너뛴다.
# 로컬 `just poi-update` 와 같은 SQL(poi_update_load.sql). 관광공사 묶음은 편의시설 다음에 상세도.
applied=()
shopt -s nullglob
for t in "$DATA"/poi-update/*.tar.gz; do
  work="$TMP/poi-update"
  rm -rf "$work" && mkdir -p "$work"
  tar -xzf "$t" -C "$work" || fail "업데이트 묶음 풀기 ($t)"
  manifests=("$work"/*/manifest.json)
  [ -f "${manifests[0]}" ] || fail "업데이트 묶음에 manifest.json 이 없다 ($t)"
  dir=$(dirname "${manifests[0]}")
  label=$(sed -n 's/.*"label": *"\([^"]*\)".*/\1/p' "$dir/manifest.json" | head -1)
  source=$(sed -n 's/.*"source": *"\([^"]*\)".*/\1/p' "$dir/manifest.json" | head -1)
  kind=$(sed -n 's/.*"kind": *"\([^"]*\)".*/\1/p' "$dir/manifest.json" | head -1)
  [ "$kind" = update ] || fail "업데이트 재료가 아니다 ($t: kind=$kind)"
  done_already=$(q -c "SELECT EXISTS (SELECT 1 FROM data_load WHERE summary->'poi_updates' ? '$label')")
  if [ "$done_already" = t ]; then
    say "②-4 업데이트 $label — 이미 넣었다(건너뜀)"
    continue
  fi
  say "②-4 업데이트 $label"
  case "$source" in
    store) files="poi_food poi_stay"; full=1 ;;
    transit) files="poi_transit"; full=1 ;;
    tour) files="poi_sight poi_tour_food poi_tour_stay"; full=0 ;;
    *) fail "모르는 출처 ($label: $source)" ;;
  esac
  : >"$TMP/poi-update-input.jsonl"
  for f in $files; do [ -f "$dir/$f.jsonl" ] && cat "$dir/$f.jsonl" >>"$TMP/poi-update-input.jsonl"; done
  : >"$TMP/poi-update-hidden.jsonl"
  [ -f "$dir/tour_hidden.jsonl" ] && cp "$dir/tour_hidden.jsonl" "$TMP/poi-update-hidden.jsonl"
  run -v source="$source" -v full="$full" -v filter_sql="$DATA/seed/poi_filter.sql" \
      -v update_sql="$DATA/seed/poi_source_update.sql" -f "$DATA/seed/poi_update_load.sql" || fail "업데이트 ($label)"
  if [ "$source" = tour ]; then
    for f in tour_detail_ko tour_detail_en; do
      if [ -s "$dir/$f.jsonl" ]; then
        cp "$dir/$f.jsonl" "$TMP/seed-poi-detail.jsonl"
        run -v detail_sql="$DATA/seed/poi_detail.sql" -f "$DATA/seed/poi_detail_load.sql" || fail "업데이트 상세 ($label $f)"
      fi
    done
    rm -f "$TMP/seed-poi-detail.jsonl"
  fi
  rm -f "$TMP/poi-update-input.jsonl" "$TMP/poi-update-hidden.jsonl"
  applied+=("\"$label\"")
done
shopt -u nullglob
poi_updates="[$(IFS=,; echo "${applied[*]}")]"

say "③ 같은 곳 연결"
cp "$DATA/seed/place_poi_links.tsv" "$TMP/place-poi-links.tsv"
run -f "$DATA/seed/place_poi_link.sql" || fail "같은 곳 연결"
rm -f "$TMP/place-poi-links.tsv"

# 관광공사 상세 — 로컬 `just seed-poi-detail` 과 같은 SQL. 영문은 이름·거리로 짝을 지어 국문 뒤에(poi_detail.sql). 판이 이미지에 없으면
# 건너뛴다(상세 판을 싣기 전의 이미지와 같게).
detail_edition=""
if [ -f "$DATA/poi-detail/manifest.json" ]; then
  detail_edition=$(sed -n 's/.*"edition": *"\([^"]*\)".*/\1/p' "$DATA/poi-detail/manifest.json" | head -1)
  say "③-2 관광공사 상세 — 판 $detail_edition"
  for f in tour_detail_ko tour_detail_en; do
    gzip -dc "$DATA/poi-detail/$f.jsonl.gz" >"$TMP/seed-poi-detail.jsonl"
    run -v detail_sql="$DATA/seed/poi_detail.sql" -f "$DATA/seed/poi_detail_load.sql" || fail "관광공사 상세 ($f)"
  done
  rm -f "$TMP/seed-poi-detail.jsonl"
fi

say "④ 인기도 · ⑤ 검색 색인"
run -c "SELECT recompute_popularity();" || fail "인기도"
run -c "REFRESH MATERIALIZED VIEW CONCURRENTLY search_term;" || fail "검색 색인"

say "⑥ 건수 검사"
counts=$(q -F' ' -c "
  SELECT (SELECT count(*) FROM content WHERE hidden_at IS NULL),
         (SELECT count(*) FROM place   WHERE hidden_at IS NULL),
         (SELECT count(*) FROM poi     WHERE closed_at IS NULL),
         (SELECT count(*) FROM place_poi_link),
         (SELECT count(*) FROM search_term),
         (SELECT count(*) FROM poi_detail)")
read -r n_content n_place n_poi n_link n_search n_detail <<<"$counts"
for pair in "작품:$n_content" "촬영지:$n_place" "편의시설:$n_poi" "검색 색인:$n_search"; do
  [ "${pair#*:}" -gt 0 ] || fail "${pair%%:*} 이 0 건이다 — 화면이 빈다"
done

took=$(($(date +%s) - started))
summary=$(printf '{"commit":"%s","poi_edition":"%s","poi_mode":"%s","csv_sha256":"%s","content":%s,"place":%s,"poi":%s,"place_poi_link":%s,"search_term":%s,"poi_detail_edition":"%s","poi_detail":%s,"poi_updates":%s,"seconds":%s}' \
  "${SCENETRIP_COMMIT:-}" "$poi_edition" "$([ "$update" = 1 ] && echo update || echo initial)" "$csv_sha" \
  "$n_content" "$n_place" "$n_poi" "$n_link" "$n_search" "$detail_edition" "$n_detail" "$poi_updates" "$took")
# 변수 치환(:'summary')은 -c 에서는 일어나지 않는다 — 표준 입력으로 넘긴다.
printf '%s\n' "INSERT INTO data_load (summary) VALUES (:'summary'::jsonb);" |
  run -v summary="$summary" || fail "기록"
printf 'DATA_LOAD_SUMMARY %s\n' "$summary"
