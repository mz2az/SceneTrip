"""MZ2AZ-554 검증 지적 — `SceneApiPlaceBook.title_detail` 은 물은 작품이 맞을 때만 상세를 준다.

`GET /contents?q=` 는 설명·출연·장소까지 훑으므로 1 위가 다른 작품일 수 있다. 그 작품의 줄거리·출연을 물은 작품의
것으로 말하면 지어낸 것과 같다. 제목·별칭이 같거나(먼저), 세 글자 이상인 물음이 제목·별칭에 들어 있을 때만 받는다.
별칭은 목록에 없어 상세(`GET /contents/{id}`)로 본다. 실제 서버는 부르지 않는다 — `_get` 을 가짜로 바꾼다.
"""

from __future__ import annotations

import unittest

from src.sceneapi import SceneApiPlaceBook

KPOP = {
    "id": 32,
    "category": "movie",
    "title": "KPop Demon Hunters",
    "aliases": ["케데헌", "케이팝 데몬 헌터스", "K-Pop Demon Hunters"],
    "placeCount": 9,
}
PARASITE = {
    "id": 25,
    "category": "movie",
    "title": "Parasite",
    "aliases": ["기생충", "Gisaengchung"],
    "placeCount": 7,
}
GOBLIN = {
    "id": 7,
    "category": "drama",
    "title": "도깨비",
    "aliases": ["Goblin"],
    "description": "기생충 같은 말이 설명에 들어 있는 다른 작품",
    "placeCount": 12,
}
DETAILS = {d["id"]: d for d in (KPOP, PARASITE, GOBLIN)}


def summary(d: dict) -> dict:
    """목록 응답(ContentSummary) — 별칭·설명은 없다."""
    return {k: d[k] for k in ("id", "category", "title", "placeCount")}


class FakeBook(SceneApiPlaceBook):
    def __init__(self, hits: list[dict]) -> None:
        super().__init__("http://scene-api.test/v1")
        self.hits = hits
        self.paths: list[str] = []

    def _get(self, path, params=None):
        self.paths.append(path)
        if path == "/contents":
            return {"items": [summary(d) for d in self.hits], "total": len(self.hits)}
        return dict(DETAILS[int(path.rsplit("/", 1)[1])])


class 맞는_작품만(unittest.TestCase):
    def test_제목이_같으면_그_작품(self):
        book = FakeBook([GOBLIN, PARASITE])
        self.assertEqual(book.title_detail("Parasite")["id"], 25)

    def test_별칭이_같으면_그_작품(self):
        # 1 위는 설명에 「기생충」 이 든 다른 작품이다.
        book = FakeBook([GOBLIN, PARASITE])
        self.assertEqual(book.title_detail("기생충")["id"], 25)

    def test_영어_대소문자_띄어쓰기가_달라도(self):
        book = FakeBook([KPOP])
        self.assertEqual(book.title_detail("kpop demon hunters")["id"], 32)
        self.assertEqual(book.title_detail("K-pop demonhunters")["id"], 32)

    def test_세_글자_이상이_제목에_들어_있으면(self):
        book = FakeBook([KPOP])
        self.assertEqual(book.title_detail("demon hunters")["id"], 32)

    def test_정확히_같은_것을_부분_일치보다_먼저(self):
        partial = {
            "id": 99,
            "category": "movie",
            "title": "Parasite Making Film",
            "aliases": [],
            "placeCount": 1,
        }
        DETAILS[99] = partial
        try:
            book = FakeBook([partial, PARASITE])
            self.assertEqual(book.title_detail("parasite")["id"], 25)
        finally:
            DETAILS.pop(99)

    def test_관계없는_1위는_받지_않는다(self):
        book = FakeBook([GOBLIN])
        self.assertIsNone(book.title_detail("기생충"))

    def test_두_글자_부분_일치는_받지_않는다(self):
        book = FakeBook([KPOP])
        self.assertIsNone(book.title_detail("헌터"))

    def test_상세는_다섯_후보까지만(self):
        others = [dict(GOBLIN, id=100 + i, title=f"다른 작품 {i}") for i in range(8)]
        for o in others:
            DETAILS[o["id"]] = o
        try:
            book = FakeBook(others)
            self.assertIsNone(book.title_detail("기생충"))
            self.assertLessEqual(len([p for p in book.paths if p != "/contents"]), 5)
        finally:
            for o in others:
                DETAILS.pop(o["id"])


class 도구_결과(unittest.TestCase):
    """`title_info` 결과 — 감독은 따로, 줄거리는 600 자 안팎에서 자른다."""

    def _run(self, detail: dict) -> dict:
        from src.session import Session
        from src.tools import run_tool

        from tests.fixtures import FakeBook as Book

        return run_tool(
            "title_info",
            {"title": detail["title"]},
            Session(book=Book({}, details=[detail])),
        )

    def test_감독은_감독으로(self):
        from tests.fixtures import PARASITE_DETAIL

        out = self._run(PARASITE_DETAIL)
        self.assertEqual(out["감독"], ["봉준호"])
        self.assertNotIn("봉준호", out["출연"])

    def test_긴_줄거리는_자른다(self):
        from tests.fixtures import PARASITE_DETAIL

        out = self._run(dict(PARASITE_DETAIL, description="가" * 2000))
        self.assertLessEqual(len(out["줄거리"]), 601)
        self.assertTrue(out["줄거리"].endswith("…"))


class 프롬프트(unittest.TestCase):
    def test_감독을_물어도_title_info(self):
        from pathlib import Path

        text = (
            Path(__file__).absolute().parent.parent / "prompts" / "system.txt"
        ).read_text(encoding="utf-8")
        rows = [
            ln for ln in text.splitlines() if ln.startswith("|") and "title_info" in ln
        ]
        self.assertTrue(any("감독 누구야" in r for r in rows), rows)


if __name__ == "__main__":
    unittest.main()
