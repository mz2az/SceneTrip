"""담은 촬영지로 짜는 일정(src/saved_planner.py, MZ2AZ-601) 시험 — 계획 docs/project/plans/saved-ai-course.md §3.

실제 모델도 실제 scene-api 도 부르지 않는다(CLAUDE.md §6). 모델은 대본 클라이언트(`ScriptedClient`),
장소 창구는 id → 촬영지를 미리 적어 둔 가짜(`SavedBook.place_by_id`)다.

계획이 정한 것:
- 담은 곳은 **하나도 버리지 않는다** — 하루 곳 수(`pace`)보다 많으면 하루에 더 넣는다.
- 못 받은 id(없음·숨김 → `place_by_id` 가 None)와 좌표 없는 곳만 빠지고 `dropped` 에 이유가 적힌다.
- 모델이 빠뜨린 곳은 코드가 가장 가까운 날에 넣는다. 지어낸 id 는 무시한다.
- 날마다 가까운 순으로 차례를 잡는다.
- 모델이 못 주면 좌표로 일수만큼 묶고 가까운 순으로 잇는다 — 그래도 담은 곳은 전부 들어간다.
- 쓸 수 있는 곳이 하나도 없으면 `PlanError`.

모델 답의 모양은 「도시로」 와 같은 `{"days": [{"day": 1, "place_ids": [...]}]}` 로 본다.
"""

from __future__ import annotations

import io
import json
import unittest
from contextlib import redirect_stderr

from src.model_client import ScriptedClient
from src.places import Place, haversine_m
from src.planner import PlanError, PlanRequest, plan_to_api
from src.saved_planner import plan_saved_with_model

from tests.fixtures import FakeBook, make_place

# ── 픽스처 ────────────────────────────────────────────────────────────────────

# 서울 종로 근처 넷(1000~1003)과 부산 해운대 근처 넷(1004~1007). 무리 안은 1km 안쪽, 무리 사이는 300km.
NORTH = [
    make_place(f"{1000 + k}", f"서울{k}", 37.57 + k * 0.002, 126.98 + k * 0.002)
    for k in range(4)
]
SOUTH = [
    make_place(f"{1004 + k}", f"부산{k}", 35.16 + k * 0.002, 129.16 + k * 0.002)
    for k in range(4)
]
NORTH_IDS = {p.place_id for p in NORTH}
SOUTH_IDS = {p.place_id for p in SOUTH}
ALL = NORTH + SOUTH
ALL_IDS = [int(p.place_id) for p in ALL]

# 좌표 없는 담은 곳 — 동선에 낄 수 없다.
BLIND = make_place("1050", "좌표없는곳", None, None)
# 없는(또는 숨긴) 촬영지 id — scene-api 가 404 를 준다.
UNKNOWN = 999


class SavedBook(FakeBook):
    """id → 촬영지. scene-api 의 `GET /places/{id}` 를 흉내 낸다. 없으면 None."""

    def __init__(self, places: list[Place]):
        super().__init__({})
        self.by_id = {int(p.place_id): p for p in places}
        self.id_calls: list[int] = []

    def place_by_id(self, place_id: int) -> Place | None:
        self.id_calls.append(place_id)
        return self.by_id.get(int(place_id))


def _req(ids, days=2, pace="normal"):
    return PlanRequest(titles=[], days=days, pace=pace, place_ids=list(ids))


def _reply(days):
    return {"role": "assistant", "content": json.dumps({"days": days})}


def _ids(plan) -> list[list[str]]:
    return [[leg.place.place_id for leg in d.legs] for d in plan.days]


def _flat(plan) -> list[str]:
    return [i for d in _ids(plan) for i in d]


def _dropped_ids(plan) -> list[int | None]:
    api = plan_to_api(plan)
    return [x["placeId"] for d in api["days"] for x in d["dropped"]]


def _km(a: Place, b: Place) -> float:
    return haversine_m(a.lat, a.lng, b.lat, b.lng)


