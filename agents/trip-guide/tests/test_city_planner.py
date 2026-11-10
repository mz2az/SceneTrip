"""도시로 짜는 일정(src/city_planner.py, MZ2AZ-548) 시험 — 계획 docs/project/plans/city-ai-course.md §1·§3.

실제 모델도 실제 scene-api 도 부르지 않는다(CLAUDE.md §6). 모델은 대본 클라이언트(`ScriptedClient`),
장소 창구는 도시 → 촬영지 목록을 미리 적어 둔 가짜다. scene-api 창구(`SceneApiPlaceBook.by_city`)는
`urllib.request.urlopen` 을 바꿔 끼워 나가는 주소만 본다.

설정은 실제 `config/llm_planner.json` 을 읽는다 — 계획의 예(서울 당일 보통 15곳 등)가 그 값으로 나와야 한다.
"""

from __future__ import annotations

import io
import json
import threading
import unittest
from contextlib import closing, redirect_stderr
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from itertools import pairwise
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

from src.city_planner import (
    area,
    build_city_prompt,
    candidate_count,
    chain_plan,
    plan_city_with_model,
    trim_days,
)
from src.internal_server import (
    BodyError,
    InternalHandler,
    InternalServer,
    LimitedDesk,
    validate_body,
)
from src.llm_planner import load_config
from src.model_client import ModelError, ScriptedClient
from src.places import Scene
from src.planner import PlanError, PlanRequest, plan_to_api
from src.production import RequestBudget
from src.sceneapi import SceneApiPlaceBook
from web.server import Desk, Handler

from tests.contract_schema import load_schema, validate_def
from tests.fixtures import FakeBook, make_place

CFG = load_config()


# ── 픽스처 ────────────────────────────────────────────────────────────────────


def _spot(
    i: int, pos: float, *, titles=("도깨비",), address="서울특별시 종로구 삼청로 1"
):
    """서울 위도 하나 위, 경도 `pos`(0.01 ≈ 880m) 자리의 촬영지. id 는 1000 + i (계약의 placeId 는 정수)."""
    p = make_place(
        f"{1000 + i}", f"촬영지{i}", 37.57, 126.95 + pos * 0.01, title=titles[0]
    )
    p.address = address
    p.scenes = [Scene(t, "", "drama", "", "", 1, 0.0) for t in titles]
    return p


def seoul_city(n: int = 20):
    """인기순(앞이 1등) n 곳. 위치는 인기 순서와 엇갈린다 — 가까운 곳 잇기와 인기순이 다르게 나오게."""
    return [_spot(i, (i * 7) % n) for i in range(n)]


class CityBook(FakeBook):
    """도시 key → 인기순 촬영지. scene-api 의 `GET /places?city=` 를 흉내 낸다."""

    def __init__(self, cities, titles=None):
        super().__init__(dict(titles or {}))
        self.cities = cities
        self.city_calls: list[tuple[str, int]] = []

    def by_city(self, city: str, limit: int = 200):
        self.city_calls.append((city, limit))
        return list(self.cities.get(city, []))[:limit]


def _req(city="서울", days=1, pace="normal", must=None):
    return PlanRequest(
        titles=[], days=days, pace=pace, city=city, must=list(must or [])
    )


def _reply(days):
    return {"role": "assistant", "content": json.dumps({"days": days})}


def _rows(prompt: str) -> list[dict]:
    """프롬프트에서 성지 줄(JSON 한 줄)만 꺼낸다."""
    out = []
    for line in prompt.splitlines():
        line = line.strip()
        if line.startswith("{"):
            row = json.loads(line)
            if "id" in row:
                out.append(row)
    return out


def _ids(plan) -> list[list[str]]:
    return [[leg.place.place_id for leg in d.legs] for d in plan.days]


# ── 후보 수 ───────────────────────────────────────────────────────────────────


