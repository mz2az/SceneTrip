"""에이전트가 실제로 내보낸 effects·ui·일정을 정본 스키마로 검사한다.

정본은 `contracts/schemas/guide/effects.schema.json` 한 벌이다(에이전트 쪽 사본은 지웠다). 여기서는
손으로 적은 기대값이 아니라 **실제 코드 경로가 낸 것**을 그 스키마에 넣는다 — 도구 실행, 대본
클라이언트로 돈 대화 턴, HTTP `/plan`·`/guide/chat` 응답. 모델도 바깥 서버도 부르지 않는다.
"""

from __future__ import annotations

import copy
import json
import threading
import unittest
from contextlib import closing
from http.client import HTTPConnection
from unittest.mock import patch

from src.agent import TripGuide
from src.internal_server import InternalHandler, InternalServer, LimitedDesk
from src.llm_planner import PlaceScores, make_llm_plan
from src.model_client import ScriptedClient
from src.plan_flow import PlanFlow
from src.planner import PlanRequest, make_plan, plan_to_api
from src.production import RequestBudget
from src.session import Anchor, Session
from src.tools import run_tool

from tests.contract_schema import (
    SchemaViolation,
    load_schema,
    validate,
    validate_def,
)
from tests.fixtures import FakeBook, PoiBook, make_place

SCHEMA = load_schema()


def numbered_places():
    """scene-api 가 주는 모양 — `placeId` 가 정수 문자열이다. 서울 넷 + 인천 둘."""
    return [
        make_place("1187", "서울중앙고", 37.5826, 126.9910, rank=1, mentions=90),
        make_place("2201", "개뿔", 37.5793, 127.0075, rank=2, mentions=70),
        make_place("2202", "북촌한옥마을", 37.5826, 126.9830, rank=3, mentions=60),
        make_place("2203", "경복궁", 37.5796, 126.9770, rank=4, mentions=50),
        make_place("2204", "덕수궁돌담길", 37.5658, 126.9751, rank=5, mentions=40),
        make_place("3101", "한미서점", 37.4736, 126.6217, rank=6, mentions=80),
        make_place("3102", "제물포구락부", 37.4729, 126.6193, rank=7, mentions=20),
    ]


def numbered_book() -> PoiBook:
    pois = [
        {
            "id": 470912,
            "name": "스타벅스 정동점",
            "category": "카페기타",
            "categoryGroup": "food",
            "address": "서울 중구 정동",
            "latitude": 37.5662,
            "longitude": 126.9752,
            "distanceMeters": 30,
            # 덕수궁돌담길과 같은 곳으로 연결된 편의시설 (MZ2AZ-379).
            "placeId": 2204,
        },
        {
            "id": 481233,
            "name": "카페돌담콩",
            "category": "카페기타",
            "categoryGroup": "food",
            "address": "서울 중구 정동",
            "latitude": 37.5662,
            "longitude": 126.9731,
            "distanceMeters": 410,
        },
    ]
    return PoiBook({"도깨비": numbered_places()}, pois=pois)


def turn_payload(session: Session) -> dict:
    return {"effects": session.effects, "ui": session.ui}


def tool_call(name: str, args: dict, cid: str = "c1") -> dict:
    return {
        "role": "assistant",
        "tool_calls": [
            {
                "id": cid,
                "function": {
                    "name": name,
                    "arguments": json.dumps(args, ensure_ascii=False),
                },
            }
        ],
    }


