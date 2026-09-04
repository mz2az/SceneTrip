"""TMAP POI 검색 — 호출, 행 모양, 칸 훑기.

**받은 것을 마음대로 버리지 않는다.** 좌표는 두 벌을 다 저장한다(2026-08-24 실측 —
frontLat 만 저장하고 noorLat 을 버려서 47만 건을 다시 받았다):
  lat/lng             noorLat  건물 좌표   — 지도에 핀을 찍는 자리
  front_lat/front_lng frontLat 도로 진입점 — 길안내가 끝나는 자리
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field

from poi_pipeline import grid

ENDPOINT = "https://apis.openapi.sk.com/tmap/pois"

FIELDS = [
    "id", "name", "lat", "lng", "front_lat", "front_lng", "addr", "road", "tel",
    "kind", "biz_upper", "biz_middle", "biz_lower", "keyword", "region", "city",
    "area", "near_spot",
]  # fmt: skip

# 갈래 → (검색어, 남길 업종). None 이면 다 남긴다.
GROUPS: dict[str, list[tuple[str, set[str] | None]]] = {
    "숙박": [
        ("호텔", {"호텔"}),
        ("모텔", {"모텔/여관"}),
        ("펜션", {"펜션"}),
        ("리조트", {"콘도/리조트"}),
        ("게스트하우스", {"게스트하우스", "전통숙소"}),
    ],
    "음식": [("음식점", None), ("카페", None)],
    # 관광 — **관광객이 실제로 갈 곳만.** 공원 4만·대교 3.5만은 대부분 동네 공원과
    # 이름 없는 교량이라 화면에 잡동사니만 늘린다.
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
# 갈래마다 파일 하나. 이름을 마음대로 지으면 같은 갈래가 두 파일로 갈라져 커버리지를
# 못 읽는다(2026-08-12 에 14,807건이 통째로 겹쳤다).
LANES = {"숙박": "poi_stay", "음식": "poi_food", "관광": "poi_sight"}


def norm(value: str | None) -> str:
    """업종 이름을 비교할 수 있게 다듬는다.

    TMAP 은 슬래시를 두 번 이스케이프해서 보낸다 — 파싱해도 `모텔\\/여관` 처럼 역슬래시가
    남아 `모텔/여관` 과 영영 안 맞는다(모텔·리조트가 0건으로 수집된 2026-08-12 사고).
    """
    return (value or "").replace("\\", "")


def row(poi: dict, keyword: str, area: str) -> dict:
    """TMAP 응답 하나를 우리 행으로. **noorLat 이 건물 좌표, frontLat 은 진입점이다.**"""
    return {
        "id": poi.get("id", ""),
        "name": poi.get("name", ""),
        "lat": poi.get("noorLat") or poi.get("frontLat", ""),
        "lng": poi.get("noorLon") or poi.get("frontLon", ""),
        "front_lat": poi.get("frontLat", ""),
        "front_lng": poi.get("frontLon", ""),
        "kind": norm(poi.get("lowerBizName")),
        "biz_upper": norm(poi.get("upperBizName")),
        "biz_middle": norm(poi.get("middleBizName")),
        "biz_lower": norm(poi.get("lowerBizName")),
        "tel": poi.get("telNo", ""),
        "region": poi.get("upperAddrName", ""),
        "city": poi.get("middleAddrName", ""),
        "addr": " ".join(
            part
            for part in (
                poi.get("upperAddrName"),
                poi.get("middleAddrName"),
                poi.get("lowerAddrName"),
            )
            if part
        ),
        "road": poi.get("roadName", ""),
        "keyword": keyword,
        "area": area,
        "near_spot": "",
    }


def build_url(
    keyword: str, lat: float, lng: float, radius_km: int, page: int, count: int
) -> str:
    query = urllib.parse.urlencode(
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
    return ENDPOINT + "?" + query


def parse_response(body: bytes) -> tuple[list[dict], int]:
    """응답 본문 → (POI 목록, 총 건수)."""
    data = json.loads(body.decode("utf-8"))
    info = data.get("searchPoiInfo") or {}
    pois = ((info.get("pois") or {}).get("poi")) or []
    return pois, int(info.get("totalCount") or 0)


def http_fetch(url: str, app_key: str, timeout: int = 30) -> bytes:
    request = urllib.request.Request(
        url, headers={"appKey": app_key, "Accept": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


Fetch = Callable[[str, str], bytes]


@dataclass
class Runner:
    """호출을 세고, 429·5xx 는 세 번까지 기다렸다 다시 부른다.

    `quota` 는 이 실행이 쓸 수 있는 호출 수다 — TMAP 은 하루 20,000건이고 Airflow 의
    Pool 이 태스크 사이를 나누지만, 태스크 하나 안에서도 넘지 않게 여기서도 센다.
    """

    app_key: str
    sleep_seconds: float = 0.12
    quota: int = 20_000
    fetch: Fetch = http_fetch
    sleep: Callable[[float], None] = time.sleep
    calls: int = 0
    skipped: int = 0
    errors: list[str] = field(default_factory=list)

    @property
    def exhausted(self) -> bool:
        return self.calls >= self.quota

    def go(self, keyword, lat, lng, radius_km, page=1, count=grid.COUNT_MAX):
        if self.exhausted:
            self.errors.append(f"{keyword}: 호출 한도 {self.quota} 소진")
            return [], -1
        url = build_url(keyword, lat, lng, radius_km, page, count)
        for attempt in range(3):
            try:
                body = self.fetch(url, self.app_key)
                self.calls += 1
                self.sleep(self.sleep_seconds)
                return parse_response(body)
            except urllib.error.HTTPError as error:
                self.calls += 1
                if error.code in (429, 500, 503) and attempt < 2:
                    self.sleep(2 + attempt * 3)
                    continue
                self.errors.append(f"{keyword} r{radius_km} p{page}: HTTP {error.code}")
                return [], -1
            except Exception as error:  # noqa: BLE001 — 네트워크 예외는 종류가 많다
                if attempt < 2:
                    self.sleep(2)
                    continue
                self.errors.append(f"{keyword}: {error}")
                return [], -1
        return [], -1


def sweep(runner, keyword, keep, cell, area, seen, rows, ledger, depth=0):
    """칸 하나를 받는다. 이미 받았으면 건너뛰고, 넘치면 쪼개고, 아니면 페이지를 다 돈다."""
    was = ledger.get(keyword, cell)
    if was and was["kind"] == "leaf":
        runner.skipped += 1
        return
    if was and was["kind"] == "split":
        for sub in grid.split4(cell):
            sweep(runner, keyword, keep, sub, area, seen, rows, ledger, depth + 1)
        return

    circle = grid.cover(cell)
    if circle.too_big and depth < grid.MAX_DEPTH:
        ledger.put(keyword, cell, "split", -1)
        for sub in grid.split4(cell):
            sweep(runner, keyword, keep, sub, area, seen, rows, ledger, depth + 1)
        return
    _, total = runner.go(keyword, circle.lat, circle.lng, circle.radius_km, 1, 1)
    if total < 0:
        return  # 오류 — 기록하지 않는다(다음 실행이 다시 시도한다)
    if total == 0:
        ledger.put(keyword, cell, "leaf", 0)
        return
    if total > grid.CAP and depth < grid.MAX_DEPTH:
        ledger.put(keyword, cell, "split", total)
        for sub in grid.split4(cell):
            sweep(runner, keyword, keep, sub, area, seen, rows, ledger, depth + 1)
        return
    for page in range(1, grid.PAGE_MAX + 1):
        pois, _ = runner.go(keyword, circle.lat, circle.lng, circle.radius_km, page)
        if not pois:
            break
        for poi in pois:
            if keep and norm(poi.get("lowerBizName")) not in keep:
                continue
            poi_id = str(poi.get("id", ""))
            if poi_id in seen:
                continue
            seen.add(poi_id)
            rows.append(row(poi, keyword, area))
        if len(pois) < grid.COUNT_MAX:
            break
    ledger.put(keyword, cell, "leaf", total)