class 후보수(unittest.TestCase):
    def test_서울_당일_보통은_15곳(self):
        self.assertEqual(candidate_count(124, 1, "normal", CFG), 15)

    def test_서울_3일_빡빡은_63곳(self):
        self.assertEqual(candidate_count(124, 3, "packed", CFG), 63)

    def test_9곳_도시는_늘_9곳(self):
        for days, pace in ((1, "relaxed"), (1, "normal"), (3, "packed"), (7, "packed")):
            with self.subTest(days=days, pace=pace):
                self.assertEqual(candidate_count(9, days, pace, CFG), 9)

    def test_적어도_15곳(self):
        # 당일 여유 3곳 × 3 = 9 → 최소 15.
        self.assertEqual(candidate_count(124, 1, "relaxed", CFG), 15)

    def test_최소보다_적은_도시는_전체(self):
        self.assertEqual(candidate_count(12, 1, "relaxed", CFG), 12)

    def test_2일_여유는_18곳(self):
        self.assertEqual(candidate_count(124, 2, "relaxed", CFG), 18)


# ── 동네 ──────────────────────────────────────────────────────────────────────


class 동네(unittest.TestCase):
    def test_시도를_뺀_다음_마디(self):
        self.assertEqual(area("서울특별시 종로구 삼청로 1"), "종로구")
        self.assertEqual(area("제주특별자치도 서귀포시 성산읍 일출로 284"), "서귀포시")
        self.assertEqual(area("경기도 수원시 팔달구 정조로 825"), "수원시")
        self.assertEqual(area("서울 중구 덕수궁길 116"), "중구")

    def test_빈_주소는_빈_문자열(self):
        self.assertEqual(area(""), "")


# ── 프롬프트 ──────────────────────────────────────────────────────────────────


class 도시프롬프트(unittest.TestCase):
    def setUp(self):
        self.places = [
            _spot(0, 0, titles=("도깨비", "이태원 클라쓰", "응답하라 1988")),
            _spot(
                1, 1, titles=("이태원 클라쓰",), address="서울특별시 용산구 이태원로 1"
            ),
            _spot(
                2, 2, titles=("나의 아저씨",), address="서울특별시 마포구 와우산로 1"
            ),
            _spot(3, 3, titles=("도깨비",), address="서울특별시 중구 세종대로 99"),
        ]

    def test_줄마다_id_name_area_lat_lng_popularity_work(self):
        text = build_city_prompt("서울", 1, "normal", [], self.places, CFG)
        rows = _rows(text)
        self.assertEqual(len(rows), 4, text)
        for row in rows:
            for key in ("id", "name", "area", "lat", "lng", "popularity", "work"):
                self.assertIn(key, row, row)
        first = rows[0]
        self.assertEqual(first["id"], "1000")
        self.assertEqual(first["name"], "촬영지0")
        self.assertEqual(first["area"], "종로구")
        self.assertAlmostEqual(first["lat"], 37.57, places=4)
        self.assertAlmostEqual(first["lng"], 126.95, places=4)
        self.assertEqual(rows[1]["area"], "용산구")
        self.assertEqual(rows[2]["area"], "마포구")

    def test_인기순_그대로이고_popularity_는_1_0_부터_내려간다(self):
        rows = _rows(build_city_prompt("서울", 1, "normal", [], self.places, CFG))
        self.assertEqual([r["id"] for r in rows], ["1000", "1001", "1002", "1003"])
        pops = [r["popularity"] for r in rows]
        self.assertEqual(pops[0], 1.0)
        for a, b in pairwise(pops):
            self.assertGreater(a, b, pops)

    def test_대표_작품은_한두_개(self):
        rows = _rows(build_city_prompt("서울", 1, "normal", [], self.places, CFG))
        work = str(rows[0]["work"])
        self.assertIn("도깨비", work)
        self.assertNotIn("응답하라 1988", work)  # 셋째 작품은 싣지 않는다
        self.assertIn("나의 아저씨", str(rows[2]["work"]))

    def test_도시_컨셉_꼭갈곳이_들어간다(self):
        text = build_city_prompt("서울", 2, "packed", ["촬영지3"], self.places, CFG)
        self.assertIn("서울", text)
        self.assertIn(CFG["pace"]["packed"], text)
        self.assertIn("촬영지3", text)

    def test_하루_곳_수를_숫자로_주지_않는다(self):
        # 2일 빡빡 — 하루 7곳, 필요한 수 14, 후보 42. 성지 줄 밖에 그 숫자가 없어야 한다(_pace_설명).
        text = build_city_prompt("서울", 2, "packed", [], self.places, CFG)
        prose = "\n".join(
            line for line in text.splitlines() if not line.strip().startswith("{")
        )
        self.assertEqual(len(_rows(text)), 4, text)  # 글이 실제로 만들어졌다
        self.assertNotIn("7", prose)
        self.assertNotIn("14", prose)
        self.assertNotIn("42", prose)


