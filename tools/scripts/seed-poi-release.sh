#!/usr/bin/env bash
# GitHub Release `poi-data-<판>` 의 POI 적재 파일을 받아 확인하고 `just seed-poi` 로 넣는다.
# 사용법: seed-poi-release.sh <판> [--update]
# 호출: just seed-poi-release <판> [--update]
#
# 원본 zip 이나 API 키 없이 같은 POI 를 넣는 길이다. 저장소가 공개라 curl 만으로 받는다 — gh 도 로그인도 필요 없다
# (나중에 배포 Job 도 같은 방식을 쓸 수 있다). 받은 파일은 저장소 밖 캐시(~/.cache/scenetrip/poi/<판>)에 두고, 다시
# 돌리면 sha256 이 맞는 파일은 다시 받지 않는다. manifest 의 sha256 과 하나라도 다르면 넣지 않는다 — 받다가
# 손상된 것을 잡는 장치다(manifest 도 같은 곳에서 오므로 바꿔치기를 막지는 않는다).
#
# `--update` 를 주면 분기 갱신으로 넣는다(같은 가게 잇기·폐업 표시 — seed-poi.sh 참고). 처음 넣을 때는 빼고 넣는다.
# 계획: docs/project/plans/poi-i18n-image.md §14.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

EDITION="${1:-}"
shift || true
[ -n "$EDITION" ] || die "사용법: just seed-poi-release <판> [--update]   예: just seed-poi-release 2026-06"
# --update 만 넘긴다. POI 를 지우는 옵션은 없다(ADR 0022).
for flag in "$@"; do
  [ "$flag" = "--update" ] || die "모르는 옵션: $flag — --update 만 받습니다"
done
[[ "$EDITION" =~ ^[0-9]{4}-[0-9]{2}$ ]] || die "판은 YYYY-MM 꼴이어야 합니다 — $EDITION"
have curl || die "curl 이 없습니다"
have python3 || die "python3 가 없습니다 (manifest 를 읽는 데 씁니다)"

REPO="${SCENETRIP_POI_RELEASE_REPO:-mz2az/SceneTrip}"
# 받을 곳을 바꿀 수 있다 — 올리기 전에 묶은 폴더로 시험할 때(file:///…/poi-2026-06), 나중에 S3 로 옮길 때.
BASE="${SCENETRIP_POI_RELEASE_BASE:-https://github.com/$REPO/releases/download/poi-data-$EDITION}"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/scenetrip/poi/$EDITION"
mkdir -p "$CACHE"

sha256() {
  if have sha256sum; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

log "판 $EDITION 목록 받기 — $BASE/manifest.json"
curl -fsSL "$BASE/manifest.json" -o "$CACHE/manifest.json" ||
  die "manifest 를 받지 못했습니다 — 판 이름을 확인하세요 (https://github.com/$REPO/releases)"

# 목록을 먼저 다 읽는다. `< <(…)` 안의 실패는 set -e 가 잡지 못해, 깨진 목록이면 파일 0 개로 지나가
# seed-poi.sh 가 저장소 표본을 넣어 버린다. 이름은 poi_*.jsonl.gz 꼴만 받는다 — 받을 곳을 바꿀 수 있어
# 목록을 믿지 않는다(../ 로 캐시 밖에 쓰지 못하게).
LIST="$(python3 - "$CACHE/manifest.json" <<'PY'
import json, re, sys
files = json.load(open(sys.argv[1]))["files"]
for f in files:
    if not re.fullmatch(r"poi_[a-z_]+\.jsonl\.gz", f["name"]) or not re.fullmatch(r"[0-9a-f]{64}", f["sha256"]):
        sys.exit(f"manifest 의 항목이 이상합니다: {f}")
    print(f["name"] + "\t" + f["sha256"])
PY
)" || die "manifest 를 읽지 못했습니다 — $CACHE/manifest.json"
[ -n "$LIST" ] || die "manifest 에 파일이 없습니다"

FILES=()
while IFS=$'\t' read -r name digest; do
  FILES+=("$CACHE/$name")
  if [ -f "$CACHE/$name" ] && [ "$(sha256 "$CACHE/$name")" = "$digest" ]; then
    log "$name — 캐시에 있음"
    continue
  fi
  log "$name 받기"
  curl -fsSL "$BASE/$name" -o "$CACHE/$name.partial" || die "$name 을 받지 못했습니다"
  mv "$CACHE/$name.partial" "$CACHE/$name"
  [ "$(sha256 "$CACHE/$name")" = "$digest" ] || die "$name 의 sha256 이 manifest 와 다릅니다 — 넣지 않습니다"
done <<<"$LIST"
[ "${#FILES[@]}" -gt 0 ] || die "받은 파일이 없습니다"

log "확인 끝 — ${#FILES[@]}개 파일을 적재합니다"
exec ./tools/scripts/seed-poi.sh "$@" "${FILES[@]}"
