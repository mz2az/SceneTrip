#!/usr/bin/env bash
# 관광공사 상세 적재 파일(`just poi-tour-detail` 의 결과)을 묶어 GitHub Release `poi-detail-<판>` 으로 올린다.
# 사용법: poi-detail-publish.sh <국문 tour_detail.jsonl> <영문 tour_detail_en.jsonl> <판>
# 호출: just poi-detail-publish <국문> <영문> <판>
#
# 원격 데이터 적재(remote-load.sh)가 이 판을 적재 이미지에 넣어 DEV·PRD 에 같은 상세를 넣는다 — 노트북의 수집 파일은 원격 DB 에
# 닿지 못한다. 받는 쪽은 MODULE.bazel 의 http_file 세 줄(판·sha256)이다.
#
# 저장소가 공개라 Release 도 공개다. 든 것은 한국관광공사 TourAPI 의 공개 자료(공공누리 — 출처 표시)뿐이다. 사진은 파일이 아니라
# 관광공사 주소만 들어 있다. 계획: docs/project/plans/poi-source.md §7.
#
# 같은 판을 다시 올리지 않는다 — 이미 있으면 멈춘다. 수집이 이어지면 새 판 이름으로 올린다(적재는 다시 돌려도 같다).
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

REPO="${SCENETRIP_POI_RELEASE_REPO:-mz2az/SceneTrip}"

KO="${1:-}"
EN="${2:-}"
EDITION="${3:-}"
[ -n "$KO" ] && [ -n "$EN" ] && [ -n "$EDITION" ] ||
  die "사용법: just poi-detail-publish <국문 tour_detail.jsonl> <영문 tour_detail_en.jsonl> <판>   예: … 2026-10-16"
[ -f "$KO" ] || die "국문 파일이 없습니다: $KO"
[ -f "$EN" ] || die "영문 파일이 없습니다: $EN"
have gh || die "gh 가 없습니다 — https://cli.github.com 설치 뒤 gh auth login"
have python3 || die "python3 가 없습니다 (manifest 를 쓰는 데 씁니다)"

TAG="poi-detail-$EDITION"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  die "$TAG 가 이미 있습니다 — 같은 판을 덮지 않습니다"
fi

DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
# -n: 압축 파일에 이름·시각을 넣지 않는다 — 같은 입력이면 같은 sha256.
gzip -nc "$KO" >"$DIR/tour_detail_ko.jsonl.gz"
gzip -nc "$EN" >"$DIR/tour_detail_en.jsonl.gz"
python3 - "$DIR" "$EDITION" "$KO" "$EN" <<'PY'
import hashlib, json, sys
d, edition, ko, en = sys.argv[1:]
files = []
for name, src in (("tour_detail_ko.jsonl.gz", ko), ("tour_detail_en.jsonl.gz", en)):
    data = open(f"{d}/{name}", "rb").read()
    rows = sum(1 for line in open(src, encoding="utf-8") if line.strip())
    files.append({"name": name, "sha256": hashlib.sha256(data).hexdigest(), "rows": rows})
json.dump({"edition": edition, "files": files, "rows": sum(f["rows"] for f in files)},
          open(f"{d}/manifest.json", "w"), ensure_ascii=False, indent=2)
PY

NOTES="관광공사 상세 적재 파일 — 판 $EDITION (국문·영문). 개요·이용시간·쉬는 날·주차·대표 메뉴·입장료·홈페이지와 사진 주소.

출처: 한국관광공사 TourAPI(공공누리 — 출처 표시, 제3유형은 변경 금지). 형식과 규칙은 docs/project/plans/poi-source.md §6-1.
원격 적재가 MODULE.bazel 의 poi_detail_* 로 받는다."

log "$REPO 의 $TAG 로 올립니다 — 공개 Release 입니다"
(cd "$DIR" && gh release create "$TAG" --repo "$REPO" --title "관광공사 상세 $EDITION" --notes "$NOTES" \
  manifest.json tour_detail_ko.jsonl.gz tour_detail_en.jsonl.gz)
log "완료 — MODULE.bazel 에 옮길 sha256:"
python3 - "$DIR/manifest.json" <<'PY'
import json, sys
for f in json.load(open(sys.argv[1]))["files"]:
    print("  %s  %s  (%d 줄)" % (f["name"], f["sha256"], f["rows"]))
PY
shasum -a 256 "$DIR/manifest.json" | sed 's/^/  manifest.json /'
