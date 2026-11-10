"""도시로 짜는 일정(src/city_planner.py, MZ2AZ-548) 시험 — 계획 docs/project/plans/city-ai-course.md §1·§3.

실제 모델도 실제 scene-api 도 부르지 않는다(CLAUDE.md §6). 모델은 대본 클라이언트(`ScriptedClient`),
장소 창구는 도시 → 촬영지 목록을 미리 적어 둔 가짜다. scene-api 창구(`SceneApiPlaceBook.by_city`)는
`urllib.request.urlopen` 을 바꿔 끼워 나가는 주소만 본다.

설정은 실제 `config/llm_planner.json` 을 읽는다 — 계획의 예(서울 당일 보통 15곳 등)가 그 값으로 나와야 한다.
"""

from __future__ import annotations

import io
import json
import math
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
    fill_days,
    plan_city_with_model,
    reorder_day,
    trim_days,
    zones,
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
from src.places import Scene, haversine_m
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
        # 당일 여유(3곳)에 3곳 — 꽉 찬 날이라 채우기(계획 §3 「빈자리 채우기」)가 끼지 않는다.
        plan = self.run_plan(client, _req(pace="relaxed"))
        # 고른 곳은 그대로, 차례는 첫 곳(1010, 위치 10)에서 가까운 순 — 1007(9) → 1003(1). 계획 §3 「구역과 순서」.
        self.assertEqual(_ids(plan), [["1010", "1007", "1003"]])
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
        # 자른 뒤 첫 곳(1010, 위치 10)에서 가까운 순: 1007(9) → 1001(7) → 1012(4) → 1003(1).
        picked = ["1010", "1003", "1007", "1001", "1012", "1005", "1000", "1009"]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": picked}])])
        plan = self.run_plan(client)
        self.assertEqual(_ids(plan), [["1010", "1007", "1001", "1012", "1003"]])
        self.assertEqual(len(client.seen), 1)

    def test_당일_여유에_5곳이면_앞_3곳(self):
        # 부산 당일 여유 실측(5곳)과 같은 꼴.
        picked = ["1004", "1011", "1002", "1008", "1006"]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": picked}])])
        plan = self.run_plan(client, _req(pace="relaxed"))
        # 앞 3곳(위치 8·17·14)을 첫 곳에서 가까운 순으로 — 1004 → 1002(14) → 1011(17).
        self.assertEqual(_ids(plan), [["1004", "1002", "1011"]])

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
        got = _ids(plan)
        # 날마다 앞 7곳만 남고(같은 곳들), 그날 첫 곳에서 가까운 순으로 다시 놓인다.
        self.assertEqual([sorted(d) for d in got], [sorted(day1[:7]), sorted(day2[:7])])
        self.assertEqual([d[0] for d in got], [day1[0], day2[0]])
        by_id = {p.place_id: p for p in self.places}
        for d in got:
            _assert_nearest_chain(self, [by_id[i] for i in d])
        # 둘째 날은 같은 거리가 없어 차례가 하나로 정해진다(위치 7→6→4→3→2→1→18).
        self.assertEqual(
            got[1], ["1001", "1018", "1012", "1009", "1006", "1003", "1014"]
        )
        chained = [
            [p.place_id for p in d] for d in chain_plan(self.places, 2, "packed", CFG)
        ]
        self.assertNotEqual(_ids(plan), chained)

    def test_한_날만_넘치면_그날만_자른다(self):
        day1 = ["1006", "1001", "1013", "1008", "1002", "1011", "1004"]
        # 둘째 날은 꽉 찬 5곳(위치 0·1·3·4·5)이고 이미 가까운 순이다 — 자르지도 채우지도 않는다.
        day2 = ["1000", "1003", "1009", "1012", "1015"]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": day1}, {"day": 2, "place_ids": day2}])]
        )
        plan = self.run_plan(client, _req(days=2, pace="normal"))
        # 첫날 앞 5곳(위치 2·7·11·16·14)을 가까운 순으로 → 2·7·11·14·16. 둘째 날은 그대로.
        self.assertEqual(_ids(plan), [["1006", "1001", "1013", "1002", "1008"], day2])

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
        self.assertEqual(_ids(plan)[0][0], "1000")
        self.assertNotIn("x0", [i for d in _ids(plan) for i in d])

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


# ── 구역 ──────────────────────────────────────────────────────────────────────