# ── 대신 짜기 ─────────────────────────────────────────────────────────────────


class 이어짜기(unittest.TestCase):
    def test_1등에서_시작해_가까운_곳을_잇는다(self):
        # 당일 여유: 필요 3, 잡는 수 6. 위치 0·5·1·2·10·3 → 0 → 1 → 2 (1000 → 1002 → 1003).
        places = [
            _spot(0, 0),
            _spot(1, 5),
            _spot(2, 1),
            _spot(3, 2),
            _spot(4, 10),
            _spot(5, 3),
        ]
        days = chain_plan(places, 1, "relaxed", CFG)
        self.assertEqual(
            [[p.place_id for p in d] for d in days], [["1000", "1002", "1003"]]
        )

    def test_필요한_수의_2배_밖은_잡지_않는다(self):
        # 일곱째(1006)는 1등 바로 옆(0.5)이지만 잡는 수(3 × 2 = 6) 밖이다.
        places = [
            _spot(0, 0),
            _spot(1, 5),
            _spot(2, 1),
            _spot(3, 2),
            _spot(4, 10),
            _spot(5, 3),
            _spot(6, 0.5),
        ]
        ids = [p.place_id for d in chain_plan(places, 1, "relaxed", CFG) for p in d]
        self.assertNotIn("1006", ids)
        self.assertEqual(ids, ["1000", "1002", "1003"])

    def test_하루_몫으로_자르고_다음_날은_이어서(self):
        # 2일 여유: 필요 6, 잡는 수 12. 한 줄 위 위치가 인기순과 엇갈린다 — 이으면 위치 0,1,2 | 3,4,5.
        positions = [0, 7, 3, 9, 1, 5, 11, 2, 8, 4, 10, 6]
        places = [_spot(i, pos) for i, pos in enumerate(positions)]
        places.append(_spot(12, -0.5))  # 잡는 수 밖
        days = chain_plan(places, 2, "relaxed", CFG)
        by_id = {p.place_id: pos for p, pos in zip(places, positions + [-0.5])}
        self.assertEqual([len(d) for d in days], [3, 3])
        self.assertEqual(
            [[by_id[p.place_id] for p in d] for d in days], [[0, 1, 2], [3, 4, 5]]
        )

    def test_보통은_하루_5곳(self):
        days = chain_plan(seoul_city(20), 1, "normal", CFG)
        self.assertEqual(len(days), 1)
        self.assertEqual(len(days[0]), 5)
        self.assertEqual(days[0][0].place_id, "1000")


# ── 하루 몫 자르기 ────────────────────────────────────────────────────────────


def _day(*idx):
    """`_spot(i, i)` 들로 하루를 만든다 — 순서는 인자 차례 그대로."""
    return [_spot(i, i) for i in idx]


def _pids(days) -> list[list[str]]:
    return [[p.place_id for p in d] for d in days]


