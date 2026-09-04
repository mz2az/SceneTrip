"""테스트가 쓰는 가짜 창구와 장소들.

**실제 모델도 실제 서버도 부르지 않는다.** 결정적인 시험을 만들려면 입력이
고정되어야 하고, scene-api 는 시드가 바뀌면 다른 답을 준다(agents/README.md).

좌표는 실제 서울·인천 근방 값을 쓴다. 클러스터링이 「서울 묶음」과 「인천 묶음」을
갈라내는지 보려면 두 묶음이 실제로 멀어야 하기 때문이다.
"""

from __future__ import annotations

from src.places import Place, PlaceSource, Scene, haversine_m, norm


def make_place(
    pid: str,
    name: str,
    lat: float | None,
    lng: float | None,
    *,
    title: str = "도깨비",
    rank: int = 1,
    mentions: int = 0,
    tier: str = "",
    kind: str = "",
    description: str = "",
) -> Place:
    return Place(
        place_id=pid,
        name=name,
        address=f"{name} 주소",
        lat=lat,
        lng=lng,
        kind=kind,
        naver_url="",
        image_url="",
        tier=tier,
        selected=False,
        mentions=mentions,
        scenes=[Scene(title, "", "drama", "", description, rank, 0.0)],
    )


# 서울 도심 묶음 — 서로 1~3km 안이다.
SEOUL = [
    make_place("s1", "서울중앙고", 37.5826, 126.9910, rank=1, mentions=90, tier="S"),
    make_place("s2", "개뿔", 37.5793, 127.0075, rank=2, mentions=70, tier="A"),
    make_place("s3", "북촌한옥마을", 37.5826, 126.9830, rank=3, mentions=60, tier="A"),
    make_place("s4", "경복궁", 37.5796, 126.9770, rank=4, mentions=50, tier="B"),
    make_place("s5", "덕수궁돌담길", 37.5658, 126.9751, rank=5, mentions=40, tier="B"),
    make_place("s6", "남산타워", 37.5512, 126.9882, rank=6, mentions=30, tier="C"),
]

# 인천 묶음 — 서울에서 30km 넘게 떨어져 있다.
INCHEON = [
    make_place("i1", "한미서점", 37.4736, 126.6217, rank=2, mentions=80, tier="S"),
    make_place("i2", "제물포구락부", 37.4729, 126.6193, rank=7, mentions=20, tier="B"),
    make_place("i3", "송현근린공원", 37.4780, 126.6250, rank=8, mentions=10, tier="C"),
]

# 좌표가 없는 장소. 동선에 낄 수 없어야 한다.
NO_COORDS = [make_place("n1", "좌표없는곳", None, None, rank=9)]


class FakeBook(PlaceSource):
    """작품 이름 → 장소 목록을 미리 적어 둔 창구.

    `by_title` 이 돌려주는 **순서가 곧 그 작품 안의 대표성**이라는 계약을 지킨다
    (planner.collect_candidates 가 그 순서를 점수로 쓴다).
    """

    def __init__(self, catalog: dict[str, list[Place]]) -> None:
        self.catalog = catalog
        self.calls: list[tuple[str, int]] = []

    def by_title(self, title: str, limit: int = 10) -> tuple[str | None, list[Place]]:
        self.calls.append((title, limit))
        for key, places in self.catalog.items():
            if norm(key) == norm(title):
                return key, places[:limit]
        return None, []

    def search(self, query: str, limit: int = 10) -> list[Place]:
        hits = [
            p for ps in self.catalog.values() for p in ps if norm(query) in norm(p.name)
        ]
        return hits[:limit]

    def resolve(self, name: str) -> Place | None:
        for places in self.catalog.values():
            for p in places:
                if norm(p.name) == norm(name):
                    return p
        return None

    def near(
        self,
        lat: float,
        lng: float,
        radius_m: int = 2000,
        limit: int = 10,
        *,
        sort: str = "distance",
    ) -> list[tuple[Place, int]]:
        """CsvPlaceBook 과 같은 계약으로 반경 안을 돌려준다.

        빈 목록을 돌려주는 가짜로 두었더니 평가의 `places_near` 사례가 「반경 안에
        촬영지가 없다」 로 떨어졌다(2026-09-02). 가짜라도 계약은 지켜야 시험이
        시험 구실을 한다.
        """
        seen: dict[str, tuple[Place, int]] = {}
        for places in self.catalog.values():
            for p in places:
                if not p.has_coords() or p.place_id in seen:
                    continue
                d = round(haversine_m(lat, lng, p.lat, p.lng))
                if d <= radius_m:
                    seen[p.place_id] = (p, d)
        found = list(seen.values())
        if sort == "popular":
            found.sort(key=lambda t: (-t[0].mentions, t[1]))
        else:
            found.sort(key=lambda t: (t[1], t[0].name))
        return found[:limit]


def seoul_incheon_book() -> FakeBook:
    """서울 6 곳 + 인천 3 곳. 두 도시가 갈리는지 보는 기본 픽스처."""
    return FakeBook({"도깨비": SEOUL + INCHEON})


def two_title_book() -> FakeBook:
    """작품 둘. 한 장소(개뿔)가 양쪽에 걸쳐 커버리지 가산점을 받는다."""
    return FakeBook(
        {
            "도깨비": SEOUL[:4],
            "이태원 클라쓰": [SEOUL[1], *INCHEON],
        }
    )
