"""담은 촬영지로 짜기(MZ2AZ-601)의 입구 — 계획 docs/project/plans/saved-ai-course.md §1·§3, 계약 1.33.0 `GuidePlanRequest.placeIds`.

`src/saved_planner.py` 를 들여오지 않는다 — 그 모듈이 없어도 여기 시험은 저마다의 이유로 실패하게.

- `plan_target(body)` → (작품들, 도시, 촬영지 id 들). `titles`·`city`·`placeIds` 중 **정확히 하나**, 빈 목록은 없는 것.
- `PlanRequest.place_ids` (기본 빈 목록).
- `validate_body("/plan", …)` — `placeIds` 는 정수 목록, 40개 이하.
- `SceneApiPlaceBook.place_by_id` — `GET /places/{id}`, 404 면 None.
- 두 서버(운영 `InternalServer` · 시험용 `web.server`)의 `POST /plan` 이 placeIds 요청을 담은 곳 짜기로 보낸다.

실제 모델도 실제 scene-api 도 부르지 않는다(CLAUDE.md §6).
"""

from __future__ import annotations

import io
import json
import threading
import unittest
from contextlib import closing, redirect_stderr
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import urlparse

from src.city_planner import plan_target
from src.internal_server import (
    BodyError,
    InternalHandler,
    InternalServer,
    LimitedDesk,
    validate_body,
)
from src.model_client import ModelError
from src.places import Place
from src.planner import PlanError, PlanRequest
from src.production import RequestBudget
from src.sceneapi import SceneApiPlaceBook
from web.server import Desk, Handler

from tests.contract_schema import load_schema, validate_def
from tests.fixtures import FakeBook, make_place

# ── plan_target ───────────────────────────────────────────────────────────────


class 세갈래(unittest.TestCase):
    def test_placeIds_만_오면_세_번째_갈래(self):
        self.assertEqual(plan_target({"placeIds": [3, 1, 2]}), ([], None, [3, 1, 2]))

    def test_작품과_도시도_세_값을_돌려준다(self):
        self.assertEqual(plan_target({"titles": ["도깨비"]}), (["도깨비"], None, []))
        self.assertEqual(plan_target({"city": "서울"}), ([], "서울", []))

    def test_빈_목록은_없는_것(self):
        # 백엔드의 생성된 요청 객체는 빠진 목록을 빈 목록으로 실어 보낸다.
        self.assertEqual(
            plan_target({"titles": [], "city": None, "placeIds": [7]}),
            ([], None, [7]),
        )
        self.assertEqual(
            plan_target({"titles": ["도깨비"], "placeIds": []}),
            (["도깨비"], None, []),
        )
        self.assertEqual(
            plan_target({"city": "서울", "placeIds": []}), ([], "서울", [])
        )

    def test_둘_이상이거나_하나도_없으면_PlanError(self):
        for body in (
            {"placeIds": [1], "titles": ["도깨비"]},
            {"placeIds": [1], "city": "서울"},
            {"placeIds": [1], "titles": ["도깨비"], "city": "서울"},
            {"placeIds": []},
            {"placeIds": [], "titles": [], "city": "  "},
            {},
        ):
            with self.subTest(body=body), self.assertRaises(PlanError):
                plan_target(body)


class 요청모양(unittest.TestCase):
    def test_place_ids_기본은_빈_목록(self):
        self.assertEqual(PlanRequest(titles=["도깨비"]).place_ids, [])

    def test_place_ids_를_받는다(self):
        req = PlanRequest(titles=[], days=2, place_ids=[11, 22])
        self.assertEqual(req.place_ids, [11, 22])

    def test_기본값은_요청마다_따로(self):
        a, b = PlanRequest(titles=[]), PlanRequest(titles=[])
        a.place_ids.append(1)
        self.assertEqual(b.place_ids, [])


# ── 몸체 검증 ─────────────────────────────────────────────────────────────────


class 몸체검증_담은곳(unittest.TestCase):
    def test_placeIds_는_정수_목록_40개_이하(self):
        invalid = [
            {"placeIds": "1,2"},
            {"placeIds": 7},
            {"placeIds": ["1", "2"]},
            {"placeIds": [1, "2"]},
            {"placeIds": [1.5]},
            {"placeIds": [None]},
            {"placeIds": [{"id": 1}]},
            {"placeIds": list(range(1, 42))},  # 41개
            # 셋 중 정확히 하나
            {"placeIds": [1], "titles": ["도깨비"]},
            {"placeIds": [1], "city": "서울"},
            {"placeIds": []},
        ]
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(BodyError):
                validate_body("/plan", payload)

    def test_올바른_placeIds_요청은_통과(self):
        for payload in (
            {"placeIds": [1, 2, 3], "days": 2, "pace": "packed"},
            {"placeIds": list(range(1, 41))},  # 40개
            {"placeIds": [5], "titles": [], "city": None, "days": 1},
        ):
            with self.subTest(payload=payload):
                validate_body("/plan", payload)


# ── scene-api 창구 ────────────────────────────────────────────────────────────


