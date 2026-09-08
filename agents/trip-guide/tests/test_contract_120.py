"""가이드 계약 1.2.0 에 맞춘 것들을 지키는 시험 (MZ2AZ-320).

여기서 지키려는 것은 하나다 — **화면이 정본이다.** 에이전트가 따로 들고 있던
목록이 화면과 어긋나면서 「지도의 2 번」과 「내 2 번」이 달라졌고, 앱에서 담은 것은
우리에게 오지 않으니 「담은 걸로 동선」은 애초에 돌 수 없었다.

경계의 이름도 함께 잡아 둔다. 백엔드는 프록시라 옮겨 담지 않으므로, 여기서 나간
이름이 그대로 앱까지 간다.
"""

from __future__ import annotations

import json
import pathlib
import unittest

from src.planner import PlanRequest, make_plan, plan_to_api
from src.session import Session
from src.tools import load_tool_specs, run_tool
from web.server import _api_args, _bad

from tests.fixtures import SEOUL, poi_book, seoul_incheon_book


def stop(number: int, place) -> dict:
    return {
        "number": number,
        "name": place.name,
        "latitude": place.lat,
        "longitude": place.lng,
    }


class 화면의번호(unittest.TestCase):
    """「2번」은 앱이 보낸 번호다. 우리가 매기지 않는다."""

    def setUp(self):
        self.session = Session(book=poi_book())
        self.session.adopt_context(
            {"stops": [stop(1, SEOUL[0]), stop(2, SEOUL[1]), stop(3, SEOUL[2])]}
        )

    def test_앱이_보낸_번호로_기준점을_잡는다(self):
        anchor, err = self.session.resolve_anchor("2")
        self.assertIsNone(err)
        self.assertEqual(anchor.label, SEOUL[1].name)

    def test_번호가_비어_있어도_그_번호를_쓴다(self):
        """다녀온 곳을 접으면 화면에 1·3 만 남는다. 3 은 여전히 3 이다.

        목록의 몇 번째로 세면 여기서 어긋난다 — 3 번을 물었는데 두 번째 것이 나온다.
        """
        self.session.adopt_context({"stops": [stop(1, SEOUL[0]), stop(3, SEOUL[2])]})
        anchor, err = self.session.resolve_anchor("3")
        self.assertIsNone(err)
        self.assertEqual(anchor.label, SEOUL[2].name)

    def test_없는_번호는_화면에_있는_번호를_알려_준다(self):
        anchor, err = self.session.resolve_anchor("9")
        self.assertIsNone(anchor)
        self.assertIn("1, 2, 3", err)

    def test_안_보내면_번호로_지목할_수_없다(self):
        """지난 턴의 번호를 이어 쓰지 않는다. 화면이 바뀌었을 수 있다."""
        self.session.adopt_context({})
        anchor, err = self.session.resolve_anchor("2")
        self.assertIsNone(anchor)
        self.assertIn("번호", err)

    def test_고른_곳은_선택으로_지목한다(self):
        self.session.adopt_context({"stops": [], "picked": stop(0, SEOUL[4])})
        anchor, err = self.session.resolve_anchor("선택")
        self.assertIsNone(err)
        self.assertEqual(anchor.label, SEOUL[4].name)

    def test_맥락에_화면의_번호가_실린다(self):
        block = self.session.context_block()
        self.assertIn("2번 — " + SEOUL[1].name, block)


class 장바구니는기억하지않는다(unittest.TestCase):
    """정본은 DB 다. 우리가 목록을 들면 화면과 반드시 갈린다."""

    def setUp(self):
        self.session = Session(book=seoul_incheon_book())
        self.session.remember(SEOUL[:3])

    def test_담아도_세션에_목록이_생기지_않는다(self):
        run_tool("update_cart", {"action": "add", "name": SEOUL[0].name}, self.session)
        self.assertFalse(hasattr(self.session, "cart"))

    def test_담기는_저장_지시로만_나간다(self):
        out = run_tool(
            "update_cart", {"action": "add", "name": SEOUL[0].name}, self.session
        )
        ops = [e["op"] for e in self.session.effects]
        self.assertEqual(ops, ["cart.add"])
        # 담긴 목록을 돌려주면 모델이 그것을 읊고, 그것은 화면과 다르다.
        self.assertNotIn("담긴 곳", out)

    def test_담기지_않은_것도_뺄_수_있다(self):
        """예전에는 우리 목록에 없으면 거절했다. 목록이 없으니 판단할 수 없다."""
        run_tool(
            "update_cart", {"action": "remove", "name": SEOUL[0].name}, self.session
        )
        self.assertEqual([e["op"] for e in self.session.effects], ["cart.remove"])


class 도구목록(unittest.TestCase):
    def test_계약과_같은_아홉_개다(self):
        names = {t["function"]["name"] for t in load_tool_specs()}
        self.assertEqual(len(names), 9)
        self.assertNotIn("draft_course", names)

    def test_없는_도구를_부르면_거절한다(self):
        session = Session(book=seoul_incheon_book())
        out = run_tool("draft_course", {}, session)
        self.assertIn("결과없음", out)


