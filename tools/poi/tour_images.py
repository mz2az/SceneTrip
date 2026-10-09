"""관광공사 대표 이미지 → 적재 파일 — `just seed-poi-images` 가 poi_image 에 넣는다.

    just poi-tour-images <관광공사 국문 list.jsonl> <결과 tour_images.jsonl>

입력은 TourAPI 목록 응답을 한 줄씩 모은 파일(자료 폴더의 `raw/tourapi/Kor/list.jsonl`)이다. 대표 이미지(`firstimage`)가
있는 곳만 한 줄씩 `{source_id, images: [{url, credit}]}` 로 쓴다. `source_id` 는 POI 적재의 관광공사 번호 꼴(`tour-<contentid>`).

- 주소는 https 로 바꾼다 — iOS 는 https 만 받고, 관광공사 사진 서버는 같은 주소를 https 로도 준다(표본 16 장 확인).
- `credit` 은 출처와 공공누리 유형 — 영어 화면에도 같은 문구다.

계획: docs/project/plans/poi-source.md §6-1.
"""

import argparse
import json
import os
import sys
from pathlib import Path

CREDIT = {
    "Type1": "한국관광공사 · 공공누리 제1유형(출처표시)",
    "Type3": "한국관광공사 · 공공누리 제3유형(출처표시·변경금지)",
}
CREDIT_UNKNOWN = "한국관광공사"


def https(url: str) -> str:
    return "https://" + url[len("http://") :] if url.startswith("http://") else url


def credit(cpyrht: str | None) -> str:
    return CREDIT.get((cpyrht or "").strip(), CREDIT_UNKNOWN)


def row(item: dict) -> dict | None:
    """목록 한 항목 → 적재 한 줄. 대표 이미지가 없으면 None."""
    url = (item.get("firstimage") or "").strip()
    cid = (item.get("contentid") or "").strip()
    if not url or not cid:
        return None
    return {
        "source_id": f"tour-{cid}",
        "images": [{"url": https(url), "credit": credit(item.get("cpyrhtDivCd"))}],
    }


def _resolve(path: str) -> Path:
    """`bazel run` 은 작업 폴더가 runfiles 라, 상대 경로는 사람이 명령을 친 곳 기준으로 푼다."""
    base = Path(os.environ.get("BUILD_WORKING_DIRECTORY", "."))
    return (base / Path(path).expanduser()).resolve()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument(
        "list_jsonl", help="관광공사 국문 목록 (raw/tourapi/Kor/list.jsonl)"
    )
    ap.add_argument("out", help="결과 파일 (예 tour_images.jsonl)")
    args = ap.parse_args(argv)

    src, out = _resolve(args.list_jsonl), _resolve(args.out)
    if not src.exists():
        print(f"오류: {src} 가 없습니다", file=sys.stderr)
        return 1
    if out.name.startswith("poi_"):
        print(
            "오류: 결과 이름이 poi_ 로 시작하면 POI 파일(poi_*.jsonl)로 잘못 읽힌다",
            file=sys.stderr,
        )
        return 1

    seen: set[str] = set()
    items = written = 0
    with src.open(encoding="utf-8") as f, out.open("w", encoding="utf-8") as w:
        for line in f:
            if not line.strip():
                continue
            items += 1
            r = row(json.loads(line))
            if r is None or r["source_id"] in seen:
                continue
            seen.add(r["source_id"])
            w.write(json.dumps(r, ensure_ascii=False) + "\n")
            written += 1
    print(f"목록 {items:,} 곳 중 대표 이미지 {written:,} 곳 → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
