"""코스 추천 엔진 오프라인 평가.

    just agent-eval trip-guide          (Bazel 테스트로)
    python3 -m evals.plan_eval          (직접, 보고서를 화면에)
    python3 -m evals.plan_eval --source scene-api   (살아 있는 데이터로)

**실제 모델을 부르지 않는다.** 도구 선택은 대본 클라이언트(`ScriptedClient`)로 재고,
일정 계산에는 애초에 모델이 없다. 그래서 이 평가는 네트워크 없이 결정적으로 돈다
— 그것이 planner.py 에서 LLM 을 뺀 대가로 얻은 것이다.

재는 것은 `docs/design/ai-course-planner.md` §5 의 지표다.

| 지표 | 왜 재나 |
| --- | --- |
| 실현 가능률 | 순수 LLM 이 약 4% 인 자리(MIT). 알고리즘이면 100% 여야 한다 |
| 결정성 | 같은 입력에 같은 일정. 이것이 깨지면 나머지 지표가 의미를 잃는다 |
| 동선 효율 | 2-opt 가 최근접 결과를 실제로 줄이는가 |
| 커버리지 | 요청한 작품이 일정에 실제로 등장하는가 |
| 도구 선택 | 「일정 짜 줘」 에 plan_course 를 고르는가 |
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src.agent import TripGuide
from src.deepseek import ScriptedClient
from src.places import norm
from src.planner import (
    PlanError,
    PlanRequest,
    collect_candidates,
    load_config,
    make_plan,
    order_stops,
    plan_to_dict,
    route_meters,
)
from src.session import Anchor, Session
from src.tools import run_tool
from tests.fixtures import INCHEON, SEOUL, FakeBook

_CASES = Path(__file__).resolve().parent / "cases.json"


def eval_book() -> FakeBook:
    """평가용 고정 창구. 사례가 같은 데이터를 보게 한다."""
    return FakeBook({"도깨비": SEOUL + INCHEON, "이태원 클라쓰": [SEOUL[1], *INCHEON]})


@dataclass
class Metric:
    name: str
    passed: int = 0
    total: int = 0
    detail: list[str] = field(default_factory=list)

    @property
    def rate(self) -> float:
        return (self.passed / self.total * 100.0) if self.total else 0.0

    def note(self, ok: bool, text: str) -> None:
        self.total += 1
        if ok:
            self.passed += 1
        else:
            self.detail.append(text)


# ── 지표 ──────────────────────────────────────────────────────────────────────


def feasibility(book: Any, cases: list[dict], cfg: dict) -> tuple[Metric, list[dict]]:
    """실현 가능률 — 하루 예산 초과·중복 방문·좌표 없는 정지점이 없는가."""
    m = Metric("실현 가능률")
    plans: list[dict] = []
    start_hour = int(cfg["day"]["start_hour"])

    for case in cases:
        req = PlanRequest(
            titles=case["titles"],
            days=int(case.get("days", 1)),
            pace=case.get("pace", "normal"),
            start=tuple(case["start"]) if case.get("start") else None,
            must=case.get("must", []),
            avoid=case.get("avoid", []),
        )
        try:
            plan = make_plan(book, req)
        except PlanError as exc:
            m.note(False, f"{case['name']}: 일정을 못 만들었다 — {exc}")
            continue

        budget = int(cfg["pace"][req.pace]["daily_minutes"])
        cap = int(cfg["pace"][req.pace]["max_stops"])
        problems: list[str] = []
        seen: set[str] = set()
        for d in plan.days:
            if not d.legs:
                continue
            used = d.end_minute - start_hour * 60
            if used > budget:
                problems.append(f"{d.day}일차 {used}분 > 예산 {budget}분")
            if len(d.legs) > cap:
                problems.append(f"{d.day}일차 정지점 {len(d.legs)} > 상한 {cap}")
            for leg in d.legs:
                if not leg.place.has_coords():
                    problems.append(f"{leg.place.name} 에 좌표가 없다")
                if leg.place.name in seen:
                    problems.append(f"{leg.place.name} 를 두 번 간다")
                seen.add(leg.place.name)

        m.note(not problems, f"{case['name']}: " + " · ".join(problems))
        plans.append({"case": case["name"], "plan": plan})
    return m, plans


def determinism(book: Any, cases: list[dict], runs: int = 5) -> Metric:
    """결정성 — 같은 입력을 여러 번 돌려 같은 일정이 나오는가."""
    m = Metric("결정성")
    for case in cases:
        req = PlanRequest(
            titles=case["titles"],
            days=int(case.get("days", 1)),
            pace=case.get("pace", "normal"),
            start=tuple(case["start"]) if case.get("start") else None,
            must=case.get("must", []),
            avoid=case.get("avoid", []),
        )
        try:
            first = json.dumps(
                plan_to_dict(make_plan(book, req)), ensure_ascii=False, sort_keys=True
            )
        except PlanError:
            continue
        same = all(
            json.dumps(
                plan_to_dict(make_plan(book, req)), ensure_ascii=False, sort_keys=True
            )
            == first
            for _ in range(runs - 1)
        )
        m.note(same, f"{case['name']}: {runs} 번 중 다른 일정이 나왔다")
    return m


def route_efficiency(book: Any, cfg: dict) -> tuple[Metric, float]:
    """동선 효율 — 2-opt 가 최근접 결과를 얼마나 줄이는가."""
    m = Metric("동선 효율 (2-opt 이 나쁘게 만들지 않는가)")
    plain = dict(cfg)
    plain["optimize"] = dict(cfg["optimize"], two_opt_rounds=0)

    gains: list[float] = []
    for titles in (["도깨비"], ["도깨비", "이태원 클라쓰"]):
        scored, _, _ = collect_candidates(book, titles, cfg)
        located = [s for s in scored if s.place.has_coords()]
        if len(located) < 4:
            continue
        greedy = route_meters(order_stops(located, None, plain))
        tuned = route_meters(order_stops(located, None, cfg))
        m.note(
            tuned <= greedy + 1e-6,
            f"{titles}: 2-opt 이 동선을 늘렸다 ({greedy:.0f}→{tuned:.0f})",
        )
        if greedy > 0:
            gains.append((greedy - tuned) / greedy * 100.0)
    return m, (sum(gains) / len(gains) if gains else 0.0)


def coverage(book: Any, cases: list[dict]) -> Metric:
    """커버리지 — 요청한 작품이 일정에 실제로 등장하는가."""
    m = Metric("작품 커버리지")
    for case in cases:
        req = PlanRequest(
            titles=case["titles"],
            days=int(case.get("days", 1)),
            pace=case.get("pace", "normal"),
            avoid=case.get("avoid", []),
        )
        try:
            plan = make_plan(book, req)
        except PlanError:
            continue
        # 정규화해서 비교한다. 창구는 정본 제목을 돌려주는데 거기엔 공백이 없다 —
        # 「이태원 클라쓰」 로 물으면 「이태원클라쓰」 가 온다. 날것으로 비교했더니
        # 멀쩡한 일정이 커버리지 실패로 잡혔다(2026-09-02).
        shown = {norm(t) for d in plan.days for leg in d.legs for t in leg.titles}
        for want in case["titles"]:
            m.note(
                norm(want) in shown, f"{case['name']}: 「{want}」 가 일정에 안 나온다"
            )
    return m


def tool_choice(cases: list[dict]) -> Metric:
    """도구 선택 — 대본 모델이 고른 도구가 실제로 실행되는가.

    **모델이 옳은 도구를 고르는지를 재는 것이 아니다.** 그것은 실제 모델을 불러야
    알 수 있고 그러면 결정적이지 않다. 여기서 재는 것은 「모델이 그 도구를 골랐을 때
    우리 쪽이 제대로 받아 실행하는가」 다 — 계약과 구현이 어긋나면 여기서 걸린다.
    """
    m = Metric("도구 선택 처리")
    for case in cases:
        expect = case["expect"]
        session = Session(book=eval_book())
        session.here = Anchor("남산", 37.5512, 126.9882)
        session.cart.extend(SEOUL[:2])
        session.shown.extend(SEOUL[:3])
        session.shown.extend(SEOUL)

        # 고치는 도구는 고칠 일정이 있어야 뜻이 있다. 사례가 요구하면 먼저 짜 둔다.
        if case.get("setup_plan"):
            run_tool("plan_course", dict(case["setup_plan"]), session)

        if expect is None:
            # 되물어야 하는 자리 — 도구를 안 부르고 답만 하는 흐름이 도는지 본다.
            client = ScriptedClient(
                [{"role": "assistant", "content": "어느 작품을 보고 싶으세요?"}]
            )
            guide = TripGuide(session, client, config={"max_tool_rounds": 4})
            turn = guide.ask(case["utterance"])
            m.note(
                not turn.tool_runs and bool(turn.reply),
                f"{case['name']}: 되묻지 못했다",
            )
            continue

        # 어느 장소가 몇 일차에 갈지는 사례 파일이 알 수 없다 — 엔진이 정한다.
        # 그래서 「그 일차의 첫 정지점」 만 실행 시점에 채워 넣는다.
        args = dict(case["args"])
        if args.get("name") == "__1일차의_첫_정지점__":
            first = next(
                (
                    d.legs[0].place.name
                    for d in (session.plan.days if session.plan else [])
                    if d.day == 1 and d.legs
                ),
                "",
            )
            args["name"] = first
        if args.get("add") == ["__일정에_없는_곳__"]:
            placed = {
                leg.place.name
                for dd in (session.plan.days if session.plan else [])
                for leg in dd.legs
            }
            spare = next((x.name for x in session.shown if x.name not in placed), None)
            args["add"] = [spare] if spare else []
        if args.get("remove") == ["__그_일차의_첫_정지점__"]:
            day = next(
                (
                    d
                    for d in (session.plan.days if session.plan else [])
                    if d.day == args["day"]
                ),
                None,
            )
            args["remove"] = [day.legs[0].place.name] if day and day.legs else []

        client = ScriptedClient(
            [
                {
                    "role": "assistant",
                    "tool_calls": [
                        {
                            "id": "c1",
                            "function": {
                                "name": expect,
                                "arguments": json.dumps(args, ensure_ascii=False),
                            },
                        }
                    ],
                },
                {"role": "assistant", "content": "여기 있습니다."},
            ]
        )
        guide = TripGuide(session, client, config={"max_tool_rounds": 4})
        turn = guide.ask(case["utterance"])
        ran = [r.name for r in turn.tool_runs]
        ok = ran == [expect] and not turn.tool_runs[0].refused
        why = (
            turn.tool_runs[0].result.get("결과없음", "")
            if turn.tool_runs
            else "도구를 안 불렀다"
        )
        m.note(ok, f"{case['name']}: {ran} — {why}")
    return m


# ── 보고서 ────────────────────────────────────────────────────────────────────


def run(book: Any) -> dict[str, Any]:
    cfg = load_config()
    cases = json.loads(_CASES.read_text(encoding="utf-8"))
    plan_cases = cases["plan"]

    feas, plans = feasibility(book, plan_cases, cfg)
    det = determinism(book, plan_cases)
    eff, gain = route_efficiency(book, cfg)
    cov = coverage(book, plan_cases)
    tools = tool_choice(cases["tool_choice"])

    metrics = [feas, det, eff, cov, tools]
    return {
        "metrics": metrics,
        "route_gain_percent": gain,
        "plans": plans,
        "passed": all(m.rate == 100.0 for m in metrics),
    }


def report(result: dict[str, Any]) -> str:
    lines = ["", "═" * 62, " trip-guide 코스 추천 엔진 평가", "═" * 62, ""]
    for m in result["metrics"]:
        mark = "통과" if m.rate == 100.0 else "실패"
        lines.append(f"  [{mark}] {m.name:34} {m.passed:3}/{m.total:<3} {m.rate:5.1f}%")
        for d in m.detail[:5]:
            lines.append(f"         ↳ {d}")
    lines.append("")
    lines.append(f"  2-opt 동선 단축      평균 {result['route_gain_percent']:.1f}%")
    lines.append("")
    lines.append("  참고 — 순수 LLM 일정의 실현 가능률은 약 4% 다 (MIT 통제 실험).")
    lines.append(
        "         이 엔진이 100% 인 것은 자랑이 아니라 알고리즘을 쓴 당연한 결과이고,"
    )
    lines.append("         그 당연함을 얻으려고 LLM 을 계산에서 뺐다.")
    lines.append("═" * 62)
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="코스 추천 엔진 평가")
    parser.add_argument(
        "--source",
        choices=["fixture", "scene-api"],
        default="fixture",
        help="fixture 는 네트워크 없이 고정 데이터로, scene-api 는 살아 있는 서버로",
    )
    parser.add_argument("--base-url", default="http://localhost:8081/v1")
    parser.add_argument("--json", help="결과를 이 경로에 JSON 으로 쓴다")
    args = parser.parse_args(argv)

    if args.source == "scene-api":
        from src.sceneapi import SceneApiPlaceBook

        book: Any = SceneApiPlaceBook(args.base_url)
    else:
        book = eval_book()

    result = run(book)
    print(report(result))

    if args.json:
        payload = {
            "source": args.source,
            "passed": result["passed"],
            "route_gain_percent": round(result["route_gain_percent"], 2),
            "metrics": [
                {
                    "name": m.name,
                    "passed": m.passed,
                    "total": m.total,
                    "rate": round(m.rate, 1),
                    "failures": m.detail,
                }
                for m in result["metrics"]
            ],
        }
        Path(args.json).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"  결과를 {args.json} 에 적었다")

    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
