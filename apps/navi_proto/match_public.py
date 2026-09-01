#!/usr/bin/env python3
"""티맵 POI 를 공공데이터(상가정보)와 대조해 **아직 영업 중인 곳**만 추린다.

티맵 POI 에는 날짜 필드가 없다 — `updateDt` 도 비어 있어 언제 것인지 알 수
없다(2026-08-26 실측, 40곳 전부 빈칸). 대신 **행동으로 드러난다.**

    티맵 음식점 405,146건 중 공공데이터에 있는 것    177,013건 (43.7%)

없는 56% 를 표본 조사하니 **88% 가 같은 자리에 다른 가게**였다 —

    「보는족족」  자리에 →「맛닭꼬송내역」   5 m
    「금산골」    자리에 →「이디야구로」     2 m

폐업하고 업종이 바뀐 것이다. 그래서 이 대조는 「없는 POI 를 더 찾는」 일이
아니라 **「죽은 POI 를 걷어내는」** 일이다.

원본 : 공공데이터포털 「소상공인시장진흥공단_상가(상권)정보」
       https://www.data.go.kr/data/15083033/fileData.do
       분기 갱신 · 2026-06-30 기준 · UTF-8(BOM) · **경도/위도가 WGS84 그대로**
       (localdata.go.kr 인허가 데이터와 달리 좌표 변환이 필요 없다)

    just poi-alive        (tools/just 에 레시피를 둔다)
"""

import collections
import csv
import json
import math
import re
import sys
import time
from pathlib import Path

csv.field_size_limit(1 << 24)
ROOT = Path(__file__).resolve().parent
CSV_DIR = ROOT / "local_data" / "public_data" / "csv"
OUT = ROOT / "local_data" / "poi_alive.jsonl"

CELL = 0.003  # 격자 한 칸 ≒ 300 m. 9칸이면 250 m 반경이 다 걸린다
NEAR_M = 60  # 여기까지는 이름이 좀 달라도 같은 곳으로 본다
FAR_M = 250  # 여기까지는 **이름이 길고 겹칠 때만**
# 어느 갈래를 어느 대분류와 맞출 것인가.
#
# **명소·교통은 넣지 않는다.** 해수욕장·산·공항은 애초에 상가가 아니라
# 「없음」이 정상인데, 0 을 찍으면 백엔드가 「없는 곳」으로 읽는다. 근거가
# 없으면 빈칸으로 두는 편이 정직하다(백화점 사고와 같은 함정).
LANES = {
    "food": ("local_data/poi_food.jsonl", "음식", "local_data/poi_alive.jsonl"),
    "stay": ("local_data/poi_stay.jsonl", "숙박", "local_data/poi_alive_stay.jsonl"),
}


def name_key(s):
    """server.py 의 것과 같아야 한다. 고치면 양쪽을 함께 고칠 것."""
    s = re.sub(r"<[^>]+>", "", s or "")
    s = re.sub(r"\[[^\]]*\]", "", s)
    return re.sub(r"[^\w가-힣]", "", s).lower()


