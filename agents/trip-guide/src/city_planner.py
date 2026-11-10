"""도시로 짜는 일정 — 후보를 추려서 모델에게 넘긴다 (MZ2AZ-548, docs/project/plans/city-ai-course.md §3).

「작품으로」(`llm_planner.py`)와 같은 창구(`POST /plan`)의 두 번째 입력이다. 작품 대신 도시를 받으면
그 도시 촬영지를 인기순으로 받아 **앞에서 필요한 만큼만** 모델에게 넘긴다. 서울은 124곳이라 다 넘기면
먼 동네가 같은 날에 섞일 위험이 크다(2026-11-10 사용자 결정 — 추려서 넘긴다).

「작품으로」 와 다른 점: 프롬프트에 고르는 법(인기 먼저·작품 고루·하루는 가까운 동네끼리)을 적는다.
「작품으로」 는 규칙 문장을 넣지 않기로 했다(2026-10-02) — 도시는 사용자가 「인기 위주 + 작품 섞기」 를
정했으므로 적고, 효과는 평가로 본다(계획 §4).

모델이 답을 못 주면 앱의 계산(`CityPlanRules.plan`)과 같은 규칙으로 짠다 — 인기순 앞에서 필요한 수의
2배를 잡아 1등에서 시작해 매번 가장 가까운 곳을 잇고 하루 몫으로 자른다.
"""

from __future__ import annotations

import json
import math
import sys
from collections.abc import Callable
from pathlib import Path
from string import Template
from typing import Any

from .llm_planner import LlmPlanError, load_config, parse_days
from .model_client import ModelError
from .places import Place, PlaceSource, haversine_m
from .planner import DayPlan, Plan, PlanError, PlanRequest, Scored, _lay_out
from .planner import load_config as load_planner_config
from .sceneapi import SceneApiError

_PROMPT = Path(__file__).absolute().parent.parent / "prompts" / "plan_city.txt"

# 계약의 `city` 상한(`GuidePlanRequest.city.maxLength`).
CITY_MAX = 40


def plan_target(body: dict[str, Any]) -> tuple[list[str], str | None]:
    """`POST /plan` 몸체에서 (작품들, 도시) — **정확히 하나.** 아니면 `PlanError`.

    빈 작품 목록과 공백뿐인 도시는 보내지 않은 것으로 본다 — 백엔드의 생성된 요청 객체는 빠진 목록을 빈 목록으로
    실어 보낸다(계약 1.28.0 `GuidePlanRequest.titles`).
    """
    titles = body.get("titles") or []
    if isinstance(titles, str):
        titles = [titles]
    city = body.get("city")
    if city is not None:
        if not isinstance(city, str) or len(city) > CITY_MAX:
            raise PlanError(f"city 는 {CITY_MAX}자 이하 문자열이어야 한다")
        city = city.strip() or None
    if bool(titles) == bool(city):
        raise PlanError("titles 와 city 중 하나만 보내야 한다")
    return [str(t) for t in titles], city


def _city_cfg(cfg: dict[str, Any]) -> dict[str, Any]:
    return cfg["city"]


def _needed(days: int, pace: str, cfg: dict[str, Any]) -> int:
    per_day = _city_cfg(cfg)["stops_per_day"]
    return int(per_day.get(pace) or per_day[cfg["pace_fallback"]]) * max(days, 0)


def candidate_count(total: int, days: int, pace: str, cfg: dict[str, Any]) -> int:
    """모델에게 넘길 후보 수 — (하루 곳 수 × 일수) × 배수, 적어도 최소, 도시 전체보다 많지 않게."""
    city = _city_cfg(cfg)
    want = max(
        _needed(days, pace, cfg) * int(city["candidates_factor"]),
        int(city["candidates_min"]),
    )
    return min(total, want)


def area(address: str) -> str:
    """주소에서 시·도를 뺀 다음 마디 — 「서울특별시 종로구 …」 → 「종로구」. 없으면 빈 문자열."""
    parts = (address or "").split()
    return parts[1] if len(parts) >= 2 else ""


def build_city_prompt(
    city: str,
    days: int,
    pace: str,
    must: list[str],
    places: list[Place],
    cfg: dict[str, Any],
) -> str:
    """모델에게 줄 글. 줄마다 id·name·area·lat·lng·popularity·work. `places` 는 인기순이다.

    인기도는 서버 순서에서 만든다 — 1등이 1.0, 뒤로 갈수록 고르게 줄어든다. 도시를 가로지르는 점수가 응답에 없어서다.
    """
    lines: list[str] = []
    count = len(places)
    zone_of = zones(places, _zone_size(pace, cfg))
    for i, p in enumerate(places):
        row: dict[str, Any] = {"id": p.place_id, "name": p.name}
        row["area"] = area(p.address)
        row["zone"] = zone_of[p.place_id]
        row["lat"] = round(p.lat, 5)
        row["lng"] = round(p.lng, 5)
        row["popularity"] = round(1.0 - i / count, 3)
        row["work"] = ",".join(p.titles[:2])
        lines.append(json.dumps(row, ensure_ascii=False))
    pace_text = cfg["pace"].get(pace) or cfg["pace"][cfg["pace_fallback"]]
    must_text = f"\n- 꼭 갈 곳: {', '.join(must)}" if must else ""
    return Template(_PROMPT.read_text(encoding="utf-8")).substitute(
        city=city, days=days, pace=pace_text, must=must_text, places="\n".join(lines)
    )


