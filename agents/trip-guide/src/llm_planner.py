"""AI 일정 생성 — 모델이 성지를 보고 날짜별 일정을 직접 짠다.

`planner.py` 의 결정적 엔진과 **나란히 있는 두 번째 길**이다. 앱의 「AI 로 일정 짜기」
마법사(`POST /plan`)가 이쪽을 먼저 부르고, 모델이 답을 못 주면 엔진으로 돌아간다.

**왜 모델에게 맡기나.** 결정적 엔진은 장소별 인기도를 몰랐고(DB 에 작품 점수만 있다),
25km 반경으로 먼저 거르는 탓에 작품의 대표 성지(도깨비의 주문진)를 버렸다. 운영 시드
24 사례 실측(2026-10-02)에서 엔진은 작품 1위 성지를 13 건만 넣었다. 성지 인기도와 위치를
주고 모델에게 고르게 하면 그 판단을 모델이 한다.

모델에게 주는 것:

| 무엇 | 왜 |
| --- | --- |
| 작품·일수·컨셉 | 사용자가 고른 그대로 |
| 성지별 id·이름·위치·위경도·인기도 | 고를 재료. 위치는 주소 앞 두 마디(「강원특별자치도 강릉시」) |

**규칙 문장을 넣지 않는다**(2026-10-02 결정). 「하루는 한 지역」 같은 제약도, 지역 묶음도
주지 않는다. 성지마다 대략의 위치만 붙이고 일정을 어떻게 나눌지는 모델이 정한다.

주지 않는 것: 출발 시각·이동 시간·체류 시간. 일정은 날짜별 장소 목록이고, 화면에 그릴
시각은 엔진의 시간표 함수가 어림으로 채운다(`travelBasis: straight-line` 그대로).

**컨셉은 숫자로 주지 않는다.** 「하루 6~7곳」 처럼 주면 모델이 그 수를 채우려고 다른
지역 성지를 끌어왔다(같은 실측). 성격만 말한다 — `config/llm_planner.json`.

**출발지는 서울로 고정한다.** 마법사가 보내는 현위치는 이 길에서 쓰지 않는다.

**모델의 판단은 고치지 않는다.** 응답이 깨졌거나 없는 id 를 넣었을 때만
`LlmPlanError` 를 던지고, 부르는 쪽이 엔진으로 돌아간다.
"""

from __future__ import annotations

import json
import re
from collections.abc import Callable
from pathlib import Path
from string import Template
from typing import Any

from .deepseek import ModelError
from .places import Place, PlaceSource, haversine_m, norm
from .planner import (
    DayPlan,
    Plan,
    PlanRequest,
    Scored,
    _lay_out,
)
from .planner import (
    load_config as load_planner_config,
)

_ROOT = Path(__file__).absolute().parent.parent
_CONFIG = _ROOT / "config" / "llm_planner.json"
_SCORES = _ROOT / "config" / "place_scores.json"
_PROMPT = _ROOT / "prompts" / "plan_llm.txt"

# 서울시청. 마법사 경로는 출발지를 서울로 고정한다.
SEOUL = (37.5665, 126.9780)


class LlmPlanError(RuntimeError):
    """모델이 쓸 수 있는 일정을 주지 못했다. 부르는 쪽이 엔진으로 돌아간다."""


def load_config(path: Path | None = None) -> dict[str, Any]:
    src = path or _CONFIG
    return {
        k: v
        for k, v in json.loads(src.read_text(encoding="utf-8")).items()
        if not k.startswith("_")
    }


class PlaceScores:
    """(작품, 장소) → 성지점수. `scripts/extract_place_scores.py` 가 만든 표다.

    scene-api 가 성지 인기도를 주지 않아서 쓰는 다리다. 표에 없는 성지는 0 이다 —
    지어내지 않는다.

    **이름으로 먼저, 안 되면 좌표로 찾는다.** 같은 장소가 작품마다 다른 이름으로 수집된
    경우가 있고(「고창 학원농장」 · 「고창 학원농장 메밀밭」), DB 는 장소를 하나로 합치면서
    이름을 하나만 남긴다. 그때는 **같은 작품** 안에서 `NEAR_M` 안의 가장 가까운 행을 쓴다.
    """

    NEAR_M = 300.0

    def __init__(self, table: dict[str, dict[str, Any]]) -> None:
        self._table: dict[str, dict[str, tuple[float, float | None, float | None]]] = {}
        for title, places in table.items():
            rows = {}
            for name, value in places.items():
                if isinstance(value, dict):
                    rows[norm(name)] = (
                        float(value.get("score", 0.0)),
                        value.get("lat"),
                        value.get("lng"),
                    )
                else:
                    rows[norm(name)] = (float(value), None, None)
            self._table[norm(title)] = rows

    @classmethod
    def load(cls, path: Path | None = None) -> PlaceScores:
        src = path or _SCORES
        if not src.is_file():
            return cls({})
        return cls(json.loads(src.read_text(encoding="utf-8")).get("scores", {}))

    def get(self, title: str, place: Place) -> float:
        rows = self._table.get(norm(title), {})
        hit = rows.get(norm(place.name))
        if hit is not None:
            return hit[0]
        if not place.has_coords():
            return 0.0
        best: tuple[float, float] | None = None
        for score, lat, lng in rows.values():
            if lat is None or lng is None:
                continue
            d = haversine_m(place.lat, place.lng, lat, lng)
            if d <= self.NEAR_M and (best is None or d < best[0]):
                best = (d, score)
        return best[1] if best else 0.0


