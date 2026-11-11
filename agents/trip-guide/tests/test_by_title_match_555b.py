"""MZ2AZ-555 후속 — `by_title` 은 제목·별칭이 맞는 작품에서만 촬영지를 준다.

dev(19d3349d)에서 「kpop demon hunters 성지 알려줘」 에 모델이 `list_title_places` 를 불렀는데, 도구가
「경이로운 소문」(id 39)의 촬영지를 돌려줬다. `GET /contents?q=demon hunters` 는 설명이 걸린 39 를 1 위로,
32(KPop Demon Hunters)를 2 위로 준다. `by_title` 이 제목이 정확히 같은 것이 없으면 `found[0]` 을 골라서다.

- 고르는 법은 `title_detail` 과 같다(`_match_content(..., partial=True)`): 제목·별칭이 같은 것 → 없으면 세 글자
  이상인 물음이 제목·별칭에 든 것 → 없으면 (None, []) 이고 `/contents/{id}/places` 는 부르지 않는다.
- 그러면 `list_title_places` 의 기존 대체 경로(장소 검색 + 「작품으로는 못 찾았다」)가 맡는다.

실제 서버를 부르지 않는다 — `_get` 을 dev 에서 본 응답 모양으로 바꾼다.
"""

from __future__ import annotations

import unittest

from src.places import norm
from src.sceneapi import SceneApiPlaceBook
from src.session import Session
from src.tools import run_tool

KPOP = {
    "id": 32,
    "category": "movie",
    "title": "KPop Demon Hunters",
    "aliases": [
        "K-Pop Demon Hunters",
        "K-Pop獵魔女團",
        "KPop Demon Hunters",
        "케이팝 데몬 헌터스",
        "케데헌",
    ],
    "placeCount": 2,
}
UNCANNY = {
    "id": 39,
    "category": "drama",
    "title": "경이로운 소문",
    "aliases": ["The Uncanny Counter", "경소문"],
    # 설명에 「demon hunters」·「악귀」 가 들어 있어 `/contents?q=` 에 걸린다 — 제목·별칭은 아니다.
    "description": "악귀를 잡는 demon hunters 카운터들의 이야기",
    "placeCount": 1,
}
DETAILS = {d["id"]: d for d in (KPOP, UNCANNY)}


def _item(pid: int, name: str, content: dict) -> dict:
    return {
        "id": pid,
        "name": name,
        "address": "서울특별시",
        "latitude": 37.55,
        "longitude": 126.98,
        "type": "",
        "imageUrl": None,
        "sceneDescription": "",
        "contents": [{"contentId": content["id"], "title": content["title"]}],
    }


TITLE_PLACES = {
    32: [_item(101, "남산서울타워", KPOP), _item(102, "북촌한옥마을", KPOP)],
    39: [_item(900, "원주시청", UNCANNY)],
}

# dev 의 실제 `GET /contents?q=` 결과 순서.
CONTENT_HITS = {
    norm("demon hunters"): [UNCANNY, KPOP],
    norm("kpop demon hunters"): [KPOP],
    norm("케이팝 데몬 헌터스"): [KPOP],
    norm("K-pop Demon Hunters"): [KPOP],
    norm("케데헌"): [KPOP],
    norm("악귀"): [UNCANNY],
}


def _summary(d: dict) -> dict:
    """목록 응답(ContentSummary) — 별칭·설명은 없다."""
    return {k: d[k] for k in ("id", "category", "title", "placeCount")}


class 가짜서버(SceneApiPlaceBook):
    def __init__(self) -> None:
        super().__init__("http://scene-api.test/v1")
        self.calls: list[tuple[str, dict]] = []

    def paths(self) -> list[str]:
        return [p for p, _ in self.calls]

    def _get(self, path, params=None):
        params = dict(params or {})
        self.calls.append((path, params))
        if path == "/contents":
            hits = CONTENT_HITS.get(norm(str(params.get("q") or "")), [])
            return {"items": [_summary(d) for d in hits], "total": len(hits)}
        if path == "/places":
            return {"items": [], "total": 0}
        parts = path.strip("/").split("/")
        if parts[0] == "contents" and len(parts) == 3 and parts[2] == "places":
            items = TITLE_PLACES.get(int(parts[1]), [])[
                : int(params.get("limit") or 200)
            ]
            return {"items": [dict(i) for i in items], "total": len(items)}
        if parts[0] == "contents" and len(parts) == 2:
            return dict(DETAILS.get(int(parts[1]), {}))
        return {}


class 작품찾기는_첫_검색결과로_넘어가지_않는다(unittest.TestCase):
    def _names(self, places) -> list[str]:
        return [p.name for p in places]

    def test_부분_제목은_제목_별칭에_든_작품으로(self):
        for query in ("demon hunters", "Demon Hunters"):
            with self.subTest(query=query):
                book = 가짜서버()
                matched, places = book.by_title(query, 10)
                self.assertEqual(matched, "KPop Demon Hunters")
                self.assertEqual(self._names(places), ["남산서울타워", "북촌한옥마을"])
                self.assertNotIn("/contents/39/places", book.paths())

    def test_정확한_제목은_그대로(self):
        book = 가짜서버()
        matched, places = book.by_title("kpop demon hunters", 10)
        self.assertEqual(matched, "KPop Demon Hunters")
        self.assertEqual(self._names(places), ["남산서울타워", "북촌한옥마을"])

    def test_별칭도_같은_작품(self):
        book = 가짜서버()
        matched, places = book.by_title("케데헌", 10)
        self.assertEqual(matched, "KPop Demon Hunters")
        self.assertEqual(len(places), 2)

    def test_설명만_걸린_작품은_고르지_않는다(self):
        book = 가짜서버()
        matched, places = book.by_title("악귀", 10)
        self.assertIsNone(matched)
        self.assertEqual(places, [])
        self.assertNotIn("/contents/39/places", book.paths())

    def test_상세_호출은_다섯_번까지(self):
        book = 가짜서버()
        book.by_title("demon hunters", 10)
        details = [
            p for p in book.paths() if p.count("/") == 2 and not p.endswith("/places")
        ]
        self.assertLessEqual(len(details), 5)

    def test_list_title_places_는_물은_작품의_촬영지(self):
        book = 가짜서버()
        s = Session(book=book)
        out = run_tool("list_title_places", {"title": "demon hunters"}, s)
        self.assertEqual(out.get("작품"), "KPop Demon Hunters")
        self.assertNotIn(
            "원주시청", [c.get("이름") or c.get("name") for c in out["촬영지"]]
        )
        self.assertNotIn("/contents/39/places", book.paths())


if __name__ == "__main__":
    unittest.main()