def _assert_nearest_chain(case: unittest.TestCase, day: list[Place]) -> None:
    """차례가 「매번 남은 곳 중 가장 가까운 곳」 인지 — 같은 거리는 어느 쪽이든 된다."""
    for k in range(len(day) - 1):
        here, nxt, rest = day[k], day[k + 1], day[k + 1 :]
        best = min(_km(here, p) for p in rest)
        case.assertAlmostEqual(
            _km(here, nxt),
            best,
            delta=1e-6,
            msg=f"{here.place_id} 다음은 가장 가까운 곳이어야 한다: {[p.place_id for p in day]}",
        )


class _Base(unittest.TestCase):
    def setUp(self):
        self.book = SavedBook(ALL + [BLIND])

    def run_plan(self, client, req):
        with redirect_stderr(io.StringIO()):
            return plan_saved_with_model(self.book, req, client)

    def assert_all_once(self, plan, expected_ids):
        flat = _flat(plan)
        self.assertEqual(
            sorted(flat), sorted(str(i) for i in expected_ids), f"일정: {_ids(plan)}"
        )
        self.assertEqual(len(flat), len(set(flat)), f"한 곳은 한 번만: {_ids(plan)}")


# ── 모델이 짜는 경로 ──────────────────────────────────────────────────────────


class 모델로짜기(_Base):
    def test_모델이_전부_나누면_쓸_수_있는_곳이_모두_한_번씩(self):
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": sorted(NORTH_IDS)},
                        {"day": 2, "place_ids": sorted(SOUTH_IDS)},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ALL_IDS, days=2))
        self.assert_all_once(plan, ALL_IDS)
        self.assertEqual(len(plan.days), 2)
        self.assertEqual([set(d) for d in _ids(plan)], [NORTH_IDS, SOUTH_IDS])
        self.assertEqual(len(client.seen), 1)

    def test_모델에게_쓸_수_있는_곳이_모두_간다(self):
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": sorted(NORTH_IDS)},
                        {"day": 2, "place_ids": sorted(SOUTH_IDS)},
                    ]
                )
            ]
        )
        self.run_plan(client, _req(ALL_IDS, days=2))
        prompt = client.seen[0][-1]["content"]
        for pid in NORTH_IDS | SOUTH_IDS:
            with self.subTest(pid=pid):
                self.assertIn(pid, prompt)

    def test_하루_곳_수보다_많아도_당일에_전부_넣는다(self):
        # 당일 여유(하루 3곳)에 8곳 — 자르지 않는다(계획 「사용자가 정한 것」 1).
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": [str(i) for i in ALL_IDS]}])]
        )
        plan = self.run_plan(client, _req(ALL_IDS, days=1, pace="relaxed"))
        self.assertEqual(len(plan.days), 1)
        self.assert_all_once(plan, ALL_IDS)

    def test_빠뜨린_곳은_가장_가까운_날에_넣는다(self):
        # 모델이 서울 1003 을 빠뜨렸다 — 서울 날(1일차)에 들어가야 한다.
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": ["1000", "1001", "1002"]},
                        {"day": 2, "place_ids": sorted(SOUTH_IDS)},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ALL_IDS, days=2))
        self.assert_all_once(plan, ALL_IDS)
        self.assertIn("1003", _ids(plan)[0])
        self.assertEqual(set(_ids(plan)[1]), SOUTH_IDS)

    def test_지어낸_id_는_무시하고_모델의_나머지_답은_쓴다(self):
        # 모델이 담지 않은 9999 를 지어냈다. 부산을 1일차로 둔 모델의 나눔은 그대로 남아야 한다.
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": [*sorted(SOUTH_IDS), "9999"]},
                        {"day": 2, "place_ids": sorted(NORTH_IDS)},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ALL_IDS, days=2))
        self.assert_all_once(plan, ALL_IDS)
        self.assertNotIn("9999", _flat(plan))
        self.assertEqual([set(d) for d in _ids(plan)], [SOUTH_IDS, NORTH_IDS])

    def test_빠뜨리고_지어내도_전부_한_번씩(self):
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": ["1000", "4242"]},
                        {"day": 2, "place_ids": ["1005", "1005", "1006"]},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ALL_IDS, days=2))
        self.assert_all_once(plan, ALL_IDS)
        self.assertNotIn("4242", _flat(plan))

    def test_날마다_가까운_순으로_차례를_잡는다(self):
        # 서울 넷을 일부러 엇갈린 순서로 — 첫 곳에서 시작해 가까운 순으로 다시 놓인다.
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": ["1000", "1003", "1001", "1002"]},
                        {"day": 2, "place_ids": ["1004", "1007", "1005", "1006"]},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ALL_IDS, days=2))
        for d in plan.days:
            _assert_nearest_chain(self, [leg.place for leg in d.legs])
        self.assertEqual(_ids(plan)[0], ["1000", "1001", "1002", "1003"])