def context_plan() -> dict:
    """앱이 `context.plan` 에 보내는 편집 사본 — 촬영지·편의시설·개인 핀 줄이 섞여 있다."""

    def stop(order, name, lat, lng, **ids):
        return {
            "order": order,
            "placeId": None,
            "name": name,
            "latitude": lat,
            "longitude": lng,
            "arriveMinute": 540 + order * 60,
            "dwellMinutes": 40,
            **ids,
        }

    return {
        "titles": ["도깨비"],
        "pace": "normal",
        "days": [
            {
                "day": 1,
                "stops": [
                    stop(1, "서울중앙고", 37.5826, 126.9910, placeId=1187),
                    stop(2, "카페돌담콩", 37.5810, 126.9890, poiId=481233),
                    stop(3, "우리 숙소", 37.5800, 126.9870, customPinId=31),
                    stop(4, "북촌한옥마을", 37.5826, 126.9830, placeId=2202),
                ],
            },
            {
                "day": 2,
                "stops": [
                    stop(1, "경복궁", 37.5796, 126.9770, placeId=2203),
                    stop(2, "개뿔", 37.5793, 127.0075, placeId=2201),
                ],
            },
        ],
    }


class 검사기(unittest.TestCase):
    """시험 도우미 자체 — 물지 않는 검사기는 시험 구실을 못 한다."""

    def test_정수는_참거짓을_거절하고_정수를_받는다(self):
        validate(3, {"type": "integer"})
        validate(None, {"type": ["integer", "null"]})
        for bad in (True, False, 3.0, "3"):
            with self.subTest(bad=bad), self.assertRaises(SchemaViolation):
                validate(bad, {"type": "integer"})

    def test_수는_정수와_실수를_받고_참거짓은_거절한다(self):
        validate(1, {"type": "number"})
        validate(1.5, {"type": "number"})
        with self.assertRaises(SchemaViolation):
            validate(False, {"type": "number"})

    def test_enum_const_minimum(self):
        validate("a", {"enum": ["a", "b"]})
        with self.assertRaises(SchemaViolation):
            validate("c", {"enum": ["a", "b"]})
        with self.assertRaises(SchemaViolation):
            validate(True, {"enum": [1]})  # 형이 다르면 같은 값이 아니다
        validate("x", {"const": "x"})
        with self.assertRaises(SchemaViolation):
            validate("y", {"const": "x"})
        validate(1, {"type": "integer", "minimum": 1})
        with self.assertRaises(SchemaViolation):
            validate(0, {"type": "integer", "minimum": 1})

    def test_위반_자리를_포인터로_알려_준다(self):
        schema = {
            "$defs": {"n": {"type": "integer"}},
            "type": "object",
            "required": ["a"],
            "properties": {"a": {"type": "array", "items": {"$ref": "#/$defs/n"}}},
        }
        validate({"a": [1, 2]}, schema)
        with self.assertRaises(SchemaViolation) as caught:
            validate({"a": [1, "x"]}, schema)
        self.assertEqual(caught.exception.path, "/a/1")
        with self.assertRaises(SchemaViolation) as caught:
            validate({}, schema)
        self.assertIn("'a'", caught.exception.message)

    def test_설명_키워드는_무시하고_모르는_키워드는_실패한다(self):
        validate(1, {"type": "integer", "description": "x", "설명": "y", "예시": {}})
        with self.assertRaises(SchemaViolation):
            validate("abc", {"type": "string", "maxLength": 2})

    def test_정본_전체를_검사기가_안다(self):
        """정본이 검사기가 모르는 키워드를 쓰기 시작하면 여기서 먼저 걸린다."""
        from tests.contract_schema import _ANNOTATIONS, _KNOWN

        def schemas(node):
            yield node
            for key in ("properties", "$defs"):
                for sub in (node.get(key) or {}).values():
                    yield from schemas(sub)
            if isinstance(node.get("items"), dict):
                yield from schemas(node["items"])

        for node in schemas(SCHEMA):
            unknown = set(node) - _KNOWN - _ANNOTATIONS
            self.assertFalse(unknown, node)
        # 정본에 적힌 예시도 정본을 지킨다.
        validate(SCHEMA["예시"], SCHEMA)


