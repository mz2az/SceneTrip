"""코스 추천 엔진 단위 시험.

**모델도 서버도 부르지 않는다.** planner.py 에 LLM 이 없다는 것이 설계의 요점이고,
그 덕에 이 시험들이 결정적으로 돈다.

여기서 지키려는 것은 설계 문서 §5 의 지표다 — 실현 가능률 100%, 결정성 100%,
2-opt 가 동선을 실제로 줄일 것.
"""

from __future__ import annotations

import itertools
import unittest

from src.planner import (
    PlanError,
    PlanRequest,
    build_day,
    cluster_by_day,
    collect_candidates,
    load_config,
    make_plan,
    move_stop,
    order_stops,
    plan_to_dict,
    region_pool,
    revise_day,
    route_meters,
    travel_minutes,
)

from tests.fixtures import (
    INCHEON,
    SEOUL,
    FakeBook,
    make_place,
    seoul_incheon_book,
    two_title_book,
)


class 점수(unittest.TestCase):
    def test_창구가_준_순서가_점수에_반영된다(self):
        cfg = load_config()
        scored, found, missing = collect_candidates(
            seoul_incheon_book(), ["도깨비"], cfg
        )
        self.assertEqual(found, ["도깨비"])
        self.assertEqual(missing, [])
        # 첫 번째로 온 장소가 가장 높은 rank 신호를 받는다.
        first = next(s for s in scored if s.place.name == "서울중앙고")
        last = next(s for s in scored if s.place.name == "송현근린공원")
        self.assertGreater(first.rank, last.rank)

    def test_두_작품에_걸친_장소는_커버리지_가산점을_받는다(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(
            two_title_book(), ["도깨비", "이태원 클라쓰"], cfg
        )
        both = next(s for s in scored if s.place.name == "개뿔")
        one = next(s for s in scored if s.place.name == "경복궁")
        self.assertEqual(both.coverage, 1.0)
        self.assertEqual(one.coverage, 0.5)
        self.assertEqual(sorted(both.titles), ["도깨비", "이태원 클라쓰"])

    def test_없는_작품은_missing_으로_돌아온다(self):
        cfg = load_config()
        scored, found, missing = collect_candidates(
            seoul_incheon_book(), ["도깨비", "없는작품"], cfg
        )
        self.assertEqual(found, ["도깨비"])
        self.assertEqual(missing, ["없는작품"])
        self.assertTrue(scored)

    def test_없는_신호는_0_이지_추측이_아니다(self):
        """scene-api 창구는 mentions·tier 를 주지 않는다. 그때 가중치를 몰래
        재분배하지 않고 그 항을 0 으로 죽인다 (설계 §3-1)."""
        cfg = load_config()
        bare = [make_place("b1", "민민", 37.5, 127.0, mentions=0, tier="")]
        scored, _, _ = collect_candidates(FakeBook({"작품": bare}), ["작품"], cfg)
        self.assertEqual(scored[0].mentions, 0.0)
        self.assertEqual(scored[0].tier, 0.0)
        self.assertGreater(scored[0].rank, 0.0)


class 날짜배분(unittest.TestCase):
    def test_멀리_떨어진_두_도시는_다른_날에_간다(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(seoul_incheon_book(), ["도깨비"], cfg)
        groups = cluster_by_day(scored, 2)
        names = [{s.place.name for s in g} for g in groups]
        seoul = {p.name for p in SEOUL}
        incheon = {p.name for p in INCHEON}
        # 어느 묶음이 몇 일차든, 서울과 인천이 섞이지 않아야 한다.
        for g in names:
            self.assertTrue(g <= seoul or g <= incheon, f"서울과 인천이 섞였다: {g}")

    def test_빈_날이_생기지_않는다(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(seoul_incheon_book(), ["도깨비"], cfg)
        for days in (1, 2, 3, 4):
            groups = cluster_by_day(scored, days)
            self.assertEqual(len(groups), days)
            self.assertTrue(all(groups), f"{days}일 배분에 빈 날이 있다")

    def test_장소보다_날이_많으면_남는_날은_비어도_된다(self):
        cfg = load_config()
        two = [SEOUL[0], SEOUL[1]]
        scored, _, _ = collect_candidates(FakeBook({"작품": two}), ["작품"], cfg)
        groups = cluster_by_day(scored, 5)
        self.assertEqual(len(groups), 5)
        self.assertEqual(sum(len(g) for g in groups), 2)


class 지역거르기(unittest.TestCase):
    """날짜에 나누기 전에 「한 여행지」 로 좁히는 단계 (planner.region_pool)."""

    def test_동떨어진_한_곳_때문에_하루가_통째로_날아가지_않는다(self):
        """1박 2일인데 2일차가 제주도 카페 한 곳이 됐다 (2026-09-02 실측).

        점수만 보고 뽑으면 멀리 있는 한 곳이 후보에 끼고, k-means 는 그것을 성실히
        별도의 날로 갈라 준다. 알고리즘은 맞게 도는데 결과가 여행이 아니다.
        """
        cfg = load_config()
        jeju = make_place("j", "제주카페", 33.4500, 126.5700, rank=2, mentions=95)
        # 후보(7곳)가 담을 자리(느긋 3곳 × 2일 = 6)보다 많아야 거르기가 돈다 —
        # 실제 상황도 후보 40곳에 자리 10곳이었다.
        book = FakeBook({"작품": [SEOUL[0], jeju, *SEOUL[1:]]})
        plan = make_plan(
            book, PlanRequest(titles=["작품"], days=2, pace="relaxed"), cfg
        )
        names = [leg.place.name for d in plan.days for leg in d.legs]
        self.assertNotIn("제주카페", names)
        self.assertTrue(all(d.legs for d in plan.days), "빈 날이 생겼다")

    def test_후보가_적으면_거르지_않는다(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(FakeBook({"작품": SEOUL[:3]}), ["작품"], cfg)
        self.assertEqual(len(region_pool(scored, 10, cfg)), 3)

    def test_출발점이_한국_안이면_그쪽_덩어리를_먼저_잡는다(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(seoul_incheon_book(), ["도깨비"], cfg)
        near_incheon = region_pool(scored, 3, cfg, (37.4736, 126.6217))
        self.assertTrue(
            {s.place.name for s in near_incheon} <= {p.name for p in INCHEON},
            "인천에서 출발했는데 서울 덩어리를 잡았다",
        )

    def test_한국_밖이면_출발점을_무시하고_가장_큰_덩어리(self):
        """도쿄에서 재나 뉴욕에서 재나 한국 어딘가가 가장 가까울 뿐이다."""
        cfg = load_config()
        scored, _, _ = collect_candidates(seoul_incheon_book(), ["도깨비"], cfg)
        from_ny = region_pool(scored, 3, cfg, (40.7128, -74.0060))
        self.assertTrue(
            {s.place.name for s in from_ny} <= {p.name for p in SEOUL},
            "가장 큰 덩어리(서울)를 잡지 않았다",
        )


class 순서(unittest.TestCase):
    def test_2opt_이_동선을_줄인다(self):
        """일부러 교차하는 배치를 만들어, 최근접만 한 결과보다 짧아지는지 본다."""
        cfg = load_config()
        # 지그재그로 놓인 네 점 — 최근접만 하면 교차가 남는다.
        pts = [
            make_place("a", "가", 37.50, 127.00),
            make_place("b", "나", 37.51, 127.03),
            make_place("c", "다", 37.50, 127.06),
            make_place("d", "라", 37.51, 127.09),
        ]
        scored, _, _ = collect_candidates(FakeBook({"작품": pts}), ["작품"], cfg)
        no_two_opt = dict(cfg)
        no_two_opt["optimize"] = dict(cfg["optimize"], two_opt_rounds=0)
        greedy = route_meters(order_stops(scored, None, no_two_opt))
        tuned = route_meters(order_stops(scored, None, cfg))
        self.assertLessEqual(tuned, greedy)

    def test_출발점이_있으면_거기서_가까운_곳부터(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(FakeBook({"작품": SEOUL}), ["작품"], cfg)
        near_namsan = order_stops(scored, (37.5512, 126.9882), cfg)
        self.assertEqual(near_namsan[0].place.name, "남산타워")


class 시간표(unittest.TestCase):
    def test_이동시간은_거리에_따라_걷기와_대중교통이_갈린다(self):
        cfg = load_config()
        close_a = make_place("x", "가", 37.5000, 127.0000)
        close_b = make_place("y", "나", 37.5050, 127.0000)  # 약 550m
        far_b = make_place("z", "다", 37.6000, 127.0000)  # 약 11km
        walk, walk_m = travel_minutes(close_a, close_b, cfg)
        transit, transit_m = travel_minutes(close_a, far_b, cfg)
        self.assertLess(walk_m, cfg["travel"]["transit_threshold_m"])
        self.assertGreater(transit_m, cfg["travel"]["transit_threshold_m"])
        self.assertGreater(transit, walk)

    def test_하루_예산을_넘으면_점수_낮은_곳부터_뺀다(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(FakeBook({"작품": SEOUL}), ["작품"], cfg)
        tight = dict(cfg)
        tight["pace"] = dict(cfg["pace"], normal={"daily_minutes": 120, "max_stops": 9})
        day = build_day(1, order_stops(scored, None, tight), None, tight, "normal")
        used = day.end_minute - tight["day"]["start_hour"] * 60
        self.assertLessEqual(used, 120)
        self.assertTrue(day.dropped)

    def test_예산_초과분은_시간을_많이_먹는_곳부터_뺀다(self):
        """점수만 보고 자르면 멀리 있는 한 곳이 예산을 다 먹는다 (2026-09-02 실측).

        서울 두 곳(가깝다)과 인천 한 곳(멀다)을 놓고 하루 예산을 조인다. 인천 쪽
        점수를 일부러 서울 한 곳보다 높게 두었다 — 점수 규칙이면 인천이 남고 가까운
        서울 한 곳이 잘리는데, 그러면 하루 종일 왕복만 하게 된다.
        """
        cfg = load_config()
        near_a = make_place("a", "가까운1", 37.5826, 126.9910, rank=1, mentions=90)
        near_b = make_place("b", "가까운2", 37.5793, 127.0075, rank=3, mentions=30)
        far = make_place("c", "멀리", 37.4736, 126.6217, rank=2, mentions=80)
        book = FakeBook({"작품": [near_a, far, near_b]})
        tight = dict(cfg)
        tight["pace"] = dict(cfg["pace"], normal={"daily_minutes": 200, "max_stops": 5})

        plan = make_plan(book, PlanRequest(titles=["작품"], days=1), tight)
        names = [leg.place.name for d in plan.days for leg in d.legs]
        self.assertNotIn("멀리", names, "시간을 다 먹는 먼 곳이 남았다")
        self.assertIn("가까운1", names)
        self.assertIn("가까운2", names)

    def test_뺀_자리는_다음_후보로_메운다(self):
        """자르기만 하고 채우지 않으면 하루가 텅 빈다 (2026-09-02 실측).

        「도깨비 당일치기」 가 360 분 예산에 2 곳 2 시간만 쓰고 끝났다. 먼 곳 하나를
        빼면 그 자리에 다음 후보가 들어와야 한다.
        """
        cfg = load_config()
        near = [
            make_place("a", "가까운1", 37.5826, 126.9910, rank=1, mentions=90),
            make_place("b", "가까운2", 37.5793, 127.0075, rank=4, mentions=20),
            make_place("c", "가까운3", 37.5826, 126.9830, rank=5, mentions=10),
        ]
        far = make_place("z", "멀리", 37.4736, 126.6217, rank=2, mentions=80)
        # 창구 순서상 「멀리」 가 앞이라 후보 상한(3)에 먼저 든다.
        book = FakeBook({"작품": [near[0], far, near[1], near[2]]})
        plan = make_plan(
            book, PlanRequest(titles=["작품"], days=1, pace="relaxed"), cfg
        )
        names = [leg.place.name for d in plan.days for leg in d.legs]
        self.assertEqual(len(names), 3, f"하루 상한 3 곳을 다 못 채웠다: {names}")
        self.assertNotIn("멀리", names)

    def test_식사는_하루에_같은_시각을_두_번_넣지_않는다(self):
        cfg = load_config()
        scored, _, _ = collect_candidates(FakeBook({"작품": SEOUL}), ["작품"], cfg)
        day = build_day(1, order_stops(scored, None, cfg), None, cfg, "packed")
        meals = sum(1 for leg in day.legs if leg.meal_after)
        self.assertLessEqual(meals, len(cfg["day"]["meal_hours"]))


class 전체흐름(unittest.TestCase):
    def test_실현_가능률_100퍼센트(self):
        """설계 §5 의 첫 지표. 순수 LLM 이 4% 인 자리에서 우리는 구조적으로 100% 다.

        본다: 하루 예산 초과 없음 · 같은 장소 두 번 없음 · 좌표 없는 정지점 없음.
        """
        cfg = load_config()
        book = seoul_incheon_book()
        checked = 0
        for days in (1, 2, 3):
            for pace in ("relaxed", "normal", "packed"):
                plan = make_plan(
                    book, PlanRequest(titles=["도깨비"], days=days, pace=pace)
                )
                budget = cfg["pace"][pace]["daily_minutes"]
                seen: set[str] = set()
                for d in plan.days:
                    used = d.end_minute - cfg["day"]["start_hour"] * 60
                    self.assertLessEqual(
                        used, budget, f"{days}일 {pace}: {d.day}일차가 예산 초과"
                    )
                    self.assertLessEqual(len(d.legs), cfg["pace"][pace]["max_stops"])
                    for leg in d.legs:
                        self.assertTrue(leg.place.has_coords())
                        self.assertNotIn(leg.place.name, seen, "같은 장소를 두 번 간다")
                        seen.add(leg.place.name)
                checked += 1
        self.assertEqual(checked, 9)

    def test_결정성_같은_입력에_같은_일정(self):
        book = seoul_incheon_book()
        req = PlanRequest(titles=["도깨비"], days=2, pace="normal")
        first = plan_to_dict(make_plan(book, req))
        for _ in range(9):
            self.assertEqual(plan_to_dict(make_plan(book, req)), first)

    def test_좌표_없는_장소는_동선에_끼지_않는다(self):
        from tests.fixtures import NO_COORDS

        book = FakeBook({"작품": SEOUL[:3] + NO_COORDS})
        plan = make_plan(book, PlanRequest(titles=["작품"], days=1))
        names = [leg.place.name for d in plan.days for leg in d.legs]
        self.assertNotIn("좌표없는곳", names)

    def test_must_는_잘려나가지_않는다(self):
        book = FakeBook({"작품": SEOUL})
        plan = make_plan(
            book,
            PlanRequest(titles=["작품"], days=1, pace="relaxed", must=["남산타워"]),
        )
        names = [leg.place.name for d in plan.days for leg in d.legs]
        self.assertIn("남산타워", names)

    def test_avoid_는_들어오지_않는다(self):
        book = FakeBook({"작품": SEOUL})
        plan = make_plan(
            book, PlanRequest(titles=["작품"], days=2, avoid=["서울중앙고", "개뿔"])
        )
        names = [leg.place.name for d in plan.days for leg in d.legs]
        self.assertNotIn("서울중앙고", names)
        self.assertNotIn("개뿔", names)

    def test_요청한_작품이_결과의_작품란에_온다(self):
        """한 장소가 열 작품에 나올 때 물어본 작품이 안 보이던 문제(2026-09-02)."""
        book = two_title_book()
        plan = make_plan(book, PlanRequest(titles=["이태원 클라쓰"], days=1))
        out = plan_to_dict(plan)
        for day in out["일정"]:
            for stop in day["동선"]:
                self.assertIn("이태원 클라쓰", stop["작품"])

    def test_점수가_낮은_작품도_최소_한_곳은_들어간다(self):
        """두 작품을 말한 사람에게 한 작품만 보여 주는 것은 요청을 절반만 들어준 것이다.

        점수순 상위 N 개만 자르면 촬영지가 적거나 순위가 낮은 작품이 통째로
        사라진다. 아래 픽스처는 그 상황을 일부러 만든다 — 「작은작품」 의 유일한
        장소는 점수가 가장 낮아, 보장이 없으면 하루 3 곳 안에 못 든다.
        """
        weak = make_place(
            "w1", "구석진곳", 37.5700, 126.9800, title="작은작품", rank=99, mentions=0
        )
        book = FakeBook({"큰작품": SEOUL, "작은작품": [weak]})
        plan = make_plan(
            book, PlanRequest(titles=["큰작품", "작은작품"], days=1, pace="relaxed")
        )
        names = [leg.place.name for d in plan.days for leg in d.legs]
        self.assertIn("구석진곳", names)
        self.assertLessEqual(len(names), 3)

    def test_출력에_좌표와_점수가_없다(self):
        """모델에게 좌표를 주면 거리를 다시 계산하려 들고 그 계산은 틀린다."""
        out = plan_to_dict(
            make_plan(seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=2))
        )
        blob = repr(out)
        self.assertNotIn("lat", blob)
        self.assertNotIn("score", blob)
        self.assertNotIn("37.5", blob)


class 거절(unittest.TestCase):
    def test_작품이_없으면_거절한다(self):
        with self.assertRaises(PlanError):
            make_plan(seoul_incheon_book(), PlanRequest(titles=[]))

    def test_찾을_수_없는_작품이면_거절한다(self):
        with self.assertRaisesRegex(PlanError, "찾은 촬영지가 없다"):
            make_plan(seoul_incheon_book(), PlanRequest(titles=["없는작품"]))

    def test_일수가_범위를_벗어나면_거절한다(self):
        for days in (0, 8, -1):
            with self.assertRaises(PlanError):
                make_plan(
                    seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=days)
                )

    def test_모르는_속도면_거절한다(self):
        with self.assertRaisesRegex(PlanError, "모르는 여행 속도"):
            make_plan(
                seoul_incheon_book(), PlanRequest(titles=["도깨비"], pace="빠르게")
            )

    def test_좌표가_하나도_없으면_거절한다(self):
        from tests.fixtures import NO_COORDS

        with self.assertRaisesRegex(PlanError, "좌표가 있는 촬영지가 하나도 없어"):
            make_plan(FakeBook({"작품": NO_COORDS}), PlanRequest(titles=["작품"]))

    def test_다_빼면_거절한다(self):
        book = FakeBook({"작품": SEOUL[:2]})
        with self.assertRaisesRegex(PlanError, "남는 촬영지가 없다"):
            make_plan(book, PlanRequest(titles=["작품"], avoid=["서울중앙고", "개뿔"]))


if __name__ == "__main__":
    unittest.main()


class 수정(unittest.TestCase):
    """대화로 고치기 (MZ2AZ-201). 여기에도 모델은 없다."""

    def plan2(self):
        return make_plan(seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=2))

    def day(self, plan, n):
        return next(d for d in plan.days if d.day == n)

    def test_뺀_곳은_그_일차에서_사라진다(self):
        plan = self.plan2()
        target = self.day(plan, 1)
        victim = target.legs[0].place.name
        rev = revise_day(plan, 1, remove=[victim])
        names = [leg.place.name for leg in self.day(rev.plan, 1).legs]
        self.assertNotIn(victim, names)
        self.assertEqual(rev.removed, [victim])

    def test_원본_일정은_그대로다(self):
        """새 Plan 을 돌려준다. 예산을 넘겼을 때 부르는 쪽이 버릴 수 있어야 한다."""
        plan = self.plan2()
        before = [leg.place.name for leg in self.day(plan, 1).legs]
        revise_day(plan, 1, remove=[before[0]])
        after = [leg.place.name for leg in self.day(plan, 1).legs]
        self.assertEqual(before, after)

    def test_빼고_나면_시간표를_다시_계산한다(self):
        """빼기만 하고 옛 시각을 그대로 두면 빈 시간이 생긴다."""
        plan = self.plan2()
        target = self.day(plan, 1)
        if len(target.legs) < 2:
            self.skipTest("정지점이 둘 미만이라 볼 것이 없다")
        rev = revise_day(plan, 1, remove=[target.legs[0].place.name])
        legs = self.day(rev.plan, 1).legs
        start = load_config()["day"]["start_hour"] * 60
        self.assertEqual(legs[0].arrive, start)
        for a, b in itertools.pairwise(legs):
            self.assertLessEqual(a.arrive + a.dwell, b.arrive)

    def test_없는_곳을_빼려_하면_거절한다(self):
        plan = self.plan2()
        with self.assertRaises(PlanError):
            revise_day(plan, 1, remove=["에펠탑"])

    def test_없는_일차면_거절한다(self):
        plan = self.plan2()
        with self.assertRaises(PlanError):
            revise_day(plan, 9, remove=["아무거나"])

    def test_아무것도_지시하지_않으면_거절한다(self):
        plan = self.plan2()
        with self.assertRaises(PlanError):
            revise_day(plan, 1)

    def test_같은_곳을_두_일차에_넣지_않는다(self):
        """이미 다른 일차에 있는 곳을 넣으라고 하면, 먼저 빼라고 말해 준다."""
        plan = self.plan2()
        other = self.day(plan, 2).legs[0].place
        with self.assertRaises(PlanError) as cm:
            revise_day(plan, 1, add=[other])
        self.assertIn("2일차", str(cm.exception))

    def test_좌표가_없으면_넣지_않는다(self):
        plan = self.plan2()
        ghost = make_place("x1", "좌표없는곳", None, None)
        with self.assertRaises(PlanError):
            revise_day(plan, 1, add=[ghost])

    def test_넣은_곳은_동선에_들어가고_순서가_다시_잡힌다(self):
        plan = make_plan(
            seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=2, pace="relaxed")
        )
        used_anywhere = {leg.place.name for d in plan.days for leg in d.legs}
        spare = next((p for p in SEOUL if p.name not in used_anywhere), None)
        if spare is None:
            self.skipTest("넣을 여분 장소가 없다")
        rev = revise_day(plan, 1, add=[spare])
        legs = self.day(rev.plan, 1).legs
        self.assertIn(spare.name, [leg.place.name for leg in legs])
        self.assertEqual([leg.order for leg in legs], list(range(1, len(legs) + 1)))
        self.assertEqual(rev.added, [spare.name])

    def test_예산을_넘으면_알려_주되_말없이_자르지_않는다(self):
        """사용자가 넣으라고 한 곳을 코드가 도로 빼면 화면과 기억이 갈린다."""
        plan = make_plan(
            seoul_incheon_book(),
            PlanRequest(titles=["도깨비"], days=1, pace="relaxed"),
        )
        used = {leg.place.name for d in plan.days for leg in d.legs}
        spares = [p for p in SEOUL + INCHEON if p.name not in used]
        if not spares:
            self.skipTest("넣을 여분 장소가 없다")
        rev = revise_day(plan, 1, add=spares)
        added = [leg.place.name for leg in self.day(rev.plan, 1).legs]
        for p in spares:
            self.assertIn(p.name, added)  # 넣으라고 한 것은 전부 들어가 있다
        self.assertTrue(rev.over_budget or rev.over_stops)
        self.assertGreater(rev.minutes_used, 0)


class 이동(unittest.TestCase):
    """move_stop — 일차 간 이동은 한 번의 호출로 끝난다."""

    def plan2(self):
        return make_plan(
            seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=2, pace="relaxed")
        )

    def where(self, plan, name):
        for d in plan.days:
            if any(leg.place.name == name for leg in d.legs):
                return d.day
        return None

    def test_옮기면_출발_일차에서_사라지고_도착_일차에_생긴다(self):
        plan = self.plan2()
        mover = plan.days[0].legs[0].place.name
        rev = move_stop(plan, mover, 2)
        self.assertEqual(self.where(rev.plan, mover), 2)
        self.assertEqual(rev.moved_from, 1)

    def test_중간_상태가_남지_않는다(self):
        """빼기만 되고 넣기가 실패하면 원본이 그대로여야 한다."""
        plan = self.plan2()
        mover = plan.days[0].legs[0].place.name
        with self.assertRaises(PlanError):
            move_stop(plan, mover, 9)  # 없는 일차
        self.assertEqual(self.where(plan, mover), 1)

    def test_같은_일차로_옮기라면_거절한다(self):
        plan = self.plan2()
        mover = plan.days[0].legs[0].place.name
        with self.assertRaises(PlanError):
            move_stop(plan, mover, 1)

    def test_일정에_없는_곳은_옮길_수_없다(self):
        plan = self.plan2()
        with self.assertRaises(PlanError):
            move_stop(plan, "에펠탑", 2)

    def test_원본은_그대로다(self):
        plan = self.plan2()
        mover = plan.days[0].legs[0].place.name
        move_stop(plan, mover, 2)
        self.assertEqual(self.where(plan, mover), 1)


class 넘쳤을때_대안(unittest.TestCase):
    """위반 사실만 알리지 않고 「무엇을 빼면 얼마가 빠지는지」 를 함께 준다."""

    def test_예산을_넘으면_뺄_후보를_아끼는_시간_순으로_준다(self):
        plan = make_plan(
            seoul_incheon_book(),
            PlanRequest(titles=["도깨비"], days=1, pace="relaxed"),
        )
        used = {leg.place.name for d in plan.days for leg in d.legs}
        spares = [p for p in SEOUL + INCHEON if p.name not in used]
        if not spares:
            self.skipTest("넣을 여분 장소가 없다")
        rev = revise_day(plan, 1, add=spares)
        self.assertTrue(rev.over_budget or rev.over_stops)
        self.assertTrue(rev.drop_candidates)
        saved = [s for _, s in rev.drop_candidates]
        self.assertEqual(saved, sorted(saved, reverse=True))
        self.assertLessEqual(len(rev.drop_candidates), 3)

    def test_넘치지_않으면_후보를_주지_않는다(self):
        plan = make_plan(seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=2))
        rev = revise_day(plan, 1, remove=[plan.days[0].legs[0].place.name])
        self.assertFalse(rev.over_budget)
        self.assertEqual(rev.drop_candidates, [])


class 이미_있는_것을_또_넣으라_할_때(unittest.TestCase):
    """실제 주행에서 나온 결함 — 섞여 온 빼기가 통째로 날아갔다 (2026-09-03)."""

    def plan1(self):
        return make_plan(
            seoul_incheon_book(), PlanRequest(titles=["도깨비"], days=2, pace="relaxed")
        )

    def test_이미_있는_곳은_건너뛰고_빼기는_실행된다(self):
        plan = self.plan1()
        day1 = plan.days[0].legs
        if len(day1) < 2:
            self.skipTest("정지점이 둘 미만이다")
        here, victim = day1[0].place, day1[1].place.name

        rev = revise_day(plan, 1, add=[here], remove=[victim])

        names = [leg.place.name for leg in rev.plan.days[0].legs]
        self.assertNotIn(victim, names)  # 빼기는 살아 있다
        self.assertIn(here.name, names)  # 이미 있던 것은 그대로
        self.assertEqual(rev.already, [here.name])
        self.assertEqual(rev.removed, [victim])

    def test_넣으라는_것이_전부_이미_있으면_거절한다(self):
        """바뀐 것이 없으면 바뀌었다고 말하지 않는다."""
        plan = self.plan1()
        here = plan.days[0].legs[0].place
        with self.assertRaises(PlanError):
            revise_day(plan, 1, add=[here])