# ── 빠지는 곳 ─────────────────────────────────────────────────────────────────


class 빠지는곳(_Base):
    def test_없는_id_와_좌표_없는_곳은_빠지고_dropped_에_이유가_적힌다(self):
        ids = [*ALL_IDS, UNKNOWN, int(BLIND.place_id)]
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": sorted(NORTH_IDS)},
                        {"day": 2, "place_ids": sorted(SOUTH_IDS)},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ids, days=2))
        self.assert_all_once(plan, ALL_IDS)
        self.assertNotIn(str(UNKNOWN), _flat(plan))
        self.assertNotIn(BLIND.place_id, _flat(plan))
        dropped = _dropped_ids(plan)
        self.assertIn(UNKNOWN, dropped)
        self.assertIn(int(BLIND.place_id), dropped)
        api = plan_to_api(plan)
        for d in api["days"]:
            for x in d["dropped"]:
                with self.subTest(dropped=x):
                    self.assertTrue(str(x["reason"]).strip(), "이유가 적혀 있다")

    def test_모델에게는_쓸_수_없는_곳이_가지_않는다(self):
        ids = [*ALL_IDS, UNKNOWN, int(BLIND.place_id)]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": [str(i) for i in ALL_IDS]}])]
        )
        self.run_plan(client, _req(ids, days=1))
        prompt = client.seen[0][-1]["content"]
        self.assertNotIn("좌표없는곳", prompt)

    def test_모든_id_를_place_by_id_로_묻는다(self):
        ids = [*ALL_IDS, UNKNOWN]
        self.run_plan(None, _req(ids, days=2))
        self.assertEqual(sorted(self.book.id_calls), sorted(ids))

    def test_쓸_수_있는_곳이_없으면_PlanError(self):
        for ids in ([UNKNOWN], [int(BLIND.place_id)], [UNKNOWN, int(BLIND.place_id)]):
            with self.subTest(ids=ids), self.assertRaises(PlanError):
                self.run_plan(ScriptedClient([]), _req(ids, days=1))

    def test_빈_목록이면_PlanError(self):
        with self.assertRaises(PlanError):
            self.run_plan(None, _req([], days=1))


# ── 모델이 못 줄 때 ───────────────────────────────────────────────────────────


class 대체경로(_Base):
    def assert_fallback(self, plan, days):
        self.assert_all_once(plan, ALL_IDS)
        self.assertEqual(len(plan.days), days)

    def test_클라이언트가_없으면_계산으로_전부_일수만큼_나눈다(self):
        plan = self.run_plan(None, _req(ALL_IDS, days=2))
        self.assert_fallback(plan, 2)

    def test_계산은_하루를_가까운_곳끼리_묶는다(self):
        plan = self.run_plan(None, _req(ALL_IDS, days=2))
        self.assertEqual(
            sorted(map(frozenset, _ids(plan)), key=sorted),
            sorted([frozenset(NORTH_IDS), frozenset(SOUTH_IDS)], key=sorted),
        )

    def test_계산도_날마다_가까운_순(self):
        plan = self.run_plan(None, _req(ALL_IDS, days=2))
        for d in plan.days:
            _assert_nearest_chain(self, [leg.place for leg in d.legs])

    def test_모델을_못_부르면_계산(self):
        plan = self.run_plan(ScriptedClient([]), _req(ALL_IDS, days=2))
        self.assert_fallback(plan, 2)

    def test_JSON_이_아니면_계산(self):
        client = ScriptedClient([{"role": "assistant", "content": "죄송해요"}])
        plan = self.run_plan(client, _req(ALL_IDS, days=2))
        self.assert_fallback(plan, 2)

    def test_계산도_하루_곳_수보다_많으면_하루에_더_넣는다(self):
        # 당일 여유(3곳)에 8곳 — 계산 경로도 하나도 버리지 않는다.
        plan = self.run_plan(None, _req(ALL_IDS, days=1, pace="relaxed"))
        self.assert_fallback(plan, 1)

    def test_계산도_없는_id_는_dropped(self):
        plan = self.run_plan(None, _req([*ALL_IDS, UNKNOWN], days=2))
        self.assert_all_once(plan, ALL_IDS)
        self.assertIn(UNKNOWN, _dropped_ids(plan))


