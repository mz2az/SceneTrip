"""AI 일정 생성(src/llm_planner.py) 시험. 실제 모델은 부르지 않는다 — 가짜 클라이언트다."""

from __future__ import annotations

import json
import unittest

from src.deepseek import ModelError, ScriptedClient
from src.llm_planner import (
    SEOUL,
    LlmPlanError,
    PlaceScores,
    build_prompt,
    collect,
    location,
    make_llm_plan,
    parse_days,
)
from src.planner import PlanRequest, plan_to_api

from tests.fixtures import FakeBook, make_place


def _at(pid, name, lat, lng, address, title="도깨비"):
    p = make_place(pid, name, lat, lng, title=title)
    p.address = address
    return p


# 서울 셋, 인천 하나(서울과 30km 남짓), 강릉 둘(서울과 150km 넘게).
SEOUL3 = [
    _at("101", "덕수궁 돌담길", 37.5676, 126.9734, "서울 중구 덕수궁길 116"),
    _at("102", "운현궁 양관", 37.5767, 126.9882, "서울 종로구 삼일대로 460"),
    _at("103", "용답역 육교", 37.5620, 127.0507, "서울 성동구 용답길 86"),
]
INCHEON1 = [_at("201", "배다리 헌책방골목", 37.4727, 126.6366, "인천 동구 금곡로 7-1")]
GANGNEUNG2 = [
    _at("301", "주문진 방사제", 37.8799, 128.8342, "강원특별자치도 강릉시 주문진읍"),
    _at("302", "주문진항", 37.8928, 128.8293, "강원 강릉시 주문진읍 해안로 1768"),
]
ALL = SEOUL3 + INCHEON1 + GANGNEUNG2
SCORES = PlaceScores(
    {
        "도깨비": {
            "주문진 방사제": 39.1,
            "배다리 헌책방골목": 15.4,
            "운현궁 양관": 14.7,
            "용답역 육교": 10.1,
            "덕수궁 돌담길": 9.0,
            "주문진항": 5.3,
        }
    }
)
CFG = {
    "enabled": True,
    "candidates_per_title": 40,
    "pace": {"relaxed": "널널하게 — 천천히", "packed": "빡빡하게 — 부지런히"},
    "pace_fallback": "relaxed",
}


def _reply(days):
    return {"role": "assistant", "content": json.dumps({"days": days})}


class LocationTest(unittest.TestCase):
    def test_first_two_words_of_address(self):
        self.assertEqual(location("강원특별자치도 강릉시 주문진읍"), "강원 강릉시")
        self.assertEqual(location("서울 중구 덕수궁길 116"), "서울 중구")
        self.assertEqual(location("서울특별시 종로구 삼청동"), "서울 종로구")
        self.assertEqual(location(""), "")


class PromptTest(unittest.TestCase):
    def setUp(self):
        _, self.places, self.works = collect(FakeBook({"도깨비": ALL}), ["도깨비"], 40)

    def test_prompt_has_location_scores_and_seoul_start(self):
        text = build_prompt(
            ["도깨비"], 3, "packed", [], self.places, self.works, SCORES, CFG
        )
        self.assertIn("서울에서 출발", text)
        self.assertIn('"location": "강원 강릉시"', text)
        self.assertIn('"popularity": 39.1', text)
        self.assertIn('"lat": 37.8799', text)

    def test_no_rules_and_no_region_grouping(self):
        # 2026-10-02 결정: 규칙 문장도, 지역 묶음도 주지 않는다. 위치만 붙인다.
        text = build_prompt(
            ["도깨비"], 3, "packed", [], self.places, self.works, SCORES, CFG
        )
        for banned in ("하루는 한 지역", "통째로 빼", "같은 작품 안에서만", "[강릉"):
            self.assertNotIn(banned, text)

    def test_pace_is_described_without_numbers(self):
        text = build_prompt(
            ["도깨비"], 2, "packed", [], self.places, self.works, SCORES, CFG
        )
        self.assertIn("빡빡하게 — 부지런히", text)

    def test_unknown_pace_uses_fallback(self):
        text = build_prompt(
            ["도깨비"], 2, "normal", [], self.places, self.works, SCORES, CFG
        )
        self.assertIn("널널하게 — 천천히", text)

    def test_must_is_passed(self):
        text = build_prompt(
            ["도깨비"],
            2,
            "relaxed",
            ["주문진 방사제"],
            self.places,
            self.works,
            SCORES,
            CFG,
        )
        self.assertIn("꼭 갈 곳: 주문진 방사제", text)

    def test_most_popular_place_comes_first(self):
        text = build_prompt(
            ["도깨비"], 2, "relaxed", [], self.places, self.works, SCORES, CFG
        )
        self.assertLess(text.index("주문진 방사제"), text.index("덕수궁 돌담길"))