def _at(i: int, lat: float, lng: float):
    """id 1000 + i, 좌표를 직접 준 촬영지."""
    return make_place(f"{1000 + i}", f"촬영지{i}", lat, lng)


def _km_between(a, b) -> float:
    return haversine_m(a.lat, a.lng, b.lat, b.lng)


def _assert_nearest_chain(case: unittest.TestCase, day) -> None:
    """차례가 「매번 남은 곳 중 가장 가까운 곳」 인지 — 같은 거리는 어느 쪽이든 된다."""
    for k in range(len(day) - 1):
        here, nxt, rest = day[k], day[k + 1], day[k + 1 :]
        best = min(_km_between(here, p) for p in rest)
        case.assertAlmostEqual(
            _km_between(here, nxt),
            best,
            delta=1e-6,
            msg=f"{here.place_id} 다음은 가장 가까운 곳이어야 한다: {[p.place_id for p in day]}",
        )


# 서로 멀리 떨어진 세 무리(각 3곳). 서울 종로·부산 해운대·광주 쯤 — 무리 안은 1km 안쪽.
_FAR = {
    "북": (37.57, 126.98),
    "남동": (35.16, 129.16),
    "남서": (35.15, 126.85),
}


def _cluster(start: int, name: str, n: int = 3):
    lat, lng = _FAR[name]
    return [_at(start + k, lat + k * 0.002, lng + k * 0.002) for k in range(n)]


class 구역(unittest.TestCase):
    """계획 §3 「빈자리 채우기와 더 잘은 구역」 1 — 인자는 구역 크기(하루 곳 수 × zone_factor).

    구역 수 = 올림(후보 수 ÷ 구역 크기), 적어도 1. (예전 「구역과 순서」 의 × 2 를 바꾼 규칙.)
    """

    def test_구역_수는_올림_후보수_나누기_구역크기(self):
        places = seoul_city(20)  # 한 줄 위 위치 0~19 가 하나씩
        for size in (3, 5, 7, 10, 20, 30):
            with self.subTest(size=size):
                want = max(1, math.ceil(len(places) / size))
                got = zones(places, size)
                self.assertEqual(len(set(got.values())), want, got)

    def test_후보가_적으면_구역은_하나(self):
        # 3곳, 구역 크기 5 → 올림(3 ÷ 5) = 1.
        got = zones(seoul_city(3), 5)
        self.assertEqual(set(got.values()), {"A"}, got)

    def test_21곳_구역크기_7은_3구역(self):
        places = [_spot(i, i) for i in range(21)]
        got = zones(places, 7)
        self.assertEqual(sorted(set(got.values())), ["A", "B", "C"], got)

    def test_21곳_구역크기_5는_5구역(self):
        # 서울 당일 보통(하루 5곳) × zone_factor 1 꼴 — 올림(21 ÷ 5) = 5.
        places = [_spot(i, i) for i in range(21)]
        got = zones(places, 5)
        self.assertEqual(sorted(set(got.values())), ["A", "B", "C", "D", "E"], got)

    def test_모든_곳이_구역을_받는다(self):
        places = seoul_city(20)
        got = zones(places, 3)
        self.assertEqual(set(got), {p.place_id for p in places})
        for pid, z in got.items():
            self.assertRegex(z, r"^[A-Z]$", pid)

    def test_이름은_A_부터_빈칸_없이(self):
        got = zones(seoul_city(20), 5)  # 올림(20 ÷ 5) = 4구역
        self.assertEqual(sorted(set(got.values())), ["A", "B", "C", "D"], got)

    def test_멀리_떨어진_두_무리는_다른_구역_무리_안은_한_구역(self):
        north, south = _cluster(0, "북", 5), _cluster(5, "남동", 5)
        # 인기순은 두 무리를 엇갈려 놓는다. 10곳, 구역 크기 5 → 올림(10 ÷ 5) = 2구역.
        places = [p for pair in zip(north, south) for p in pair]
        got = zones(places, 5)
        self.assertEqual(len({got[p.place_id] for p in north}), 1, got)
        self.assertEqual(len({got[p.place_id] for p in south}), 1, got)
        self.assertNotEqual(got[north[0].place_id], got[south[0].place_id], got)

    def test_인기_1등이_든_구역이_A(self):
        north, south = _cluster(0, "북", 5), _cluster(5, "남동", 5)
        # 1등은 남동 무리. 10곳, 구역 크기 5 → 2구역.
        places = [p for pair in zip(south, north) for p in pair]
        got = zones(places, 5)
        self.assertEqual(got[south[0].place_id], "A", got)
        self.assertEqual(got[north[0].place_id], "B", got)

    def test_이름은_인기순으로_처음_나온_무리부터(self):
        north, se, sw = _cluster(0, "북"), _cluster(3, "남동"), _cluster(6, "남서")
        # 9곳, 구역 크기 3 → 올림(9 ÷ 3) = 3구역. 인기순 처음 나오는 차례: 남서 → 북 → 남동.
        places = [
            sw[0],
            north[0],
            sw[1],
            se[0],
            north[1],
            se[1],
            sw[2],
            north[2],
            se[2],
        ]
        got = zones(places, 3)
        for group, label in ((sw, "A"), (north, "B"), (se, "C")):
            with self.subTest(label=label):
                self.assertEqual({got[p.place_id] for p in group}, {label}, got)

    def test_같은_입력이면_같은_답(self):
        places = seoul_city(20)
        first = zones(places, 3)
        self.assertTrue(first)
        for _ in range(3):
            self.assertEqual(zones(list(places), 3), first)