class 일정초안(unittest.TestCase):
    """(a) 초안 — 결정적 엔진·모델 일정·한 문장 경로·HTTP `/plan`."""

    def test_엔진_일정이_guidePlan_이다(self):
        for days in (1, 2, 3):
            with self.subTest(days=days):
                plan = make_plan(
                    numbered_book(), PlanRequest(titles=["도깨비"], days=days)
                )
                validate_def(plan_to_api(plan), "guidePlan", SCHEMA)

    def test_id_없는_창구의_일정도_guidePlan_이다(self):
        """CSV 창구의 일정은 `placeId` 가 null 이다 — 그래도 모양은 맞아야 한다."""
        book = FakeBook(
            {
                "도깨비": [
                    make_place(f"st_{i}", n, *ll)
                    for i, (n, ll) in enumerate(
                        [
                            ("가", (37.58, 126.99)),
                            ("나", (37.57, 126.98)),
                            ("다", (37.56, 126.97)),
                        ]
                    )
                ]
            }
        )
        wire = plan_to_api(make_plan(book, PlanRequest(titles=["도깨비"], days=1)))
        validate_def(wire, "guidePlan", SCHEMA)
        self.assertTrue(all(s["placeId"] is None for s in wire["days"][0]["stops"]))

    def test_모델이_짠_일정도_guidePlan_이다(self):
        """빈 날을 채워 넣는 경로까지 — 정지점 없는 날도 계약 모양이어야 한다."""
        client = ScriptedClient(
            [
                {
                    "role": "assistant",
                    "content": json.dumps(
                        {"days": [{"day": 1, "place_ids": ["2202", "1187", "2201"]}]}
                    ),
                }
            ]
        )
        plan = make_llm_plan(
            numbered_book(),
            PlanRequest(titles=["도깨비"], days=3, pace="packed"),
            client,
            scores=PlaceScores({}),
            cfg={
                "enabled": True,
                "candidates_per_title": 40,
                "pace": {"packed": "빡빡하게"},
                "pace_fallback": "packed",
            },
        )
        wire = plan_to_api(plan)
        self.assertEqual(len(wire["days"]), 3)
        validate_def(wire, "guidePlan", SCHEMA)

    def test_한_문장_경로의_일정도_guidePlan_이다(self):
        """CLI 한 문장 경로(`PlanFlow`)도 계약 모양의 일정을 만든다 (MZ2AZ-435).

        `understand` 는 `plan_course` 도구 계약(한국어 열거 「느긋하게·보통·빡빡하게」)으로 검증해
        기본값 「보통」 을 채운다. 예전에는 그 말이 그대로 `PlanRequest` 로 가 `make_plan` 이
        「모르는 여행 속도」 로 거절했다 — 이제 도구 호출과 같이 `words_for_code` 로 되돌린다.
        """
        client = ScriptedClient(
            [
                {"role": "assistant", "content": '{"titles": ["도깨비"], "days": 2}'},
                # 일정은 모델이 짠다(plan_with_model) — 날짜별 성지 id.
                {
                    "role": "assistant",
                    "content": '{"days": [{"day": 1, "place_ids": [1187, 2202]},'
                    ' {"day": 2, "place_ids": [2203, 2204]}]}',
                },
                {"role": "assistant", "content": "이틀 일정입니다."},
            ]
        )
        _, plan, _, _ = PlanFlow(Session(book=numbered_book()), client).run(
            "도깨비 1박 2일"
        )
        self.assertTrue(plan.signals.get("llm"), "모델이 짠 일정이어야 한다")
        validate_def(plan_to_api(plan), "guidePlan", SCHEMA)

    def test_한_문장_경로가_예전_속도_값도_받는다(self):
        """`prompts/plan_intent.txt` 대로 모델이 "normal" 같은 예전 값을 보내도 거절하지 않는다."""
        client = ScriptedClient(
            [
                {
                    "role": "assistant",
                    "content": '{"titles": ["도깨비"], "days": 1, "pace": "packed"}',
                },
                {
                    "role": "assistant",
                    "content": '{"days": [{"day": 1, "place_ids": [1187, 2202, 2203]}]}',
                },
                {"role": "assistant", "content": "하루 일정입니다."},
            ]
        )
        _, plan, _, _ = PlanFlow(Session(book=numbered_book()), client).run(
            "도깨비 하루 빡빡하게"
        )
        validate_def(plan_to_api(plan), "guidePlan", SCHEMA)


