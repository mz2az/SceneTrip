"""도구 층 시험 — 인자 검증과 실행, 그리고 에이전트 루프.

모델 자리에는 `ScriptedClient`(대본 클라이언트)를 넣는다. 실제 모델을 부르면 값이
들고 결과가 매번 달라져 시험 구실을 못 한다(agents/README.md).
"""

from __future__ import annotations

import json
import unittest

from src.agent import TripGuide, detect_language
from src.deepseek import ScriptedClient
from src.session import Anchor, Session
from src.tools import ToolArgError, load_tool_specs, run_tool, validate_args

from tests.fixtures import SEOUL, FakeBook, seoul_incheon_book


def specs() -> dict:
    return {t["function"]["name"]: t["function"] for t in load_tool_specs()}


def call(name: str, args: dict, session: Session) -> dict:
    return run_tool(name, args, session)


class 인자검증(unittest.TestCase):
    def test_목록_인자를_받는다(self):
        schema = specs()["plan_course"]["parameters"]
        out = validate_args(schema, {"titles": ["도깨비", "이태원 클라쓰"], "days": 2})
        self.assertEqual(out["titles"], ["도깨비", "이태원 클라쓰"])
        self.assertEqual(out["days"], 2)
        self.assertEqual(out["pace"], "normal")  # 기본값이 채워진다

    def test_목록_자리에_문자열_하나가_와도_감싸서_받는다(self):
        """모델이 titles="도깨비" 로 보내는 일이 잦다. 뜻이 분명하므로 거절하지 않는다."""
        schema = specs()["plan_course"]["parameters"]
        out = validate_args(schema, {"titles": "도깨비"})
        self.assertEqual(out["titles"], ["도깨비"])

    def test_빈_문자열_원소는_버린다(self):
        schema = specs()["plan_course"]["parameters"]
        out = validate_args(schema, {"titles": ["도깨비", "  ", ""]})
        self.assertEqual(out["titles"], ["도깨비"])

    def test_원소_타입이_다르면_거절한다(self):
        schema = specs()["plan_course"]["parameters"]
        with self.assertRaisesRegex(ToolArgError, "원소는 string"):
            validate_args(schema, {"titles": [1, 2]})

    def test_minItems_를_못_채우면_거절한다(self):
        schema = specs()["plan_course"]["parameters"]
        with self.assertRaises(ToolArgError):
            validate_args(schema, {"titles": ["   "]})

    def test_maxItems_를_넘으면_잘라낸다(self):
        schema = specs()["plan_course"]["parameters"]
        out = validate_args(
            schema, {"titles": ["가", "나", "다", "라", "마", "바", "사"]}
        )
        self.assertEqual(len(out["titles"]), 5)

    def test_범위를_벗어난_일수는_경계로_당긴다(self):
        schema = specs()["plan_course"]["parameters"]
        self.assertEqual(
            validate_args(schema, {"titles": ["가"], "days": 99})["days"], 7
        )
        self.assertEqual(
            validate_args(schema, {"titles": ["가"], "days": 0})["days"], 1
        )

    def test_모르는_인자는_거절한다(self):
        schema = specs()["plan_course"]["parameters"]
        with self.assertRaisesRegex(ToolArgError, "모르는 인자"):
            validate_args(schema, {"titles": ["가"], "예산": 100000})

    def test_모르는_속도는_거절한다(self):
        schema = specs()["plan_course"]["parameters"]
        with self.assertRaises(ToolArgError):
            validate_args(schema, {"titles": ["가"], "pace": "빠르게"})


class 계약(unittest.TestCase):
    def test_스키마에_적힌_도구를_전부_실행할_수_있다(self):
        """계약과 구현이 어긋나면 모델은 있다고 배운 도구를 못 부른다."""
        session = Session(book=seoul_incheon_book())
        for name in specs():
            result = run_tool(name, {}, session)
            self.assertNotIn(
                "아직 만들어지지 않았다", json.dumps(result, ensure_ascii=False), name
            )