# ── 하루 차례 ─────────────────────────────────────────────────────────────────


class 하루차례(unittest.TestCase):
    """계획 §3 「구역과 순서」 2 — 그날 첫 곳에서 시작해 매번 가장 가까운 곳. 고른 곳은 그대로."""

    def test_첫_곳에서_시작해_가까운_곳으로(self):
        # 위치 10·0·13·3·11·6 → 10 → 11 → 13 → 6 → 3 → 0 (같은 거리 없음).
        positions = [10, 0, 13, 3, 11, 6]
        day = [_spot(i, pos) for i, pos in enumerate(positions)]
        out = reorder_day(day)
        self.assertEqual(
            [p.place_id for p in out],
            ["1000", "1004", "1002", "1005", "1003", "1001"],
        )

    def test_좌표로_잰_거리를_쓴다(self):
        # 경도만 보면 b 가 가깝지만 b 는 위도로 멀다(약 11km). c 는 약 2.6km.
        a = _at(0, 37.50, 127.00)
        b = _at(1, 37.60, 127.005)
        c = _at(2, 37.50, 127.03)
        out = reorder_day([a, b, c])
        self.assertEqual([p.place_id for p in out], ["1000", "1002", "1001"])

    def test_같은_곳들이_남는다(self):
        day = [_spot(i, pos) for i, pos in enumerate([7, 2, 9, 0, 5, 12, 1])]
        out = reorder_day(day)
        self.assertEqual(
            sorted(p.place_id for p in out), sorted(p.place_id for p in day)
        )
        self.assertEqual(out[0].place_id, day[0].place_id)
        _assert_nearest_chain(self, out)
        self.assertNotEqual([p.place_id for p in out], [p.place_id for p in day])

    def test_한두_곳인_날은_그대로(self):
        for day in ([_spot(0, 5)], [_spot(0, 9), _spot(1, 0)], []):
            with self.subTest(n=len(day)):
                self.assertEqual(
                    [p.place_id for p in reorder_day(list(day))],
                    [p.place_id for p in day],
                )


# ── 구역이 프롬프트·일정에 ────────────────────────────────────────────────────


class 구역프롬프트(unittest.TestCase):
    def test_줄마다_zone_이_있다(self):
        places = seoul_city(4)
        rows = _rows(build_city_prompt("서울", 1, "normal", [], places, CFG))
        self.assertEqual(len(rows), 4)
        for row in rows:
            self.assertIn("zone", row, row)
        # 4곳, 하루 5곳 → 1구역.
        self.assertEqual({r["zone"] for r in rows}, {"A"})

    def test_zone_은_하루_곳_수_곱하기_zone_factor_크기로_묶은_구역(self):
        places = seoul_city(20)
        factor = CFG["city"]["zone_factor"]
        for pace, per_day in CFG["city"]["stops_per_day"].items():
            with self.subTest(pace=pace):
                rows = _rows(build_city_prompt("서울", 1, pace, [], places, CFG))
                want = zones(places, per_day * factor)
                self.assertTrue(want)
                self.assertEqual({r["id"]: r["zone"] for r in rows}, want)
                # 구역 수는 올림(20 ÷ (하루 곳 수 × zone_factor)) — 여유 7·보통 4·빡빡 3 (factor 1).
                self.assertEqual(
                    len({r["zone"] for r in rows}),
                    max(1, math.ceil(len(places) / (per_day * factor))),
                    rows,
                )

    def test_zone_factor_를_바꾸면_구역이_커진다(self):
        places = seoul_city(20)
        cfg = json.loads(json.dumps(CFG))
        cfg["city"]["zone_factor"] = 2
        rows = _rows(build_city_prompt("서울", 1, "normal", [], places, cfg))
        # 보통 5곳 × 2 = 구역 크기 10 → 올림(20 ÷ 10) = 2구역.
        self.assertEqual(sorted({r["zone"] for r in rows}), ["A", "B"], rows)

    def test_프롬프트가_zone_을_말한다(self):
        text = build_city_prompt("서울", 1, "normal", [], seoul_city(4), CFG)
        prose = "\n".join(
            line for line in text.splitlines() if not line.strip().startswith("{")
        )
        self.assertIn("zone", prose)
        self.assertIn("구역", prose)
        self.assertTrue(all("zone" in r for r in _rows(text)))