class 하루몫자르기(unittest.TestCase):
    """계획 §3 「하루 몫 자르기」 — 모델이 고른 날·순서는 두고 날마다 앞에서 하루 곳 수까지만."""

    def test_여유는_앞에서_3곳(self):
        day = _day(4, 0, 3, 1, 2)
        self.assertEqual(
            _pids(trim_days([day], "relaxed", CFG)), [["1004", "1000", "1003"]]
        )

    def test_보통은_앞에서_5곳(self):
        day = _day(9, 2, 7, 0, 5, 1, 8)
        self.assertEqual(
            _pids(trim_days([day], "normal", CFG)),
            [["1009", "1002", "1007", "1000", "1005"]],
        )

    def test_빡빡은_앞에서_7곳(self):
        day = _day(12, 3, 10, 0, 8, 1, 6, 2, 11, 4)
        self.assertEqual(
            _pids(trim_days([day], "packed", CFG)),
            [["1012", "1003", "1010", "1000", "1008", "1001", "1006"]],
        )

    def test_하루_곳_수는_설정의_stops_per_day(self):
        many = _day(*range(12))
        for pace, n in CFG["city"]["stops_per_day"].items():
            with self.subTest(pace=pace):
                out = trim_days([list(many)], pace, CFG)
                self.assertEqual(_pids(out), [[f"{1000 + i}" for i in range(n)]])

    def test_하루_곳_수_이하인_날은_그대로(self):
        cases = [
            ("relaxed", _day(2, 0, 1)),
            ("relaxed", _day(5)),
            ("normal", _day(3, 1, 4, 0, 2)),
            ("packed", _day(6, 0, 5, 1, 4, 2, 3)),
            ("packed", _day(1, 0)),
        ]
        for pace, day in cases:
            with self.subTest(pace=pace, n=len(day)):
                expected = _pids([day])
                self.assertEqual(_pids(trim_days([day], pace, CFG)), expected)

    def test_날마다_따로_자른다(self):
        # 서울 2일 빡빡 실측(13·12곳)처럼 넘친 날과 모자란 날이 섞여 있다.
        days = [
            _day(*range(13)),
            _day(20, 15, 18),
            _day(*range(30, 42)),
        ]
        out = trim_days(days, "packed", CFG)
        self.assertEqual(
            _pids(out),
            [
                [f"{1000 + i}" for i in range(7)],
                ["1020", "1015", "1018"],
                [f"{1000 + i}" for i in range(30, 37)],
            ],
        )

    def test_모르는_속도는_설정의_pace_fallback_하루_곳_수(self):
        fallback = CFG["pace_fallback"]
        n = CFG["city"]["stops_per_day"][fallback]
        day = _day(*range(10, 0, -1))
        out = trim_days([day], "turbo", CFG)
        self.assertEqual(_pids(out), [[f"{1000 + i}" for i in range(10, 10 - n, -1)]])
        self.assertEqual(len(out[0]), 5)  # 지금 설정은 보통 5곳

    def test_빈_날은_생기지_않는다(self):
        days = [_day(3), _day(*range(4, 12))]
        out = trim_days(days, "relaxed", CFG)
        self.assertEqual(len(out), 2)
        for d in out:
            self.assertTrue(d)
        self.assertEqual(_pids(out), [["1003"], ["1004", "1005", "1006"]])


# ── 진입점 ────────────────────────────────────────────────────────────────────


