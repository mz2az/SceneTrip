"""담은 촬영지로 짜는 일정 — 사용자가 담은 곳을 날짜별로 나누고 차례를 정한다 (MZ2AZ-601, docs/project/plans/saved-ai-course.md §3).

「작품으로」(`llm_planner.py`)·「도시로」(`city_planner.py`)와 같은 창구(`POST /plan`)의 세 번째 입력(`placeIds`)이다.
앞의 둘은 **무엇을 갈지** 고르지만, 여기서는 사용자가 이미 골랐다. 그래서 규칙이 하나 더 있다.

    담은 곳은 하나도 버리지 않는다.

하루 곳 수(`pace`)보다 많으면 하루에 더 넣는다(2026-11-27 사용자 결정). 빠지는 곳은 쓸 수 없는 곳뿐이다 —
scene-api 가 모르는 id(없음·숨김)와 좌표 없는 곳. 그 둘은 첫날의 `dropped` 에 이유와 함께 적는다.

모델에게는 「모두 쓰되 하루는 가까운 곳끼리」 를 묻고, 답을 그대로 믿지 않는다 — 지어낸 id 는 버리고 빠뜨린 곳은
가장 가까운 날에 넣는다(「작품으로」 의 `parse_days` 는 지어낸 id 하나에 답 전체를 버리지만, 여기서는 고를 것이 없고
나눔만 쓰면 되므로 나머지를 살린다). 모델이 못 주면 좌표로 일수만큼 묶어(`city_planner.zones`) 같은 일을 한다.
"""

from __future__ import annotations

import json
import math
import sys
from collections.abc import Callable
from pathlib import Path
from string import Template
from typing import Any

from .city_planner import PLACES_MAX, _km, area, reorder_day, zones
from .llm_planner import LlmPlanError, _strip_fence, load_config
from .places import Place, PlaceSource
from .planner import DayPlan, Plan, PlanError, PlanRequest, Scored, _lay_out
from .planner import load_config as load_planner_config
from .sceneapi import SceneApiError

_PROMPT = Path(__file__).absolute().parent.parent / "prompts" / "plan_saved.txt"

# 계약의 `placeIds` 상한 — 검사는 `plan_target` 이 하고, 여기서는 직접 부를 때(평가·CLI)를 위해 한 번 더 자른다.
SAVED_MAX = PLACES_MAX

_GONE = "찾을 수 없는 촬영지(없거나 숨김)"
_BLIND = "좌표가 없어 동선에 넣을 수 없다"


def _gather(
    book: PlaceSource, ids: list[int]
) -> tuple[list[Place], list[tuple[Place, str]]]:
    """id 마다 `place_by_id` 로 묻는다. (쓸 수 있는 곳, 빠진 곳) — 같은 id 는 한 번만."""
    usable: list[Place] = []
    dropped: list[tuple[Place, str]] = []
    seen: set[int] = set()
    for raw in ids:
        pid = int(raw)
        if pid in seen:
            continue
        seen.add(pid)
        place = book.place_by_id(pid)
        if place is None:
            dropped.append((_stub(pid), _GONE))
        elif not place.has_coords():
            dropped.append((place, _BLIND))
        else:
            usable.append(place)
    return usable, dropped


def _stub(pid: int) -> Place:
    """못 받은 id 를 `dropped` 에 싣기 위한 빈 장소 — 계약의 `placeId` 만 의미가 있다."""
    return Place(
        place_id=str(pid),
        name="",
        address="",
        lat=None,
        lng=None,
        kind="",
        naver_url="",
        image_url="",
        tier="",
        selected=False,
        mentions=0,
    )


def _zone_count(n: int, days: int) -> int:
    return max(1, math.ceil(n / max(days, 1)))


def build_saved_prompt(
    days: int, pace: str, places: list[Place], cfg: dict[str, Any]
) -> str:
    """모델에게 줄 글. 줄마다 id·name·area·zone·lat·lng·work. 하루 곳 수는 숫자로 주지 않는다(`city_planner` 와 같은 이유)."""
    zone_of = zones(places, _zone_count(len(places), days))
    lines = []
    for p in places:
        row: dict[str, Any] = {
            "id": p.place_id,
            "name": p.name,
            "area": area(p.address),
            "zone": zone_of[p.place_id],
            "lat": round(p.lat, 5),
            "lng": round(p.lng, 5),
            "work": ",".join(p.titles[:2]),
        }
        lines.append(json.dumps(row, ensure_ascii=False))
    pace_text = cfg["pace"].get(pace) or cfg["pace"][cfg["pace_fallback"]]
    return Template(_PROMPT.read_text(encoding="utf-8")).substitute(
        days=days, pace=pace_text, places="\n".join(lines)
    )