def _zone_size(pace: str, cfg: dict[str, Any]) -> int:
    return _needed(1, pace, cfg) * int(_city_cfg(cfg).get("zone_factor", 1))


def fill_days(
    chosen: list[list[Place]],
    candidates: list[Place],
    zone_of: dict[str, str],
    days: int,
    per_day: int,
) -> list[list[Place]]:
    """모자란 날·빠진 날을 같은 구역의 안 쓴 후보로 채운다(계획 §3 「빈자리 채우기」). `candidates` 는 인기순이다.

    모델은 하루 몫보다 적게 고르거나 날을 빠뜨렸다(2026-11-10 평가: 부산 3일 빡빡에 2일). 채운 곳은 그날 곳들 뒤에 붙고,
    다른 구역에서는 끌어오지 않는다 — 다른 구역을 섞으면 하루 이동이 다시 길어진다.
    """
    out = [list(day) for day in chosen]
    used = {p.place_id for day in out for p in day}

    def top_up(day: list[Place]) -> None:
        zone = zone_of.get(day[0].place_id)
        pool = [
            p
            for p in candidates
            if p.place_id not in used and zone_of.get(p.place_id) == zone
        ]
        while len(day) < per_day and pool:
            best = min(pool, key=lambda c: min(_km(c, p) for p in day))
            pool.remove(best)
            used.add(best.place_id)
            day.append(best)

    for day in out:
        if day:
            top_up(day)
    while len(out) < days:
        opener = next((p for p in candidates if p.place_id not in used), None)
        if opener is None:
            break
        used.add(opener.place_id)
        day = [opener]
        top_up(day)
        out.append(day)
    return out


def _km(a: Place, b: Place) -> float:
    return haversine_m(a.lat, a.lng, b.lat, b.lng)


def chain_plan(
    places: list[Place], days: int, pace: str, cfg: dict[str, Any]
) -> list[list[Place]]:
    """모델이 못 했을 때의 계산 — 인기순 앞에서 (필요한 수 × fallback_factor) 를 잡아 가까운 곳으로 잇고 하루 몫으로 자른다.

    앱의 `CityPlanRules.plan` 과 같은 규칙이다(iOS·웹·안드로이드). 빈 날은 뺀다.
    """
    want = _needed(days, pace, cfg)
    if want <= 0 or not places:
        return []
    pool = list(places[: want * int(_city_cfg(cfg)["fallback_factor"])])
    chained = [pool.pop(0)]
    while pool:
        last = chained[-1]
        best = min(range(len(pool)), key=lambda i: _km(last, pool[i]))
        chained.append(pool.pop(best))
    picked = chained[:want]
    per_day = want // days
    out = [picked[d * per_day : (d + 1) * per_day] for d in range(days)]
    return [d for d in out if d]


def zones(places: list[Place], zone_size: int) -> dict[str, str]:
    """후보를 좌표로 묶은 구역 — place_id → 「A」·「B」…(계획 §3 「구역과 순서」). `places` 는 인기순이다.

    구역 수는 올림(곳 수 ÷ `zone_size`) — 부르는 쪽이 하루 곳 수 × `zone_factor` 를 넘긴다. 중심은 인기 1등에서 시작해 이미 고른
    중심에서 가장 먼 곳을 차례로 더하고(가장 먼 점 고르기), 각 곳을 가장 가까운 중심에 넣고 중심을 무리의 평균으로 옮기기를
    10번 한다. 난수가 없어 같은 입력이면 같은 답이다. 행정 동네(`area`)는 제주처럼 둘뿐인 도시에서 단서가 되지 못했다.
    """
    if not places:
        return {}
    count = max(1, math.ceil(len(places) / max(zone_size, 1)))
    points = [(p.lat, p.lng) for p in places]
    centers = [points[0]]
    while len(centers) < min(count, len(points)):
        far = max(points, key=lambda q: min(haversine_m(*q, *c) for c in centers))
        centers.append(far)
    owner = [0] * len(points)
    for _ in range(10):
        owner = [
            min(range(len(centers)), key=lambda k, q=q: haversine_m(*q, *centers[k]))
            for q in points
        ]
        for k in range(len(centers)):
            members = [q for q, o in zip(points, owner, strict=True) if o == k]
            if members:
                centers[k] = (
                    sum(q[0] for q in members) / len(members),
                    sum(q[1] for q in members) / len(members),
                )
    names: dict[int, str] = {}
    for o in owner:  # 인기순으로 처음 나온 무리부터 A·B·C
        names.setdefault(o, chr(ord("A") + len(names)))
    return {p.place_id: names[o] for p, o in zip(places, owner, strict=True)}