class 도시로짜기(unittest.TestCase):
    def setUp(self):
        self.places = seoul_city(20)
        self.book = CityBook({"서울": self.places})

    def run_plan(self, client, req=None):
        with redirect_stderr(io.StringIO()):
            return plan_city_with_model(self.book, req or _req(), client)

    def test_받는_상한은_설정의_fetch_limit(self):
        self.run_plan(None)
        self.assertEqual(self.book.city_calls, [("서울", CFG["city"]["fetch_limit"])])

    def test_모델이_고른_id_를_그대로_쓴다(self):
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": ["1010", "1003", "1007"]}])]
        )
        plan = self.run_plan(client)
        self.assertEqual(_ids(plan), [["1010", "1003", "1007"]])
        self.assertEqual(len(client.seen), 1)

    def test_모델에게는_추린_후보만_간다(self):
        client = ScriptedClient([_reply([{"day": 1, "place_ids": ["1000"]}])])
        self.run_plan(client)
        prompt = client.seen[0][-1]["content"]
        ids = [r["id"] for r in _rows(prompt)]
        # 당일 보통 → 15곳. 인기순 앞에서부터.
        self.assertEqual(ids, [f"{1000 + i}" for i in range(15)])

    def test_모델이_하루_곳_수보다_많이_고르면_앞에서_자른다(self):
        # 당일 보통(5곳)에 8곳 — 모델의 순서대로 앞 5곳. 이어짜기(1000 시작)가 아니다.
        picked = ["1010", "1003", "1007", "1001", "1012", "1005", "1000", "1009"]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": picked}])])
        plan = self.run_plan(client)
        self.assertEqual(_ids(plan), [picked[:5]])
        self.assertEqual(len(client.seen), 1)

    def test_당일_여유에_5곳이면_앞_3곳(self):
        # 부산 당일 여유 실측(5곳)과 같은 꼴.
        picked = ["1004", "1011", "1002", "1008", "1006"]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": picked}])])
        plan = self.run_plan(client, _req(pace="relaxed"))
        self.assertEqual(_ids(plan), [["1004", "1011", "1002"]])

    def test_2일_빡빡에_날마다_넘치면_날마다_7곳(self):
        # 서울 2일 빡빡 실측(13·12곳)과 같은 꼴 — 후보는 도시 전체 20곳.
        day1 = ["1019", "1002", "1015", "1007", "1011", "1000", "1013", "1004", "1017"]
        day2 = [
            "1001",
            "1018",
            "1006",
            "1012",
            "1009",
            "1014",
            "1003",
            "1016",
            "1005",
            "1010",
        ]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": day1}, {"day": 2, "place_ids": day2}])]
        )
        plan = self.run_plan(client, _req(days=2, pace="packed"))
        self.assertEqual(_ids(plan), [day1[:7], day2[:7]])
        chained = [
            [p.place_id for p in d] for d in chain_plan(self.places, 2, "packed", CFG)
        ]
        self.assertNotEqual(_ids(plan), chained)

    def test_한_날만_넘치면_그날만_자른다(self):
        day1 = ["1006", "1001", "1013", "1008", "1002", "1011", "1004"]
        day2 = ["1003", "1009"]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": day1}, {"day": 2, "place_ids": day2}])]
        )
        plan = self.run_plan(client, _req(days=2, pace="normal"))
        self.assertEqual(_ids(plan), [day1[:5], day2])

    def test_JSON_이_아니면_이어짜기(self):
        client = ScriptedClient(
            [{"role": "assistant", "content": "죄송해요, 못 짜겠어요"}]
        )
        plan = self.run_plan(client)
        expected = [
            [p.place_id for p in d] for d in chain_plan(self.places, 1, "normal", CFG)
        ]
        self.assertEqual(_ids(plan), expected)
        self.assertEqual(_ids(plan)[0][0], "1000")
        self.assertEqual(len(_ids(plan)[0]), 5)

    def test_후보에_없는_id_면_이어짜기(self):
        # 1018 은 도시에 있지만 후보(앞 15곳) 밖이다. 「없는 id」 로 친다.
        for bad in ("999", "1018"):
            with self.subTest(bad=bad):
                client = ScriptedClient(
                    [_reply([{"day": 1, "place_ids": ["1001", bad]}])]
                )
                plan = self.run_plan(client)
                expected = [
                    [p.place_id for p in d]
                    for d in chain_plan(self.places, 1, "normal", CFG)
                ]
                self.assertEqual(_ids(plan), expected)
                self.assertEqual(_ids(plan)[0][0], "1000")

    def test_모델을_못_부르면_이어짜기(self):
        client = ScriptedClient([])  # 부르면 ModelError
        plan = self.run_plan(client, _req(days=2, pace="relaxed"))
        self.assertEqual([len(d) for d in _ids(plan)], [3, 3])
        self.assertEqual(_ids(plan)[0][0], "1000")

    def test_클라이언트가_없으면_이어짜기(self):
        plan = self.run_plan(None, _req(days=2, pace="relaxed"))
        expected = [
            [p.place_id for p in d] for d in chain_plan(self.places, 2, "relaxed", CFG)
        ]
        self.assertEqual(_ids(plan), expected)
        self.assertEqual(len(plan.days), 2)

    def test_좌표_없는_촬영지는_후보에서_뺀다(self):
        blind = make_place("x0", "좌표없음", None, None)
        self.book.cities["서울"] = [blind, *self.places]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": ["1000"]}])])
        plan = self.run_plan(client)
        ids = [r["id"] for r in _rows(client.seen[0][-1]["content"])]
        self.assertNotIn("x0", ids)
        self.assertEqual(ids[0], "1000")
        self.assertEqual(_ids(plan), [["1000"]])

    def test_좌표_있는_촬영지가_없는_도시는_PlanError(self):
        self.book.cities["빈도시"] = [make_place("x1", "좌표없음", None, None)]
        for city in ("빈도시", "없는도시"):
            with self.subTest(city=city), self.assertRaises(PlanError):
                self.run_plan(ScriptedClient([]), _req(city=city))

    def test_plan_titles_는_처음_나온_차례의_작품들(self):
        places = [
            _spot(0, 0, titles=("도깨비",)),
            _spot(1, 1, titles=("이태원 클라쓰", "도깨비")),
            _spot(2, 2, titles=("나의 아저씨",)),
            _spot(3, 3, titles=("도깨비", "나의 아저씨")),
        ]
        self.book.cities["서울"] = places
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": ["1001", "1000"]},
                        {"day": 2, "place_ids": ["1003"]},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(days=2))
        self.assertEqual(
            plan.request.titles, ["이태원 클라쓰", "도깨비", "나의 아저씨"]
        )
        self.assertEqual(
            plan_to_api(plan)["titles"], ["이태원 클라쓰", "도깨비", "나의 아저씨"]
        )