def parse_saved_days(
    text: str, by_id: dict[str, Place], days: int
) -> list[list[Place]]:
    """모델 답을 날짜별 장소로. 지어낸 id·두 번 나온 id 는 버린다. JSON 이 아니거나 days 가 없으면 `LlmPlanError`."""
    try:
        data = json.loads(_strip_fence(text))
    except json.JSONDecodeError as exc:
        raise LlmPlanError(f"모델 응답이 JSON 이 아니다: {exc}") from exc
    raw_days = data.get("days") if isinstance(data, dict) else None
    if not isinstance(raw_days, list) or not raw_days:
        raise LlmPlanError("모델 응답에 days 가 없다")
    ordered = sorted(
        (d for d in raw_days if isinstance(d, dict)),
        key=lambda d: d.get("day") if isinstance(d.get("day"), int) else 0,
    )
    out: list[list[Place]] = []
    seen: set[str] = set()
    for d in ordered[:days]:
        ids = d.get("place_ids")
        if not isinstance(ids, list):
            raise LlmPlanError("place_ids 가 목록이 아니다")
        day = []
        for raw in ids:
            pid = str(raw)
            if pid in by_id and pid not in seen:
                seen.add(pid)
                day.append(by_id[pid])
        out.append(day)
    if not any(out):
        raise LlmPlanError("모델이 담은 곳을 하나도 나누지 않았다")
    return [d for d in out if d]


def place_missing(chosen: list[list[Place]], places: list[Place]) -> list[list[Place]]:
    """나눔에서 빠진 곳을 가장 가까운 곳이 있는 날에 넣는다. 날이 하나도 없으면 한 날로."""
    out = [list(d) for d in chosen if d]
    used = {p.place_id for d in out for p in d}
    for p in places:
        if p.place_id in used:
            continue
        if not out:
            out.append([p])
        else:
            best = min(range(len(out)), key=lambda k: min(_km(p, q) for q in out[k]))
            out[best].append(p)
        used.add(p.place_id)
    return out


def split_by_zone(places: list[Place], days: int) -> list[list[Place]]:
    """모델이 못 했을 때 — 좌표로 일수만큼 묶어 한 묶음을 하루로. 묶음이 일수보다 적으면 그만큼만."""
    if not places:
        return []
    count = min(days, len(places))
    zone_of = zones(places, _zone_count(len(places), count))
    groups: dict[str, list[Place]] = {}
    for p in places:
        groups.setdefault(zone_of[p.place_id], []).append(p)
    out = [groups[k] for k in sorted(groups)]
    while len(out) > days:  # 묶음이 일수보다 많으면 가장 작은 둘을 합친다
        out.sort(key=len)
        a = out.pop(0)
        b = min(
            range(len(out)), key=lambda k: min(_km(x, y) for x in a for y in out[k])
        )
        out[b].extend(a)
    return out


def _per_day(pace: str, cfg: dict[str, Any]) -> int:
    """하루 곳 수 — 「도시로」 와 같은 설정(`city.stops_per_day`)."""
    table = cfg["city"]["stops_per_day"]
    return int(table.get(pace) or table["normal"])