class 도시로짜기_차례(unittest.TestCase):
    """모델이 고른 날을 자른 뒤 날마다 첫 곳에서 가까운 순으로 다시 놓는다."""

    def setUp(self):
        self.places = seoul_city(20)
        self.book = CityBook({"서울": self.places})

    def run_plan(self, client, req=None):
        with redirect_stderr(io.StringIO()):
            return plan_city_with_model(self.book, req or _req(), client)

    def test_지그재그로_고른_날이_가까운_순으로_돌아온다(self):
        # 후보는 앞 15곳(1000~1014). 위치 2·18·1·10·4 → 2 → 1 → 4 → 10 → 18.
        picked = ["1006", "1014", "1003", "1010", "1012"]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": picked}])])
        plan = self.run_plan(client)
        self.assertEqual(_ids(plan), [["1006", "1003", "1012", "1010", "1014"]])
        self.assertTrue(plan.signals["llm"])

    def test_자른_뒤에_차례를_잡는다(self):
        # 보통 5곳에 7곳. 앞 5곳(위치 2·10·16·14·11)만 남고 → 2 → 10 → 11 → 14 → 16.
        # 뒤의 1003(1)·1009(3)은 첫 곳 옆이지만 잘려 나갔다.
        picked = ["1006", "1010", "1008", "1002", "1013", "1003", "1009"]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": picked}])])
        plan = self.run_plan(client)
        self.assertEqual(_ids(plan), [["1006", "1010", "1013", "1002", "1008"]])

    def test_날마다_따로_차례를_잡는다(self):
        # 2일 여유(하루 3곳) — 두 날 다 꽉 차서 채우기가 끼지 않는다.
        day1 = ["1010", "1003", "1007"]  # 10·1·9 → 10·9·1
        day2 = ["1000", "1012", "1006"]  # 0·4·2 → 0·2·4
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": day1}, {"day": 2, "place_ids": day2}])]
        )
        plan = self.run_plan(client, _req(days=2, pace="relaxed"))
        self.assertEqual(
            _ids(plan),
            [["1010", "1007", "1003"], ["1000", "1006", "1012"]],
        )

    def test_모델에게_간_줄에_zone_이_있다(self):
        client = ScriptedClient([_reply([{"day": 1, "place_ids": ["1000"]}])])
        self.run_plan(client)
        rows = _rows(client.seen[0][-1]["content"])
        self.assertEqual(len(rows), 15)
        self.assertTrue(all("zone" in r for r in rows), rows)


# ── 빈자리 채우기 ─────────────────────────────────────────────────────────────


def _zoned(spec):
    """(i, 위치, 구역) 들 → (인기순 후보, place_id → 구역). 위치는 `_spot` 의 경도 자리(0.01 ≈ 880m)."""
    places = [_spot(i, pos) for i, pos, _ in spec]
    return places, {p.place_id: z for p, (_, _, z) in zip(places, spec, strict=True)}


def _all_ids(days) -> list[str]:
    return [p.place_id for d in days for p in d]


