"""MZ2AZ-554 — 작품 정보 도구 `title_info`: 「기생충 무슨 내용이야」·「출연진 누구야」.

줄거리·출연진은 **도구 결과로만** 말한다. 모델이 아는 척 지어내지 않게 재료를 서버(ContentDetail)에서 받는다.
실제 모델도 실제 서버도 부르지 않는다. 가짜 창구(`tests.fixtures.FakeBook`)와 urlopen 가짜만 쓴다.
"""

from __future__ import annotations

import json
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

from src.sceneapi import SceneApiPlaceBook
from src.session import Session
from src.tools import load_tool_specs, run_tool

from tests.fixtures import PARASITE_DETAIL, SEOUL, FakeBook


def book() -> FakeBook:
    return FakeBook({"도깨비": SEOUL[:2]}, details=[PARASITE_DETAIL])


def specs() -> dict:
    return {t["function"]["name"]: t["function"] for t in load_tool_specs()}


class 도구_스키마(unittest.TestCase):
    def test_tools_json_에_title_info_가_있다(self):
        self.assertIn("title_info", specs())

    def test_인자는_title_하나이고_필수다(self):
        params = specs()["title_info"]["parameters"]
        self.assertEqual(params.get("type"), "object")
        self.assertEqual(params.get("required"), ["title"])
        self.assertEqual(params["properties"]["title"]["type"], "string")
        self.assertFalse(params.get("additionalProperties", True))

    def test_설명이_줄거리_출연을_말한다(self):
        desc = specs()["title_info"]["description"]
        self.assertIn("줄거리", desc)
        self.assertIn("출연", desc)


class 작품정보를_돌려준다(unittest.TestCase):
    def test_찾으면_작품정보를_모델에게_준다(self):
        out = run_tool("title_info", {"title": "기생충"}, Session(book=book()))
        self.assertNotIn("결과없음", out, out)
        self.assertEqual(out["작품"], "기생충")
        self.assertEqual(out["종류"], "movie")
        self.assertEqual(out["연도"], 2019)
        self.assertEqual(out["장르"], ["드라마", "스릴러"])
        self.assertEqual(out["줄거리"], PARASITE_DETAIL["description"])
        self.assertEqual(out["촬영지 수"], 7)

    def test_출연에_배우_이름이_들어간다(self):
        out = run_tool("title_info", {"title": "기생충"}, Session(book=book()))
        cast = json.dumps(out.get("출연"), ensure_ascii=False)
        self.assertIn("송강호", cast)
        self.assertIn("최우식", cast)

    def test_일반단어를_떼고_찾는다(self):
        b = book()
        out = run_tool("title_info", {"title": "기생충 촬영지"}, Session(book=b))
        self.assertNotIn("결과없음", out, out)
        self.assertEqual(b.detail_calls, ["기생충"])

    def test_영어_별칭으로도_찾는다(self):
        out = run_tool("title_info", {"title": "Parasite"}, Session(book=book()))
        self.assertEqual(out.get("작품"), "기생충", out)

    def test_없는_작품이면_거절한다(self):
        s = Session(book=book())
        out = run_tool("title_info", {"title": "없는작품"}, s)
        self.assertIn("결과없음", out)
        self.assertIn("「없는작품」 라는 작품 정보가 없다", out["결과없음"])
        self.assertNotIn("줄거리", out)

    def test_title_이_빠지면_거절한다(self):
        out = run_tool("title_info", {}, Session(book=book()))
        self.assertIn("결과없음", out)
        self.assertNotIn("모르는 도구", json.dumps(out, ensure_ascii=False))
        self.assertNotIn("라는 도구는 없다", out["결과없음"])


class scene_api_창구(unittest.TestCase):
    """`SceneApiPlaceBook.title_detail` — GET /contents?q= 로 찾고 GET /contents/{id} 로 상세를 받는다."""

    def _run(self, responses: list[dict], title: str):
        book = SceneApiPlaceBook("http://scene-api.test/v1", timeout=5)
        with patch("urllib.request.urlopen") as opened:
            opened.return_value.__enter__.return_value.read.side_effect = [
                json.dumps(r).encode() for r in responses
            ]
            got = book.title_detail(title)
        urls = []
        for c in opened.call_args_list:
            req = c.args[0]
            urls.append(urlparse(req if isinstance(req, str) else req.full_url))
        return got, urls

    def test_검색한_뒤_정확히_같은_제목의_상세를_받는다(self):
        found = {
            "items": [
                {"id": 9, "category": "drama", "title": "기생충 다큐", "placeCount": 1},
                {"id": 42, "category": "movie", "title": "기생충", "placeCount": 7},
            ],
            "total": 2,
        }
        got, urls = self._run([found, PARASITE_DETAIL], "기생충")
        self.assertEqual(len(urls), 2)
        self.assertEqual(urls[0].path, "/v1/contents")
        self.assertEqual(parse_qs(urls[0].query).get("q"), ["기생충"])
        self.assertEqual(urls[1].path, "/v1/contents/42")
        self.assertEqual(got, PARASITE_DETAIL)

    def test_검색이_비면_None(self):
        got, urls = self._run([{"items": [], "total": 0}], "없는작품")
        self.assertIsNone(got)
        self.assertEqual(len(urls), 1)


_PROMPT = Path(__file__).absolute().parent.parent / "prompts" / "system.txt"


class 프롬프트(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = _PROMPT.read_text(encoding="utf-8")
        cls.section3 = cls.text.split("## 3.", 1)[1].split("## 4.", 1)[0]

    def test_3절_표에_title_info_줄이_있다(self):
        rows = [
            line
            for line in self.section3.splitlines()
            if line.startswith("|") and "title_info" in line
        ]
        self.assertEqual(len(rows), 1, rows)
        self.assertIn("기생충 무슨 내용이야", rows[0])
        self.assertIn("출연진 누구야", rows[0])

    def test_줄거리_출연은_도구로만_지어내지_않는다(self):
        lines = [
            line
            for line in self.text.splitlines()
            if "title_info" in line and "지어내지 마라" in line
        ]
        self.assertTrue(
            lines, "줄거리·출연을 title_info 로만 말하고 지어내지 말라는 줄이 없다"
        )


if __name__ == "__main__":
    unittest.main()
