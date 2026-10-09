"""적재 파일 묶기 — 다른 사람·배포 환경이 같은 POI 를 넣을 수 있게 한 판을 공유용으로 만든다.

    just poi-pack <POI 폴더> <결과 폴더> <판>          예: just poi-pack ~/…/out-en ~/…/poi-2026-06 2026-06

POI 폴더(보통 `just poi-en` 의 결과)의 poi_*.jsonl(.gz) 을 줄마다 읽어 원본 전 칸 `src` 를 빼고 gzip 으로 쓴다 —
적재(seed/poi.sql)는 `src` 를 읽지 않아 결과가 같고, 크기가 1.6 GB 에서 약 70 MB 로 준다. 함께 `manifest.json` 을
쓴다 — 판·만든 시각·파일마다 행 수·바이트·sha256. 받는 쪽(`just seed-poi-release`)이 이것으로 파일을 확인한다.

같은 폴더에 사진 적재 파일 `tour_images.jsonl(.gz)`(`just poi-tour-images` 의 결과)이 있으면 함께 싣는다 — 받는 쪽이
POI 적재 뒤 `just seed-poi-images` 로 넣는다(poi-source.md §6-1). manifest 의 POI 행 수(`rows`)에는 세지 않는다.

계획: docs/project/plans/poi-i18n-image.md §14.
"""

import argparse
import datetime
import gzip
import hashlib
import json
import os
import re
import sys
from pathlib import Path

MANIFEST = "manifest.json"
IMAGES = "tour_images"  # 사진 적재 파일 이름. poi_ 로 시작하지 않아야 POI 파일로 읽히지 않는다
EDITION = re.compile(r"^\d{4}-\d{2}$")  # 상가정보 기준 연-월. 예 2026-06


def _resolve(path: str) -> Path:
    """`bazel run` 은 작업 폴더가 runfiles 라, 상대 경로는 사람이 명령을 친 곳 기준으로 푼다."""
    base = Path(os.environ.get("BUILD_WORKING_DIRECTORY", "."))
    return (base / Path(path).expanduser()).resolve()


def _open_text(path: Path):
    return (
        gzip.open(path, "rt", encoding="utf-8")
        if path.suffix == ".gz"
        else path.open(encoding="utf-8")
    )


def slim(line: str) -> str:
    """한 줄에서 `src` 를 뺀다. 칸 순서는 그대로 둔다."""
    d = json.loads(line)
    d.pop("src", None)
    return json.dumps(d, ensure_ascii=False)


def pack_file(src: Path, dst: Path) -> dict:
    """한 파일을 묶고 manifest 항목을 돌려준다. gzip 머리에 시각을 넣지 않아(mtime=0) 같은 입력이면 같은 바이트다."""
    rows = 0
    with (
        _open_text(src) as r,
        dst.open("wb") as raw,
        gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz,
    ):
        for line in r:
            if not line.strip():
                continue
            gz.write((slim(line) + "\n").encode("utf-8"))
            rows += 1
    digest = hashlib.sha256(dst.read_bytes()).hexdigest()
    return {
        "name": dst.name,
        "rows": rows,
        "bytes": dst.stat().st_size,
        "sha256": digest,
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("poi_dir")
    ap.add_argument("out_dir")
    ap.add_argument("edition", help="상가정보 기준 연-월. 예 2026-06")
    args = ap.parse_args(argv)

    if not EDITION.match(args.edition):
        print(f"오류: 판은 YYYY-MM 꼴이어야 합니다 — {args.edition}", file=sys.stderr)
        return 1
    poi_dir, out_dir = _resolve(args.poi_dir), _resolve(args.out_dir)
    files = sorted([*poi_dir.glob("poi_*.jsonl"), *poi_dir.glob("poi_*.jsonl.gz")])
    if not files:
        print(f"오류: {poi_dir} 에 poi_*.jsonl 이 없습니다", file=sys.stderr)
        return 1
    images = sorted(
        [*poi_dir.glob(IMAGES + ".jsonl"), *poi_dir.glob(IMAGES + ".jsonl.gz")]
    )
    if len(images) > 1:
        print(
            f"오류: {IMAGES}.jsonl 과 .gz 가 둘 다 있습니다 — 하나만 두세요",
            file=sys.stderr,
        )
        return 1
    if out_dir == poi_dir:
        print("오류: 결과 폴더는 입력 폴더와 달라야 합니다", file=sys.stderr)
        return 1
    out_dir.mkdir(parents=True, exist_ok=True)

    entries = []
    for f in files:
        name = f.name.removesuffix(".gz") + ".gz"
        entry = pack_file(f, out_dir / name)
        entries.append(entry)
        print(f"  {name:<28} {entry['rows']:>9,} 행  {entry['bytes'] / 1e6:6.1f} MB")

    manifest = {
        "edition": args.edition,
        "created_at": datetime.datetime.now(datetime.UTC).isoformat(timespec="seconds"),
        "rows": sum(e["rows"] for e in entries),
        "files": entries,
    }
    # 사진은 files 와 따로 둔다 — files 는 POI 파일만이라는 약속을 옛 받는 쪽도 믿고 있다.
    if images:
        entry = pack_file(images[0], out_dir / (IMAGES + ".jsonl.gz"))
        manifest["images"] = entry
        print(
            f"  {entry['name']:<28} {entry['rows']:>9,} 곳  {entry['bytes'] / 1e6:6.1f} MB  (사진)"
        )
    (out_dir / MANIFEST).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    total = sum(e["bytes"] for e in entries) + (
        manifest["images"]["bytes"] if images else 0
    )
    print(
        f"판 {args.edition}: {manifest['rows']:,} 행, {total / 1e6:.1f} MB → {out_dir}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