class 일정도구(unittest.TestCase):
    def setUp(self) -> None:
        self.session = Session(book=seoul_incheon_book())

    def test_일정을_돌려준다(self):
        out = call("plan_course", {"titles": ["도깨비"], "days": 2}, self.session)
        self.assertIn("일정", out)
        self.assertEqual(len(out["일정"]), 2)
        self.assertIn("할 일", out)

    def test_돌려준_장소는_지목_가능해진다(self):
        """일정에 나온 곳을 사용자가 이름으로 부를 수 있어야 한다."""
        call("plan_course", {"titles": ["도깨비"], "days": 1}, self.session)
        self.assertTrue(self.session.shown)
        name = self.session.shown[0].name
        self.assertIsNotNone(self.session.find_shown(name))

    def test_없는_작품이면_결과_대신_이유만_준다(self):
        out = call("plan_course", {"titles": ["없는작품"]}, self.session)
        self.assertIn("결과없음", out)
        self.assertNotIn("일정", out)

    def test_출발점을_못_풀면_결과를_주지_않는다(self):
        """조용히 지도 한가운데로 떨어지지 않는다 — session.resolve_anchor 규칙."""
        out = call(
            "plan_course",
            {"titles": ["도깨비"], "start": "본_적_없는_곳"},
            self.session,
        )
        self.assertIn("결과없음", out)

    def test_현위치를_출발점으로_쓸_수_있다(self):
        """출발점에서 가장 가까운 곳이 첫 정지점이 된다.

        `packed` 를 쓰는 이유는 남산타워가 이 픽스처에서 점수가 가장 낮아, 하루
        상한이 5 곳인 `normal` 에서는 후보 단계에서 잘려 나가기 때문이다. 잘리는
        것 자체는 정상 동작이라 여기서는 상한을 늘려 순서만 본다.
        """
        from src.session import Anchor

        session = Session(book=FakeBook({"도깨비": SEOUL}))
        session.here = Anchor("남산", 37.5512, 126.9882)
        out = call(
            "plan_course",
            {"titles": ["도깨비"], "start": "현위치", "pace": "packed"},
            session,
        )
        self.assertEqual(out.get("출발점"), "남산")
        self.assertEqual(out["일정"][0]["동선"][0]["이름"], "남산타워")

    def test_모르는_인자는_결과_대신_이유만_준다(self):
        out = call("plan_course", {"titles": ["도깨비"], "예산": 5}, self.session)
        self.assertIn("결과없음", out)


class 도구선택(unittest.TestCase):
    """모델이 어떤 도구를 고르는가. 대본 클라이언트라 모델을 부르지 않는다."""

    @staticmethod
    def guide() -> tuple[TripGuide, ScriptedClient]:
        client = ScriptedClient([])
        session = Session(book=seoul_incheon_book())
        return TripGuide(session, client, config={"max_tool_rounds": 4}), client

    def test_일정_요청에_plan_course_를_부르면_실행된다(self):
        guide, client = self.guide()
        client.replies = [
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "c1",
                        "function": {
                            "name": "plan_course",
                            "arguments": json.dumps({"titles": ["도깨비"], "days": 2}),
                        },
                    }
                ],
            },
            {"role": "assistant", "content": "2일 일정을 짰어요."},
        ]
        turn = guide.ask("도깨비 촬영지로 1박 2일 짜 줘")
        self.assertEqual([r.name for r in turn.tool_runs], ["plan_course"])
        self.assertFalse(turn.tool_runs[0].refused)
        self.assertIn("일정", turn.tool_runs[0].result)

    def test_인자가_망가져도_대화가_이어진다(self):
        """JSON 이 깨져 와도 예외로 죽지 않고 이유를 되먹인다."""
        guide, client = self.guide()
        client.replies = [
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "c1",
                        "function": {"name": "plan_course", "arguments": "{망가짐"},
                    }
                ],
            },
            {"role": "assistant", "content": "다시 알려주시겠어요?"},
        ]
        turn = guide.ask("일정 짜 줘")
        self.assertTrue(turn.tool_runs[0].refused)
        self.assertTrue(turn.reply)

    def test_도구_결과는_대화_이력에_쌓이지_않는다(self):
        """열 턴 뒤 프롬프트가 수천 줄이 되는 것을 막는 규칙(agent.py 머리말)."""
        guide, client = self.guide()
        client.replies = [
            {
                "role": "assistant",
                "tool_calls": [
                    {
                        "id": "c1",
                        "function": {
                            "name": "plan_course",
                            "arguments": json.dumps({"titles": ["도깨비"]}),
                        },
                    }
                ],
            },
            {"role": "assistant", "content": "짰어요."},
        ]
        guide.ask("도깨비로 일정 짜 줘")
        self.assertEqual([m["role"] for m in guide.history], ["user", "assistant"])