BY_ID = {p.place_id: p for p in ALL}


class ParseTest(unittest.TestCase):
    by_id = BY_ID

    def test_parses_days_in_order(self):
        text = json.dumps(
            {
                "days": [
                    {"day": 2, "place_ids": ["301"]},
                    {"day": 1, "place_ids": ["101", "102"]},
                ]
            }
        )
        days = parse_days(text, self.by_id, 2)
        self.assertEqual(
            [[p.name for p in d] for d in days],
            [["덕수궁 돌담길", "운현궁 양관"], ["주문진 방사제"]],
        )

    def test_unknown_id_rejects_whole_answer(self):
        text = json.dumps({"days": [{"day": 1, "place_ids": ["101", "999"]}]})
        with self.assertRaises(LlmPlanError):
            parse_days(text, self.by_id, 1)

    def test_broken_json_is_rejected(self):
        with self.assertRaises(LlmPlanError):
            parse_days("일정은 다음과 같습니다", self.by_id, 1)

    def test_fenced_json_is_accepted(self):
        text = '```json\n{"days":[{"day":1,"place_ids":["101"]}]}\n```'
        self.assertEqual(parse_days(text, self.by_id, 1)[0][0].place_id, "101")

    def test_duplicate_kept_on_first_day_only(self):
        text = json.dumps(
            {
                "days": [
                    {"day": 1, "place_ids": ["101"]},
                    {"day": 2, "place_ids": ["101", "102"]},
                ]
            }
        )
        days = parse_days(text, self.by_id, 2)
        self.assertEqual([p.place_id for p in days[1]], ["102"])

    def test_empty_answer_is_rejected(self):
        with self.assertRaises(LlmPlanError):
            parse_days(
                json.dumps({"days": [{"day": 1, "place_ids": []}]}), self.by_id, 1
            )