def reorder_day(day: list[Place]) -> list[Place]:
    """그날 첫 곳에서 시작해 매번 가장 가까운 곳으로 차례를 다시 잡는다. 고른 곳은 그대로."""
    if len(day) < 3:
        return list(day)
    left = list(day[1:])
    out = [day[0]]
    while left:
        last = out[-1]
        best = min(range(len(left)), key=lambda i: _km(last, left[i]))
        out.append(left.pop(best))
    return out


def trim_days(
    chosen: list[list[Place]], pace: str, cfg: dict[str, Any]
) -> list[list[Place]]:
    """모델이 고른 날마다 앞에서 하루 곳 수까지만 남긴다 — 순서는 그대로(계획 §3 「하루 몫 자르기」).

    모델은 하루 곳 수를 지키지 않았다(2026-11-10 실측: 서울 2일 빡빡에 13·12곳). 숫자를 모델에게 주면 다른 지역을
    끌어오므로(`config/llm_planner.json` 의 `_pace_설명`) 주지 않고 여기서 자른다.
    """
    per_day = _needed(1, pace, cfg)
    return [day[:per_day] for day in chosen]


def _plan(
    req: PlanRequest, chosen: list[list[Place]], considered: int, *, llm: bool
) -> Plan:
    """날짜별 장소를 앱이 그릴 일정으로. 순서는 받은 그대로, 시각은 엔진의 어림."""
    planner_cfg = load_planner_config()
    titles: list[str] = []
    plan_days: list[DayPlan] = []
    for i, day in enumerate(chosen, start=1):
        items = []
        for p in day:
            for t in p.titles:
                if t not in titles:
                    titles.append(t)
            items.append(Scored(p, 0.0, 0.0, 0.0, 0.0, 0.0, p.titles[:3]))
        plan_days.append(DayPlan(day=i, legs=_lay_out(items, None, planner_cfg)))
    how = (
        "AI 가 도시 촬영지의 인기와 위치를 보고 고른 일정이다."
        if llm
        else "인기순으로 잡아 가까운 곳끼리 이은 일정이다."
    )
    return Plan(
        request=PlanRequest(
            titles=titles,
            days=req.days,
            pace=req.pace,
            must=list(req.must),
            avoid=list(req.avoid),
            city=req.city,
        ),
        days=plan_days,
        considered=considered,
        signals={"llm": llm, "city": True},
        notes=[
            how,
            "시각은 직선거리로 어림한 값이다. 영업시간·휴무일은 반영하지 않았다.",
        ],
    )


def plan_city_with_model(
    book: PlaceSource,
    req: PlanRequest,
    client: Any | None,
    *,
    budget: Callable[[], float] | None = None,
    cfg: dict[str, Any] | None = None,
) -> Plan:
    """도시로 짠다. 모델이 있으면 모델, 못 하면 `chain_plan`. 도시에 좌표 있는 촬영지가 없으면 `PlanError`.

    모델 실패는 이유를 stderr 에 남기고 계산으로 넘어간다(`llm_planner.plan_with_model` 과 같은 규칙).
    """
    cfg = cfg or load_config()  # 평가(evals/city_plan_live.py)가 후보 수를 바꿔 넘긴다
    city = req.city or ""
    fetched = book.by_city(city, int(_city_cfg(cfg)["fetch_limit"]))
    avoid = {a.strip() for a in req.avoid}
    places = [
        p
        for p in fetched
        if p.has_coords() and p.place_id and p.name.strip() not in avoid
    ]
    if not places:
        raise PlanError(f"「{city}」 에 좌표 있는 촬영지가 없다")
    candidates = places[: candidate_count(len(places), req.days, req.pace, cfg)]

    if client is not None and cfg.get("enabled"):
        try:
            prompt = build_city_prompt(
                city, req.days, req.pace, req.must, candidates, cfg
            )
            reply = client.chat(
                [{"role": "user", "content": prompt}], budget=budget, json_mode=True
            )
            by_id = {p.place_id: p for p in candidates}
            chosen = parse_days(str(reply.get("content") or ""), by_id, req.days)
            kept = trim_days([d for d in chosen if d], req.pace, cfg)
            zone_of = zones(candidates, _zone_size(req.pace, cfg))
            per_day = _needed(1, req.pace, cfg)
            filled = fill_days(kept, candidates, zone_of, req.days, per_day)
            kept = [reorder_day(d) for d in filled]
            return _plan(req, kept, len(candidates), llm=True)
        except (PlanError, SceneApiError):
            raise
        except Exception as exc:  # noqa: BLE001 — 모델 경로가 깨져도 일정은 나가야 한다
            kind = "모델" if isinstance(exc, (LlmPlanError, ModelError)) else "예상 밖"
            print(f"[plan] 도시 {kind} 일정 실패 → 계산: {exc!r}", file=sys.stderr)
    return _plan(
        req, chain_plan(places, req.days, req.pace, cfg), len(candidates), llm=False
    )