class 언어(unittest.TestCase):
    def test_쓴_말을_알아본다(self):
        self.assertEqual(detect_language("도깨비 촬영지 알려줘")[0], "ko")
        self.assertEqual(detect_language("Where was Goblin filmed?")[0], "en")
        self.assertEqual(detect_language("ロケ地を教えて")[0], "ja")

    def test_섞어_쓰면_한국어로_본다(self):
        """「Goblin 촬영지 알려줘」 쪽이 훨씬 흔하다."""
        self.assertEqual(detect_language("Goblin 촬영지 알려줘")[0], "ko")


if __name__ == "__main__":
    unittest.main()


class 일정수정(unittest.TestCase):
    """revise_plan — 대화로 일정 고치기 (MZ2AZ-201)."""

    def plan_session(self, days=2):
        s = Session(book=seoul_incheon_book())
        out = call("plan_course", {"titles": ["도깨비"], "days": days}, s)
        self.assertNotIn("결과없음", out)
        return s

    def names(self, session, day):
        d = next(x for x in session.plan.days if x.day == day)
        return [leg.place.name for leg in d.legs]

    def test_일정을_짜면_세션이_들고_있는다(self):
        """이것이 없으면 다음 턴에 고칠 대상이 없다."""
        s = self.plan_session()
        self.assertIsNotNone(s.plan)
        self.assertEqual(len(s.plan.days), 2)

    def test_짜_둔_일정이_없으면_거절한다(self):
        s = Session(book=seoul_incheon_book())
        out = call("revise_plan", {"day": 1, "remove": ["개뿔"]}, s)
        self.assertIn("결과없음", out)

    def test_뺀_곳이_세션_일정에서_사라진다(self):
        s = self.plan_session()
        victim = self.names(s, 1)[0]
        out = call("revise_plan", {"day": 1, "remove": [victim]}, s)
        self.assertNotIn("결과없음", out)
        self.assertNotIn(victim, self.names(s, 1))
        self.assertEqual(out["고친 것"]["뺀 곳"], [victim])

    def test_보여_준_적_없는_이름은_넣지_않는다(self):
        s = self.plan_session()
        out = call("revise_plan", {"day": 1, "add": ["에펠탑"]}, s)
        self.assertIn("결과없음", out)

    def test_거절된_수정은_세션에_흔적을_남기지_않는다(self):
        """revise_day 가 원본을 안 고치므로, 실패한 수정은 아무것도 바꾸지 않는다."""
        s = self.plan_session()
        before = self.names(s, 1)
        out = call("revise_plan", {"day": 1, "remove": ["없는곳"]}, s)
        self.assertIn("결과없음", out)
        self.assertEqual(before, self.names(s, 1))

    def test_넘치면_경고하되_넣으라고_한_것은_넣는다(self):
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 1, "pace": "relaxed"}, s)
        used = {leg.place.name for d in s.plan.days for leg in d.legs}
        spares = [p.name for p in SEOUL if p.name not in used]
        if not spares:
            self.skipTest("넣을 여분 장소가 없다")
        call(
            "search_places", {"query": "도깨비", "limit": 20}, s
        )  # 보여 준 것으로 만든다
        out = call("revise_plan", {"day": 1, "add": spares}, s)
        self.assertNotIn("결과없음", out)
        for n in spares:
            self.assertIn(n, self.names(s, 1))
        self.assertIn("경고", out)
        self.assertIn("임의로 빼지 마라", out["할 일"])

    def test_고친_일정이_다음_턴_맥락에_실린다(self):
        """모델은 턴마다 백지에서 시작한다 — 코드가 넣어 주지 않으면 모른다."""
        s = self.plan_session()
        victim = self.names(s, 1)[0]
        call("revise_plan", {"day": 1, "remove": [victim]}, s)
        block = s.context_block()
        self.assertIn("짜 둔 일정", block)
        self.assertIn("1일차", block)
        self.assertNotIn(victim, block.split("1일차")[1].split("\n")[0])