# ── 고른 일수 채우기(계획 「정한 것」 5) ──────────────────────────────────────
#
# 나눔(모델이든 계산이든)과 빠뜨린 곳 넣기가 끝난 뒤에도 날 수가 고른 일수보다 적으면, 하루 곳 수
# (`config/llm_planner.json` 의 `city.stops_per_day` — 여유 3·보통 5·빡빡 7)를 **넘는** 날만 지리적으로 둘로 나눈다.
# 나눈 두 날은 각각 2곳 이상, 큰 날부터, 고른 일수를 넘지 않는다. 하루 곳 수 안의 날은 나누지 않는다.

# 종로 근처 열둘(2000~2011) — 한 줄로 약 200m 간격.
TIGHT = [
    make_place(f"{2000 + k}", f"종로{k}", 37.570 + k * 0.0018, 126.980)
    for k in range(12)
]
TIGHT_IDS = [int(p.place_id) for p in TIGHT]
# 종로 셋(2100~2102)과 강남 셋(2103~2105) — 무리 안은 수백 m, 무리 사이는 약 9km.
JONGNO = [
    make_place(f"{2100 + k}", f"종로묶음{k}", 37.572 + k * 0.002, 126.979 + k * 0.002)
    for k in range(3)
]
GANGNAM = [
    make_place(f"{2103 + k}", f"강남묶음{k}", 37.498 + k * 0.002, 127.028 + k * 0.002)
    for k in range(3)
]
JONGNO_IDS = {p.place_id for p in JONGNO}
GANGNAM_IDS = {p.place_id for p in GANGNAM}
# 한 건물 안 여덟 곳(2200~2207) — 좌표가 같다. 계산 경로(좌표로 묶기)가 한 묶음밖에 못 만드는 경우.
SAME_SPOT = [
    make_place(f"{2200 + k}", f"한건물{k}", 37.5665, 126.9780) for k in range(8)
]
SAME_SPOT_IDS = [int(p.place_id) for p in SAME_SPOT]


