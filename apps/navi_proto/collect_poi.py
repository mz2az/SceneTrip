#!/usr/bin/env python3
"""촬영지 주변의 음식점·카페를 모아 CSV 로 저장한다.

왜 격자가 아니라 촬영지 주변인가 — 이 데이터는 **루트 중간에 끼워 넣을 후보** 로
쓴다. 성지에서 멀리 떨어진 가게는 넣을 일이 없으므로, 성지를 중심으로 반경 안만
훑는 편이 적은 호출로 쓸모 있는 결과를 준다.

호출량은 `촬영지 수 × 키워드 수 × 페이지 수` 다. TMAP POI 검색은 Free 하루
20,000 건이므로 서울 92 곳 × 키워드 2 개 × 2 페이지 = 368 건이면 여유롭다.

촬영지가 늘어나면 반경이 겹친다. 그래서 **훑은 자리를 기록해 두고 이미 덮인 곳은
호출하기 전에 건너뛴다.** 결과를 id 로 거르는 것만으로는 절약이 안 된다 — 그때는
호출을 이미 쓴 뒤이기 때문이다.

지금 서울 92 곳으로 재보면 350 m 기준에서 24 곳(26%)이 건너뛰어진다. 촬영지가 한
동네에 몰리는 성격이라 늘어날수록 이 비율은 오른다.

    ./collect_poi.py                 # 서울 촬영지 주변
    ./collect_poi.py --busan         # 부산 기준점 주변도 함께
    ./collect_poi.py --radius 0.5    # 반경 500 m
    ./collect_poi.py --skip-within 0 # 겹침 무시하고 전부 훑는다
    ./collect_poi.py --refresh-days 30  # 30일 지난 자리는 다시 훑는다
    ./collect_poi.py --dry-run       # 호출 없이 몇 건 쓸지만 계산한다
"""

import argparse
import csv
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "local_data"

# 검색에 쓸 낱말. TMAP 은 업종 코드가 아니라 낱말로 찾으므로 둘을 따로 돈다.
KEYWORDS = ["음식점", "카페"]

# 부산에는 촬영지 시드가 없다(0 곳). 그래서 사람이 아는 중심지를 기준점으로 둔다.
BUSAN_ANCHORS = [
    ("해운대해수욕장", 35.15870, 129.16040),
    ("서면", 35.15780, 129.05930),
    ("남포동·자갈치", 35.09880, 129.03040),
    ("광안리해수욕장", 35.15320, 129.11860),
    ("감천문화마을", 35.09750, 129.01080),
    ("부산역", 35.11500, 129.04150),
    ("센텀시티", 35.16940, 129.13100),
    ("송정해수욕장", 35.17860, 129.19970),
    ("영도 흰여울문화마을", 35.07770, 129.04360),
    ("동래·온천장", 35.21360, 129.08400),
]

SEOUL = (37.42, 37.70, 126.76, 127.18)  # 남·북·서·동
BUSAN = (35.05, 35.40, 128.80, 129.30)

COVERAGE = OUT / "poi_coverage.json"  # 이미 훑은 자리


def dist_m(a, b):
    R = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    h = (
        math.sin((p2 - p1) / 2) ** 2
        + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    )
    return 2 * R * math.asin(math.sqrt(h))


def load_coverage():
    if not COVERAGE.exists():
        return []
    try:
        return json.loads(COVERAGE.read_text(encoding="utf-8"))
    except Exception:
        return []


def already_covered(lat, lng, radius_km, cov, skip_within_m, stale_before):
    """이 자리를 다시 훑을 필요가 있나.

    가까운 데를 이미 훑었고, 그때 반경이 지금보다 좁지 않고, 너무 오래되지 않았으면
    건너뛴다. 셋 중 하나라도 어긋나면 훑는다.
    """
    if skip_within_m <= 0:
        return None
    for c in cov:
        if c.get("at", "") < stale_before:
            continue  # 오래됐다 — 다시 훑는다
        if (c.get("radius_km") or 0) + 1e-9 < radius_km:
            continue  # 그때 더 좁게 봤다
        d = dist_m((lat, lng), (c["lat"], c["lng"]))
        if d <= skip_within_m:
            return c
    return None


