#!/usr/bin/env bash
# 편의시설 업데이트 재료 묶음을 공개 GitHub Release 로 올린다 — 운영 적재가 받는다 (MZ2AZ-492).
# 사용법: poi-update-publish.sh <store|tour|transit> <묶음 폴더> <이름>
# 호출: just poi-update-publish <출처> <묶음 폴더> <이름>     예: just poi-update-publish store ~/…/out-en 2026-09
#
# 묶음을 tar 하나(poi-update-<출처>-<이름>.tar.gz)로 만들어 Release `poi-update-<출처>-<이름>` 에 올린다. 안의 manifest.json 이
# 이름표다 — {"kind": "update", "source", "label", "files"}. 관광공사 묶음(just poi-tour-sync)은 이미 있고, 상가정보·교통은 여기서 쓴다.
# 같은 이름은 덮지 않는다. 올린 뒤 MODULE.bazel 과 services/scene-api/BUILD.bazel 의 POI_UPDATES 에 옮길 줄을 찍는다.
# 운영 적재(remote-load.sh ②)가 아직 넣지 않은 묶음만 이름 순서대로 넣는다. 계획: docs/project/plans/poi-load-cleanup.md §5-6.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

REPO="${SCENETRIP_POI_RELEASE_REPO:-mz2az/SceneTrip}"
SOURCE="${1:-}"
BUNDLE="${2:-}"
LABEL="${3:-}"
case "$SOURCE" in
  store) FILES=(poi_food poi_stay) ;;
  transit) FILES=(poi_transit) ;;
  tour) FILES=(poi_sight poi_tour_food poi_tour_stay tour_hidden tour_detail_ko tour_detail_en) ;;
  *) die "사용법: just poi-update-publish <store|tour|transit> <묶음 폴더> <이름>" ;;
esac
[ -n "$LABEL" ] || die "이름이 필요합니다 — 상가정보·교통은 판(예 2026-09), 관광공사는 기간(예 20261017-20261031)"
[[ "$LABEL" =~ ^[0-9A-Za-z._-]+$ ]] || die "이름은 영숫자·점·밑줄·빼기만: $LABEL"
[ -d "$BUNDLE" ] || die "묶음 폴더가 없습니다: $BUNDLE"
have gh || die "gh 가 없습니다 — https://cli.github.com 설치 뒤 gh auth login"
have python3 || die "python3 가 없습니다 (manifest 를 쓰는 데 씁니다)"

TAG="poi-update-$SOURCE-$LABEL"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  die "$TAG 가 이미 있습니다 — 같은 이름을 덮지 않습니다"
fi

DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
STAGE="$DIR/$TAG"
mkdir -p "$STAGE"
for f in "${FILES[@]}"; do
  if [ -f "$BUNDLE/$f.jsonl.gz" ]; then gzip -dc "$BUNDLE/$f.jsonl.gz" >"$STAGE/$f.jsonl"
  elif [ -f "$BUNDLE/$f.jsonl" ]; then cp "$BUNDLE/$f.jsonl" "$STAGE/$f.jsonl"
  fi
done
ls "$STAGE"/*.jsonl >/dev/null 2>&1 || die "$BUNDLE 에 $SOURCE 파일(${FILES[*]})이 없습니다"

python3 - "$STAGE" "$SOURCE" "$LABEL" "$BUNDLE" <<'PY'
import hashlib, json, os, sys
stage, source, label, bundle = sys.argv[1:]
old = {}
mf = os.path.join(bundle, "manifest.json")
if os.path.exists(mf):
    old = json.load(open(mf, encoding="utf-8"))
    if old.get("kind") not in (None, "update") or old.get("source") not in (None, source):
        sys.exit(f"묶음의 이름표가 맞지 않습니다: kind={old.get('kind')} source={old.get('source')}")
files = []
for name in sorted(os.listdir(stage)):
    p = os.path.join(stage, name)
    data = open(p, "rb").read()
    files.append({"name": name, "rows": data.count(b"\n"), "sha256": hashlib.sha256(data).hexdigest()})
m = {"kind": "update", "source": source, "label": f"{source}-{label}", "files": files}
for k in ("from", "to", "edition"):
    if k in old:
        m[k] = old[k]
json.dump(m, open(os.path.join(stage, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(json.dumps({f["name"]: f["rows"] for f in files}, ensure_ascii=False))
PY

TARBALL="$DIR/$TAG.tar.gz"
tar -czf "$TARBALL" -C "$DIR" "$TAG"
SHA=$(shasum -a 256 "$TARBALL" | cut -d' ' -f1)

NOTES="편의시설 업데이트 재료 — 출처 $SOURCE, $LABEL. 안의 manifest.json 이 이름표다.
출처: 공공데이터(소상공인시장진흥공단 상가정보 · 한국관광공사 TourAPI · 교통 공공자료). 형식과 규칙은 docs/project/plans/poi-load-cleanup.md §5-6.
원격 적재가 MODULE.bazel 의 poi_update_* 로 받아, 아직 넣지 않았으면 넣는다."
log "$REPO 의 $TAG 로 올립니다 — 공개 Release 입니다"
gh release create "$TAG" --repo "$REPO" --title "편의시설 업데이트 $SOURCE $LABEL" --notes "$NOTES" "$TARBALL" ||
  die "올리지 못했습니다"

NAME="poi_update_${SOURCE}_$(printf '%s' "$LABEL" | tr -c '0-9A-Za-z_\n' '_')"
log "완료 — 아래를 옮기는 PR 을 낸다"
cat <<EOF

# MODULE.bazel (poi_release_* 아래)
http_file(
    name = "$NAME",
    downloaded_file_path = "$TAG.tar.gz",
    sha256 = "$SHA",
    urls = ["https://github.com/$REPO/releases/download/$TAG/$TAG.tar.gz"],
)

# services/scene-api/BUILD.bazel 의 POI_UPDATES 에 한 줄
    "@$NAME//file",
EOF
