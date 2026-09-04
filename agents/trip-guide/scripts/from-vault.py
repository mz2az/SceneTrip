#!/usr/bin/env python3
"""볼트의 수집 CSV 를 v6.sql 이 먹을 수 있는 모양으로 바꾼다.

`just seed <경로>` 는 CSV 를 그대로 `seed_staging` 으로 COPY 한다. 그래서 입력은
**컬럼이 정확히 25 개, 순서까지 같아야** 한다. 볼트의 수집 산출물은 팀원마다
컬럼이 달라(선별 등급·중복 표시 같은 작업용 컬럼이 붙는다) 그대로는 안 들어간다.
이 스크립트가 그 사이를 메운다.

    just seed-convert <볼트 CSV> <출력 CSV>

## 무엇을 걸러 내나

v6.sql 이 장소를 합치는 기준이 `naver_place_url` 이고(`DISTINCT ON`), 좌표는
`place_latitude::DOUBLE PRECISION` 으로 형변환된다. 그래서 둘 중 하나라도 비면
시드가 깨지거나 엉뚱하게 합쳐진다 — **네이버 URL 이 빈 행이 여럿이면 그것들이
전부 한 장소로 뭉친다.** 그 앞에서 미리 잘라 낸다.

수집자가 붙여 둔 품질 표시도 여기서 존중한다. `dup_of` 는 "이 행은 저 행과 같은
장소다" 라는 판정이고, `is_overseas` 는 해외 촬영분이다.

거른 행은 버리는 것이 아니라 볼트 원본에 그대로 남아 있다. 이 스크립트는 원본을
읽기만 한다.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path

# v6.sql 의 seed_staging 이 기대하는 컬럼과 순서. 이 목록을 바꾸려면 v6.sql 을
# 함께 고쳐야 한다 — 한쪽만 고치면 COPY 가 조용히 어긋난 열에 값을 넣는다.
STAGING_COLUMNS = [
    "id",
    "title",
    "title_aliases",
    "title_category",
    "title_network",
    "title_year",
    "title_genre",
    "title_cast",
    "place_name",
    "place_type",
    "place_address",
    "place_latitude",
    "place_longitude",
    "place_image_url",
    "place_naver_url",
    "scene_description",
    "scene_image_url",
    "source_url",
    "last_updated",
    "famous_rank",
    "recent_rank",
    "audience_acc",
    "award",
    "director",
    "poster_url",
]


def reject_reason(row: dict[str, str], keep_overseas: bool) -> str | None:
    """이 행을 버려야 하면 이유를, 넣어도 되면 None 을 돌려준다."""
    if (row.get("dup_of") or "").strip():
        return "중복으로 표시된 행 (dup_of)"
    if not keep_overseas and (row.get("is_overseas") or "").strip().upper() == "Y":
        return "해외 촬영지"
    if not (row.get("place_latitude") or "").strip():
        return "좌표 없음 — geom 을 만들 수 없다"
    if not (row.get("place_naver_url") or "").strip():
        return "네이버 URL 없음 — 장소 합치기 기준이 비어 다른 장소와 뭉친다"
    if not (row.get("place_name") or "").strip():
        return "장소 이름 없음"
    return None


def convert(
    src: Path, dst: Path, *, keep_overseas: bool = False, selected_only: bool = False
) -> None:
    dropped: Counter[str] = Counter()
    kept = 0
    seen_urls: set[str] = set()

    with (
        src.open(encoding="utf-8-sig", newline="") as fin,
        dst.open("w", encoding="utf-8", newline="") as fout,
    ):
        writer = csv.DictWriter(fout, fieldnames=STAGING_COLUMNS, extrasaction="ignore")
        writer.writeheader()

        for row in csv.DictReader(fin):
            if selected_only and (row.get("is_selected") or "").strip().upper() != "Y":
                dropped["엄선 목록이 아님 (is_selected≠Y)"] += 1
                continue
            reason = reject_reason(row, keep_overseas)
            if reason:
                dropped[reason] += 1
                continue

            # 수집 CSV 에 없는 컬럼은 빈 값으로 채운다. v6.sql 이 NULLIF 로 걸러
            # 주므로 빈 문자열이 NULL 이 된다. 없는 값을 지어내지 않는다.
            out = {col: (row.get(col) or "").strip() for col in STAGING_COLUMNS}
            writer.writerow(out)
            seen_urls.add(out["place_naver_url"])
            kept += 1

    total = kept + sum(dropped.values())
    print(f"읽은 행      {total:6,}")
    for reason, count in dropped.most_common():
        print(f"  버림       {count:6,}  {reason}")
    print(f"넣은 행      {kept:6,}")
    print(
        f"장소 수      {len(seen_urls):6,}  (네이버 URL 로 합친 뒤. DB 의 place 행수다)"
    )
    print(f"쓴 곳        {dst}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="볼트 수집 CSV → v6.sql 시드 CSV")
    parser.add_argument("source", type=Path, help="볼트의 수집 CSV")
    parser.add_argument("output", type=Path, help="만들어 낼 시드 CSV")
    parser.add_argument(
        "--keep-overseas", action="store_true", help="해외 촬영지도 넣는다"
    )
    parser.add_argument(
        "--selected-only",
        action="store_true",
        help="is_selected=Y 인 엄선 목록만 넣는다",
    )
    args = parser.parse_args(argv)

    if not args.source.is_file():
        print(f"입력 CSV 가 없다: {args.source}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    convert(
        args.source,
        args.output,
        keep_overseas=args.keep_overseas,
        selected_only=args.selected_only,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