class 일차이동(unittest.TestCase):
    """move_stop 도구 층."""

    def session_with_plan(self):
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 2, "pace": "relaxed"}, s)
        return s

    def where(self, session, name):
        for d in session.plan.days:
            if any(leg.place.name == name for leg in d.legs):
                return d.day
        return None

    def test_일정이_없으면_거절한다(self):
        s = Session(book=seoul_incheon_book())
        out = call("move_stop", {"name": "개뿔", "to_day": 2}, s)
        self.assertIn("결과없음", out)

    def test_옮기면_세션_일정이_바뀐다(self):
        s = self.session_with_plan()
        mover = s.plan.days[0].legs[0].place.name
        out = call("move_stop", {"name": mover, "to_day": 2}, s)
        self.assertNotIn("결과없음", out)
        self.assertEqual(self.where(s, mover), 2)
        self.assertEqual(out["고친 것"]["어디로"], "2일차")

    def test_실패하면_세션_일정이_그대로다(self):
        s = self.session_with_plan()
        mover = s.plan.days[0].legs[0].place.name
        out = call("move_stop", {"name": mover, "to_day": 5}, s)
        self.assertIn("결과없음", out)
        self.assertEqual(self.where(s, mover), 1)

    def test_넘치면_뺄_후보를_함께_준다(self):
        """사실만 알리지 않는다 — 대안을 함께 줘야 다음 수를 고를 수 있다."""
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 1, "pace": "relaxed"}, s)
        used = {leg.place.name for d in s.plan.days for leg in d.legs}
        spares = [p.name for p in SEOUL if p.name not in used]
        if not spares:
            self.skipTest("넣을 여분 장소가 없다")
        call("search_places", {"query": "도깨비", "limit": 20}, s)
        out = call("revise_plan", {"day": 1, "add": spares}, s)
        self.assertIn("경고", out)
        self.assertIn("빼면 좋은 후보", out)
        self.assertIn("분 준다", out["빼면 좋은 후보"][0])


class 바깥으로_나가는_지시(unittest.TestCase):
    """챗봇은 시트 안에 있고 지도·코스는 바깥에 있다 — 지시를 내보내야 끝난다."""

    def test_담으면_저장_지시와_지도_이동이_함께_나간다(self):
        s = Session(book=seoul_incheon_book())
        call("search_places", {"query": "도깨비"}, s)
        s.clear_outbox()
        call("update_cart", {"action": "add", "name": SEOUL[0].name}, s)
        ops = [e["op"] for e in s.effects]
        self.assertIn("cart.add", ops)
        self.assertIn("map.focus", [u["op"] for u in s.ui])

    def test_일정을_짜면_저장_지시와_코스_열기가_나간다(self):
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 2}, s)
        self.assertEqual([e["op"] for e in s.effects], ["plan.draft"])
        self.assertIn("course.open", [u["op"] for u in s.ui])

    def test_고치면_바뀐_일차를_짚어_준다(self):
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 2}, s)
        victim = s.plan.days[1].legs[0].place.name
        s.clear_outbox()
        call("revise_plan", {"day": 2, "remove": [victim]}, s)
        effect = next(e for e in s.effects if e["op"] == "plan.revise")
        self.assertEqual(effect["day"], 2)
        self.assertEqual(effect["remove"], [victim])
        focus = next(u for u in s.ui if u["op"] == "course.focus")
        self.assertEqual(focus["day"], 2)
        self.assertIn(victim, focus["changed"])

    def test_옮기면_도착_일차로_데려간다(self):
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 2, "pace": "relaxed"}, s)
        mover = s.plan.days[0].legs[0].place.name
        s.clear_outbox()
        call("move_stop", {"name": mover, "to_day": 2}, s)
        effect = next(e for e in s.effects if e["op"] == "plan.move")
        self.assertEqual((effect["day"], effect["toDay"]), (1, 2))
        self.assertEqual(next(u for u in s.ui if u["op"] == "course.focus")["day"], 2)

    def test_거절된_수정은_아무_지시도_내보내지_않는다(self):
        """실패했는데 화면이 바뀌면 사용자는 됐다고 믿는다."""
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 2}, s)
        s.clear_outbox()
        out = call("revise_plan", {"day": 2, "remove": ["에펠탑"]}, s)
        self.assertIn("결과없음", out)
        self.assertEqual(s.effects, [])
        self.assertEqual(s.ui, [])

    def test_턴이_바뀌면_지난_지시는_사라진다(self):
        """남아 있으면 다음 턴에 화면이 엉뚱한 데로 튄다."""
        from src.agent import TripGuide
        from src.deepseek import ScriptedClient

        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 2}, s)
        self.assertTrue(s.ui)
        client = ScriptedClient([{"role": "assistant", "content": "네."}])
        turn = TripGuide(s, client, config={}).ask("고마워")
        self.assertEqual(turn.ui, [])
        self.assertEqual(turn.effects, [])