# ── scene-api 창구 ────────────────────────────────────────────────────────────


class 도시창구(unittest.TestCase):
    def test_GET_places_city_limit_로_묻는다(self):
        book = SceneApiPlaceBook("http://scene-api.test/v1", timeout=5)
        item = {
            "id": 7,
            "name": "경복궁",
            "address": "서울특별시 종로구 사직로 161",
            "latitude": 37.5796,
            "longitude": 126.977,
            "contents": [{"title": "도깨비", "popularity": 12.0}],
        }
        with patch("urllib.request.urlopen") as opened:
            opened.return_value.__enter__.return_value.read.return_value = json.dumps(
                {"items": [item], "total": 1}
            ).encode()
            places = book.by_city("경기 광주", limit=200)
        self.assertEqual(opened.call_count, 1)
        request = opened.call_args.args[0]
        url = request if isinstance(request, str) else request.full_url
        parsed = urlparse(url)
        self.assertEqual(parsed.path, "/v1/places")
        query = parse_qs(parsed.query)
        self.assertEqual(query.get("city"), ["경기 광주"])
        self.assertEqual(query.get("limit"), ["200"])
        self.assertNotIn("q", query)
        self.assertEqual([p.place_id for p in places], ["7"])
        self.assertEqual(places[0].titles, ["도깨비"])


# ── 요청 검증 ─────────────────────────────────────────────────────────────────


class 몸체검증(unittest.TestCase):
    def test_titles_와_city_중_정확히_하나_city_는_1_40자(self):
        invalid = [
            {"titles": ["도깨비"], "city": "서울"},
            {"days": 2},
            {"city": ""},
            {"city": "가" * 41},
            {"city": 1},
            {"city": ["서울"]},
            {"city": None},
            # 빈 titles 목록은 「작품 없음」 이다(백엔드는 늘 빈 목록을 실어 보낸다). 공백뿐인 city 도 없음.
            {"titles": []},
            {"titles": [], "city": None},
            {"city": "   "},
            {"titles": [], "city": "   "},
        ]
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(BodyError):
                validate_body("/plan", payload)
        for payload in (
            {"city": "서울", "days": 2, "pace": "packed"},
            {"city": "가" * 40},
            {"city": "경기 광주", "must": ["경복궁"], "avoid": ["남산"]},
            {"titles": ["도깨비"], "days": 2},
            {"titles": [], "city": "서울", "days": 2},
            {"titles": ["도깨비"], "city": "  "},
        ):
            with self.subTest(payload=payload):
                validate_body("/plan", payload)


# ── HTTP ──────────────────────────────────────────────────────────────────────