class 고른일수채우기(_Base):
    def setUp(self):
        self.book = SavedBook(ALL + TIGHT + JONGNO + GANGNAM + SAME_SPOT)

    def assert_min_two(self, plan):
        for d in _ids(plan):
            with self.subTest(day=d):
                self.assertGreaterEqual(len(d), 2, f"나눈 날은 2곳 이상: {_ids(plan)}")

    def test_과밀한_하루를_나눠_고른_일수를_채운다(self):
        # 모델이 가까운 열두 곳을 1일차에만 넣었다. 3일·보통(5) → 12 > 5 라 나눠 3일이 된다.
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": [str(i) for i in TIGHT_IDS]}])]
        )
        plan = self.run_plan(client, _req(TIGHT_IDS, days=3, pace="normal"))
        self.assertEqual(len(plan.days), 3, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, TIGHT_IDS)
        self.assert_min_two(plan)

    def test_고른_일수를_넘겨_나누지_않는다(self):
        # 12곳·2일·보통(5) — 나눈 두 날이 아직 5를 넘어도 고른 일수(2)에서 멈춘다.
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": [str(i) for i in TIGHT_IDS]}])]
        )
        plan = self.run_plan(client, _req(TIGHT_IDS, days=2, pace="normal"))
        self.assertEqual(len(plan.days), 2, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, TIGHT_IDS)
        self.assert_min_two(plan)

    def test_하루_곳_수_안이면_나누지_않는다(self):
        # 3곳·2일·여유(3) — 3 > 3 이 아니므로 하루 그대로.
        ids = TIGHT_IDS[:3]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": [str(i) for i in ids]}])]
        )
        plan = self.run_plan(client, _req(ids, days=2, pace="relaxed"))
        self.assertEqual(len(plan.days), 1, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, ids)

    def test_한_곳짜리_날이_생기면_더_나누지_않는다(self):
        # 4곳·3일·여유(3) — 4 > 3 이라 2+2 로 한 번 나누고, 3일을 만들려면 1곳짜리 날이 생기므로 2일에서 멈춘다.
        ids = TIGHT_IDS[:4]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": [str(i) for i in ids]}])]
        )
        plan = self.run_plan(client, _req(ids, days=3, pace="relaxed"))
        self.assertEqual(len(plan.days), 2, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, ids)
        self.assertEqual(sorted(len(d) for d in _ids(plan)), [2, 2])

    def test_모델이_고른_일수를_채웠으면_나눔을_바꾸지_않는다(self):
        # 2일에 2일로 답했다 — 하루 곳 수(여유 3)를 넘는 날이 있어도 더 나누지 않는다.
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": sorted(NORTH_IDS)},
                        {"day": 2, "place_ids": sorted(SOUTH_IDS)},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ALL_IDS, days=2, pace="relaxed"))
        self.assertEqual([set(d) for d in _ids(plan)], [NORTH_IDS, SOUTH_IDS])

    def test_나눌_때는_지리적으로_무리끼리(self):
        # 1일차에 종로 셋 + 강남 셋(약 9km 떨어짐). 2일·여유(3) → 6 > 3 이라 나누고, 날마다 한 무리.
        ids = [int(p.place_id) for p in JONGNO + GANGNAM]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": [str(i) for i in ids]}])]
        )
        plan = self.run_plan(client, _req(ids, days=2, pace="relaxed"))
        self.assertEqual(len(plan.days), 2, f"일정: {_ids(plan)}")
        self.assertEqual(
            sorted(map(frozenset, _ids(plan)), key=sorted),
            sorted([frozenset(JONGNO_IDS), frozenset(GANGNAM_IDS)], key=sorted),
        )

    def test_가장_큰_과밀한_날부터_나누고_하루_곳_수_안의_날은_그대로(self):
        # 1일차 서울 넷 + 종로 열둘(16곳), 2일차 부산 셋. 3일·여유(3) → 큰 1일차를 나누고 부산 날은 그대로.
        day1 = [*sorted(NORTH_IDS), *[str(i) for i in TIGHT_IDS]]
        busan = sorted(SOUTH_IDS)[:3]
        ids = [int(i) for i in day1 + busan]
        client = ScriptedClient(
            [_reply([{"day": 1, "place_ids": day1}, {"day": 2, "place_ids": busan}])]
        )
        plan = self.run_plan(client, _req(ids, days=3, pace="relaxed"))
        self.assertEqual(len(plan.days), 3, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, ids)
        self.assert_min_two(plan)
        self.assertIn(
            set(busan), [set(d) for d in _ids(plan)], "부산 날은 나누지 않는다"
        )

    def test_계산_경로도_과밀한_날을_나눠_채운다(self):
        # 클라이언트 없음 — 한 건물 여덟 곳은 좌표로 묶으면 한 묶음뿐. 2일·보통(5) → 8 > 5 라 나눠 2일.
        plan = self.run_plan(None, _req(SAME_SPOT_IDS, days=2, pace="normal"))
        self.assertEqual(len(plan.days), 2, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, SAME_SPOT_IDS)
        self.assert_min_two(plan)


# ── 한 곳짜리 날 합치기(날을 억지로 채우지 않는다) ─────────────────────────────
#
# 날이 둘 이상인 일정에는 1곳짜리 날이 없어야 한다. 1곳짜리 날(모델 답이든 어디서든)은 그곳과 가장 가까운
# 곳들이 있는 날로 합친다. 합치기는 고른 일수 채우기보다 **먼저** 하므로, 합친 뒤 과밀해진 날은 다시 나뉠 수 있다.
# 예외: 쓸 수 있는 곳이 모두 합쳐 1곳이면 일정은 그 한 곳짜리 하루다.


