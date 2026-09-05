#!/usr/bin/env bash
# POI 원본(jsonl)에서 허용목록 갈래(poi.md §3-4)만 남긴 파일을 만든다.
# 사용법: poi-filter.sh <원본.jsonl(.gz)> <출력.jsonl.gz>
# 호출: just poi-filter
#
# 원본은 손대지 않는다 — 승길이 준 파일이 정본이고, 고치면 「어느 판을 어떻게 고쳤나」가
# 사라진다. 대신 이 스크립트가 같은 원본에서 언제나 같은 결과를 만든다.
#
# 기준은 biz_middle 하나다 — poi.sql 의 허용목록과 같은 아홉 값. TMAP 수집기가
# keyword=음식점 으로 긁다가 정육점·반찬가게(biz_middle=음식료, 쇼핑 갈래)·꽃집(생활서비스)
# 을 같이 담아 왔는데, 그것들은 전부 다른 biz_middle 을 달고 있어 이 조건 하나로 갈린다
# (2026-09-03 실측, 8/26 판: poi_food 405,146 행 중 171 행이 빠진다. 나머지 세 파일은
# 빠지는 행이 없다). poi.sql 도 같은 목록으로 거르므로 이 스크립트 없이 원본을 바로
# 넣어도 결과는 같다 — 파일을 미리 걸러 두는 건 「무엇을 넣었나」를 파일로 남기기 위해서다.
#
# 예: just poi-filter "~/Downloads/압축 poi 2/poi_food.jsonl.gz" "~/Downloads/압축 poi 2/허용목록만/poi_food.jsonl.gz"
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

SRC="${1:-}"
DST="${2:-}"
[ -n "$SRC" ] && [ -n "$DST" ] || die "사용법: just poi-filter <원본.jsonl(.gz)> <출력.jsonl.gz>"
[ -f "$SRC" ] || die "원본을 찾을 수 없습니다: $SRC"
[ -e "$DST" ] && die "출력 파일이 이미 있습니다 — 지우고 다시 실행하세요: $DST"
case "$DST" in *.jsonl.gz) ;; *) die "출력은 .jsonl.gz 여야 합니다: $DST" ;; esac

# JSON Lines 한 줄이 한 건이고 키 순서가 고정이라 문자열 매칭으로 충분하다. jq 같은 도구를
# 호스트에 요구하지 않으려는 선택이다. 콜론 뒤 공백은 판마다 다르다 — 8/13 판은 없고
# 8/26 판은 있다 — 그래서 ` *` 로 둘 다 받는다. LC_ALL=C 는 grep 이 한글을 바이트열로
# 다루게 한다 — 로케일에 따라 멀티바이트 패턴을 못 읽는 grep 이 있다.
KEEP='"biz_middle": *"(음식점|카페|술집|숙박|관광명소|종교|문화생활시설|레저/스포츠|교통시설)"'

read_src() {
  case "$SRC" in
    *.gz) gzip -dc "$SRC" ;;
    *) cat "$SRC" ;;
  esac
}

mkdir -p "$(dirname "$DST")"
IN=$(read_src | wc -l | tr -d ' ')
read_src | LC_ALL=C grep -E "$KEEP" | gzip -9 >"$DST" || die "거르기 실패"
OUT=$(gzip -dc "$DST" | wc -l | tr -d ' ')

log "원본 $IN 행 → 허용목록 갈래 $OUT 행 (뺀 것 $((IN - OUT)) 행)"
log "만든 파일: $DST"
log "적재:  just seed-poi \"$DST\""
