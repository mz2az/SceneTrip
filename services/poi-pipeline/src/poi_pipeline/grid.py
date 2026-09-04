"""위경도 격자 — 지역을 칸으로 나누고, 칸을 원으로 덮고, 넘치면 넷으로 쪼갠다.

왜 격자인가 — TMAP POI 검색은 **한 질의로 9,900건까지만** 꺼낼 수 있다(count 150 ×
page 66, 실측). 전국 「펜션」 4만 건은 한 번에 못 받는다. 칸마다 총 건수를 먼저 세어
보고(count=1 호출 한 번) 상한에 가까우면 넷으로 나눈다 — 안 그러면 **가장 밀집한
칸이 조용히 잘려 나간다**, 하필 우리한테 제일 중요한 동네가.
"""

from __future__ import annotations

import json
import math
import time
from dataclasses import dataclass
from pathlib import Path

COUNT_MAX = 150
PAGE_MAX = 66
CAP = COUNT_MAX * PAGE_MAX  # 9,900 — 한 질의로 꺼낼 수 있는 상한
# `radius` 는 **최대 33 km** 다. 34 를 주면 HTTP 400 이 온다(실측). 칸 크기는 이 한계에서
# 거꾸로 정한다 — 0.4도 칸의 대각선 절반이 29 km 라 들어온다.
RADIUS_MAX = 33
MAX_DEPTH = 6

Cell = tuple[float, float, float, float]  # 남, 서, 북, 동

AREAS: dict[str, Cell] = {
    "전국": (33.0, 125.0, 38.65, 129.65),
    "서울": (37.41, 126.76, 37.71, 127.19),
    "부산": (35.03, 128.75, 35.39, 129.31),
    "경주": (35.63, 129.05, 36.03, 129.55),
    "강릉": (37.63, 128.65, 37.92, 129.05),
    "제주": (33.10, 126.10, 33.60, 126.98),
}


def km_per_deg(lat: float) -> tuple[float, float]:
    """위도 1도·경도 1도가 몇 km 인가. 경도는 위도에 따라 줄어든다."""
    return 111.32, 111.32 * math.cos(math.radians(lat))


def cells(box: Cell, step_deg: float):
    """상자를 `step_deg` 도 칸으로 나눈다. 가장자리 칸은 상자에서 잘린다."""
    south, west, north, east = box
    lat = south
    while lat < north:
        lng = west
        while lng < east:
            yield (lat, lng, min(lat + step_deg, north), min(lng + step_deg, east))
            lng += step_deg
        lat += step_deg


@dataclass(frozen=True)
class Circle:
    lat: float
    lng: float
    radius_km: int
    too_big: bool  # 33 km 로도 못 덮는다 — 부르지 말고 쪼개야 한다


def cover(cell: Cell) -> Circle:
    """칸을 덮는 원의 중심과 반지름(정수 km). TMAP 은 정수 km 만 받는다."""
    south, west, north, east = cell
    clat, clng = (south + north) / 2, (west + east) / 2
    k_lat, k_lng = km_per_deg(clat)
    width, height = (east - west) * k_lng, (north - south) * k_lat
    radius = max(1, math.ceil(math.hypot(width, height) / 2))
    return Circle(clat, clng, min(radius, RADIUS_MAX), radius > RADIUS_MAX)


def split4(cell: Cell) -> tuple[Cell, Cell, Cell, Cell]:
    south, west, north, east = cell
    mid_lat, mid_lng = (south + north) / 2, (west + east) / 2
    return (
        (south, west, mid_lat, mid_lng),
        (south, mid_lng, mid_lat, east),
        (mid_lat, west, north, mid_lng),
        (mid_lat, mid_lng, north, east),
    )


class Ledger:
    """훑은 칸을 남겨 **다음에 이어받는다.**

    이미 받은 칸을 또 부르면 호출만 버린다. 칸마다 결과를 기록해 두고, 다시 돌릴 때 기록이
    있으면 건너뛴다. 중간에 끊겨도 거기서부터 이어진다 — Airflow 의 재시도·백필이 그대로
    이 위에 얹힌다.

      leaf   실제로 다 받은 칸
      split  상한에 걸려 넷으로 쪼갠 칸 (다시 세어 볼 필요가 없다)
    """

    def __init__(self, path: Path, refresh_days: int = 0, now=time.time):
        self.path = path
        self.now = now
        self.stale = now() - refresh_days * 86400 if refresh_days else 0
        try:
            self.data: dict = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = {}

    @staticmethod
    def key(keyword: str, cell: Cell) -> str:
        return f"{keyword}|" + ",".join(f"{x:.4f}" for x in cell)

    def get(self, keyword: str, cell: Cell) -> dict | None:
        entry = self.data.get(self.key(keyword, cell))
        if not entry:
            return None
        if self.stale and entry.get("at", 0) < self.stale:
            return None
        return entry

    def put(self, keyword: str, cell: Cell, kind: str, count: int = 0) -> None:
        self.data[self.key(keyword, cell)] = {
            "kind": kind,
            "count": count,
            "at": int(self.now()),
        }

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(self.data, ensure_ascii=False), encoding="utf-8"
        )
