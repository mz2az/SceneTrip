#!/usr/bin/env python3
"""전국 교통 거점(공항·버스터미널·지하철역·기차역)을 모아 CSV·JSON 으로 남긴다.

왜 따로 만드나 — 음식점·카페(`collect_poi.py`)는 **촬영지 주변** 만 훑으면 되지만,
교통 거점은 **여정의 시작·끝점** 이라 전국이 다 필요하다. 공항에 내려서 성지로 가고
터미널에서 다음 도시로 넘어간다.

키워드만으로 거르면 안 된다 — 「공항」으로 검색하면 4,319건이 나오는데 공항동·공항로의
가게가 잔뜩 섞인다. TMAP 이 주는 **업종 분류** 로 한 번 더 거른다:

    교통편의 > 교통시설 > 공항 / 버스터미널 / 지하철역 / 기차역

전국 검색(radius=0)은 페이지를 넘겨 받는다. 한 질의로 꺼낼 수 있는 상한은
count 150 × page 66 = **9,900건** 이다(실측). 교통 거점은 그보다 훨씬 적어 상한에
걸리지 않는다.
"""

import argparse
import csv
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "local_data"
PAGE_MAX, COUNT_MAX = 66, 150

# (검색어, 남길 lowerBizName 들)
TARGETS = [
    ("공항", {"공항"}),
    ("버스터미널", {"버스터미널"}),
    ("고속버스터미널", {"버스터미널"}),
    ("시외버스터미널", {"버스터미널"}),
    ("지하철역", {"지하철역"}),
    ("기차역", {"기차역"}),
]


def load_key():
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("TMAP_APP_KEY="):
                return line.split("=", 1)[1].strip()
    return ""


def search(key, kw, page, count):
    q = urllib.parse.urlencode(
        {
            "version": 1,
            "searchKeyword": kw,
            "centerLon": 127.8,
            "centerLat": 36.5,
            "radius": 0,
            "searchType": "all",
            "page": page,
            "count": count,
            "reqCoordType": "WGS84GEO",
            "resCoordType": "WGS84GEO",
            "multiPoint": "N",
        }
    )
    req = urllib.request.Request(
        "https://apis.openapi.sk.com/tmap/pois?" + q,
        headers={"appKey": key, "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.loads(r.read().decode("utf-8"))
    info = d.get("searchPoiInfo") or {}
    pois = ((info.get("pois") or {}).get("poi")) or []
    return pois, int(info.get("totalCount") or 0)


def row(p, kw):
    # noorLat 이 건물 좌표, frontLat 은 도로 진입점이다. collect_area.py 와
    # 같은 버그 — 2026-08-24 에 발견해 같이 고친다.
    return {
        "id": p.get("id", ""),
        "name": p.get("name", ""),
        "lat": p.get("noorLat") or p.get("frontLat", ""),
        "lng": p.get("noorLon") or p.get("frontLon", ""),
        # 도로 진입점도 함께 남긴다 — 길안내가 끝나는 자리라 그 자체로 쓸모가 있다
        "front_lat": p.get("frontLat", ""),
        "front_lng": p.get("frontLon", ""),
        "kind": p.get("lowerBizName", ""),
        "biz_upper": p.get("upperBizName", ""),
        "biz_middle": p.get("middleBizName", ""),
        "tel": p.get("telNo", ""),
        "region": p.get("upperAddrName", ""),
        "city": p.get("middleAddrName", ""),
        "addr": " ".join(
            x
            for x in (
                p.get("upperAddrName"),
                p.get("middleAddrName"),
                p.get("lowerAddrName"),
            )
            if x
        ),
        "road": p.get("roadName", ""),
        "keyword": kw,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sleep", type=float, default=0.25, help="호출 사이 쉬는 시간(초)")
    ap.add_argument("--dry-run", action="store_true", help="건수만 세고 받지는 않는다")
    ap.add_argument(
        "--tag", default="", help="붙이면 기존 파일을 안 건드리고 옆에 새로 받는다"
    )
    a = ap.parse_args()

    key = load_key()
    if not key:
        print("TMAP_APP_KEY 가 없다 (.env)")
        sys.exit(1)

    seen, rows, calls, errs = {}, [], 0, []
    for kw, keep in TARGETS:
        try:
            _, total = search(key, kw, 1, 1)
            calls += 1
        except Exception as e:
            errs.append(f"{kw}: {e}")
            continue
        print(f"── {kw} — 검색 결과 {total:,}건 (업종으로 거른다)")
        if a.dry_run:
            continue
        got = 0
        for page in range(1, PAGE_MAX + 1):
            try:
                pois, _ = search(key, kw, page, COUNT_MAX)
                calls += 1
            except Exception as e:
                errs.append(f"{kw} p{page}: {e}")
                break
            if not pois:
                break
            for p in pois:
                if p.get("lowerBizName") not in keep:
                    continue
                pid = str(p.get("id", ""))
                if pid in seen:
                    continue
                seen[pid] = True
                rows.append(row(p, kw))
                got += 1
            if len(pois) < COUNT_MAX:
                break
            time.sleep(a.sleep)
        print(f"   → 남긴 것 {got:,}건 (누적 {len(rows):,})")

    if a.dry_run:
        print(f"\n호출 {calls}건만 썼다 (건수 확인)")
        return

    OUT.mkdir(parents=True, exist_ok=True)
    # `collect_area.py` 와 **같은 모양·같은 이름** 으로 쓴다. 갈래마다 파일 하나다.
    # 여기서 `.json` 으로 쓰면 서버가 안 읽고, 갈래가 둘로 갈라진다.
    from collect_area import FIELDS, write_rows

    # `--tag` 를 주면 기존 파일을 안 건드리고 옆에 새로 받는다 (collect_area 와 같다)
    stem = f"poi_transit.{a.tag}" if a.tag else "poi_transit"
    # 이미 받아 둔 것과 합친다 — 같은 id 는 한 번만
    path = OUT / f"{stem}.jsonl"
    merged, seen = [], set()
    if path.exists():
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                if line.strip():
                    r = json.loads(line)
                    if str(r.get("id")) not in seen:
                        seen.add(str(r.get("id")))
                        merged.append(r)
    dup = 0
    for r in rows:
        if str(r.get("id")) in seen:
            dup += 1
            continue
        seen.add(str(r.get("id")))
        merged.append(r)
    if dup:
        print(f"   이미 갖고 있던 {dup:,}건은 겹쳐 쓰지 않았다")
    rows = merged
    write_rows(path, rows)
    with (OUT / f"{stem}.csv").open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        w.writeheader()
        w.writerows({k: r.get(k, "") for k in FIELDS} for r in rows)

    from collections import Counter

    c = Counter(r["kind"] for r in rows)
    print(f"\n총 {len(rows):,}건 · 호출 {calls}건")
    for k, v in c.most_common():
        print(f"   {k:<10} {v:>6,}")
    if errs:
        print(f"\n오류 {len(errs)}건:")
        for e in errs[:5]:
            print("   ", e)


if __name__ == "__main__":
    main()