class _CityHttp:
    """두 서버(운영 InternalServer · 시험용 web.server)에 같은 시험을 돌린다."""

    def make_server(self, book):  # pragma: no cover - 하위 클래스가 채운다
        raise NotImplementedError

    def setUp(self):
        self.places = seoul_city(20)
        # 작품 창구도 살아 있다 — 「titles 와 city 를 함께」 가 작품으로 짜여 200 이 되면 안 된다.
        self.book = CityBook(
            {"서울": self.places, "빈도시": [make_place("x1", "좌표없음", None, None)]},
            titles={"도깨비": self.places[:6]},
        )
        self.server = self.make_server(self.book)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def post(self, body, client=None):
        payload = json.dumps(body, ensure_ascii=False).encode()
        factory = (lambda: client) if client is not None else _no_model
        with (
            patch("web.server.ModelClient", side_effect=factory),
            redirect_stderr(io.StringIO()),
            closing(HTTPConnection(*self.server.server_address, timeout=5)) as conn,
        ):
            conn.request(
                "POST",
                "/plan",
                body=payload,
                headers={"Content-Type": "application/json"},
            )
            response = conn.getresponse()
            return response.status, json.loads(response.read())

    def test_도시로_짜면_모델의_일정이_나간다(self):
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": ["1004", "1001"]},
                        {"day": 2, "place_ids": ["1002"]},
                    ]
                )
            ]
        )
        status, body = self.post({"city": "서울", "days": 2, "pace": "normal"}, client)
        self.assertEqual(status, 200, body)
        plan = body["plan"]
        self.assertEqual(
            [[s["placeId"] for s in d["stops"]] for d in plan["days"]],
            [[1004, 1001], [1002]],
        )
        self.assertEqual(plan["titles"], ["도깨비"])
        validate_def(plan, "guidePlan", load_schema())
        self.assertEqual([e["op"] for e in body["effects"]], ["plan.draft"])
        self.assertEqual(self.book.city_calls, [("서울", CFG["city"]["fetch_limit"])])

    def test_모델이_없어도_도시_초안은_나간다(self):
        status, body = self.post({"city": "서울", "days": 1, "pace": "relaxed"})
        self.assertEqual(status, 200, body)
        stops = body["plan"]["days"][0]["stops"]
        self.assertEqual(len(stops), 3)
        self.assertEqual(stops[0]["placeId"], 1000)

    def test_백엔드처럼_빈_titles_를_실어도_도시로_짠다(self):
        status, body = self.post(
            {"titles": [], "city": "서울", "days": 1, "pace": "relaxed"}
        )
        self.assertEqual(status, 200, body)
        stops = body["plan"]["days"][0]["stops"]
        self.assertEqual([s["placeId"] for s in stops][:1], [1000])
        self.assertEqual(len(stops), 3)
        self.assertEqual(self.book.city_calls, [("서울", CFG["city"]["fetch_limit"])])

    def test_잘못된_도시_요청은_400(self):
        cases = [
            {"titles": ["도깨비"], "city": "서울", "days": 1},
            {"days": 1},
            {"city": "", "days": 1},
            {"city": "가" * 41, "days": 1},
            {"city": 7, "days": 1},
            {"city": "빈도시", "days": 1},
            {"city": "없는도시", "days": 1},
            {"titles": [], "days": 1},
            {"titles": [], "city": None, "days": 1},
            {"city": "   ", "days": 1},
        ]
        for payload in cases:
            with self.subTest(payload=payload):
                status, body = self.post(payload)
                self.assertEqual(status, 400, body)
                self.assertEqual(body.get("code"), "INVALID_PARAMETER", body)


def _no_model():
    raise ModelError("시험 — 모델 없음")


class 운영서버_도시(_CityHttp, unittest.TestCase):
    def make_server(self, book):
        class TestHandler(InternalHandler):
            desk = LimitedDesk(book, "test", {})
            budget = RequestBudget()
            active = threading.BoundedSemaphore(4)

        return InternalServer(("127.0.0.1", 0), TestHandler)


class 시험서버_도시(_CityHttp, unittest.TestCase):
    def make_server(self, book):
        class TestHandler(Handler):
            desk = Desk(book, "csv(시험)", {})

        return ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)


if __name__ == "__main__":
    unittest.main()
