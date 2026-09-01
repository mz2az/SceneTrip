#!/usr/bin/env python3
"""지역을 격자로 훑어 POI 를 모은다. 상한에 걸리는 칸은 넷으로 쪼갠다.

왜 격자인가 — TMAP POI 검색은 **한 질의로 9,900건까지만** 꺼낼 수 있다
(count 150 × page 66, 실측). 전국 「펜션」 4만 건은 한 번에 못 받는다. 그래서 지역을
칸으로 나눠 각각 받는다.

**칸을 미리 세어 보고 쪼갠다.** count=1 로 부르면 총 건수만 1 호출로 나온다. 그 값이
상한에 가까우면 칸을 넷으로 나눈다. 이렇게 하지 않으면 **가장 밀집한 칸이 조용히 잘려
나간다** — 하필 우리한테 제일 중요한 동네다.

`radius` 는 **정수 km 만 받는다**(0.7 을 주면 400 이 온다, 실측). 칸을 원으로 덮어야
하므로 대각선의 절반을 올림해서 쓴다.
"""

import argparse
import csv
import json
import math
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "local_data"

# 네 갈래가 같은 필드를 갖게 맞춘다. 예전에는 수집 방식마다 필드가 달라
# (반경 방식은 near_spot·biz_lower, 격자 방식은 area·city·kind) 합칠 때 손이 갔다.
#
# **좌표는 두 벌을 다 저장한다** (2026-08-24 추가).
#   lat/lng             noorLat  건물 좌표     -- 지도에 핀을 찍는 자리
#   front_lat/front_lng frontLat 도로 진입점  -- 길안내가 끝나는 자리
# 둘은 다른 뜻이고 쓰임도 다르다. 8/12 수집 때 frontLat 만 저장하고 noorLat 을
# 버려서 47만 건을 다시 받게 됐다 -- **받은 것을 마음대로 버리지 않는다.**
FIELDS = [
    "id",
    "name",
    "lat",
    "lng",
    "front_lat",
    "front_lng",
    "addr",
    "road",
    "tel",
    "kind",
    "biz_upper",
    "biz_middle",
    "biz_lower",
    "keyword",
    "region",
    "city",
    "area",
    "near_spot",
]


