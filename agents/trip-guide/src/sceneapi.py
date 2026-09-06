"""scene-api 에 물어서 장소를 가져오는 창구.

에이전트는 데이터베이스를 직접 만지지 않는다 — 서비스를 부른다
(agents/README.md). 성지 데이터의 주인은 scene-api 이고, 이 파일은 그 API 를
`PlaceSource` 모양으로 감싸 도구 층이 창구의 속을 몰라도 되게 한다.

**여기서 하지 않는 일이 중요하다.** 반경 안에서 고르는 것도, 인기순으로 줄
세우는 것도 서버가 한다. 그 계산을 여기서 또 하면 앱이 보는 순서와 챗봇이 보는
순서가 갈린다.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

from .places import Place, PlaceSource, Scene, norm


class SceneApiError(RuntimeError):
    """scene-api 를 부르지 못했을 때. 조용히 빈 목록으로 바꾸지 않는다.

    빈 목록으로 바꾸면 「그런 촬영지가 없다」 와 「서버가 죽었다」 가 사용자에게
    똑같이 보인다. 둘은 완전히 다른 일이라 구별되어야 한다.
    """


class SceneApiPlaceBook(PlaceSource):
    def __init__(
        self,
        base_url: str = "http://localhost:8081/v1",
        *,
        lang: str = "ko",
        timeout: int = 10,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.lang = lang
        self.timeout = timeout

    # ── HTTP ──────────────────────────────────────────────────────────────────

    def _get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        query = urllib.parse.urlencode(
            {k: v for k, v in (params or {}).items() if v is not None}, encoding="utf-8"
        )
        url = f"{self.base_url}{path}" + (f"?{query}" if query else "")
        request = urllib.request.Request(url, headers={"Accept-Language": self.lang})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return {}
            raise SceneApiError(f"scene-api 가 {exc.code} 를 돌려줬다: {path}") from exc
        except urllib.error.URLError as exc:
            raise SceneApiError(
                f"scene-api 에 닿지 못했다 ({self.base_url}). 스택이 떠 있는지 확인해라: "
                f"just cluster-status — 원인: {exc.reason}"
            ) from exc

    def health(self) -> str:
        """붙는지 확인하고 데이터 규모를 한 줄로 돌려준다. 시작할 때 부른다."""
        data = self._get("/contents", {"limit": 1})
        return f"작품 {data.get('total', 0)} 편"

    # ── 변환 ──────────────────────────────────────────────────────────────────

    @staticmethod
    def _to_place(item: dict[str, Any]) -> Place:
        """PlaceSummary 를 우리 모델로 옮긴다.

        등급(tier)·엄선 여부·언급량은 scene-api 가 주지 않는다. 그것들은 볼트의
        수집 CSV 에만 있는 작업용 값이라 API 표면에 없다. 여기서는 기본값으로
        두고, 순서는 서버가 정한 것을 그대로 쓴다.
        """
        scenes: list[Scene] = []
        described = (item.get("sceneDescription") or "").strip()
        for ref in item.get("contents") or []:
            scenes.append(
                Scene(
                    title=ref.get("title") or "",
                    title_en="",
                    category="",
                    cast="",
                    description=described,
                    rank_in_title=9999,
                    popularity=0.0,
                )
            )
        if not scenes and described:
            scenes.append(Scene("", "", "", "", described, 9999, 0.0))

        return Place(
            place_id=str(item.get("id") or ""),
            name=item.get("name") or "",
            address=item.get("address") or "",
            lat=item.get("latitude"),
            lng=item.get("longitude"),
            kind=item.get("type") or "",
            naver_url="",
            image_url=item.get("imageUrl") or "",
            tier="",
            selected=False,
            mentions=0,
            scenes=scenes,
        )

    # ── 질의 ──────────────────────────────────────────────────────────────────

    def search(self, query: str, limit: int = 10) -> list[Place]:
        """`GET /places?q=` — 장소명·작품·출연진·설명을 한꺼번에 훑는다.

        서버가 인기도 내림차순으로 정렬해 준다. 우리가 다시 정렬하지 않는 이유는
        앱의 검색 탭과 같은 순서를 보기 위해서다.
        """
        data = self._get("/places", {"q": query, "limit": limit})
        return [self._to_place(i) for i in data.get("items", [])]

    def by_title(self, title: str, limit: int = 10) -> tuple[str | None, list[Place]]:
        """작품을 먼저 찾고, 그 작품의 촬영지를 받는다.

        두 번 부르는 이유는 `GET /contents/{id}/places` 가 **작품을 정확히
        지목했을 때만** 장면 설명을 채워 주기 때문이다. `/places?q=도깨비` 로
        한 번에 받으면 장면 설명이 비어 나온다 — 사용자가 가장 보고 싶어 하는
        바로 그 문장이다.
        """
        found = self._get("/contents", {"q": title, "limit": 5}).get("items", [])
        if not found:
            return None, []

        # 서버는 인기도순으로 준다. 그중 제목이 정확히 같은 것이 있으면 그것을
        # 고른다 — 「도깨비」 를 물었는데 설명에 그 말이 든 다른 작품이 1 위로
        # 올라오는 경우를 막는다.
        want = norm(title)
        best = next((c for c in found if norm(c.get("title", "")) == want), found[0])

        data = self._get(f"/contents/{best['id']}/places", {"limit": limit})
        return best.get("title"), [self._to_place(i) for i in data.get("items", [])]

    def near(
        self,
        lat: float,
        lng: float,
        radius_m: int = 2000,
        limit: int = 10,
        *,
        sort: str = "distance",
    ) -> list[tuple[Place, int]]:
        """`GET /places?lat&lng&radiusMeters` — 반경 안의 촬영지.

        `sort="popular"` 일 때는 정렬 인자를 아예 보내지 않는다. 서버의 기본이
        인기도 내림차순이라 그것이 곧 우리가 원하는 것이고, 없는 정렬값을 지어
        보내면 400 이 온다.
        """
        params: dict[str, Any] = {
            "lat": lat,
            "lng": lng,
            "radiusMeters": radius_m,
            "limit": limit,
        }
        if sort == "distance":
            params["sort"] = "distance"

        data = self._get("/places", params)
        out: list[tuple[Place, int]] = []
        for item in data.get("items", []):
            place = self._to_place(item)
            distance = item.get("distanceMeters")
            out.append((place, int(distance) if distance is not None else -1))
        return out

    # ── 편의시설 (MZ2AZ-314) ──────────────────────────────────────────────────

    def pois_near(
        self,
        lat: float,
        lng: float,
        radius_m: int = 300,
        group: str | None = None,
        limit: int = 15,
    ) -> list[dict[str, Any]]:
        """`GET /pois` — 촬영지 곁의 음식점·카페·숙박·명소·교통.

        **촬영지(`/places`)와 다른 표다.** 성지 155 개와 편의시설 50 만 개를 한 목록에
        섞으면 「성지만 더 보기」를 표현할 수 없어 계약이 둘을 갈라 두었다
        (docs/project/plans/poi.md §3-1).

        계약이 못 박은 것 셋을 지킨다 —
          · `bbox` 와 `radiusMeters` 를 함께 보내지 않는다 (둘 다 오면 400)
          · `sort=distance` 는 기준점이 있을 때만 보낸다 (없이 주면 400)
          · 영역 조건 없이 부르지 않는다 (50 만 건을 전국 대상으로 줄 정렬 기준이 없다)

        갈래는 네 가지뿐이다(`PoiCategoryGroup`) — food·stay·sight·transit.
        """
        params: dict[str, Any] = {
            "lat": lat,
            "lng": lng,
            "radiusMeters": radius_m,
            "limit": limit,
            "sort": "distance",
        }
        if group:
            params["categoryGroup"] = group
        return self._get("/pois", params).get("items", [])

    def resolve(self, name: str) -> Place | None:
        """이름으로 장소 하나를 찾는다. 정확히 같은 것만 인정한다.

        비슷한 것을 돌려주지 않는 이유는 「커피」 사건이다 — 이름이 짧으면
        검색이 아무 데나 걸리고, 그 결과를 그대로 채택하면 628 m 떨어진 남의
        가게가 1 위로 올라온다 (v6 문서 §5).
        """
        want = norm(name)
        if not want:
            return None
        for item in self._get("/places", {"q": name, "limit": 10}).get("items", []):
            if norm(item.get("name", "")) == want:
                return self._to_place(item)
        return None

    def enrich(self, place: Place) -> Place:
        """`GET /places/{id}` 로 장면 설명과 네이버 링크를 채워 넣는다.

        목록 응답(PlaceSummary)에는 이것들이 없다. 목록마다 상세를 부르면 열 번을
        더 부르게 되므로, 사용자가 한 곳을 콕 집어 물었을 때만 부른다.
        """
        if not place.place_id:
            return place
        data = self._get(f"/places/{place.place_id}")
        if not data:
            return place

        place.naver_url = data.get("naverPlaceUrl") or ""
        scenes = [
            Scene(
                title=s.get("contentTitle") or "",
                title_en="",
                category="",
                cast="",
                description=(s.get("sceneDescription") or "").strip(),
                rank_in_title=9999,
                popularity=0.0,
            )
            for s in data.get("scenes") or []
        ]
        if scenes:
            place.scenes = scenes
        return place
