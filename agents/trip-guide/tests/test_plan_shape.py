"""앱이 그리는 일정 모양과 편집 사본 시험 (MZ2AZ-318).

지키려는 것은 둘이다.

1. **내보낸 일정을 되읽을 수 있다.** 앱이 그리려면 좌표와 `placeId` 가 있어야 하고,
   되읽으려면 그것만으로 충분해야 한다. 왕복이 어긋나면 편집이 무너진다.
2. **편집 중에는 앱이 정본이다.** 사용자가 손으로 지운 곳이 챗봇을 거치며 되살아나면
   안 된다 (정권호, 2026-09-07 「일정 모양과 편집 사본」).

모델도 서버도 부르지 않는다.
"""

from __future__ import annotations

import json
import unittest

from src.planner import (
    PlanError,
    PlanRequest,
    make_plan,
    plan_from_api,
    plan_to_api,
)
from src.session import Session
from src.tools import run_tool

from tests.fixtures import SEOUL, FakeBook, make_place, seoul_incheon_book


def numbered_book() -> FakeBook:
    """`placeId` 가 정수인 창구. scene-api 가 주는 모양이다."""
    return FakeBook(
        {
            "도깨비": [
                make_place(
                    "1187", "서울중앙고", 37.5826, 126.9910, rank=1, mentions=90
                ),
                make_place("2201", "개뿔", 37.5793, 127.0075, rank=2, mentions=70),
                make_place(
                    "2202", "북촌한옥마을", 37.5826, 126.9830, rank=3, mentions=60
                ),
                make_place("2203", "경복궁", 37.5796, 126.9770, rank=4, mentions=50),
            ]
        }
    )


class 왕복(unittest.TestCase):
    """`make_plan` → `plan_to_api` → `plan_from_api` 가 같은 일정을 돌려준다."""

    def setUp(self):
        self.book = numbered_book()
        self.plan = make_plan(self.book, PlanRequest(titles=["도깨비"], days=2))

    def test_정지점의_id_순서_도착분이_그대로다(self):
        back = plan_from_api(plan_to_api(self.plan), self.book)

        def shape(plan):
            return [
                [
                    (leg.order, leg.place.place_id, leg.arrive, leg.dwell)
                    for leg in day.legs
                ]
                for day in plan.days
            ]

        self.assertEqual(shape(back), shape(self.plan))

    def test_뺀_거리와_시간이_좌표에서_그대로_복원된다(self):
        """`minutesToNext` 를 선에 싣지 않아도 왕복이 어긋나지 않는다.

        같은 `travel_minutes` 가 같은 좌표로 같은 값을 내기 때문이다. 어림값을
        선에 실어 「사실인 척」 하지 않으면서도 편집은 계속 된다.
        """
        wire = plan_to_api(self.plan)
        self.assertNotIn("minutesToNext", json.dumps(wire))

        back = plan_from_api(wire, self.book)
        for got, want in zip(back.days, self.plan.days, strict=True):
            self.assertEqual(
                [(l.travel_to_next, l.meters_to_next) for l in got.legs],
                [(l.travel_to_next, l.meters_to_next) for l in want.legs],
            )

    def test_거리가_어림임을_밝힌다(self):
        """직선거리에 우회 계수를 곱한 값이다. 받는 쪽이 그것을 알아야 한다."""
        self.assertEqual(plan_to_api(self.plan)["travelBasis"], "straight_line")

    def test_키가_전부_영문이다(self):
        """한글 키로는 Swift·Kotlin 클라이언트 생성기가 필드를 만들지 못한다."""

        def keys(node):
            if isinstance(node, dict):
                for k, v in node.items():
                    yield k
                    yield from keys(v)
            elif isinstance(node, list):
                for v in node:
                    yield from keys(v)

        for k in keys(plan_to_api(self.plan)):
            self.assertTrue(k.isascii(), f"한글 키가 남아 있다: {k}")