def write_rows(path, rows):
    """한 줄에 하나씩 쓴다. 필드를 FIELDS 로 맞춰 갈래끼리 모양이 같게 한다."""
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(
                json.dumps(
                    {k: r.get(k, "") for k in FIELDS},
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
                + "\n"
            )


COUNT_MAX, PAGE_MAX, CAP = 150, 66, 9900
# `radius` 는 **최대 33 km** 다. 34 를 주면 HTTP 400 이 온다(실측). 문서에 없어서
# 처음에 0.5° 칸(대각선 71 km → 반지름 36 km)으로 잡았다가 **거의 모든 칸이 오류로
# 버려졌다.** 칸 크기는 이 한계에서 거꾸로 정해야 한다.
RADIUS_MAX = 33

AREAS = {
    "전국": (33.0, 125.0, 38.65, 129.65),
    "서울": (37.41, 126.76, 37.71, 127.19),
    "부산": (35.03, 128.75, 35.39, 129.31),
    "경주": (35.63, 129.05, 36.03, 129.55),
    "강릉": (37.63, 128.65, 37.92, 129.05),
    # 제주 — 숙박 5,541·명소 827 은 전국 수집에 들어왔는데 **음식이 0건**이었다
    # (음식은 4개 도시만 훑었기 때문). 2026-08-25 에 추가. 218회·2분이면 끝난다.
    "제주": (33.10, 126.10, 33.60, 126.98),
}
GROUPS = {
    "숙박": [
        ("호텔", {"호텔"}),
        ("모텔", {"모텔/여관"}),
        ("펜션", {"펜션"}),
        ("리조트", {"콘도/리조트"}),
        ("게스트하우스", {"게스트하우스", "전통숙소"}),
    ],
    "음식": [("음식점", None), ("카페", None)],
    # 관광 — **관광객이 실제로 갈 곳만** 넣는다. 공원 4만·대교 3.5만은 대부분 동네
    # 공원과 이름 없는 교량이라 화면에 잡동사니만 늘린다(8/10 의 400건 상한 문제와
    # 같은 자리다). 필요해지면 그때 30분이면 받는다.
    "관광": [
        ("문화유적지", {"문화유적지"}),
        ("박물관", {"박물관/기념관"}),
        ("미술관", {"미술관"}),
        ("해수욕장", {"해수욕장"}),
        ("전망대", {"전망대", "탑"}),
        ("테마파크", {"테마파크", "관광농원"}),
        ("관광명소", {"관광명소기타", "문화유적지", "전망대"}),
        ("절", {"절"}),
    ],
}


def load_key():
    f = ROOT / ".env"
    for line in f.read_text(encoding="utf-8").splitlines() if f.exists() else []:
        if line.strip().startswith("TMAP_APP_KEY="):
            return line.split("=", 1)[1].strip()
    return ""


def km_per_deg(lat):
    return 111.32, 111.32 * math.cos(math.radians(lat))


def call(key, kw, lat, lng, radius, page, count):
    q = urllib.parse.urlencode(
        {
            "version": 1,
            "searchKeyword": kw,
            "centerLon": lng,
            "centerLat": lat,
            "radius": int(radius),
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
    return (((info.get("pois") or {}).get("poi")) or []), int(
        info.get("totalCount") or 0
    )


class Runner:
    def __init__(self, key, sleep):
        self.key, self.sleep, self.calls, self.errs = key, sleep, 0, []
        self.skipped = 0

    def go(self, kw, lat, lng, radius, page=1, count=COUNT_MAX):
        for attempt in range(3):
            try:
                r = call(self.key, kw, lat, lng, radius, page, count)
                self.calls += 1
                time.sleep(self.sleep)
                return r
            except urllib.error.HTTPError as e:
                self.calls += 1
                if e.code in (429, 500, 503) and attempt < 2:
                    time.sleep(2 + attempt * 3)
                    continue
                self.errs.append(f"{kw} r{radius} p{page}: HTTP {e.code}")
                return [], -1
            except Exception as e:
                if attempt < 2:
                    time.sleep(2)
                    continue
                self.errs.append(f"{kw}: {e}")
                return [], -1
        return [], -1


def cells(box, step_deg):
    s, w, n, e = box
    lat = s
    while lat < n:
        lng = w
        while lng < e:
            yield (lat, lng, min(lat + step_deg, n), min(lng + step_deg, e))
            lng += step_deg
        lat += step_deg


def cover(cell):
    """칸을 덮는 원의 중심과 반지름(정수 km).

    반지름이 33 km 를 넘으면 TMAP 이 400 을 준다. 넘으면 **덮지 못한다** 는 뜻이므로
    호출하지 말고 칸을 더 쪼개야 한다 — 그래서 넘는지 여부를 함께 돌려준다.
    """
    s, w, n, e = cell
    clat, clng = (s + n) / 2, (w + e) / 2
    kl, kn = km_per_deg(clat)
    h, v = (e - w) * kn, (n - s) * kl
    r = max(1, math.ceil(math.hypot(h, v) / 2))
    return clat, clng, min(r, RADIUS_MAX), r > RADIUS_MAX


class Cover:
    """훑은 자리를 남겨 **다음에 이어받는다.**

    이미 받은 칸을 또 부르면 호출만 버린다. 칸마다 결과를 기록해 두고, 다시 돌릴 때
    기록이 있으면 건너뛴다. 중간에 끊겨도 거기서부터 이어진다.

    두 가지를 기록한다 —
      leaf   실제로 다 받은 칸
      split  상한에 걸려 넷으로 쪼갠 칸 (다시 세어 볼 필요가 없다)
    """

    def __init__(self, path, refresh_days=0):
        self.path = path
        self.stale = time.time() - refresh_days * 86400 if refresh_days else 0
        try:
            self.d = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            self.d = {}

    @staticmethod
    def key(kw, cell):
        return f"{kw}|" + ",".join(f"{x:.4f}" for x in cell)

    def get(self, kw, cell):
        v = self.d.get(self.key(kw, cell))
        if not v:
            return None
        if self.stale and v.get("at", 0) < self.stale:
            return None
        return v

    def put(self, kw, cell, kind, count=0):
        self.d[self.key(kw, cell)] = {
            "kind": kind,
            "count": count,
            "at": int(time.time()),
        }

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.d, ensure_ascii=False), encoding="utf-8")


def norm(v):
    """업종 이름을 비교할 수 있게 다듬는다.

    **TMAP 은 슬래시를 두 번 이스케이프해서 보낸다.** 원본이 `"모텔\\\\\\/여관"` 이라
    JSON 을 파싱하면 `모텔\\/여관` 처럼 **역슬래시가 문자로 남는다.** 그래서
    `모텔/여관` 과 비교하면 영영 안 맞는다 — 실제로 모텔·리조트가 **0건** 으로
    수집됐다(2026-08-12). 오류가 아니라 **조용히 비어 있어서** 알아채기 어려웠다.
    """
    return (v or "").replace("\\", "")


def row(p, kw, area):
    # **noorLat 이 건물 좌표, frontLat 은 도로 진입점이다.** 거꾸로 짜여 있었다
    # — TMAP 웹 자체가 검색 결과 핀을 noorLat 로 찍는데 우리는 frontLat 을
    # 우선했다. 실측(2026-08-24, 「탄백」 등 15곳 표본) — 중앙값 8.2 m,
    # 최대 50.9 m 어긋난다. 47만 건 전체에 걸린 문제다.
    return {
        "id": p.get("id", ""),
        "name": p.get("name", ""),
        "lat": p.get("noorLat") or p.get("frontLat", ""),
        "lng": p.get("noorLon") or p.get("frontLon", ""),
        "front_lat": p.get("frontLat", ""),
        "front_lng": p.get("frontLon", ""),
        "kind": norm(p.get("lowerBizName")),
        "biz_upper": norm(p.get("upperBizName")),
        "biz_middle": norm(p.get("middleBizName")),
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
        "area": area,
    }


def split4(cell):
    s, w, n, e = cell
    ml, mn = (s + n) / 2, (w + e) / 2
    return ((s, w, ml, mn), (s, mn, ml, e), (ml, w, n, mn), (ml, mn, n, e))


def sweep(run, kw, keep, cell, area, seen, rows, cov, depth=0):
    was = cov.get(kw, cell)
    if was and was["kind"] == "leaf":
        run.skipped += 1
        return  # 이미 받았다 — 부르지 않는다
    if was and was["kind"] == "split":
        for sub in split4(cell):  # 세어 볼 필요 없이 바로 쪼갠다
            sweep(run, kw, keep, sub, area, seen, rows, cov, depth + 1)
        return

    clat, clng, rad, too_big = cover(cell)
    if too_big and depth < 6:
        # 원으로 덮을 수 없는 칸이다. 세어 보지 말고 바로 쪼갠다.
        cov.put(kw, cell, "split", -1)
        for sub in split4(cell):
            sweep(run, kw, keep, sub, area, seen, rows, cov, depth + 1)
        return
    _, total = run.go(kw, clat, clng, rad, 1, 1)
    if total < 0:
        return  # 오류 — 기록하지 않는다(다음에 다시 시도)
    if total == 0:
        cov.put(kw, cell, "leaf", 0)
        return
    if total > CAP and depth < 6:  # 상한에 걸리면 넷으로 쪼갠다
        cov.put(kw, cell, "split", total)
        for sub in split4(cell):
            sweep(run, kw, keep, sub, area, seen, rows, cov, depth + 1)
        return
    for page in range(1, PAGE_MAX + 1):
        pois, _ = run.go(kw, clat, clng, rad, page, COUNT_MAX)
        if not pois:
            break
        for p in pois:
            if keep and norm(p.get("lowerBizName")) not in keep:
                continue
            pid = str(p.get("id", ""))
            if pid in seen:
                continue
            seen[pid] = True
            rows.append(row(p, kw, area))
        if len(pois) < COUNT_MAX:
            break
    cov.put(kw, cell, "leaf", total)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", choices=list(GROUPS), required=True)
    ap.add_argument("--areas", nargs="+", required=True)
    ap.add_argument(
        "--step",
        type=float,
        default=0.4,
        help="첫 격자 크기(도). 0.4 면 반지름이 33 km 안에 들어온다",
    )
    ap.add_argument("--sleep", type=float, default=0.12)
    ap.add_argument("--out", required=True)
    ap.add_argument(
        "--refresh-days",
        type=int,
        default=0,
        help="이 날짜보다 오래된 칸은 다시 받는다 (0 이면 영구히 건너뛴다)",
    )
    ap.add_argument("--fresh", action="store_true", help="기록을 무시하고 처음부터")
    ap.add_argument(
        "--tag",
        default="",
        help="붙이면 기존 파일을 안 건드리고 옆에 새로 받는다 "
        "(예: --tag noor -> poi_food.noor.jsonl)",
    )
    a = ap.parse_args()

    key = load_key()
    if not key:
        print("TMAP_APP_KEY 가 없다")
        sys.exit(1)
    run = Runner(key, a.sleep)
    # **갈래마다 파일 하나.** 이름을 마음대로 지으면 같은 갈래가 두 파일로 갈라져
    # 서로의 커버리지를 못 읽는다 — 2026-08-12 에 `poi_food` 와 `poi_food_cities`
    # 로 따로 받아 14,807건이 통째로 겹쳤다. 그래서 이름을 넷으로 묶어 둔다.
    CATEGORIES = {"poi_food", "poi_stay", "poi_sight", "poi_transit"}
    if a.out not in CATEGORIES:
        print(f"--out 은 {sorted(CATEGORIES)} 중 하나여야 한다. 받은 값: {a.out!r}")
        print("  갈래를 늘리려면 이 목록에 먼저 넣어라. 새 이름으로 받으면 갈라진다.")
        sys.exit(1)
    # `--tag` 를 주면 **기존 파일을 건드리지 않고** 옆에 새로 받는다
    # (예: `--tag noor` -> poi_food.noor.jsonl). 8/24 에 좌표 필드를 고쳐
    # 다시 받을 때 만들었다 — 이미 받아 둔 47만 건도 그 자체로 자료이므로
    # 덮어쓰지 않는다.
    stem = f"{a.out}.{a.tag}" if a.tag else a.out
    out_path = OUT / f"{stem}.jsonl"
    cov_path = OUT / f"{stem}_coverage.json"
    if a.fresh:
        for f in (out_path, cov_path):
            if f.exists():
                f.unlink()

    # 이전에 받은 것을 이어받는다 — 같은 것을 또 부르지 않는다
    # JSONL 로 둔다 — 한 줄에 하나. 통째로 읽으면 37.8만 건에서 1.2 GB 를 먹는다
    # (서버는 한 줄씩 흘려보내 199 MB 로 읽는다).
    rows = []
    if out_path.exists():
        with out_path.open(encoding="utf-8") as fh:
            for line in fh:
                if line.strip():
                    try:
                        rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
    seen = {str(r.get("id", "")): True for r in rows}
    cov = Cover(cov_path, a.refresh_days)
    if rows:
        print(f"이어받는다 — 이미 {len(rows):,}건 · 훑은 칸 {len(cov.d):,}개\n")
    t0 = time.time()
    for area in a.areas:
        box = AREAS[area]
        for kw, keep in GROUPS[a.group]:
            n0 = len(rows)
            for cell in cells(box, a.step):
                sweep(run, kw, keep, cell, area, seen, rows, cov)
            cov.save()  # 키워드 하나가 끝날 때마다 남긴다
            out_path.parent.mkdir(parents=True, exist_ok=True)
            write_rows(out_path, rows)
            print(
                f"  {area} · {kw:<8} +{len(rows) - n0:>6,}건  "
                f"(누적 {len(rows):,} · 호출 {run.calls:,} · 건너뜀 {run.skipped:,})",
                flush=True,
            )

    cov.save()
    write_rows(out_path, rows)
    if rows:
        with (OUT / f"{a.out}.csv").open("w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
            w.writeheader()
            w.writerows({k: r.get(k, "") for k in FIELDS} for r in rows)
    from collections import Counter

    print(
        f"\n총 {len(rows):,}건 · 호출 {run.calls:,} · 건너뛴 칸 {run.skipped:,} · "
        f"{(time.time() - t0) / 60:.1f}분"
    )
    for k, v in Counter(r["kind"] for r in rows).most_common(12):
        print(f"   {k:<12} {v:>7,}")
    if run.errs:
        print(f"오류 {len(run.errs)}건:", run.errs[:3])


if __name__ == "__main__":
    main()