def haversine_m(a, b, c, d):
    R = 6371000.0
    p, q = math.radians(a), math.radians(c)
    dp, dq = q - p, math.radians(d - b)
    h = math.sin(dp / 2) ** 2 + math.cos(p) * math.cos(q) * math.sin(dq / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def load_grid(big_cat):
    """공공데이터를 격자에 올린다. 840,255곳이 약 1.1 GB 가 아니라 튜플로 들어간다."""
    grid = collections.defaultdict(list)
    n = 0
    files = sorted(CSV_DIR.glob("*.csv"))
    if not files:
        sys.exit(
            f"공공데이터 CSV 가 없다: {CSV_DIR}\n"
            f"  data.go.kr/data/15083033 에서 받아 zip 을 풀어 둘 것"
        )
    for f in files:
        with f.open(encoding="utf-8-sig", newline="") as fh:
            for row in csv.DictReader(fh):
                if row.get("상권업종대분류명") != big_cat:
                    continue
                try:
                    la, lo = float(row["위도"]), float(row["경도"])
                except (TypeError, ValueError, KeyError):
                    continue
                nm = row.get("상호명") or ""
                grid[(int(la / CELL), int(lo / CELL))].append(
                    (name_key(nm), la, lo, nm, row.get("상권업종소분류명") or "")
                )
                n += 1
    return grid, n


def find(grid, la, lo, tname):
    """가장 가까운 **같은 이름**의 가게. 없으면 None.

    **거리 한 줄로 자르면 백화점이 통째로 날아간다.** 60 m 로 잘랐더니
    더현대서울 입점 매장 181곳이 전부 빠졌다(2026-08-26 실측). 공공데이터는
    몰·백화점 매장을 **건물 좌표 하나**로 등록하는데, 티맵은 매장마다 좌표를
    따로 준다. 그 차이가 95 m 였다.

    그래서 두 층으로 나눈다 — 가까우면 느슨하게, 멀면 이름이 길고 겹칠 때만.
    길이 조건이 「커피」 사건(2026-08-25)을 막는다. 두세 글자짜리 이름은
    아무 데나 붙는다.
    """
    key = name_key(tname)
    best = None
    ci, cj = int(la / CELL), int(lo / CELL)
    for i in (ci - 1, ci, ci + 1):
        for j in (cj - 1, cj, cj + 1):
            for k, pla, plo, nm, cat in grid.get((i, j), ()):
                d = haversine_m(la, lo, pla, plo)
                if d > FAR_M:
                    continue
                exact = k == key
                part = bool(k) and bool(key) and (k in key or key in k)
                if d <= NEAR_M:
                    ok = exact or (part and len(key) > 3)
                else:
                    ok = exact or (part and len(key) >= 6 and len(k) >= 6)
                if ok and (best is None or d < best[0]):
                    best = (d, nm, cat, exact)
    return best


def main():
    lane = sys.argv[1] if len(sys.argv) > 1 else "food"
    if lane not in LANES:
        sys.exit(f"갈래는 {' | '.join(LANES)} 중 하나 (받은 것: {lane})")
    rel, big_cat, out_rel = LANES[lane]
    out_path = ROOT / out_rel

    t0 = time.time()
    grid, n_pub = load_grid(big_cat)
    print(
        f"공공데이터 {big_cat}  {n_pub:,}곳 · 격자 {len(grid):,}칸 "
        f"({time.time() - t0:.0f}초)",
        flush=True,
    )

    src = ROOT / rel
    by_region = collections.defaultdict(collections.Counter)
    n = 0
    with out_path.open("w", encoding="utf-8") as out:
        for line in src.open(encoding="utf-8"):
            try:
                d = json.loads(line)
                la, lo = float(d["lat"]), float(d["lng"])
            except (ValueError, KeyError, TypeError):
                continue
            n += 1
            r = find(grid, la, lo, d["name"])
            by_region[d.get("region") or "?"]["있음" if r else "없음"] += 1
            if r:
                out.write(
                    json.dumps(
                        {"id": d["id"], "pub": r[1], "d": round(r[0], 1), "cat": r[2]},
                        ensure_ascii=False,
                    )
                    + "\n"
                )

    tot = collections.Counter()
    print(f"\n티맵 {lane} {n:,}건 · {time.time() - t0:.0f}초\n")
    print(f"  {'지역':8} {'살아있음':>10} {'없음':>10}   생존율")
    for reg in sorted(by_region, key=lambda r: -sum(by_region[r].values())):
        c = by_region[reg]
        s_ = c["있음"] + c["없음"]
        tot.update(c)
        if s_ >= 1500:
            print(
                f"  {reg:8} {c['있음']:>10,} {c['없음']:>10,}   {c['있음'] / s_ * 100:>5.1f}%"
            )
    s_ = tot["있음"] + tot["없음"]
    print(f"  {'―' * 42}")
    print(
        f"  {'전국':8} {tot['있음']:>10,} {tot['없음']:>10,}   {tot['있음'] / s_ * 100:>5.1f}%"
    )
    print(f"\n→ {out_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