class 앱이정본(unittest.TestCase):
    """`context.plan` 이 오면 세션이 들고 있던 일정을 버린다."""

    def setUp(self):
        self.book = numbered_book()
        self.session = Session(book=self.book)
        self.session.plan = make_plan(self.book, PlanRequest(titles=["도깨비"], days=1))
        # 「보여 준 적 없는 이름은 넣지 않는다」 규칙 때문에 후보를 먼저 보여 둔다.
        self.session.remember(self.book.catalog["도깨비"])

    def test_사용자가_지운_곳은_되살아나지_않는다(self):
        """앱에서 두 곳을 지우고 그중 하나만 다시 넣어 달라고 한다.

        나머지 하나가 결과에 남아 있으면, 에이전트가 앱이 보낸 사본이 아니라 자기
        세션의 낡은 일정을 고친 것이다 — 사용자 눈에는 「지웠는데 챗봇이 다시
        넣었다」 로 보인다.
        """
        legs = self.session.plan.days[0].legs
        self.assertGreaterEqual(len(legs), 3)
        put_back, stays_erased = legs[-2].place.name, legs[-1].place.name

        edited = plan_to_api(self.session.plan)
        edited["days"][0]["stops"] = edited["days"][0]["stops"][:-2]
        kept = [s["name"] for s in edited["days"][0]["stops"]]

        self.session.adopt_plan({"plan": edited})
        run_tool("revise_plan", {"day": 1, "add": [put_back]}, self.session)

        names = [leg.place.name for leg in self.session.plan.days[0].legs]
        self.assertNotIn(stays_erased, names)
        self.assertEqual(sorted(names), sorted([*kept, put_back]))

    def test_보낸_사본이_다음_턴_맥락에_실린다(self):
        """모델이 프롬프트에서 보는 일정도 앱이 보낸 것이어야 한다."""
        gone = self.session.plan.days[0].legs[-1].place.name
        edited = plan_to_api(self.session.plan)
        edited["days"][0]["stops"] = edited["days"][0]["stops"][:1]
        self.session.adopt_plan({"plan": edited})

        # 「보여 준 장소」 줄에는 남아 있어도 된다 — 일정 줄만 본다.
        block = self.session.context_block()
        itinerary = block[block.index("짜 둔 일정") :]
        self.assertIn(edited["days"][0]["stops"][0]["name"], itinerary)
        self.assertNotIn(gone, itinerary)

    def test_망가진_사본은_조용히_넘어가지_않는다(self):
        with self.assertRaises(PlanError):
            self.session.adopt_plan({"plan": {"days": []}})


class 사본이없으면(unittest.TestCase):
    """`context.plan` 이 없으면 일정이 없는 것으로 본다."""

    def setUp(self):
        self.book = numbered_book()
        self.session = Session(book=self.book)
        self.session.plan = make_plan(self.book, PlanRequest(titles=["도깨비"], days=1))

    def test_세션에_일정이_있어도_거절한다(self):
        """낡은 일정을 몰래 고치느니 「없다」 고 말하는 편이 낫다."""
        self.session.adopt_plan({})
        self.assertIsNone(self.session.plan)

        out = run_tool("revise_plan", {"day": 1, "remove": ["개뿔"]}, self.session)
        self.assertIn("짜 둔 일정이 없다", out["결과없음"])

    def test_context_자체가_없어도_같다(self):
        self.session.adopt_plan(None)
        self.assertIsNone(self.session.plan)

    def test_맥락이_일정_없음을_말한다(self):
        """침묵하면 모델이 앞 대화 기록에서 옛 일정을 되짚어 읊는다.

        2026-09-08 실측 — `context.plan` 을 빼고 「2일차에서 빼 줘」를 물었더니
        도구는 안 불렀지만 「현재 2일차는 인천 서운고등학교…」 라고 답했다.
        상태의 정본은 이 블록이지 대화 기록이 아니다.
        """
        gone = self.session.plan.days[0].legs[0].place.name
        self.session.adopt_plan({})

        block = self.session.context_block()
        itinerary = block[block.index("짜 둔 일정") :]
        self.assertIn("없다", itinerary)
        self.assertNotIn(gone, itinerary)


class placeId(unittest.TestCase):
    """CSV 창구에는 정수 id 가 없다. 그때 이름으로 대체하지 않는다."""

    def test_숫자가_아니면_null_이고_이름을_넣지_않는다(self):
        plan = make_plan(seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=1))
        wire = plan_to_api(plan)
        for stop in wire["days"][0]["stops"]:
            self.assertIsNone(stop["placeId"], stop)
            self.assertTrue(stop["name"])

    def test_정수_id_는_문자열이_아니라_정수로_나간다(self):
        """계약의 `placeId` 는 int64 다. 문자열이면 백엔드가 되받지 못한다."""
        book = numbered_book()
        plan = make_plan(book, PlanRequest(titles=["도깨비"], days=1))
        for stop in plan_to_api(plan)["days"][0]["stops"]:
            self.assertIsInstance(stop["placeId"], int)

    def test_id_없는_일정도_되읽을_수_있다(self):
        """저장은 못 해도 대화로 고치는 것은 좌표만 있으면 된다."""
        book = FakeBook({"도깨비": SEOUL})
        plan = make_plan(book, PlanRequest(titles=["도깨비"], days=1))
        back = plan_from_api(plan_to_api(plan), book)
        self.assertEqual(
            [l.place.name for l in back.days[0].legs],
            [l.place.name for l in plan.days[0].legs],
        )


if __name__ == "__main__":
    unittest.main()