class HTTP응답(unittest.TestCase):
    """백엔드가 실제로 받는 몸체 — `/plan` 과 `/guide/chat`."""

    def setUp(self):
        book = numbered_book()

        class TestHandler(InternalHandler):
            desk = LimitedDesk(book, "test", {})
            budget = RequestBudget()
            active = threading.BoundedSemaphore(4)

        self.handler = TestHandler
        self.book = book
        self.server = InternalServer(("127.0.0.1", 0), TestHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def post(self, path, body):
        payload = json.dumps(body, ensure_ascii=False).encode()
        with closing(HTTPConnection(*self.server.server_address, timeout=5)) as conn:
            conn.request(
                "POST", path, body=payload, headers={"Content-Type": "application/json"}
            )
            response = conn.getresponse()
            return response.status, json.loads(response.read())

    def test_plan_응답의_일정과_effects_가_계약_모양이다(self):
        # 모델 경로를 끈다 — 결정적 엔진만 돈다(네트워크 없음).
        with patch("src.llm_planner.load_config", return_value={"enabled": False}):
            status, body = self.post("/plan", {"titles": ["도깨비"], "days": 2})
        self.assertEqual(status, 200, body)
        validate_def(body["plan"], "guidePlan", SCHEMA)
        validate({"effects": body["effects"], "ui": body["ui"]}, SCHEMA)
        self.assertEqual([e["op"] for e in body["effects"]], ["plan.draft"])

    def test_guide_chat_의_고친_일정이_계약_모양이고_정체를_돌려준다(self):
        client = ScriptedClient(
            [
                tool_call("revise_plan", {"day": 1, "remove": ["북촌한옥마을"]}),
                {"role": "assistant", "content": "뺐어요."},
            ]
        )
        guide = TripGuide(Session(book=self.book), client, config={})
        self.handler.desk.guides = {"s": guide}
        self.handler.desk.seen = {"s": float("inf")}
        status, body = self.post(
            "/guide/chat",
            {
                "sessionId": "s",
                "messages": [
                    {"role": "user", "content": "1일차에서 북촌한옥마을 빼 줘"}
                ],
                "context": {"plan": context_plan()},
            },
        )
        self.assertEqual(status, 200, body)
        validate({"effects": body["effects"], "ui": body["ui"]}, SCHEMA)
        revise = next(e for e in body["effects"] if e["op"] == "plan.revise")
        stops = {s["name"]: s for s in revise["plan"]["days"][0]["stops"]}
        self.assertEqual(stops["카페돌담콩"]["poiId"], 481233)
        self.assertEqual(stops["우리 숙소"]["customPinId"], 31)
        self.assertNotIn("북촌한옥마을", stops)


class 대화턴(unittest.TestCase):
    """(b)(c)(d) 대본 클라이언트로 돈 한 턴의 effects·ui."""

    def ask(self, session, *steps, text="부탁해"):
        client = ScriptedClient([*steps, {"role": "assistant", "content": "됐어요."}])
        turn = TripGuide(session, client, config={"max_tool_rounds": 6}).ask(text)
        self.assertFalse([r.name for r in turn.tool_runs if r.refused], turn.tool_runs)
        payload = {"effects": turn.effects, "ui": turn.ui}
        validate(payload, SCHEMA)
        return turn

    def test_일정을_짜면_draft_와_화면_지시가_계약_모양이다(self):
        turn = self.ask(
            Session(book=numbered_book()),
            tool_call("plan_course", {"titles": ["도깨비"], "days": 2}),
        )
        self.assertEqual([e["op"] for e in turn.effects], ["plan.draft"])
        self.assertEqual([u["op"] for u in turn.ui], ["course.open", "sheet.collapse"])

    def test_고친_일정이_받은_정체를_그대로_돌려준다(self):
        """MZ2AZ-424 — `poiId`·`customPinId` 는 받은 값 그대로, 촬영지 줄에는 없다."""
        session = Session(book=numbered_book())
        session.adopt_context({"plan": context_plan()})
        turn = self.ask(
            session, tool_call("revise_plan", {"day": 1, "remove": ["북촌한옥마을"]})
        )
        revise = next(e for e in turn.effects if e["op"] == "plan.revise")
        self.assertEqual(revise["remove"], ["북촌한옥마을"])
        got = {
            s["name"]: (s["placeId"], s["poiId"], s["customPinId"])
            for d in revise["plan"]["days"]
            for s in d["stops"]
        }
        self.assertEqual(
            got,
            {
                "서울중앙고": (1187, None, None),
                "카페돌담콩": (None, 481233, None),
                "우리 숙소": (None, None, 31),
                "경복궁": (2203, None, None),
                "개뿔": (2201, None, None),
            },
        )
        self.assertEqual([u["op"] for u in turn.ui], ["course.focus"])

    def test_넣기도_계약_모양이다(self):
        session = Session(book=numbered_book())
        session.adopt_context({"plan": context_plan()})
        turn = self.ask(
            session,
            tool_call("search_places", {"query": "덕수궁"}),
            tool_call("revise_plan", {"day": 2, "add": ["덕수궁돌담길"]}, "c2"),
        )
        self.assertIn("plan.revise", [e["op"] for e in turn.effects])

    def test_옮기기도_계약_모양이다(self):
        session = Session(book=numbered_book())
        session.adopt_context({"plan": context_plan()})
        turn = self.ask(
            session, tool_call("move_stop", {"name": "경복궁", "to_day": 1})
        )
        move = next(e for e in turn.effects if e["op"] == "plan.move")
        self.assertEqual((move["day"], move["toDay"]), (2, 1))
        pins = [s for d in move["plan"]["days"] for s in d["stops"] if s["customPinId"]]
        self.assertEqual([s["customPinId"] for s in pins], [31])

    def test_담기와_빼기가_정수_placeId_로_나간다(self):
        session = Session(book=numbered_book())
        turn = self.ask(
            session,
            tool_call("list_title_places", {"title": "도깨비"}),
            tool_call("update_cart", {"action": "add", "name": "경복궁"}, "c2"),
            tool_call("update_cart", {"action": "remove", "name": "개뿔"}, "c3"),
        )
        carts = [(e["op"], e["placeId"]) for e in turn.effects]
        self.assertEqual(carts, [("cart.add", 2203), ("cart.remove", 2201)])

    def test_편의시설_이름으로_담아도_촬영지_id_다(self):
        session = Session(book=numbered_book())
        session.here = Anchor("현위치", 37.5662, 126.9752)
        turn = self.ask(
            session,
            tool_call("poi_nearby", {"group": "음식"}),
            tool_call(
                "update_cart", {"action": "add", "name": "스타벅스 정동점"}, "c2"
            ),
        )
        self.assertEqual(
            [(e["op"], e["placeId"]) for e in turn.effects], [("cart.add", 2204)]
        )

    def test_지도_지시가_계약_모양이다(self):
        """map.focus(검색·작품·근처·편의시설)와 place.card."""
        session = Session(book=numbered_book())
        session.here = Anchor("현위치", 37.5800, 126.9850)
        turn = self.ask(
            session,
            tool_call("search_places", {"query": "궁"}),
            tool_call("list_title_places", {"title": "도깨비"}, "c2"),
            tool_call("places_near", {"near": "현위치", "radius_m": 3000}, "c3"),
            tool_call("place_detail", {"name": "경복궁"}, "c4"),
            tool_call("poi_nearby", {"group": "음식"}, "c5"),
        )
        ops = [u["op"] for u in turn.ui]
        self.assertEqual(
            ops, ["map.focus", "map.focus", "map.focus", "place.card", "map.focus"]
        )
        self.assertEqual(turn.ui[3]["placeId"], 2203)


class 검사가문다(unittest.TestCase):
    """일부러 틀린 것은 거절한다 — 통과만 하는 검사는 아무것도 지키지 못한다."""

    def good_turn(self) -> dict:
        session = Session(book=numbered_book())
        session.adopt_context({"plan": context_plan()})
        run_tool("revise_plan", {"day": 1, "remove": ["북촌한옥마을"]}, session)
        session.remember(numbered_places())
        run_tool("update_cart", {"action": "add", "name": "경복궁"}, session)
        payload = copy.deepcopy(turn_payload(session))
        validate(payload, SCHEMA)
        return payload

    def assertRejected(self, payload, path):
        with self.assertRaises(SchemaViolation) as caught:
            validate(payload, SCHEMA)
        self.assertEqual(caught.exception.path, path)

    def test_모르는_op(self):
        p = self.good_turn()
        p["effects"][0]["op"] = "plan.save"
        self.assertRejected(p, "/effects/0/op")
        p = self.good_turn()
        p["ui"][0]["op"] = "map.zoom"
        self.assertRejected(p, "/ui/0/op")

    def test_문자열_placeId(self):
        p = self.good_turn()
        cart = next(i for i, e in enumerate(p["effects"]) if e["op"] == "cart.add")
        p["effects"][cart]["placeId"] = "2203"
        self.assertRejected(p, f"/effects/{cart}/placeId")

    def test_참거짓_placeId(self):
        p = self.good_turn()
        p["effects"][0]["plan"]["days"][0]["stops"][0]["placeId"] = True
        self.assertRejected(p, "/effects/0/plan/days/0/stops/0/placeId")

    def test_op_가_없다(self):
        p = self.good_turn()
        del p["effects"][0]["op"]
        self.assertRejected(p, "/effects/0")

    def test_정지점_필수_필드가_없다(self):
        p = self.good_turn()
        del p["effects"][0]["plan"]["days"][1]["stops"][0]["arriveMinute"]
        self.assertRejected(p, "/effects/0/plan/days/1/stops/0")

    def test_일차가_0(self):
        p = self.good_turn()
        p["effects"][0]["day"] = 0
        self.assertRejected(p, "/effects/0/day")

    def test_거리_기준이_다르다(self):
        p = self.good_turn()
        p["effects"][0]["plan"]["travelBasis"] = "straight_line"
        self.assertRejected(p, "/effects/0/plan/travelBasis")

    def test_poiId_문자열(self):
        p = self.good_turn()
        p["effects"][0]["plan"]["days"][0]["stops"][1]["poiId"] = "481233"
        self.assertRejected(p, "/effects/0/plan/days/0/stops/1/poiId")


class CSV창구의_장바구니(unittest.TestCase):
    """CSV 창구(시험·데모용)는 `st_04123` 같은 문자열 id 를 들고 있다.

    정본은 `placeId` 를 `integer|null` 로 정했으므로 숫자가 아닌 id 는 null 로 내보낸다
    (MZ2AZ-435). 예전에는 문자열을 그대로 내보내 계약을 어겼다.
    """

    def test_문자열_id_장소를_담으면_placeId_는_null(self):
        session = Session(
            book=FakeBook({"도깨비": [make_place("st_1", "가", 37.5, 127.0)]})
        )
        session.remember(session.book.catalog["도깨비"])
        run_tool("update_cart", {"action": "add", "name": "가"}, session)
        payload = turn_payload(session)
        validate(payload, SCHEMA)
        self.assertIsNone(payload["effects"][0]["placeId"])


if __name__ == "__main__":
    unittest.main()