class 마법사_경로(unittest.TestCase):
    """모델을 거치지 않는 계산 전용 경로 (앱의 AI 일정짜기 마법사).

    앱은 「도깨비 / 2박 3일 / 빡빡하게」 를 이미 구조화된 값으로 들고 있다. 그것을
    문장으로 만들어 모델에게 주고 모델이 다시 값으로 되돌리는 것은 시간과 토큰을
    쓰고 아무것도 얻지 못한다 — 이해할 것이 없기 때문이다.
    """

    def test_같은_입력이면_챗봇_경로와_같은_일정이_나온다(self):
        """입구가 둘이라고 결과가 달라지면 안 된다."""
        from src.planner import PlanRequest, make_plan, plan_to_dict

        book = seoul_incheon_book()
        direct = plan_to_dict(
            make_plan(book, PlanRequest(titles=["도깨비"], days=2, pace="normal"))
        )

        s = Session(book=seoul_incheon_book())
        out = call("plan_course", {"titles": ["도깨비"], "days": 2}, s)

        self.assertEqual(
            [d["동선"] for d in direct["일정"]],
            [d["동선"] for d in out["일정"]],
        )

    def test_마법사도_초안만_건넨다(self):
        """저장은 사용자의 「완료」가 부른다 — 8/11 회의 확정."""
        s = Session(book=seoul_incheon_book())
        call("plan_course", {"titles": ["도깨비"], "days": 2}, s)
        self.assertEqual([e["op"] for e in s.effects], ["plan.draft"])


class 편의시설(unittest.TestCase):
    """poi_nearby — 촬영지와 **다른 표**를 본다 (계약 §/pois)."""

    def session(self):
        from tests.fixtures import poi_book

        s = Session(book=poi_book())
        s.here = Anchor("현위치", 37.5665, 126.978)
        return s

    def test_갈래를_계약의_값으로_바꿔_보낸다(self):
        """도구는 한국어로 받고 서버에는 food·stay·sight·transit 로 보낸다."""
        s = self.session()
        out = call("poi_nearby", {"group": "음식"}, s)
        self.assertNotIn("결과없음", out)
        self.assertEqual(s.book.poi_calls[0][3], "food")

    def test_기본_반경은_300m(self):
        s = self.session()
        call("poi_nearby", {"group": "음식"}, s)
        self.assertEqual(s.book.poi_calls[0][2], 300)

    def test_모델에게_좌표를_주지_않는다(self):
        """좌표를 보면 모델이 스스로 거리를 재려 들고 하버사인을 틀린다."""
        s = self.session()
        out = call("poi_nearby", {"group": "음식"}, s)
        payload = str(out)
        self.assertNotIn("37.567", payload)
        self.assertNotIn("126.977", payload)

    def test_화면에는_좌표가_가도록_id_를_보낸다(self):
        s = self.session()
        call("poi_nearby", {"group": "음식"}, s)
        focus = next(u for u in s.ui if u["op"] == "map.focus")
        self.assertEqual(focus["placeIds"], [470912, 481233])

    def test_기준점을_못_풀면_거절한다(self):
        from tests.fixtures import poi_book

        s = Session(book=poi_book())  # here 를 안 잡았다
        out = call("poi_nearby", {"group": "음식"}, s)
        self.assertIn("결과없음", out)
        self.assertEqual(s.book.poi_calls, [])

    def test_없으면_이유를_준다(self):
        from tests.fixtures import PoiBook

        s = Session(book=PoiBook({"도깨비": SEOUL}, pois=[]))
        s.here = Anchor("현위치", 37.5665, 126.978)
        out = call("poi_nearby", {"group": "숙박"}, s)
        self.assertIn("결과없음", out)

    def test_편의시설_자료가_없는_창구는_그렇게_말한다(self):
        """「근처에 카페가 없다」 와 「이 창구는 카페를 모른다」 는 다른 이야기다."""
        s = Session(book=seoul_incheon_book())  # pois_near 가 없는 창구
        s.here = Anchor("현위치", 37.5665, 126.978)
        out = call("poi_nearby", {"group": "음식"}, s)
        self.assertIn("csv", out["결과없음"])

    def test_촬영지와_섞지_말라고_적어_보낸다(self):
        s = self.session()
        out = call("poi_nearby", {"group": "음식"}, s)
        self.assertIn("촬영지가 아니다", out["주의"])