class id로받기(unittest.TestCase):
    """`SceneApiPlaceBook.place_by_id` — `GET /places/{id}`. 서비스를 거친다(DB 직접 금지)."""

    def test_GET_places_id_로_묻고_촬영지를_돌려준다(self):
        book = SceneApiPlaceBook("http://scene-api.test/v1", timeout=5)
        detail = {
            "id": 7,
            "name": "경복궁",
            "address": "서울특별시 종로구 사직로 161",
            "latitude": 37.5796,
            "longitude": 126.977,
            "contents": [{"contentId": 3, "title": "도깨비", "popularity": 12.0}],
        }
        with patch("urllib.request.urlopen") as opened:
            opened.return_value.__enter__.return_value.read.return_value = json.dumps(
                detail
            ).encode()
            place = book.place_by_id(7)
        self.assertEqual(opened.call_count, 1)
        request = opened.call_args.args[0]
        url = request if isinstance(request, str) else request.full_url
        self.assertEqual(urlparse(url).path, "/v1/places/7")
        self.assertIsNotNone(place)
        self.assertEqual(place.place_id, "7")
        self.assertEqual(place.name, "경복궁")
        self.assertTrue(place.has_coords())

    def test_404_면_None(self):
        book = SceneApiPlaceBook("http://scene-api.test/v1", timeout=5)
        err = HTTPError(
            "http://scene-api.test/v1/places/999", 404, "Not Found", {}, None
        )
        with patch("urllib.request.urlopen", side_effect=err):
            self.assertIsNone(book.place_by_id(999))


# ── HTTP ──────────────────────────────────────────────────────────────────────

SPOTS = [
    make_place(f"{1000 + k}", f"서울{k}", 37.57 + k * 0.002, 126.98 + k * 0.002)
    for k in range(5)
]
BLIND = make_place("1050", "좌표없는곳", None, None)


class SavedBook(FakeBook):
    """id → 촬영지(없으면 None). 작품 창구도 살아 있다 — placeIds 와 titles 를 함께 보낸 요청이 작품으로 짜여 200 이 되면 안 된다."""

    def __init__(self, places: list[Place]):
        super().__init__({"도깨비": list(SPOTS)})
        self.by_id = {int(p.place_id): p for p in places}
        self.id_calls: list[int] = []

    def place_by_id(self, place_id: int) -> Place | None:
        self.id_calls.append(place_id)
        return self.by_id.get(int(place_id))


def _no_model():
    raise ModelError("시험 — 모델 없음")


class _SavedHttp:
    """두 서버에 같은 시험을 돌린다(test_city_planner._CityHttp 와 같은 틀)."""

    def make_server(self, book):  # pragma: no cover - 하위 클래스가 채운다
        raise NotImplementedError

    def setUp(self):
        self.book = SavedBook([*SPOTS, BLIND])
        self.server = self.make_server(self.book)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def post(self, body):
        payload = json.dumps(body, ensure_ascii=False).encode()
        with (
            patch("web.server.ModelClient", side_effect=_no_model),
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

    def test_담은_곳으로_짜면_전부_초안에_든다(self):
        # 당일 여유(하루 3곳)에 5곳 — 하나도 버리지 않는다. 모델이 없어 계산 경로다.
        ids = [1000, 1001, 1002, 1003, 1004]
        status, body = self.post({"placeIds": ids, "days": 1, "pace": "relaxed"})
        self.assertEqual(status, 200, body)
        plan = body["plan"]
        got = [s["placeId"] for d in plan["days"] for s in d["stops"]]
        self.assertEqual(sorted(got), ids)
        validate_def(plan, "guidePlan", load_schema())
        self.assertEqual([e["op"] for e in body["effects"]], ["plan.draft"])
        self.assertEqual(sorted(self.book.id_calls), ids)

    def test_백엔드처럼_빈_titles_와_null_city_를_실어도_담은_곳으로(self):
        status, body = self.post(
            {"titles": [], "city": None, "placeIds": [1000, 1001], "days": 1}
        )
        self.assertEqual(status, 200, body)
        got = [s["placeId"] for d in body["plan"]["days"] for s in d["stops"]]
        self.assertEqual(sorted(got), [1000, 1001])

    def test_없는_id_와_좌표_없는_곳은_dropped(self):
        status, body = self.post(
            {"placeIds": [1000, 1001, 999, 1050], "days": 1, "pace": "normal"}
        )
        self.assertEqual(status, 200, body)
        plan = body["plan"]
        got = [s["placeId"] for d in plan["days"] for s in d["stops"]]
        self.assertEqual(sorted(got), [1000, 1001])
        dropped = [x["placeId"] for d in plan["days"] for x in d["dropped"]]
        self.assertIn(999, dropped)
        self.assertIn(1050, dropped)
        validate_def(plan, "guidePlan", load_schema())

    def test_잘못된_담은_곳_요청은_400(self):
        cases = [
            {"placeIds": [999], "days": 1},  # 쓸 수 있는 곳이 없다
            {"placeIds": [1050], "days": 1},  # 좌표 없음뿐
            {"placeIds": [], "days": 1},
            {"placeIds": ["1000"], "days": 1},
            {"placeIds": list(range(1000, 1041)), "days": 1},  # 41개
            {"placeIds": [1000], "titles": ["도깨비"], "days": 1},
            {"placeIds": [1000], "city": "서울", "days": 1},
        ]
        for payload in cases:
            with self.subTest(payload=payload):
                status, body = self.post(payload)
                self.assertEqual(status, 400, body)
                self.assertEqual(body.get("code"), "INVALID_PARAMETER", body)


class 운영서버_담은곳(_SavedHttp, unittest.TestCase):
    def make_server(self, book):
        class TestHandler(InternalHandler):
            desk = LimitedDesk(book, "test", {})
            budget = RequestBudget()
            active = threading.BoundedSemaphore(4)

        return InternalServer(("127.0.0.1", 0), TestHandler)


class 시험서버_담은곳(_SavedHttp, unittest.TestCase):
    def make_server(self, book):
        class TestHandler(Handler):
            desk = Desk(book, "csv(시험)", {})

        return ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)


if __name__ == "__main__":
    unittest.main()
