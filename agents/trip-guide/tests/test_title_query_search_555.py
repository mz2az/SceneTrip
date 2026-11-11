"""MZ2AZ-555 — `search_places` 의 검색어가 작품 이름과 정확히 같으면 그 작품의 촬영지만 준다.

dev 에서 「kpop demon hunters 성지 알려줘」 에 모델이 `search_places` 를 불렀고, `/places?q=` 가 낱말이 걸린
인기 장소를 돌려줘 다른 작품(경이로운 소문)의 원주시청이 섞이고 실제 촬영지 몇 곳은 빠졌다.

- 검색어(일반 단어를 뗀 뒤)가 작품 제목·별칭과 **정확히** 같으면 → `list_title_places` 와 같은 모양
  (「작품」·「안내」·「촬영지」), 대표 순서, 기억하고 지도에 띄운다. 다른 작품의 장소는 넣지 않는다.
- 부분 일치(지역·배우·제목의 일부)는 예전 장소 검색 그대로.
- 창구 메서드 `exact_title(query, limit=10) -> (작품명 | None, [Place])` — 두 창구(scene-api·CSV) 다.

실제 모델도 실제 서버도 부르지 않는다 — scene-api 창구는 `_get` 을 가짜로 바꾸고, CSV 창구는 메모리의 장소로 만든다.
"""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from src.places import CsvPlaceBook, Place, Scene, norm
from src.sceneapi import SceneApiPlaceBook
from src.session import Session
from src.tools import run_tool

_ROOT = Path(__file__).absolute().parent.parent

# ── scene-api 가짜 ────────────────────────────────────────────────────────────
# 모양은 contracts/openapi 그대로 — ContentSummary·ContentDetail·PlaceSummary.

KPOP = {
    "id": 32,
    "category": "movie",
    "title": "KPop Demon Hunters",
    "aliases": ["케데헌", "케이팝 데몬 헌터스", "K-Pop Demon Hunters"],
    "placeCount": 3,
}
UNCANNY = {
    "id": 50,
    "category": "drama",
    "title": "경이로운 소문",
    "aliases": ["The Uncanny Counter"],
    "placeCount": 1,
}
GOBLIN = {
    "id": 7,
    "category": "drama",
    "title": "도깨비",
    "aliases": ["Goblin"],
    # 설명·출연에 「전주」·「공유」 가 들어 있어 `/contents?q=` 에 걸린다 — 그래도 제목은 아니다.
    "description": "전주 한옥마을에서도 찍은 판타지 드라마",
    "cast": [{"personId": 1, "name": "공유", "roleType": "actor"}],
    "placeCount": 4,
}
DETAILS = {d["id"]: d for d in (KPOP, UNCANNY, GOBLIN)}


def _ref(content: dict) -> list[dict]:
    return [{"contentId": content["id"], "title": content["title"]}]


def _item(pid: int, name: str, address: str, content: dict, scene: str = "") -> dict:
    return {
        "id": pid,
        "name": name,
        "address": address,
        "latitude": 37.55,
        "longitude": 126.98,
        "type": "",
        "imageUrl": None,
        "sceneDescription": scene,
        "contents": _ref(content),
    }


NAMSAN = _item(101, "남산서울타워", "서울특별시 용산구 남산공원길 105", KPOP)
BUKCHON = _item(102, "북촌한옥마을", "서울특별시 종로구 계동길 37", KPOP)
NAKSAN = _item(103, "낙산공원", "서울특별시 종로구 낙산길 41", KPOP)
WONJU = _item(
    900, "원주시청", "강원특별자치도 원주시 시청로 1", UNCANNY, "KPop 팬도 들르는 곳"
)
JEONJU = _item(301, "전주 한옥마을", "전북특별자치도 전주시 완산구 기린대로 99", GOBLIN)
DEOKSU = _item(
    302,
    "덕수궁돌담길",
    "서울특별시 중구 세종대로 99",
    GOBLIN,
    "공유가 걷던 길, 전주 아님",
)

# 작품의 촬영지 — 서버가 정한 대표 순서.
TITLE_PLACES = {32: [BUKCHON, NAMSAN, NAKSAN], 50: [WONJU], 7: [DEOKSU, JEONJU]}

# `GET /contents?q=` — 제목·별칭·설명·출연까지 훑는다(그래서 부분 일치도 걸린다).
CONTENT_HITS = {
    norm("kpop demon hunters"): [KPOP],
    norm("케데헌"): [KPOP],
    norm("demon hunters"): [KPOP],
    norm("전주"): [GOBLIN],
    norm("공유"): [GOBLIN],
}

