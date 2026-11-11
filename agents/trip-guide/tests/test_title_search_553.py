"""MZ2AZ-553 — 작품 이름에 붙은 「성지」 를 떼고 찾기, 못 찾으면 장소 검색으로 잇기, 교통 질문은 거절하지 않기.

실제 모델도 실제 서버도 부르지 않는다. 가짜 창구(`tests.fixtures.FakeBook`)와 프롬프트 파일만 본다.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

from src.session import Session
from src.tools import run_tool

from tests.fixtures import FakeBook, make_place

KPDH = [
    make_place("101", "남산서울타워", 37.5512, 126.9882, title="KPop Demon Hunters"),
    make_place("102", "북촌한옥마을", 37.5826, 126.9830, title="KPop Demon Hunters"),
]
GOBLIN = [make_place("g1", "덕수궁돌담길", 37.5658, 126.9751, title="도깨비")]


class 기록하는창구(FakeBook):
    """`search` 로 무엇을 물었는지 남기는 가짜 — 정리한 검색어로 물었는지 본다."""

    def __init__(self, catalog):
        super().__init__(catalog)
        self.search_calls: list[str] = []

    def search(self, query, limit=10):
        self.search_calls.append(query)
        return super().search(query, limit)


def book() -> 기록하는창구:
    return 기록하는창구({"KPop Demon Hunters": KPDH, "도깨비": GOBLIN})


def ops(session: Session) -> list[str]:
    return [d.get("op") for d in session.ui]


class 작품이름의_일반단어를_뗀다(unittest.TestCase):
    def test_성지가_붙은_작품이름도_찾는다(self):
        s = Session(book=book())
        out = run_tool("list_title_places", {"title": "kpop demon hunters 성지"}, s)
        self.assertNotIn("결과없음", out, out)
        self.assertEqual(out["작품"], "KPop Demon Hunters")
        self.assertEqual(
            [p["이름"] for p in out["촬영지"]], ["남산서울타워", "북촌한옥마을"]
        )

    def test_by_title_에는_정리한_이름이_간다(self):
        b = book()
        run_tool(
            "list_title_places", {"title": "kpop demon hunters 촬영지"}, Session(book=b)
        )
        self.assertEqual(b.calls[0][0], "kpop demon hunters")

    def test_정리해서_찾으면_기억하고_지도에_띄운다(self):
        s = Session(book=book())
        run_tool("list_title_places", {"title": "kpop demon hunters 성지"}, s)
        self.assertEqual({p.place_id for p in s.shown}, {"101", "102"})
        self.assertIn("map.focus", ops(s))


class 작품으로_못찾으면_장소검색으로(unittest.TestCase):
    """`by_title` 이 비면 `/places?q=` 경로(book.search)로 한 번 더 찾는다."""

    def test_작품이_없어도_장소이름으로_찾으면_돌려준다(self):
        b = book()
        s = Session(book=b)
        out = run_tool("list_title_places", {"title": "북촌 성지"}, s)
        self.assertNotIn("결과없음", out, out)
        self.assertEqual([p["이름"] for p in out["찾은 곳"]], ["북촌한옥마을"])
        # 작품 목록이 아니라 장소 검색에서 왔다는 표시
        self.assertIn("출처", out)
        self.assertIn("장소 검색", out["출처"])

    def test_장소검색에도_정리한_검색어가_간다(self):
        b = book()
        run_tool("list_title_places", {"title": "북촌 성지"}, Session(book=b))
        self.assertEqual(b.search_calls, ["북촌"])

    def test_장소검색으로_찾아도_기억하고_지도에_띄운다(self):
        s = Session(book=book())
        run_tool("list_title_places", {"title": "북촌 성지"}, s)
        self.assertEqual([p.place_id for p in s.shown], ["102"])
        focus = [d for d in s.ui if d.get("op") == "map.focus"]
        self.assertEqual(len(focus), 1)
        self.assertEqual(len(focus[0]["placeIds"]), 1)

    def test_둘_다_비면_그때만_거절한다(self):
        s = Session(book=book())
        out = run_tool("list_title_places", {"title": "없는작품 성지"}, s)
        self.assertIn("결과없음", out)
        self.assertEqual(s.shown, [])
        self.assertEqual(s.ui, [])


_PROMPT = Path(__file__).absolute().parent.parent / "prompts" / "system.txt"


class 교통질문은_거절하지_않는다(unittest.TestCase):
    """프롬프트가 「어떻게 가?」 를 거절하라고 시키지 않고, 대략 답한 뒤 앱의 길찾기로 보내게 한다."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = _PROMPT.read_text(encoding="utf-8")

    def test_어떻게_가를_답할_수_없다고_하지_않는다(self):
        bad = [
            line
            for line in self.text.splitlines()
            if re.search(r"어떻게\s*가|가는\s*방법", line)
            and re.search(r"답할\s*수\s*없|모른다|거절", line)
        ]
        self.assertEqual(bad, [], "교통 질문을 거절·모른다로 시키는 줄이 남아 있다")

    def test_모른다_목록에_가는_방법이_없다(self):
        section = self.text.split("## 1.", 1)[1].split("## 2.", 1)[0]
        self.assertNotIn("가는 방법", section)

    def test_대략적인_수단을_말하게_한다(self):
        for word in ("공항철도", "지하철", "택시", "버스"):
            self.assertIn(word, self.text)

    def test_정확한_경로는_앱의_길찾기로_보낸다(self):
        self.assertIn("앱의 길찾기", self.text)

    def test_시간_요금을_지어내지_않게_한다(self):
        self.assertIn("시간·요금을 지어내지 마라", self.text)


if __name__ == "__main__":
    unittest.main()