class MakePlanTest(unittest.TestCase):
    def test_model_choice_becomes_plan_in_its_order(self):
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": ["102", "101", "103"]},
                        {"day": 2, "place_ids": ["301", "302"]},
                    ]
                )
            ]
        )
        plan = make_llm_plan(
            FakeBook({"도깨비": ALL}),
            PlanRequest(titles=["도깨비"], days=2, pace="packed"),
            client,
            scores=SCORES,
            cfg=CFG,
        )
        self.assertEqual(
            [l.place.place_id for l in plan.days[0].legs], ["102", "101", "103"]
        )
        self.assertEqual(plan.request.start, SEOUL)
        api = plan_to_api(plan)
        self.assertEqual(len(api["days"]), 2)
        self.assertEqual(api["days"][1]["stops"][0]["placeId"], 301)

    def test_asks_for_json(self):
        seen = {}

        class Spy(ScriptedClient):
            def chat(self, messages, tools=None, budget=None, json_mode=False):
                seen["json_mode"] = json_mode
                return super().chat(messages, tools, budget)

        make_llm_plan(
            FakeBook({"도깨비": ALL}),
            PlanRequest(titles=["도깨비"], days=1),
            Spy([_reply([{"day": 1, "place_ids": ["101"]}])]),
            scores=SCORES,
            cfg=CFG,
        )
        self.assertTrue(seen["json_mode"])

    def test_avoid_is_not_offered(self):
        client = ScriptedClient([_reply([{"day": 1, "place_ids": ["101"]}])])
        make_llm_plan(
            FakeBook({"도깨비": ALL}),
            PlanRequest(titles=["도깨비"], days=1, avoid=["주문진항"]),
            client,
            scores=SCORES,
            cfg=CFG,
        )
        self.assertNotIn("주문진항", client.seen[0][0]["content"])

    def test_missing_days_are_padded(self):
        client = ScriptedClient([_reply([{"day": 1, "place_ids": ["101"]}])])
        plan = make_llm_plan(
            FakeBook({"도깨비": ALL}),
            PlanRequest(titles=["도깨비"], days=3),
            client,
            scores=SCORES,
            cfg=CFG,
        )
        self.assertEqual(len(plan.days), 3)

    def test_model_error_becomes_plan_error(self):
        class Down:
            def chat(self, *a, **k):
                raise ModelError("늦었다")

        with self.assertRaises(LlmPlanError):
            make_llm_plan(
                FakeBook({"도깨비": ALL}),
                PlanRequest(titles=["도깨비"], days=1),
                Down(),
                scores=SCORES,
                cfg=CFG,
            )

    def test_unknown_title_is_plan_error(self):
        with self.assertRaises(LlmPlanError):
            make_llm_plan(
                FakeBook({"도깨비": ALL}),
                PlanRequest(titles=["없는작품"], days=1),
                ScriptedClient([]),
                scores=SCORES,
                cfg=CFG,
            )


class ScoresTest(unittest.TestCase):
    def test_score_is_per_title(self):
        s = PlaceScores(
            {"도깨비": {"청라호수공원": 10.3}, "더 글로리": {"청라호수공원": 16.4}}
        )
        place = make_place("1", "청라호수공원", 37.5, 126.6)
        self.assertEqual(s.get("도깨비", place), 10.3)
        self.assertEqual(s.get("더 글로리", place), 16.4)
        self.assertEqual(s.get("없는작품", place), 0.0)

    def test_renamed_place_is_found_by_coordinates(self):
        # 폭싹 행은 「고창 학원농장」, 도깨비 행은 「고창 학원농장 메밀밭」 — DB 는 앞 이름만 남긴다.
        s = PlaceScores(
            {
                "도깨비": {
                    "고창 학원농장 메밀밭": {
                        "score": 14.3,
                        "lat": 35.37544,
                        "lng": 126.54259,
                    }
                }
            }
        )
        db_place = make_place("69", "고창 학원농장", 35.3754435, 126.5425884)
        self.assertEqual(s.get("도깨비", db_place), 14.3)

    def test_far_place_is_not_matched_by_coordinates(self):
        s = PlaceScores(
            {"도깨비": {"주문진 방사제": {"score": 39.1, "lat": 37.88, "lng": 128.83}}}
        )
        self.assertEqual(
            s.get("도깨비", make_place("1", "다른 곳", 37.89, 128.83)), 0.0
        )

    def test_api_value_wins_over_table(self):
        from src.llm_planner import popularity
        from src.places import Scene

        place = make_place("1", "주문진 방사제", 37.88, 128.83)
        place.scenes = [Scene("도깨비", "", "", "", "", 9999, 41.0)]
        self.assertEqual(popularity(place, "도깨비", SCORES), 41.0)

    def test_table_used_when_api_has_no_value(self):
        from src.llm_planner import popularity

        place = make_place("1", "주문진 방사제", 37.88, 128.83)  # popularity 0
        self.assertEqual(popularity(place, "도깨비", SCORES), 39.1)

    def test_shipped_table_loads(self):
        s = PlaceScores.load()
        self.assertGreater(
            s.get("도깨비", make_place("x", "강릉 주문진 방사제", 1, 1)), 30
        )


if __name__ == "__main__":
    unittest.main()