class 한곳짜리날합치기(_Base):
    def setUp(self):
        self.book = SavedBook(ALL + TIGHT)

    def assert_min_two(self, plan):
        for d in _ids(plan):
            with self.subTest(day=d):
                self.assertGreaterEqual(len(d), 2, f"날마다 2곳 이상: {_ids(plan)}")

    def assert_no_single_day(self, plan):
        if len(plan.days) < 2:
            return
        for d in _ids(plan):
            with self.subTest(day=d):
                self.assertGreaterEqual(
                    len(d), 2, f"1곳짜리 날이 남으면 안 된다: {_ids(plan)}"
                )

    def test_두_곳과_한_곳으로_답하면_하루로_합친다(self):
        # 가까운 세 곳을 [a,b] / [c] 로 답했다. 2일·여유(3) → c 를 합쳐 3곳 하루(3 > 3 아님, 나누지 않음).
        ids = TIGHT_IDS[:3]
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": [str(i) for i in ids[:2]]},
                        {"day": 2, "place_ids": [str(ids[2])]},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ids, days=2, pace="relaxed"))
        self.assertEqual(len(plan.days), 1, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, ids)

    def test_칠_사_일로_답하면_한_곳을_가까운_날에_합치고_과밀하지_않으면_두_날(self):
        # 종로 열둘을 7/4/1 로 답했다. 1곳(맨 끝)은 4곳 날과 맞닿아 있다. 3일·빡빡(7) → 7/5, 7 을 넘는 날이 없어 2일.
        day1 = [str(i) for i in TIGHT_IDS[:7]]
        day2 = [str(i) for i in TIGHT_IDS[7:11]]
        day3 = [str(TIGHT_IDS[11])]
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": day1},
                        {"day": 2, "place_ids": day2},
                        {"day": 3, "place_ids": day3},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(TIGHT_IDS, days=3, pace="packed"))
        self.assertEqual(len(plan.days), 2, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, TIGHT_IDS)
        self.assert_no_single_day(plan)
        self.assertIn(
            set(day2 + day3), [set(d) for d in _ids(plan)], "한 곳은 가장 가까운 날로"
        )

    def test_합친_뒤_과밀하면_다시_나눠_고른_일수를_채운다(self):
        # 같은 7/4/1, 3일·여유(3) → 합쳐 7/5 가 되고 둘 다 3 을 넘으니 큰 날을 나눠 3일. 날마다 2곳 이상.
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": [str(i) for i in TIGHT_IDS[:7]]},
                        {"day": 2, "place_ids": [str(i) for i in TIGHT_IDS[7:11]]},
                        {"day": 3, "place_ids": [str(TIGHT_IDS[11])]},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(TIGHT_IDS, days=3, pace="relaxed"))
        self.assertEqual(len(plan.days), 3, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, TIGHT_IDS)
        self.assert_min_two(plan)

    def test_한_곳은_가장_가까운_날로_간다(self):
        # 1일차 서울 셋, 2일차 부산 셋, 3일차 부산 한 곳. 3일·보통(5) → 부산 한 곳은 부산 날로(4곳, 나누지 않음).
        north = sorted(NORTH_IDS)[:3]
        south = sorted(SOUTH_IDS)
        ids = [int(i) for i in north + south]
        client = ScriptedClient(
            [
                _reply(
                    [
                        {"day": 1, "place_ids": north},
                        {"day": 2, "place_ids": south[:3]},
                        {"day": 3, "place_ids": south[3:]},
                    ]
                )
            ]
        )
        plan = self.run_plan(client, _req(ids, days=3, pace="normal"))
        self.assertEqual(len(plan.days), 2, f"일정: {_ids(plan)}")
        self.assert_all_once(plan, ids)
        self.assert_no_single_day(plan)
        self.assertEqual(
            sorted(map(frozenset, _ids(plan)), key=sorted),
            sorted([frozenset(north), frozenset(south)], key=sorted),
        )

    def test_쓸_수_있는_곳이_한_곳뿐이면_그_한_곳짜리_하루(self):
        # 쓸 수 있는 곳이 하나(나머지는 없는 id). 2일 → 오류 없이 1일, 그 한 곳.
        only = TIGHT_IDS[0]
        client = ScriptedClient([_reply([{"day": 1, "place_ids": [str(only)]}])])
        plan = self.run_plan(client, _req([only, UNKNOWN], days=2, pace="normal"))
        self.assertEqual(_ids(plan), [[str(only)]])


if __name__ == "__main__":
    unittest.main()