# `GET /places?q=` — 낱말이 걸린 인기 장소. dev 에서 본 그대로 다른 작품의 원주시청이 섞이고 낙산공원은 빠진다.
PLACE_HITS = {
    norm("kpop demon hunters"): [NAMSAN, WONJU, BUKCHON],
    norm("케데헌"): [NAMSAN, WONJU],
    norm("demon hunters"): [NAMSAN, WONJU],
    norm("전주"): [JEONJU, DEOKSU],
    norm("공유"): [DEOKSU, JEONJU],
}


def _summary(d: dict) -> dict:
    """목록 응답(ContentSummary) — 별칭·설명·출연은 없다."""
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
        q = norm(str(params.get("q") or ""))
        if path == "/contents":
            hits = CONTENT_HITS.get(q, [])
            return {"items": [_summary(d) for d in hits], "total": len(hits)}
        if path == "/places":
            items = PLACE_HITS.get(q, [])[: int(params.get("limit") or 200)]
            return {"items": [dict(i) for i in items], "total": len(items)}
        parts = path.strip("/").split("/")
        if parts[0] == "contents" and len(parts) == 3 and parts[2] == "places":
            items = TITLE_PLACES.get(int(parts[1]), [])
            items = items[: int(params.get("limit") or 200)]
            return {"items": [dict(i) for i in items], "total": len(items)}
        if parts[0] == "contents" and len(parts) == 2:
            return dict(DETAILS.get(int(parts[1]), {}))
        return {}


# ── CSV 창구 ──────────────────────────────────────────────────────────────────


def _csv_place(
    pid: str,
    name: str,
    address: str,
    title: str,
    *,
    alias: str = "",
    rank: int = 1,
    cast: str = "",
    scene: str = "",
) -> Place:
    return Place(
        place_id=pid,
        name=name,
        address=address,
        lat=37.55,
        lng=126.98,
        kind="",
        naver_url="",
        image_url="",
        tier="A",
        selected=False,
        mentions=50,
        scenes=[Scene(title, alias, "movie", cast, scene, rank, 0.0)],
    )


def csv_book() -> CsvPlaceBook:
    places = [
        _csv_place(
            "201",
            "남산서울타워",
            "서울특별시 용산구 남산공원길 105",
            "KPop Demon Hunters",
            alias="케데헌",
            rank=2,
        ),
        # 다른 작품인데 장면 설명에 작품 이름이 들어 있다 — 장소 검색이면 걸린다.
        _csv_place(
            "900",
            "원주시청",
            "강원특별자치도 원주시 시청로 1",
            "경이로운 소문",
            alias="The Uncanny Counter",
            scene="KPop Demon Hunters 팬도 들르는 곳",
        ),
        _csv_place(
            "202",
            "북촌한옥마을",
            "서울특별시 종로구 계동길 37",
            "KPop Demon Hunters",
            alias="케데헌",
            rank=1,
        ),
        _csv_place(
            "301",
            "전주 한옥마을",
            "전북특별자치도 전주시 완산구 기린대로 99",
            "도깨비",
            alias="Goblin",
            cast="공유, 김고은",
        ),
    ]
    return CsvPlaceBook(places, Path("test-555.csv"))


def _names(rows: list[dict]) -> list[str]:
    return [r["이름"] for r in rows]


def _focus(session: Session) -> list[dict]:
    return [u for u in session.ui if u.get("op") == "map.focus"]


# ── 1. 창구 메서드 exact_title ───────────────────────────────────────────────


class 서버창구_exact_title(unittest.TestCase):
    def test_제목이_같으면_그_작품의_촬영지를_대표순서로(self):
        book = 가짜서버()
        matched, places = book.exact_title("kpop demon hunters")
        self.assertEqual(matched, "KPop Demon Hunters")
        self.assertEqual(
            [p.name for p in places], ["북촌한옥마을", "남산서울타워", "낙산공원"]
        )

    def test_대소문자_띄어쓰기_문장부호가_달라도(self):
        matched, _ = 가짜서버().exact_title("K-pop  Demon-Hunters")
        self.assertEqual(matched, "KPop Demon Hunters")

    def test_별칭이_같아도_그_작품(self):
        matched, places = 가짜서버().exact_title("케데헌")
        self.assertEqual(matched, "KPop Demon Hunters")
        self.assertEqual([p.place_id for p in places], ["102", "101", "103"])

    def test_다른_작품의_장소는_없다(self):
        _, places = 가짜서버().exact_title("kpop demon hunters")
        self.assertNotIn("원주시청", [p.name for p in places])

    def test_limit_만큼만(self):
        _, places = 가짜서버().exact_title("kpop demon hunters", limit=2)
        self.assertEqual([p.name for p in places], ["북촌한옥마을", "남산서울타워"])

    def test_제목의_일부면_없음(self):
        self.assertEqual(가짜서버().exact_title("demon hunters"), (None, []))

    def test_설명_출연에만_걸린_작품은_없음(self):
        self.assertEqual(가짜서버().exact_title("전주"), (None, []))
        self.assertEqual(가짜서버().exact_title("공유"), (None, []))

    def test_작품이_하나도_안_걸리면_없음(self):
        self.assertEqual(가짜서버().exact_title("없는작품"), (None, []))


