#!/usr/bin/env bash
# 묶은 POI 한 판(`just poi-pack` 의 결과)을 GitHub Release `poi-data-<판>` 으로 올린다.
# 사용법: poi-publish.sh <묶은 폴더> <판>
# 호출: just poi-publish <묶은 폴더> <판>
#
# 저장소가 공개라 Release 도 공개다 — 누구나 받을 수 있다. 든 것은 공공데이터뿐이다(상가정보·영문도로명주소DB 는
# 이용허락 제한 없음, 관광공사 TourAPI 는 출처 표시). 네이버 카드는 들어 있지 않다. 계획:
# docs/project/plans/poi-i18n-image.md §14.
#
# 같은 판을 다시 올리지 않는다 — 이미 있으면 멈춘다. 판을 고쳐야 하면 새 판 이름을 쓰거나 Release 를 손으로 지운다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

# 받는 쪽(seed-poi-release.sh)과 같은 저장소에 올린다 — gh 가 지금 폴더의 git 원격(포크일 수 있다)을 따르지 않게.
REPO="${SCENETRIP_POI_RELEASE_REPO:-mz2az/SceneTrip}"

DIR="${1:-}"
EDITION="${2:-}"
[ -n "$DIR" ] && [ -n "$EDITION" ] || die "사용법: just poi-publish <묶은 폴더> <판>   예: just poi-publish ~/Downloads/poi-2026-06 2026-06"
[ -f "$DIR/manifest.json" ] || die "$DIR/manifest.json 이 없습니다 — 먼저 just poi-pack 으로 묶으세요"
have gh || die "gh 가 없습니다 — https://cli.github.com 설치 뒤 gh auth login"
have python3 || die "python3 가 없습니다 (manifest 를 읽는 데 씁니다)"

MANIFEST_EDITION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["edition"])' "$DIR/manifest.json")"
[ "$MANIFEST_EDITION" = "$EDITION" ] || die "manifest 의 판($MANIFEST_EDITION)과 인자($EDITION)가 다릅니다"

TAG="poi-data-$EDITION"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  die "$TAG 가 이미 있습니다 — 같은 판을 덮지 않습니다"
fi

# 목록을 먼저 다 읽는다 — `< <(…)` 안의 실패는 set -e 가 잡지 못한다. mapfile 은 bash 4 이상이라 쓰지 않는다
# (macOS 기본 bash 는 3.2).
LIST="$(python3 -c 'import json,sys; [print(f["name"]) for f in json.load(open(sys.argv[1]))["files"]]' "$DIR/manifest.json")" ||
  die "manifest 를 읽지 못했습니다"
[ -n "$LIST" ] || die "manifest 에 파일이 없습니다"
FILES=()
while IFS= read -r f; do FILES+=("$f"); done <<<"$LIST"
for f in "${FILES[@]}"; do
  [ -f "$DIR/$f" ] || die "manifest 에 있는 $f 가 $DIR 에 없습니다"
done

ROWS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["rows"])' "$DIR/manifest.json")"
NOTES="편의시설(POI) 적재 파일 — 판 $EDITION, $ROWS 행. 받아서 넣기: \`just seed-poi-release $EDITION\`.

출처: 소상공인시장진흥공단 상가(상권)정보 · 한국관광공사 TourAPI · 교통 공공데이터(공항·철도·도시철도·TAGO·환승센터) ·
행정안전부 영문도로명주소DB·영문주소 검색 API. 원본 전 칸(\`src\`)은 뺐다. 형식과 규칙은
docs/project/plans/poi-i18n-image.md §14."

log "$REPO 의 $TAG 로 올립니다 — 공개 Release 입니다"
(cd "$DIR" && gh release create "$TAG" --repo "$REPO" --title "POI 적재 파일 $EDITION" --notes "$NOTES" manifest.json "${FILES[@]}")
log "완료 — 받기: just seed-poi-release $EDITION"