class 빈자리채우기(unittest.TestCase):
    """계획 §3 「빈자리 채우기와 더 잘은 구역」 2 — 모자란 날·빠진 날을 같은 구역의 안 쓴 후보로."""

    def test_모자란_날은_같은_구역에서_그날_곳에_가까운_것부터(self):
        # 그날 곳은 위치 5(A). A 의 안 쓴 후보: 0·3·6·10 → 가까운 둘은 6(1칸)·3(2칸).
        # B 의 5.5 는 가장 가깝지만 다른 구역이라 쓰지 않는다.
        cands, zone_of = _zoned(
            [
                (0, 5, "A"),
                (1, 0, "A"),
                (2, 10, "A"),
                (3, 5.5, "B"),
                (4, 3, "A"),
                (5, 6, "A"),
            ]
        )
        by = {p.place_id: p for p in cands}
        out = fill_days([[by["1000"]]], cands, zone_of, 1, 3)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0][0].place_id, "1000")  # 그날 곳은 앞에 그대로
        self.assertEqual(sorted(_pids(out)[0]), ["1000", "1004", "1005"])
        self.assertNotIn("1003", _pids(out)[0])

    def test_가까운_것부터_하나씩(self):
        # 위치 0(A) 에 한 곳, 하루 4곳. A 후보: 1·2·3·9 → 1·2·3 이 들고 9 는 남는다.
        cands, zone_of = _zoned(
            [(0, 0, "A"), (1, 9, "A"), (2, 3, "A"), (3, 1, "A"), (4, 2, "A")]
        )
        out = fill_days([[cands[0]]], cands, zone_of, 1, 4)
        self.assertEqual(sorted(_pids(out)[0]), ["1000", "1002", "1003", "1004"])

    def test_다른_구역에서는_끌어오지_않는다(self):
        # A 에 남은 곳이 하나뿐 — 하루 3곳이라도 B 의 곳은 쓰지 않고 두 곳으로 둔다.
        cands, zone_of = _zoned(
            [(0, 0, "A"), (1, 1, "B"), (2, 2, "B"), (3, 8, "A"), (4, 0.5, "B")]
        )
        out = fill_days([[cands[0]]], cands, zone_of, 1, 3)
        self.assertEqual(_pids(out), [["1000", "1003"]])

    def test_같은_구역에_채울_곳이_없으면_그대로(self):
        cands, zone_of = _zoned([(0, 0, "A"), (1, 1, "B"), (2, 2, "B")])
        out = fill_days([[cands[0]]], cands, zone_of, 1, 3)
        self.assertEqual(_pids(out), [["1000"]])

    def test_꽉_찬_날은_그대로(self):
        cands, zone_of = _zoned(
            [(0, 4, "A"), (1, 0, "A"), (2, 9, "A"), (3, 1, "A"), (4, 2, "A")]
        )
        by = {p.place_id: p for p in cands}
        day = [by["1002"], by["1000"], by["1001"]]  # 순서도 그대로 남아야 한다
        out = fill_days([list(day)], cands, zone_of, 1, 3)
        self.assertEqual(_pids(out), [["1002", "1000", "1001"]])

    def test_여러_구역에_걸친_날은_첫_곳의_구역으로(self):
        # 그날 곳: 위치 0(A)·20(B). 빈자리 하나 — A 의 1 과 B 의 21 이 똑같이 가깝지만 첫 곳의 구역 A 에서.
        cands, zone_of = _zoned(
            [(0, 0, "A"), (1, 20, "B"), (2, 21, "B"), (3, 1, "A"), (4, 19, "B")]
        )
        by = {p.place_id: p for p in cands}
        out = fill_days([[by["1000"], by["1001"]]], cands, zone_of, 1, 3)
        self.assertEqual(sorted(_pids(out)[0]), ["1000", "1001", "1003"])

    def test_여러_구역에_걸친_날_첫_곳이_B_면_B_에서(self):
        cands, zone_of = _zoned([(0, 0, "A"), (1, 20, "B"), (2, 21, "B"), (3, 1, "A")])
        by = {p.place_id: p for p in cands}
        out = fill_days([[by["1001"], by["1000"]]], cands, zone_of, 1, 3)
        self.assertEqual(sorted(_pids(out)[0]), ["1000", "1001", "1002"])

    def test_빠진_날은_가장_인기_있는_안_쓴_곳으로_열고_그_구역으로_채운다(self):
        # 인기순: 1000(A, 씀) · 1001(B) · 1002(A) · 1003(B) · 1004(B) · 1005(B).
        # 둘째 날은 안 쓴 곳 중 1등 1001(B, 위치 20)로 열고, B 에서 가까운 21·22 로 채운다(30 은 남는다).
        cands, zone_of = _zoned(
            [
                (0, 0, "A"),
                (1, 20, "B"),
                (2, 1, "A"),
                (3, 30, "B"),
                (4, 22, "B"),
                (5, 21, "B"),
            ]
        )
        by = {p.place_id: p for p in cands}
        out = fill_days([[by["1000"], by["1002"]]], cands, zone_of, 2, 3)
        self.assertEqual(len(out), 2)
        self.assertEqual(sorted(_pids(out)[0]), ["1000", "1002"])  # A 에 남은 곳이 없다
        self.assertEqual(out[1][0].place_id, "1001")
        self.assertEqual(sorted(_pids(out)[1]), ["1001", "1004", "1005"])

    def test_여는_곳은_다른_구역이어도_된다(self):
        # 첫날 구역 A 가 다 찼어도, 빠진 날은 구역과 상관없이 안 쓴 곳 중 1등으로 연다.
        cands, zone_of = _zoned(
            [
                (0, 0, "A"),
                (1, 1, "A"),
                (2, 2, "A"),
                (3, 40, "C"),
                (4, 20, "B"),
                (5, 41, "C"),
            ]
        )
        by = {p.place_id: p for p in cands}
        out = fill_days([[by["1000"], by["1001"], by["1002"]]], cands, zone_of, 2, 3)
        self.assertEqual(len(out), 2, _pids(out))
        self.assertEqual(_pids(out)[0], ["1000", "1001", "1002"])
        self.assertEqual(out[1][0].place_id, "1003")
        self.assertEqual(
            sorted(_pids(out)[1]), ["1003", "1005"]
        )  # C 에 둘뿐 — B 는 끌어오지 않는다

    def test_빠진_날이_여럿이면_차례로_연다(self):
        # 모델이 한 날만 줬고 3일. 남은 곳 1등부터 날을 연다.
        cands, zone_of = _zoned(
            [
                (0, 0, "A"),
                (1, 20, "B"),
                (2, 40, "C"),
                (3, 1, "A"),
                (4, 21, "B"),
                (5, 41, "C"),
            ]
        )
        by = {p.place_id: p for p in cands}
        out = fill_days([[by["1000"], by["1003"]]], cands, zone_of, 3, 2)
        self.assertEqual(
            [sorted(d) for d in _pids(out)],
            [["1000", "1003"], ["1001", "1004"], ["1002", "1005"]],
        )
        self.assertEqual([d[0].place_id for d in out], ["1000", "1001", "1002"])

    def test_새_날을_열_곳이_없으면_날이_모자란_채로(self):
        # 3곳뿐, 하루 3곳, 2일 — 첫날을 채우고 나면 둘째 날을 열 곳이 없다.
        cands, zone_of = _zoned([(0, 0, "A"), (1, 1, "A"), (2, 2, "A")])
        out = fill_days([[cands[0]]], cands, zone_of, 2, 3)
        self.assertEqual([sorted(d) for d in _pids(out)], [["1000", "1001", "1002"]])

    def test_연_날도_채울_곳이_없으면_짧게_둔다(self):
        # 4곳, 하루 3곳, 2일 — 첫날이 1001·1002 를 가져가고 둘째 날은 1003 하나로 연다.
        cands, zone_of = _zoned([(0, 0, "A"), (1, 1, "A"), (2, 2, "A"), (3, 3, "A")])
        out = fill_days([[cands[0]]], cands, zone_of, 2, 3)
        self.assertEqual(
            [sorted(d) for d in _pids(out)], [["1000", "1001", "1002"], ["1003"]]
        )

    def test_한_곳은_한_번만(self):
        # 두 날이 같은 구역 A 를 쓰고 둘 다 모자라다 — 채운 곳이 겹치지 않는다.
        spec = [(i, i, "A" if i < 10 else "B") for i in range(20)]
        cands, zone_of = _zoned(spec)
        by = {p.place_id: p for p in cands}
        chosen = [[by["1000"]], [by["1009"]]]
        out = fill_days(chosen, cands, zone_of, 3, 4)
        ids = _all_ids(out)
        self.assertEqual(len(ids), len(set(ids)), _pids(out))
        self.assertTrue(set(ids) <= set(by), ids)
        # A 는 10곳 → 두 날이 4곳씩 가져간다. 셋째 날은 안 쓴 곳 중 1등(1004, A)으로 열고
        # A 에 남은 1005 하나만 더한다 — B 는 끌어오지 않는다.
        self.assertEqual([len(d) for d in out], [4, 4, 2], _pids(out))
        for d in out:
            self.assertEqual({zone_of[p.place_id] for p in d}, {"A"})
        self.assertEqual(out[2][0].place_id, "1004")
        self.assertEqual(sorted(_pids(out)[2]), ["1004", "1005"])

    def test_받은_목록을_바꾸지_않는다(self):
        cands, zone_of = _zoned([(0, 0, "A"), (1, 1, "A"), (2, 2, "A")])
        chosen = [[cands[0]]]
        fill_days(chosen, cands, zone_of, 1, 3)
        self.assertEqual(_pids(chosen), [["1000"]])