class 편의시설도지도에(unittest.TestCase):
    def test_보여_준_편의시설을_기억한다(self):
        """예전에는 말로만 알려 주고 `places` 에 안 실어 핀이 안 찍혔다."""
        session = Session(book=poi_book())
        session.adopt_context({"stops": [stop(1, SEOUL[0])]})
        run_tool("poi_nearby", {"group": "음식", "near": "1"}, session)
        self.assertTrue(session.shown_pois)
        self.assertTrue(all(r.get("latitude") is not None for r in session.shown_pois))

    def test_촬영지_목록에는_섞지_않는다(self):
        """두 표는 id 가 따로 매겨져 있어 섞으면 이름 지목이 엉뚱한 곳에 걸린다."""
        session = Session(book=poi_book())
        session.adopt_context({"stops": [stop(1, SEOUL[0])]})
        run_tool("poi_nearby", {"group": "음식", "near": "1"}, session)
        self.assertEqual(session.shown, [])


class 경계의이름(unittest.TestCase):
    def test_거리_기준이_하이픈이다(self):
        plan = make_plan(seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=1))
        self.assertEqual(plan_to_api(plan)["travelBasis"], "straight-line")

    def test_도구_인자를_계약_이름으로_바꿔_싣는다(self):
        self.assertEqual(
            _api_args({"radius_m": 300, "to_day": 2, "group": "음식"}),
            {"radiusMeters": 300, "toDay": 2, "group": "음식"},
        )

    def test_모델에게_주는_이름은_그대로다(self):
        """계약이 정하는 것은 응답에 실리는 이름뿐이다."""
        args = {
            t["function"]["name"]: t["function"]["parameters"]
            for t in load_tool_specs()
        }
        self.assertIn("radius_m", args["poi_nearby"]["properties"])
        self.assertIn("to_day", args["move_stop"]["properties"])

    def test_오류가_계약_모양이다(self):
        self.assertEqual(
            _bad("어느 작품으로 돌지 받지 못했다"),
            {"code": "INVALID_PARAMETER", "message": "어느 작품으로 돌지 받지 못했다"},
        )
        self.assertNotIn("error", json.dumps(_bad("x")))


class 시간예산(unittest.TestCase):
    def test_턴_예산이_설정에_있다(self):
        from src.deepseek import load_config

        cfg = load_config()
        self.assertLessEqual(cfg["turn_budget_seconds"], 40)  # scene-api 보다 짧아야
        self.assertLess(cfg["timeout_seconds"], cfg["turn_budget_seconds"])

    def test_예산을_다_쓰면_모델을_부르지_않는다(self):
        from src.agent import TripGuide
        from src.deepseek import ModelError, ScriptedClient

        client = ScriptedClient([{"content": "안녕하세요"}])
        guide = TripGuide(
            Session(book=seoul_incheon_book()),
            client,
            config={"turn_budget_seconds": 0, "max_tool_rounds": 2},
        )
        with self.assertRaisesRegex(ModelError, "시간"):
            guide.ask("안녕")


class 세션만료(unittest.TestCase):
    def test_오래된_대화를_버린다(self):
        """앱은 시트를 열 때마다 새 sessionId 를 만든다. 안 지우면 계속 쌓인다."""
        import time

        from web.server import Desk

        desk = Desk(seoul_incheon_book(), "csv", {})
        desk.guides = {"a": object(), "b": object()}
        desk.seen = {"a": time.monotonic() - 3600, "b": time.monotonic()}

        self.assertEqual(desk._sweep(), 1)
        self.assertEqual(list(desk.guides), ["b"])


if __name__ == "__main__":
    unittest.main()


class 세션열쇠(unittest.TestCase):
    """계약의 이름은 `sessionId` 다. 안 읽으면 대화가 서로 섞인다."""

    def desk(self):
        from web.server import Desk

        return Desk(seoul_incheon_book(), "csv", {})

    def test_sessionId_로_대화가_갈린다(self):
        import web.server as srv

        seen = []

        class Fake:
            def __init__(self, *a, **k):
                pass

        srv.DeepSeekClient = Fake
        desk = self.desk()
        a = desk.guide("uuid-a")
        b = desk.guide("uuid-b")
        self.assertIsNot(a, b)
        self.assertEqual(len(desk.guides), 2)
        seen.append(True)

    def test_요청에서_sessionId_를_읽는다(self):
        """`sid` 만 읽던 시절에는 모든 요청이 빈 키 하나로 몰렸다."""
        import re

        source = pathlib.Path("web/server.py").read_text(encoding="utf-8")
        line = next(
            l for l in source.splitlines() if l.strip().startswith("sid = str(")
        )
        self.assertIn("sessionId", line, line)
        self.assertTrue(re.search(r'sessionId.*\bor\b.*"sid"', line), line)
