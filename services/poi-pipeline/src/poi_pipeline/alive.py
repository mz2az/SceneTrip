"""티맵 POI 를 공공데이터(상가정보)와 대조해 **아직 영업 중인 곳**만 추린다.

티맵 POI 에는 날짜가 없다(`updateDt` 도 빈칸, 40곳 실측). 대신 행동으로 드러난다 —
음식 405,146건 중 공공데이터에 있는 것 177,013건(43.7%). 없는 쪽 표본의 88% 가 같은
자리에 다른 가게였다. 그래서 이 대조는 「없는 POI 를 더 찾는」 일이 아니라 **「죽은 POI 를
걷어내는」** 일이다.

원본: 공공데이터포털 「소상공인시장진흥공단_상가(상권)정보」(data.go.kr/data/15083033).
분기 갱신 · UTF-8(BOM) · 경도/위도가 WGS84 그대로.

**명소·교통은 대조하지 않는다.** 해수욕장·산·공항은 상가가 아니라 「없음」이 정상인데,
0 을 찍으면 「없는 곳」으로 읽힌다. 근거가 없으면 빈칸이 정직하다.
"""

from __future__ import annotations

import collections
import csv
import math
import re
from pathlib import Path

csv.field_size_limit(1 << 24)

CELL = 0.003  # 격자 한 칸 ≒ 300 m. 9칸이면 250 m 반경이 다 걸린다
NEAR_M = 60  # 여기까지는 이름이 좀 달라도 같은 곳으로 본다
FAR_M = 250  # 여기까지는 **이름이 길고 겹칠 때만**
BIG_CATEGORY = {"food": "음식", "stay": "숙박"}  # 대조하는 갈래 → 상가 대분류


def name_key(text: str | None) -> str:
    """이름 비교 열쇠. scene-api 의 NaverMatcher 와 같은 규칙이어야 한다."""
    text = re.sub(r"<[^>]+>", "", text or "")
    text = re.sub(r"\[[^\]]*\]", "", text)
    return re.sub(r"[^\w가-힣]", "", text).lower()


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    radius = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi, d_lambda = phi2 - phi1, math.radians(lng2 - lng1)
    h = (
        math.sin(d_phi / 2) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    )
    return 2 * radius * math.asin(math.sqrt(h))


Grid = dict[tuple[int, int], list[tuple[str, float, float, str, str]]]


def add_to_grid(grid: Grid, lat: float, lng: float, name: str, category: str) -> None:
    grid[(int(lat / CELL), int(lng / CELL))].append(
        (name_key(name), lat, lng, name, category)
    )


def load_grid(csv_dir: Path, big_category: str) -> tuple[Grid, int]:
    """공공데이터를 격자에 올린다. 84만 곳이 튜플로 들어가 1.1 GB 가 아니라 수백 MB 다."""
    grid: Grid = collections.defaultdict(list)
    count = 0
    for file in sorted(csv_dir.glob("*.csv")):
        with file.open(encoding="utf-8-sig", newline="") as handle:
            for record in csv.DictReader(handle):
                if record.get("상권업종대분류명") != big_category:
                    continue
                try:
                    lat, lng = float(record["위도"]), float(record["경도"])
                except (TypeError, ValueError, KeyError):
                    continue
                add_to_grid(
                    grid,
                    lat,
                    lng,
                    record.get("상호명") or "",
                    record.get("상권업종소분류명") or "",
                )
                count += 1
    return grid, count


def find(grid: Grid, lat: float, lng: float, name: str):
    """가장 가까운 **같은 이름**의 가게. 없으면 None.

    거리 한 줄로 자르면 백화점이 통째로 날아간다 — 공공데이터는 몰 매장을 건물 좌표 하나로
    등록하고 티맵은 매장마다 좌표를 주어 95 m 어긋난다(더현대서울 181곳, 2026-08-26).
    그래서 두 층 — 가까우면 느슨하게, 멀면 이름이 길고 겹칠 때만. 두세 글자 이름은 아무 데나
    붙으므로 길이 조건이 「커피」 사건을 막는다.
    """
    key = name_key(name)
    best = None
    ci, cj = int(lat / CELL), int(lng / CELL)
    for i in (ci - 1, ci, ci + 1):
        for j in (cj - 1, cj, cj + 1):
            for other_key, other_lat, other_lng, other_name, category in grid.get(
                (i, j), ()
            ):
                distance = haversine_m(lat, lng, other_lat, other_lng)
                if distance > FAR_M:
                    continue
                exact = other_key == key
                partial = (
                    bool(other_key)
                    and bool(key)
                    and (other_key in key or key in other_key)
                )
                if distance <= NEAR_M:
                    ok = exact or (partial and len(key) > 3)
                else:
                    ok = exact or (partial and len(key) >= 6 and len(other_key) >= 6)
                if ok and (best is None or distance < best[0]):
                    best = (distance, other_name, category, exact)
    return best


def alive_rows(rows, grid: Grid):
    """살아 있는 행만 흘려보낸다. 함께 지역별 생존 수를 센다."""
    by_region: dict[str, collections.Counter] = collections.defaultdict(
        collections.Counter
    )
    for record in rows:
        try:
            lat, lng = float(record["lat"]), float(record["lng"])
        except (KeyError, TypeError, ValueError):
            continue
        hit = find(grid, lat, lng, record.get("name", ""))
        by_region[record.get("region") or "?"]["alive" if hit else "gone"] += 1
        if hit:
            yield record
    alive_rows.last_stats = by_region  # type: ignore[attr-defined]