class 도시로짜기_채우기(unittest.TestCase):
    """모델 경로: 고르기 → 자르기 → 채우기 → 날마다 차례(계획 §3)."""

    def setUp(self):
        # 멀리 떨어진 두 무리(각 5곳). 인기순은 엇갈린다: 북0·남0·북1·남1… → 10곳, 보통 5곳 → 2구역.
        self.north = _cluster(0, "북", 5)  # 1000~1004
        self.south = _cluster(5, "남동", 5)  # 1005~1009
        self.places = [p for pair in zip(self.north, self.south) for p in pair]
        self.book = CityBook({"서울": self.places})

    def run_plan(self, client, req=None):
        with redirect_stderr(io.StringIO()):
            return plan_city_with_model(self.book, req or _req(), client)

    def test_모자란_날은_같은_구역으로_채워진다(self):
        client = ScriptedClient([_reply([{"day": 1, "place_ids": ["1000", "1001"]}])])
        plan = self.run_plan(client)
        got = _ids(plan)
        self.assertEqual(len(got), 1)
        self.assertEqual(sorted(got[0]), [f"{1000 + k}" for k in range(5)])
        self.assertEqual(got[0][0], "1000")
        self.assertTrue(plan.signals["llm"])

    def test_빠진_날은_다른_무리로_열려_채워진다(self):
        # 2일 보통 — 모델이 첫날(북 5곳)만 줬다. 둘째 날은 안 쓴 곳 중 1등(1005, 남동)으로 열린다.
        north_ids = [f"{1000 + k}" for k in range(5)]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": north_ids}])])
        plan = self.run_plan(client, _req(days=2))
        got = _ids(plan)
        self.assertEqual(len(got), 2, got)
        self.assertEqual(got[0], north_ids)
        self.assertEqual(got[1][0], "1005")
        self.assertEqual(sorted(got[1]), [f"{1005 + k}" for k in range(5)])

    def test_채운_뒤에_차례를_잡는다(self):
        # 모델은 북4·북0 두 곳 — 채우면 북 다섯 곳. 첫 곳(북4)에서 가까운 순은 4·3·2·1·0.
        # 채우기 전에 차례를 잡거나 안 잡으면 북0 이 둘째에 온다.
        client = ScriptedClient([_reply([{"day": 1, "place_ids": ["1004", "1000"]}])])
        plan = self.run_plan(client)
        self.assertEqual(_ids(plan), [["1004", "1003", "1002", "1001", "1000"]])

    def test_빠진_날도_차례가_잡힌다(self):
        north_ids = [f"{1000 + k}" for k in range(5)]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": north_ids}])])
        plan = self.run_plan(client, _req(days=2))
        by_id = {p.place_id: p for p in self.places}
        got = _ids(plan)
        self.assertEqual(len(got), 2, got)
        _assert_nearest_chain(self, [by_id[i] for i in got[1]])
        self.assertEqual(got[1], [f"{1005 + k}" for k in range(5)])


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
                        {"day": 1, "place_ids": ["1004", "1001", "1011"]},
                        {"day": 2, "place_ids": ["1002", "1012", "1006"]},
                    ]
                )
            ]
        )
        # 2일 여유(하루 3곳)에 꽉 찬 두 날 — 채우기가 끼지 않는다.
        # 첫날 위치 8·7·17 은 이미 가까운 순, 둘째 날 14·4·2 → 14 → 4 → 2.
        status, body = self.post({"city": "서울", "days": 2, "pace": "relaxed"}, client)
        self.assertEqual(status, 200, body)
        plan = body["plan"]
        self.assertEqual(
            [[s["placeId"] for s in d["stops"]] for d in plan["days"]],
            [[1004, 1001, 1011], [1002, 1012, 1006]],
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