def split_two(day: list[Place]) -> tuple[list[Place], list[Place]]:
    """하루를 지리적으로 둘로 — 가장 먼 두 곳을 씨앗으로 가까운 쪽에 붙인다. 양쪽 모두 2곳 이상이 되게 맞춘다.

    좌표가 모두 같으면(한 건물 안 여러 곳) 거리로는 가를 수 없으므로 차례의 앞뒤 절반으로 나눈다.
    """
    n = len(day)
    pairs = [(i, j) for i in range(n) for j in range(i + 1, n)]
    i, j = max(pairs, key=lambda ij: _km(day[ij[0]], day[ij[1]]))
    if _km(day[i], day[j]) <= 0:
        chained = reorder_day(day)
        return chained[: n // 2], chained[n // 2 :]
    a, b = day[i], day[j]
    left = [p for p in day if _km(p, a) <= _km(p, b)]
    right = [p for p in day if _km(p, a) > _km(p, b)]
    while (
        len(left) < 2 or len(right) < 2
    ):  # 한쪽이 모자라면 상대편에서 가장 가까운 곳을 옮겨 온다
        small, big = (left, right) if len(left) < len(right) else (right, left)
        move = min(big, key=lambda p: min(_km(p, q) for q in small))
        big.remove(move)
        small.append(move)
    return left, right


def merge_single_days(days: list[list[Place]]) -> list[list[Place]]:
    """1곳짜리 날을 가장 가까운 날에 합친다 — 날이 하나뿐이면 그대로(계획 「정한 것」 5).

    모델은 고른 일수를 채우려고 한 곳만 따로 떼어 하루로 두곤 했다(2026-11-27 로컬: 서울 12곳·3일에 7·4·1).
    하루에 한 곳은 「억지로 나눈 날」 이다. 작은 날부터 합치고, 합친 뒤 과밀하면 `fill_requested_days` 가 다시 나눈다.
    """
    out = [list(d) for d in days if d]
    while len(out) > 1:
        single = next((k for k, d in enumerate(out) if len(d) == 1), None)
        if single is None:
            break
        lone = out.pop(single)[0]
        nearest = min(range(len(out)), key=lambda k: min(_km(lone, q) for q in out[k]))
        out[nearest].append(lone)
    return out


def fill_requested_days(
    days: list[list[Place]], target: int, per_day: int
) -> list[list[Place]]:
    """고른 일수를 최대한 채우되 억지로 나누지 않는다(2026-11-27 사용자 결정, 계획 「정한 것」 5).

    날이 `target` 보다 적으면, 하루 곳 수(`per_day`)를 넘는 날 중 가장 큰 날을 둘로 나눈다. 나눈 두 날은 각각 2곳 이상이어야
    하므로 4곳 미만인 날은 나누지 않는다. 하루 곳 수 안의 날은 그대로 둔다. `target` 을 넘지 않는다.
    """
    out = [list(d) for d in days if d]
    while len(out) < target:
        crowded = [k for k, d in enumerate(out) if len(d) > per_day and len(d) >= 4]
        if not crowded:
            break
        k = max(crowded, key=lambda c: len(out[c]))
        first, second = split_two(out[k])
        out[k : k + 1] = [first, second]
    return out


def plan_saved_with_model(
    book: PlaceSource,
    req: PlanRequest,
    client: Any | None,
    *,
    budget: Callable[[], float] | None = None,
    cfg: dict[str, Any] | None = None,
) -> Plan:
    """담은 촬영지로 짠다. 쓸 수 있는 곳이 없으면 `PlanError`. 모델이 못 하면 좌표로 묶어 나눈다."""
    cfg = cfg or load_config()
    if not req.place_ids:
        raise PlanError("담은 촬영지가 없다")
    places, dropped = _gather(book, list(req.place_ids)[:SAVED_MAX])
    if not places:
        raise PlanError("담은 촬영지 중 쓸 수 있는 곳(좌표 있는 곳)이 없다")

    chosen: list[list[Place]] | None = None
    llm = False
    if client is not None and cfg.get("enabled"):
        try:
            prompt = build_saved_prompt(req.days, req.pace, places, cfg)
            reply = client.chat(
                [{"role": "user", "content": prompt}], budget=budget, json_mode=True
            )
            by_id = {p.place_id: p for p in places}
            chosen = parse_saved_days(str(reply.get("content") or ""), by_id, req.days)
            llm = True
        except (PlanError, SceneApiError):
            raise
        except Exception as exc:  # noqa: BLE001 — 모델 경로가 깨져도 일정은 나가야 한다
            print(f"[plan] 담은 곳 모델 일정 실패 → 계산: {exc!r}", file=sys.stderr)
            chosen = None
    if chosen is None:
        chosen = split_by_zone(places, req.days)
    filled = fill_requested_days(
        merge_single_days(place_missing(chosen, places)),
        req.days,
        _per_day(req.pace, cfg),
    )
    return _plan(req, [reorder_day(d) for d in filled], dropped, len(places), llm=llm)


def _plan(
    req: PlanRequest,
    chosen: list[list[Place]],
    dropped: list[tuple[Place, str]],
    considered: int,
    *,
    llm: bool,
) -> Plan:
    """날짜별 장소를 앱이 그릴 일정으로. 순서는 받은 그대로, 시각은 엔진의 어림. 빠진 곳은 첫날에 적는다."""
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
        plan_days.append(
            DayPlan(
                day=i,
                legs=_lay_out(items, None, planner_cfg),
                dropped=list(dropped) if i == 1 else [],
            )
        )
    how = (
        "AI 가 담은 촬영지를 위치를 보고 날짜별로 나눈 일정이다."
        if llm
        else "담은 촬영지를 가까운 곳끼리 묶어 나눈 일정이다."
    )
    return Plan(
        request=PlanRequest(
            titles=titles,
            days=req.days,
            pace=req.pace,
            must=list(req.must),
            avoid=list(req.avoid),
            place_ids=list(req.place_ids),
        ),
        days=plan_days,
        considered=considered,
        signals={"llm": llm, "saved": True},
        notes=[
            how,
            "담은 곳은 하루 곳 수보다 많아도 모두 넣었다.",
            "시각은 직선거리로 어림한 값이다. 영업시간·휴무일은 반영하지 않았다.",
        ],
    )