def popularity(place: Place, title: str, scores: PlaceScores) -> float:
    """작품 안 성지 인기도. **scene-api 가 준 값이 먼저**, 없으면 점수 표.

    scene-api 가 `contents[].popularity`(장소×작품)를 실으면 `Scene.popularity` 로 들어온다
    (src/sceneapi.py). 그 값이 0 보다 크면 그것을 쓴다 — DB 가 정본이다. 아직 안 실리는
    동안에는 `config/place_scores.json` 을 쓴다.
    """
    want = norm(title)
    for scene in place.scenes:
        if norm(scene.title) == want and scene.popularity > 0:
            return float(scene.popularity)
    return scores.get(title, place)


# ── 위치 ──────────────────────────────────────────────────────────────────────


# 시·도 이름을 짧은 꼴 하나로 맞춘다. 수집 CSV 의 주소가 행마다 「서울」·「서울특별시」,
# 「강원」·「강원특별자치도」 로 섞여 있어(2026-10-02 실측) 같은 곳이 다른 곳처럼 보인다.
_PROVINCE = {
    "서울특별시": "서울",
    "인천광역시": "인천",
    "부산광역시": "부산",
    "대구광역시": "대구",
    "대전광역시": "대전",
    "광주광역시": "광주",
    "울산광역시": "울산",
    "세종특별자치시": "세종",
    "경기도": "경기",
    "강원도": "강원",
    "강원특별자치도": "강원",
    "충청북도": "충북",
    "충청남도": "충남",
    "전라북도": "전북",
    "전북특별자치도": "전북",
    "전라남도": "전남",
    "경상북도": "경북",
    "경상남도": "경남",
    "제주도": "제주",
    "제주특별자치도": "제주",
    "전남광주통합특별시": "전남광주",
}


def location(address: str) -> str:
    """주소의 앞 두 마디 — 「강원 강릉시」 · 「서울 종로구」.

    모델에게 「대략 어디인가」 를 사람이 읽는 말로 준다. 묶거나 나누지 않는다.
    """
    parts = (address or "").split()[:2]
    if parts:
        parts[0] = _PROVINCE.get(parts[0], parts[0])
    return " ".join(parts)


# ── 프롬프트 ──────────────────────────────────────────────────────────────────


def collect(
    book: PlaceSource, titles: list[str], limit: int
) -> tuple[list[str], list[Place], dict[str, list[str]]]:
    """작품마다 성지를 모은다. (찾은 작품명, 성지, 성지 id → 요청 작품들)."""
    found: list[str] = []
    places: list[Place] = []
    works: dict[str, list[str]] = {}
    for title in titles:
        matched, items = book.by_title(title, limit)
        if not matched:
            continue
        found.append(matched)
        for p in items:
            if not p.has_coords() or not p.place_id:
                continue
            if p.place_id not in works:
                places.append(p)
                works[p.place_id] = []
            if matched not in works[p.place_id]:
                works[p.place_id].append(matched)
    return found, places, works


def build_prompt(
    titles: list[str],
    days: int,
    pace: str,
    must: list[str],
    places: list[Place],
    works: dict[str, list[str]],
    scores: PlaceScores,
    cfg: dict[str, Any],
) -> str:
    def score(p: Place) -> float:
        return max(
            (popularity(p, t, scores) for t in works.get(p.place_id, [])), default=0.0
        )

    lines: list[str] = []
    for p in sorted(places, key=lambda p: (-score(p), p.name)):
        row: dict[str, Any] = {"id": p.place_id, "name": p.name}
        if location(p.address):
            row["location"] = location(p.address)
        row["lat"] = round(p.lat, 5)
        row["lng"] = round(p.lng, 5)
        row["popularity"] = score(p)
        if len(titles) > 1:
            row["work"] = ",".join(works.get(p.place_id, []))
        lines.append(json.dumps(row, ensure_ascii=False))

    pace_text = cfg["pace"].get(pace) or cfg["pace"][cfg["pace_fallback"]]
    must_text = f"\n- 꼭 갈 곳: {', '.join(must)}" if must else ""
    return Template(_PROMPT.read_text(encoding="utf-8")).substitute(
        titles=", ".join(titles),
        days=days,
        pace=pace_text,
        must=must_text,
        places="\n".join(lines),
    )