class CSV창구_exact_title(unittest.TestCase):
    def test_제목이_같으면_그_작품의_촬영지를_대표순서로(self):
        matched, places = csv_book().exact_title("kpop demon hunters")
        self.assertEqual(matched, "KPop Demon Hunters")
        self.assertEqual([p.name for p in places], ["북촌한옥마을", "남산서울타워"])

    def test_별칭이_같아도_그_작품(self):
        matched, _ = csv_book().exact_title("케데헌")
        self.assertEqual(matched, "KPop Demon Hunters")

    def test_제목의_일부면_없음(self):
        self.assertEqual(csv_book().exact_title("demon hunters"), (None, []))

    def test_지역_배우면_없음(self):
        self.assertEqual(csv_book().exact_title("전주"), (None, []))
        self.assertEqual(csv_book().exact_title("공유"), (None, []))

    def test_limit_만큼만(self):
        _, places = csv_book().exact_title("kpop demon hunters", limit=1)
        self.assertEqual([p.name for p in places], ["북촌한옥마을"])


# ── 2. search_places 가 작품 이름이면 그 작품의 촬영지만 ─────────────────────


class 서버창구_search_places_작품모드(unittest.TestCase):
    def run_search(self, query: str, **extra):
        book = 가짜서버()
        s = Session(book=book)
        out = run_tool("search_places", {"query": query, **extra}, s)
        return book, s, out

    def test_성지를_떼고_작품이름이면_그_작품의_촬영지(self):
        _, _, out = self.run_search("kpop demon hunters 성지")
        self.assertNotIn("결과없음", out, out)
        self.assertEqual(out.get("작품"), "KPop Demon Hunters", out)
        self.assertIn("안내", out)
        self.assertEqual(
            _names(out["촬영지"]), ["북촌한옥마을", "남산서울타워", "낙산공원"]
        )

    def test_다른_작품의_장소가_섞이지_않는다(self):
        _, s, out = self.run_search("kpop demon hunters 성지")
        flat = json.dumps(out, ensure_ascii=False)
        self.assertNotIn("원주시청", flat)
        self.assertNotIn("900", [p.place_id for p in s.shown])

    def test_기억하고_그_작품의_장소로_지도를_옮긴다(self):
        _, s, _ = self.run_search("kpop demon hunters 성지")
        self.assertEqual({p.place_id for p in s.shown}, {"101", "102", "103"})
        focus = _focus(s)
        self.assertEqual(len(focus), 1, s.ui)
        self.assertEqual(focus[0]["placeIds"], [102, 101, 103])

    def test_별칭으로_물어도_작품모드(self):
        _, _, out = self.run_search("케데헌")
        self.assertEqual(out.get("작품"), "KPop Demon Hunters", out)
        self.assertNotIn("원주시청", _names(out.get("촬영지", [])))

    def test_limit_을_지킨다(self):
        _, _, out = self.run_search("kpop demon hunters", limit=2)
        self.assertEqual(_names(out["촬영지"]), ["북촌한옥마을", "남산서울타워"])


class 서버창구_search_places_부분일치는_그대로(unittest.TestCase):
    """`/contents?q=` 에 작품이 걸려도 제목·별칭이 그 말이 아니면 예전 장소 검색이다."""

    def run_search(self, query: str):
        book = 가짜서버()
        s = Session(book=book)
        return book, s, run_tool("search_places", {"query": query}, s)

    def _no_title_mode(self, book: 가짜서버, out: dict) -> None:
        self.assertNotIn("작품", out, out)
        self.assertNotIn("촬영지", out, out)
        self.assertFalse(
            [p for p in book.paths() if p.endswith("/places") and p != "/places"],
            f"작품 촬영지를 불렀다: {book.paths()}",
        )

    def test_지역은_예전처럼_주소로_거른다(self):
        book, _, out = self.run_search("전주")
        self._no_title_mode(book, out)
        self.assertEqual(out.get("지역"), "전주", out)
        self.assertEqual(_names(out["찾은 곳"]), ["전주 한옥마을"])

    def test_배우는_예전처럼_장소검색(self):
        book, _, out = self.run_search("공유")
        self._no_title_mode(book, out)
        self.assertEqual(_names(out["찾은 곳"]), ["덕수궁돌담길", "전주 한옥마을"])

    def test_제목의_일부는_예전처럼_장소검색(self):
        book, _, out = self.run_search("demon hunters")
        self._no_title_mode(book, out)
        self.assertEqual(_names(out["찾은 곳"]), ["남산서울타워", "원주시청"])