def in_box(lat, lng, box):
    s, n, w, e = box
    return s < lat < n and w < lng < e


def load_env():
    f = ROOT / ".env"
    if not f.exists():
        return
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            v = v.strip().strip("'\"")
            if v:
                os.environ.setdefault(k.strip(), v)


def search(key, keyword, lat, lng, radius_km, page, count=200):
    q = urllib.parse.urlencode(
        {
            "version": 1,
            "searchKeyword": keyword,
            "centerLon": lng,
            "centerLat": lat,
            "radius": int(radius_km),
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
    with urllib.request.urlopen(req, timeout=25) as r:
        d = json.loads(r.read().decode("utf-8"))
    info = d.get("searchPoiInfo") or {}
    pois = ((info.get("pois") or {}).get("poi")) or []
    return pois, int(info.get("totalCount") or 0)


def anchors_seoul():
    f = OUT / "places.json"
    if not f.exists():
        return []
    seen, out = set(), []
    for grp in json.loads(f.read_text(encoding="utf-8")):
        for p in grp["places"]:
            k = (round(p["lat"], 6), round(p["lng"], 6))
            if k in seen or not in_box(p["lat"], p["lng"], SEOUL):
                continue
            seen.add(k)
            out.append((p["name"], p["lat"], p["lng"]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--radius",
        type=int,
        default=1,
        help="반경 km. **정수만 된다** — 0.7 을 주면 400 이 온다(실측)",
    )
    ap.add_argument("--pages", type=int, default=2, help="키워드당 페이지 수 (기본 2)")
    ap.add_argument("--busan", action="store_true", help="부산 기준점도 함께 훑는다")
    ap.add_argument("--seoul-only", action="store_true")
    ap.add_argument(
        "--skip-within",
        type=float,
        default=350,
        help="이 거리(m) 안을 이미 훑었으면 건너뛴다. 0 이면 전부 훑는다",
    )
    ap.add_argument(
        "--refresh-days", type=int, default=90, help="이만큼 지난 자리는 다시 훑는다"
    )
    ap.add_argument(
        "--dry-run", action="store_true", help="호출하지 않고 몇 건 쓸지만 센다"
    )
    args = ap.parse_args()

    load_env()
    key = os.environ.get("TMAP_APP_KEY", "")
    if not key:
        print("TMAP_APP_KEY 가 없다 (.env 확인)", file=sys.stderr)
        return 1

    spots = []
    for nm, la, lo in anchors_seoul():
        spots.append(("서울", nm, la, lo))
    if args.busan and not args.seoul_only:
        for nm, la, lo in BUSAN_ANCHORS:
            spots.append(("부산", nm, la, lo))

    # ── 이미 훑은 자리는 호출하기 전에 걸러낸다 ────────────────────────────
    cov = load_coverage()
    stale_before = (
        datetime.now(timezone.utc) - timedelta(days=args.refresh_days)
    ).isoformat()
    todo, skipped = [], []
    for sp in spots:
        hit = already_covered(
            sp[2], sp[3], args.radius, cov, args.skip_within, stale_before
        )
        (skipped if hit else todo).append(sp)

    budget = len(todo) * len(KEYWORDS) * args.pages
    print(
        f"기준점 {len(spots)}곳 중 훑을 곳 {len(todo)}곳 "
        f"· 이미 덮여서 건너뜀 {len(skipped)}곳"
        + (
            f" (기준 {args.skip_within:.0f} m)"
            if args.skip_within > 0
            else " (겹침 무시)"
        )
    )
    print(
        f"낱말 {len(KEYWORDS)}개 × {args.pages}쪽 = 최대 {budget}건 호출 "
        f"(TMAP POI 검색 Free 하루 20,000건)"
    )
    print(
        f"반경 {args.radius} km · 기록이 {args.refresh_days}일보다 오래되면 다시 훑는다\n"
    )
    if args.dry_run:
        for sp in skipped[:10]:
            print(f"    건너뜀  {sp[0]} {sp[1]}")
        if len(skipped) > 10:
            print(f"    … 외 {len(skipped) - 10}곳")
        print(f"\n  --dry-run 이라 호출하지 않았다. 최대 {budget}건 쓸 예정.")
        return 0
    spots = todo

    rows, seen_id, calls, failed = {}, set(), 0, 0
    for i, (region, anchor, la, lo) in enumerate(spots, 1):
        got = 0
        for kw in KEYWORDS:
            for page in range(1, args.pages + 1):
                try:
                    pois, _total = search(key, kw, la, lo, args.radius, page)
                    calls += 1
                except urllib.error.HTTPError as ex:
                    failed += 1
                    if ex.code == 429:
                        print("\n한도 초과(429) — 여기까지 저장하고 멈춘다")
                        return save(rows, cov, spots, args, calls, failed, skipped)
                    if failed <= 3:  # 조용히 삼키면 0건이 왜 나오는지 못 본다
                        body = ""
                        try:
                            body = ex.read().decode("utf-8", "replace")[:160]
                        except Exception:
                            pass
                        print(f"    ! HTTP {ex.code} {body}")
                    pois = []
                except Exception as ex:
                    failed += 1
                    if failed <= 3:
                        print(f"    ! {ex}")
                    pois = []
                for p in pois:
                    pid = str(p.get("id") or "")
                    if not pid or pid in seen_id:
                        continue
                    lat = float(p.get("frontLat") or p.get("noorLat") or 0)
                    lng = float(p.get("frontLon") or p.get("noorLon") or 0)
                    if not lat or not lng:
                        continue
                    box = SEOUL if region == "서울" else BUSAN
                    if not in_box(lat, lng, box):
                        continue
                    seen_id.add(pid)
                    got += 1
                    rows[pid] = {
                        "id": pid,
                        "name": p.get("name", ""),
                        "lat": lat,
                        "lng": lng,
                        "biz_upper": p.get("upperBizName", ""),
                        "biz_middle": p.get("middleBizName", ""),
                        "biz_lower": p.get("lowerBizName", ""),
                        "tel": p.get("telNo", ""),
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
                        "region": region,
                        "near_spot": anchor,
                        "keyword": kw,
                    }
                if len(pois) < 200:
                    break
                time.sleep(0.1)
        print(
            f"  [{i:>3}/{len(spots)}] {region} {anchor[:20]:<22} +{got:>3}건 "
            f"(누적 {len(rows)})",
            flush=True,
        )
        time.sleep(0.1)

    return save(rows, cov, spots, args, calls, failed, skipped)


def save(rows, cov, spots, args, calls, failed, skipped):
    # 훑은 자리를 기록한다 — 다음 실행이 이걸 보고 건너뛴다
    now = datetime.now(timezone.utc).isoformat()
    for region, anchor, la, lo in spots:
        cov.append(
            {
                "lat": la,
                "lng": lo,
                "radius_km": args.radius,
                "at": now,
                "region": region,
                "anchor": anchor,
                "keywords": KEYWORDS,
            }
        )
    OUT.mkdir(exist_ok=True)
    COVERAGE.write_text(json.dumps(cov, ensure_ascii=False, indent=1), encoding="utf-8")

    cols = [
        "id",
        "name",
        "lat",
        "lng",
        "biz_upper",
        "biz_middle",
        "biz_lower",
        "tel",
        "addr",
        "road",
        "region",
        "near_spot",
        "keyword",
    ]
    csv_path = OUT / "poi_food.csv"
    with csv_path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for r in rows.values():
            w.writerow(r)
    (OUT / "poi_food.json").write_text(
        json.dumps(list(rows.values()), ensure_ascii=False, indent=1), encoding="utf-8"
    )

    print(f"\n호출 {calls}건 (실패 {failed}) · 모은 곳 {len(rows)}건")
    print(f"  건너뛰어 아낀 호출 약 {len(skipped) * len(KEYWORDS) * args.pages}건")
    print(f"  커버리지 기록 {len(cov)}자리 → {COVERAGE}")
    print(f"  {csv_path}")
    print(f"  {OUT / 'poi_food.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