# ── 응답 ──────────────────────────────────────────────────────────────────────


def parse_days(text: str, by_id: dict[str, Place], days: int) -> list[list[Place]]:
    """모델 응답을 날짜별 성지로 바꾼다. 쓸 수 없으면 `LlmPlanError`.

    **없는 id 가 하나라도 있으면 통째로 버린다.** 그 장소만 빼고 쓰면 모델이 지어낸
    것을 조용히 덮는 셈이고, 그런 응답은 나머지도 믿기 어렵다.
    """
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
        day: list[Place] = []
        for raw in ids:
            pid = str(raw)
            if pid not in by_id:
                raise LlmPlanError(f"후보에 없는 성지 id: {pid}")
            if pid in seen:
                continue  # 같은 곳을 두 날에 넣었으면 앞 날에만 둔다
            seen.add(pid)
            day.append(by_id[pid])
        out.append(day)
    if not any(out):
        raise LlmPlanError("모델이 성지를 하나도 고르지 않았다")
    return out


def _strip_fence(text: str) -> str:
    t = (text or "").strip()
    if t.startswith("```"):
        t = re.sub(r"^```[a-zA-Z]*\s*|\s*```$", "", t)
    return t


# ── 진입점 ────────────────────────────────────────────────────────────────────


def make_llm_plan(
    book: PlaceSource,
    req: PlanRequest,
    client: Any,
    *,
    scores: PlaceScores | None = None,
    cfg: dict[str, Any] | None = None,
    budget: Callable[[], float] | None = None,
) -> Plan:
    """모델에게 일정을 짜게 한다. 못 하면 `LlmPlanError`."""
    cfg = cfg or load_config()
    scores = scores or PlaceScores.load()
    planner_cfg = load_planner_config()

    found, places, works = collect(book, req.titles, int(cfg["candidates_per_title"]))
    avoid = {norm(a) for a in req.avoid}
    places = [p for p in places if norm(p.name) not in avoid]
    if not places:
        raise LlmPlanError(f"「{', '.join(req.titles)}」 로 찾은 성지가 없다")

    prompt = build_prompt(
        found, req.days, req.pace, req.must, places, works, scores, cfg
    )
    try:
        reply = client.chat(
            [{"role": "user", "content": prompt}],
            budget=budget,
            json_mode=True,
        )
    except ModelError as exc:
        raise LlmPlanError(f"모델을 부르지 못했다: {exc}") from exc

    by_id = {p.place_id: p for p in places}
    chosen = parse_days(str(reply.get("content") or ""), by_id, req.days)
    while len(chosen) < req.days:
        chosen.append([])

    def score(p: Place) -> float:
        return max(
            (popularity(p, t, scores) for t in works.get(p.place_id, [])), default=0.0
        )

    plan_days: list[DayPlan] = []
    for i, day in enumerate(chosen, start=1):
        items = [
            Scored(p, score(p), 0.0, 0.0, 0.0, 0.0, list(works.get(p.place_id, [])))
            for p in day
        ]
        # 시각은 화면용 어림이다. 순서는 모델이 준 그대로 둔다.
        plan_days.append(DayPlan(day=i, legs=_lay_out(items, None, planner_cfg)))

    picked = {p.place_id for d in chosen for p in d}
    left_out = [p for p in places if p.place_id not in picked]
    notes = [
        "AI 가 성지 인기도와 위치를 보고 고른 일정이다. 서울에서 출발한다고 보고 짰다.",
        "시각은 직선거리로 어림한 값이다. 영업시간·휴무일은 반영하지 않았다.",
    ]
    if left_out:
        notes.append(
            f"후보 {len(places)} 곳 중 {len(left_out)} 곳은 이번 일정에 넣지 않았다."
        )
    return Plan(
        request=PlanRequest(
            titles=found,
            days=req.days,
            pace=req.pace,
            start=SEOUL,
            start_label="서울",
            must=list(req.must),
            avoid=list(req.avoid),
        ),
        days=plan_days,
        considered=len(places),
        signals={"llm": True, "popularity": any(score(p) > 0 for p in places)},
        notes=notes,
    )