class CSV창구_search_places_작품모드(unittest.TestCase):
    def test_작품이름이면_그_작품의_촬영지만(self):
        s = Session(book=csv_book())
        out = run_tool("search_places", {"query": "KPop Demon Hunters 성지"}, s)
        self.assertEqual(out.get("작품"), "KPop Demon Hunters", out)
        self.assertEqual(_names(out["촬영지"]), ["북촌한옥마을", "남산서울타워"])
        self.assertNotIn("원주시청", json.dumps(out, ensure_ascii=False))
        self.assertEqual([p.place_id for p in s.shown], ["202", "201"])
        self.assertEqual(_focus(s)[0]["placeIds"], [202, 201])

    def test_지역은_예전처럼(self):
        s = Session(book=csv_book())
        out = run_tool("search_places", {"query": "전주"}, s)
        self.assertNotIn("작품", out, out)
        self.assertEqual(_names(out["찾은 곳"]), ["전주 한옥마을"])


# ── 3. 모델에게 주는 말 ───────────────────────────────────────────────────────


class 도구설명(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        specs = json.loads((_ROOT / "schemas" / "tools.json").read_text("utf-8"))
        tools = specs["tools"] if isinstance(specs, dict) else specs
        cls.desc = next(
            t["function"]["description"]
            for t in tools
            if t["function"]["name"] == "search_places"
        )

    def test_작품이름으로_찾으라고_하지_않는다(self):
        self.assertNotIn("작품 이름", self.desc)

    def test_작품을_말하면_list_title_places_로_보낸다(self):
        self.assertIn("list_title_places", self.desc)


class 프롬프트(unittest.TestCase):
    def test_영어_작품이름_성지는_list_title_places(self):
        text = (_ROOT / "prompts" / "system.txt").read_text(encoding="utf-8")
        rows = [
            ln
            for ln in text.splitlines()
            if "kpop demon hunters" in ln.lower() and "list_title_places" in ln
        ]
        self.assertTrue(
            rows, "「kpop demon hunters 성지」 → list_title_places 줄이 없다"
        )


# ── 4. 지역이 먼저 — 지역 결과면 작품 이름을 확인하지 않는다 ──────────────────

HAEUNDAE = {
    "id": 60,
    "category": "movie",
    "title": "해운대",
    "aliases": ["Haeundae"],
    "placeCount": 1,
}
HAEUNDAE_BEACH = _item(
    401, "해운대해수욕장", "부산광역시 해운대구 해운대해변로 264", HAEUNDAE
)
DONGBAEK = _item(402, "동백섬", "부산광역시 해운대구 우동 710-1", GOBLIN)


class 지역서버(가짜서버):
    """「해운대」 는 지역이면서 작품 제목이다. 「전주」 는 `/contents?q=` 에 후보 다섯이 걸린다."""

    def _get(self, path, params=None):
        q = norm(str((params or {}).get("q") or ""))
        if path == "/places" and q == norm("해운대"):
            self.calls.append((path, dict(params or {})))
            items = [HAEUNDAE_BEACH, DONGBAEK]
            return {"items": [dict(i) for i in items], "total": len(items)}
        if path == "/contents" and q in (norm("해운대"), norm("전주")):
            self.calls.append((path, dict(params or {})))
            many = [HAEUNDAE] if q == norm("해운대") else [GOBLIN] * 5
            return {"items": [_summary(d) for d in many], "total": len(many)}
        return super()._get(path, params)


class 지역이_먼저(unittest.TestCase):
    def run_search(self, query: str):
        book = 지역서버()
        s = Session(book=book)
        return book, s, run_tool("search_places", {"query": query}, s)

    def _no_content_calls(self, book: 지역서버) -> None:
        self.assertFalse(
            [p for p in book.paths() if p.startswith("/contents")],
            f"지역 검색인데 작품을 확인했다: {book.paths()}",
        )

    def test_작품과_이름이_같은_지역은_지역결과(self):
        book, _, out = self.run_search("해운대")
        self.assertEqual(out.get("지역"), "해운대", out)
        self.assertNotIn("작품", out, out)
        self.assertEqual(_names(out["찾은 곳"]), ["해운대해수욕장", "동백섬"])
        self._no_content_calls(book)

    def test_지역검색은_작품확인_호출을_더하지_않는다(self):
        book, _, out = self.run_search("전주")
        self.assertEqual(out.get("지역"), "전주", out)
        self._no_content_calls(book)


if __name__ == "__main__":
    unittest.main()
