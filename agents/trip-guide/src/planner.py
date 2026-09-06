"""코스 추천 엔진 — 일정을 실제로 계산하는 층.

**이 파일에는 LLM 이 없다.** 그것이 이 파일의 존재 이유다.

순수 LLM 에게 일정을 맡기면 실현 가능한 일정이 나오는 비율이 약 4% 다(MIT 통제
실험, 복잡한 일정에서 GPT-4 는 0.6%). LLM 과 알고리즘을 결합하면 97% 로 오른다
(Hao et al. 2024). Google 은 Gemini 로 초안을 내고 2 단계 최적화기로 실현
가능하게 다듬어 90% 대를 얻는다. 근거와 인용은
`docs/design/ai-course-planner.md` §1 에 있다.

그래서 우리 구조는 **LLM 샌드위치**다 —

    ① 이해 [LLM]  →  ② 계획 [이 파일]  →  ③ 설명 [LLM]

가운데는 결정적이다. 같은 입력에 같은 일정이 나오고, 모델 없이 테스트가 돌고,
모델을 갈아 끼워도 일정은 변하지 않는다.

계산 순서는 넷이다.

    1. 후보 모으기와 점수   작품 기준으로 모은다 (MZ2AZ-200)
    2. 날짜 배분            지리 클러스터링 (k-means, 결정적 초기화)
    3. 하루 안 순서         최근접 이웃 → 2-opt
    4. 시간표·잘라내기·채우기  「아낀 시간 ÷ 잃는 점수」 로 빼고, 「점수 ÷ 늘어난
                              시간」 으로 채운다 (오리엔티어링의 이익 대비 비용)

그 뒤 날짜끼리 장소를 맞바꿔 보는 지역 탐색이 한 번 더 돈다(Google 2 단계 대응).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .places import Place, PlaceSource, haversine_m, norm

_CONFIG = Path(__file__).resolve().parent.parent / "config" / "planner.json"


def load_config(path: Path | None = None) -> dict[str, Any]:
    """계수를 읽어 온다. `_` 로 시작하는 설명 키는 버린다."""
    src = path or _CONFIG
    if not src.is_file():
        raise FileNotFoundError(f"코스 엔진 설정 파일이 없다: {src}")
    return _strip(json.loads(src.read_text(encoding="utf-8")))


def _strip(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: _strip(v) for k, v in value.items() if not k.startswith("_")}
    return value


class PlanError(ValueError):
    """일정을 만들 수 없을 때. 빈 일정을 돌려주지 않고 이유를 들고 올라간다."""


# ── 주고받는 모양 ─────────────────────────────────────────────────────────────


@dataclass
class PlanRequest:
    """무엇을 어떻게 돌 것인가. ① 이해 단계의 산출물이다."""

    titles: list[str]
    days: int = 1
    pace: str = "normal"
    start: tuple[float, float] | None = None
    start_label: str = ""
    must: list[str] = field(default_factory=list)
    avoid: list[str] = field(default_factory=list)


@dataclass
class Scored:
    """후보 하나와 그 점수. 어떤 신호가 얼마나 기여했는지 함께 들고 다닌다."""

    place: Place
    score: float
    rank: float
    mentions: float
    tier: float
    coverage: float
    titles: list[str]

    @property
    def key(self) -> str:
        return self.place.place_id or norm(self.place.name)


@dataclass
class Leg:
    """정지점 하나. 시간은 전부 분 단위 정수다."""

    order: int
    place: Place
    arrive: int  # 그날 0 시 기준 분
    dwell: int
    travel_to_next: int  # 다음 정지점까지 분. 마지막은 0
    meters_to_next: int
    meal_after: bool  # 이 정지점 뒤에 식사 시간을 넣었는가
    titles: list[str] = field(default_factory=list)
    """**사용자가 요청한 작품 중** 이 장소가 걸리는 것들.

    `place.titles` 를 그대로 쓰면 안 된다. 한 장소가 열 작품에 나오는 일이 흔해서,
    「이태원 클라쓰」 를 물었는데 목록 앞머리에 「아파트·베테랑」 이 오고 정작 물어본
    작품은 안 보인다(2026-09-02 실측). 사용자가 알고 싶은 것은 「내가 말한 그 작품의
    어디인가」 다.
    """


@dataclass
class DayPlan:
    day: int
    legs: list[Leg]
    dropped: list[tuple[Place, str]] = field(default_factory=list)

    @property
    def total_meters(self) -> int:
        return sum(l.meters_to_next for l in self.legs)

    @property
    def end_minute(self) -> int:
        if not self.legs:
            return 0
        last = self.legs[-1]
        return last.arrive + last.dwell


@dataclass
class Plan:
    request: PlanRequest
    days: list[DayPlan]
    considered: int
    signals: dict[str, bool]
    notes: list[str] = field(default_factory=list)


# ── 1. 후보 모으기와 점수 ─────────────────────────────────────────────────────


def collect_candidates(
    book: PlaceSource, titles: list[str], cfg: dict[str, Any]
) -> tuple[list[Scored], list[str], list[str]]:
    """작품 이름들로 후보를 모아 점수를 매긴다.

    **작품을 기준으로 던지는 이유**(MZ2AZ-200) — 같은 장소가 여러 작품에 나오는데
    우리 데이터는 「이 장소가 어느 작품 때문에 담겼는지」를 장소 쪽에 저장하지 않아
    장소만으로는 작품을 되짚을 수 없다.

    (후보, 찾은 작품, 못 찾은 작품) 을 돌려준다.
    """
    per_title = int(cfg["optimize"]["candidates_per_title"])
    weights = cfg["scoring"]

    pool: dict[str, Scored] = {}
    found: list[str] = []
    missing: list[str] = []

    for title in titles:
        matched, places = book.by_title(title, per_title)
        if not places:
            missing.append(title)
            continue
        found.append(matched or title)
        total = len(places)
        for idx, place in enumerate(places):
            # 창구가 준 순서가 곧 그 작품 안의 대표성이다. scene-api 는 인기순으로
            # 주고, CSV 는 rank_in_title 순으로 준다 — 양쪽 다 앞이 대표적이다.
            rank = 1.0 - (idx / total) if total > 1 else 1.0
            key = place.place_id or norm(place.name)
            if key in pool:
                prev = pool[key]
                prev.rank = max(prev.rank, rank)
                if (matched or title) not in prev.titles:
                    prev.titles.append(matched or title)
            else:
                pool[key] = Scored(
                    place=place,
                    score=0.0,
                    rank=rank,
                    mentions=0.0,
                    tier=PlaceSource._quality(place) / 4.0,
                    coverage=0.0,
                    titles=[matched or title],
                )

    if not pool:
        return [], found, missing

    scored = list(pool.values())
    top_mentions = max((s.place.mentions for s in scored), default=0)
    wanted = max(len(titles), 1)
    for s in scored:
        s.mentions = (s.place.mentions / top_mentions) if top_mentions else 0.0
        s.coverage = len(s.titles) / wanted
        s.score = (
            weights["rank"] * s.rank
            + weights["mentions"] * s.mentions
            + weights["tier"] * s.tier
            + weights["coverage"] * s.coverage
        )

    # 점수 내림차순, 동점이면 이름순 — 정렬이 흔들리면 결정성이 깨진다.
    scored.sort(key=lambda s: (-s.score, s.place.name))
    return scored, found, missing


# ── 1.5. 지역으로 먼저 거르기 ─────────────────────────────────────────────────


def region_pool(
    scored: list[Scored],
    need: int,
    cfg: dict[str, Any],
    near: tuple[float, float] | None = None,
) -> list[Scored]:
    """후보를 **한 여행지 안으로** 좁힌다. 날짜 배분보다 먼저 도는 단계다.

    이 단계가 없으면 이런 일이 난다(2026-09-02 실측, 저장소 기준 데이터 155곳) —
    「도깨비 + 이태원 클라쓰 1박 2일」 이 **2일차에 제주도 카페 한 곳**을 넣었다.
    점수만 보고 상위 10곳을 뽑았더니 제주 한 곳이 끼었고, k-means 는 그것을 성실히
    별도의 날로 갈라 주었다. 알고리즘은 맞게 돌았는데 결과가 여행이 아니었다.

    그래서 **촬영지가 가장 많이 몰린 덩어리**부터 담는다. 많이 몰린 곳이 그 작품의
    중심지이고, 여행자가 가는 곳도 거기다. 모자라면 가장 가까운 옆 덩어리를 붙인다 —
    그때도 「가까운 옆」이라 전국구가 되지 않는다.

    같은 문제를 승길님이 iOS 쪽에서 먼저 풀었고(`RoutePlanner.regionPool`), 반경
    25km 도 그쪽 실측값을 그대로 쓴다. 두 구현이 다른 값을 쓰면 앱과 챗봇이 서로
    다른 코스를 내놓는다.

    `near` 가 한국 안이면 **거기서 가장 가까운 덩어리**를 먼저 잡는다. 부산에 있는
    사람에게 서울 코스를 내밀 이유가 없다.
    """
    located = [s for s in scored if s.place.has_coords()]
    if len(located) <= need:
        return located

    radius_m = float(cfg["region"]["cluster_radius_km"]) * 1000.0
    groups = _region_clusters(located, radius_m)
    if not groups:
        return located

    if near is not None and _in_korea(near):
        start = min(
            range(len(groups)),
            key=lambda i: haversine_m(near[0], near[1], *_centroid(groups[i])),
        )
    else:
        start = 0  # _region_clusters 가 큰 것부터 돌려준다

    pool = groups.pop(start)
    while len(pool) < need and groups:
        here = _centroid(pool)
        nearest = min(
            range(len(groups)),
            key=lambda i: (haversine_m(here[0], here[1], *_centroid(groups[i])), i),
        )
        pool += groups.pop(nearest)
    return pool


def _region_clusters(items: list[Scored], radius_m: float) -> list[list[Scored]]:
    """반경 안을 한 덩어리로 묶는다. 큰 덩어리부터 돌려준다.

    **이웃이 가장 많은 곳을 씨앗으로 삼는다.** 아무 곳에서나 시작하면 변두리 한 곳이
    씨앗이 되어 덩어리가 잘게 갈린다. 동점이면 이름순 — 결정성을 지켜야 한다.
    """

    def neighbours(s: Scored, pool: list[Scored]) -> int:
        return sum(
            1
            for o in pool
            if haversine_m(s.place.lat, s.place.lng, o.place.lat, o.place.lng)
            <= radius_m
        )

    left = list(items)
    out: list[list[Scored]] = []
    while left:
        seed = max(left, key=lambda s: (neighbours(s, left), s.place.name))
        group = [
            s
            for s in left
            if haversine_m(seed.place.lat, seed.place.lng, s.place.lat, s.place.lng)
            <= radius_m
        ]
        out.append(group)
        taken = {id(s) for s in group}
        left = [s for s in left if id(s) not in taken]
    out.sort(key=lambda g: (-len(g), g[0].place.name))
    return out


def _in_korea(point: tuple[float, float]) -> bool:
    """한국 안인가. 네모로 판단한다 — 국경선을 정확히 그릴 이유가 없다.

    밖이면 「가장 가까운 덩어리」 가 뜻이 없다. 이 앱은 외국인이 오기 **전에도**
    코스를 짜는데, 도쿄에서 재나 뉴욕에서 재나 한국 어딘가가 가장 가까울 뿐이고
    그것이 그 사람이 가고 싶은 곳은 아니다.
    """
    lat, lng = point
    return 33.0 <= lat <= 38.7 and 124.5 <= lng <= 132.0


# ── 2. 날짜 배분 ──────────────────────────────────────────────────────────────


def _centroid(items: list[Scored]) -> tuple[float, float]:
    return (
        sum(s.place.lat for s in items) / len(items),
        sum(s.place.lng for s in items) / len(items),
    )


def cluster_by_day(scored: list[Scored], days: int) -> list[list[Scored]]:
    """지리 기준으로 날짜에 나눈다. k-means, k = 일수.

    초기 중심은 **가장 멀리 떨어진 점부터** 고른다(Gonzalez 의 최대최소 방식).
    확률적 k-means++ 를 쓰지 않는 이유는 하나다 — 난수가 들어가면 같은 입력에
    다른 일정이 나오고, 그러면 평가를 돌릴 수 없다.
    """
    located = [s for s in scored if s.place.has_coords()]
    if not located:
        return [[] for _ in range(days)]
    if days <= 1:
        return [located]
    if len(located) <= days:
        return [[s] for s in located] + [[] for _ in range(days - len(located))]

    # 초기 중심: 첫 점은 가장 북쪽(결정적), 그다음부터는 이미 고른 중심들에서
    # 가장 먼 점을 차례로 고른다.
    seeds = [max(located, key=lambda s: (s.place.lat, s.place.name))]
    while len(seeds) < days:
        far = max(
            located,
            key=lambda s: (
                min(
                    haversine_m(s.place.lat, s.place.lng, c.place.lat, c.place.lng)
                    for c in seeds
                ),
                s.place.name,
            ),
        )
        if far in seeds:
            break
        seeds.append(far)

    centers = [(s.place.lat, s.place.lng) for s in seeds]
    groups: list[list[Scored]] = [[] for _ in range(days)]

    for _ in range(30):
        groups = [[] for _ in range(days)]
        for s in located:
            best = min(
                range(len(centers)),
                key=lambda i: haversine_m(
                    s.place.lat, s.place.lng, centers[i][0], centers[i][1]
                ),
            )
            groups[best].append(s)
        moved = False
        for i, g in enumerate(groups):
            if not g:
                continue
            c = _centroid(g)
            if abs(c[0] - centers[i][0]) > 1e-9 or abs(c[1] - centers[i][1]) > 1e-9:
                centers[i] = c
                moved = True
        if not moved:
            break

    # 빈 날이 생기면 가장 붐비는 날에서 점수가 가장 낮은 하나를 떼어 준다.
    for i, g in enumerate(groups):
        if g:
            continue
        donor = max(range(days), key=lambda j: len(groups[j]))
        if len(groups[donor]) > 1:
            g.append(groups[donor].pop())

    # 날짜 순서를 북→남으로 고정한다. 같은 배분이 같은 일차 번호를 갖게 하려는 것.
    groups.sort(key=lambda g: -_centroid(g)[0] if g else 0.0)
    return groups


# ── 3. 하루 안 순서 ───────────────────────────────────────────────────────────


def _meters(a: Place, b: Place) -> float:
    return haversine_m(a.lat, a.lng, b.lat, b.lng)


def order_stops(
    items: list[Scored], start: tuple[float, float] | None, cfg: dict[str, Any]
) -> list[Scored]:
    """최근접 이웃으로 사슬을 만들고 2-opt 로 다듬는다.

    2-opt 는 교차하는 동선을 없앤다 — 사람이 지도에서 바로 알아보는 못난 경로다.
    하루 3~7 곳이라 비교 횟수가 수십 번이고, 개선이 없으면 그전에 멈춘다.
    """
    if len(items) <= 2:
        return list(items)

    remaining = list(items)
    if start is not None:
        current = min(
            remaining,
            key=lambda s: haversine_m(start[0], start[1], s.place.lat, s.place.lng),
        )
    else:
        current = max(remaining, key=lambda s: (s.place.lat, s.place.name))
    route = [current]
    remaining.remove(current)
    while remaining:
        nxt = min(
            remaining, key=lambda s: (_meters(current.place, s.place), s.place.name)
        )
        route.append(nxt)
        remaining.remove(nxt)
        current = nxt

    rounds = int(cfg["optimize"]["two_opt_rounds"])
    for _ in range(rounds):
        improved = False
        for i in range(len(route) - 1):
            for j in range(i + 2, len(route)):
                a, b = route[i], route[i + 1]
                c = route[j]
                d = route[j + 1] if j + 1 < len(route) else None
                before = _meters(a.place, b.place) + (
                    _meters(c.place, d.place) if d else 0.0
                )
                after = _meters(a.place, c.place) + (
                    _meters(b.place, d.place) if d else 0.0
                )
                if after < before - 1e-6:
                    route[i + 1 : j + 1] = reversed(route[i + 1 : j + 1])
                    improved = True
        if not improved:
            break
    return route


def route_meters(items: list[Scored]) -> float:
    return sum(
        _meters(items[i].place, items[i + 1].place) for i in range(len(items) - 1)
    )


# ── 4. 시간표 ─────────────────────────────────────────────────────────────────


def travel_minutes(a: Place, b: Place, cfg: dict[str, Any]) -> tuple[int, int]:
    """두 장소 사이 (분, 미터). 직선거리에 우회 계수를 곱한 어림이다."""
    t = cfg["travel"]
    straight = _meters(a, b)
    meters = straight * float(t["detour_factor"])
    if meters >= float(t["transit_threshold_m"]):
        minutes = meters / 1000.0 / float(t["transit_kmh"]) * 60.0 + float(
            t["transit_overhead_minutes"]
        )
    else:
        minutes = meters / 1000.0 / float(t["walk_kmh"]) * 60.0
    return round(minutes), round(meters)


def dwell_minutes(place: Place, cfg: dict[str, Any]) -> int:
    table = cfg["dwell_minutes"]
    return int(table.get(place.kind.strip(), table["기본"]))


def build_day(
    day: int,
    ordered: list[Scored],
    start: tuple[float, float] | None,
    cfg: dict[str, Any],
    pace_key: str = "normal",
) -> DayPlan:
    """순서가 정해진 하루를 시간표로 만든다. 예산을 넘으면 잘라낸다.

    **자르는 기준이 두 가지로 다르다.**

    - 정지점 개수가 넘칠 때 → 점수가 가장 낮은 곳. 시간은 아직 문제가 아니다.
    - 하루 시간이 넘칠 때 → **빼면 시간이 가장 많이 절약되는 곳을 점수로 나눈 값**이
      가장 큰 곳. 오리엔티어링에서 쓰는 「이익 대비 비용」 규칙이다.

    두 번째가 중요하다. 점수만 보고 자르면 이런 일이 난다(2026-09-02 실측) —
    「도깨비 당일치기」 에서 서울중앙고(1위)와 인천 한미서점(2위)을 남기고 2.5km
    옆의 개뿔(3위)을 뺐다. 서울↔인천 43km 를 왕복하느라 시간이 없어서였다. 점수는
    셋 다 비슷한데 **한미서점 하나가 예산을 다 먹는다** — 그 사실이 「점수가 낮은
    것부터」 규칙에는 보이지 않는다.
    """
    pace = cfg["pace"][pace_key]
    budget = int(pace["daily_minutes"])
    max_stops = int(pace["max_stops"])

    working = list(ordered)
    dropped: list[tuple[Place, str]] = []

    while len(working) > max_stops:
        worst = min(working, key=lambda s: (s.score, s.place.name))
        working.remove(worst)
        dropped.append((worst.place, f"하루 정지점 상한 {max_stops} 곳"))

    while True:
        used = _minutes_used(working, start, cfg)
        if used is None or used <= budget or len(working) == 1:
            break

        # 하나씩 빼 보고 「아낀 시간 ÷ 잃는 점수」 가 가장 큰 것을 뺀다.
        # 꼭 넣어 달라고 한 곳은 점수에 +10 이 붙어 있어 여기서 잘 살아남는다.
        best: Scored | None = None
        best_ratio = -1.0
        for s in working:
            rest = [x for x in working if x is not s]
            after = _minutes_used(rest, start, cfg)
            if after is None:
                continue
            saved = used - after
            ratio = saved / max(s.score, 0.01)
            if ratio > best_ratio or (
                ratio == best_ratio and best and s.score < best.score
            ):
                best, best_ratio = s, ratio
        if best is None:
            break

        working.remove(best)
        dropped.append((best.place, f"하루 {budget} 분을 넘어선다"))

    # 뺀 뒤에는 순서를 다시 잡는다 — 남은 곳들의 최적 동선이 달라져 있다.
    return DayPlan(
        day=day,
        legs=_lay_out(order_stops(working, start, cfg), start, cfg),
        dropped=dropped,
    )


def fill_day(
    day: DayPlan,
    chosen: list[Scored],
    spare: list[Scored],
    start: tuple[float, float] | None,
    cfg: dict[str, Any],
    pace_key: str,
) -> tuple[DayPlan, list[Scored]]:
    """남는 시간에 후보를 더 넣는다. (채운 하루, 쓴 후보) 를 돌려준다.

    자르기만 하고 채우지 않으면 하루가 텅 빈다 — 「도깨비 당일치기」 가 360 분
    예산에 2 곳 2 시간만 쓰고 끝났다(2026-09-02 실측). 먼 곳 하나를 빼고 나면
    그 자리를 다음 후보로 메워야 한다.

    고르는 기준은 **점수 ÷ 늘어나는 시간**이다. 가까우면서 점수가 높은 곳이 먼저
    들어온다 — 자를 때 쓴 기준을 뒤집은 것이라 두 방향이 서로 싸우지 않는다.
    """
    pace = cfg["pace"][pace_key]
    budget = int(pace["daily_minutes"])
    max_stops = int(pace["max_stops"])

    picked = list(chosen)
    left = list(spare)

    while len(picked) < max_stops and left:
        base = _minutes_used(picked, start, cfg) or 0
        best: Scored | None = None
        best_ratio = -1.0
        for s in left:
            after = _minutes_used([*picked, s], start, cfg)
            if after is None or after > budget:
                continue
            added = max(after - base, 1)
            ratio = s.score / added
            if ratio > best_ratio or (
                ratio == best_ratio and best and s.place.name < best.place.name
            ):
                best, best_ratio = s, ratio
        if best is None:
            break
        picked.append(best)
        left.remove(best)

    ordered = order_stops(picked, start, cfg)
    return (
        DayPlan(day=day.day, legs=_lay_out(ordered, start, cfg), dropped=day.dropped),
        picked,
    )


def _minutes_used(
    items: list[Scored], start: tuple[float, float] | None, cfg: dict[str, Any]
) -> int | None:
    """이 정지점들을 도는 데 걸리는 분. 비어 있으면 None.

    **순서를 다시 잡고 잰다.** 정지점 하나를 빼면 남은 것들의 최적 순서가 달라지는데,
    옛 순서 그대로 재면 「빼도 시간이 안 줄어든다」 는 잘못된 결론이 나온다.
    """
    if not items:
        return None
    ordered = order_stops(items, start, cfg)
    legs = _lay_out(ordered, start, cfg)
    if not legs:
        return None
    return legs[-1].arrive + legs[-1].dwell - int(cfg["day"]["start_hour"]) * 60


def _lay_out(
    items: list[Scored], start: tuple[float, float] | None, cfg: dict[str, Any]
) -> list[Leg]:
    if not items:
        return []
    day_cfg = cfg["day"]
    clock = int(day_cfg["start_hour"]) * 60
    meals = sorted(int(h) * 60 for h in day_cfg["meal_hours"])
    meal_len = int(day_cfg["meal_minutes"])
    eaten: set[int] = set()

    legs: list[Leg] = []
    for i, s in enumerate(items):
        stay = dwell_minutes(s.place, cfg)
        arrive = clock
        clock = arrive + stay

        to_next, meters = 0, 0
        if i + 1 < len(items):
            to_next, meters = travel_minutes(s.place, items[i + 1].place, cfg)

        # 식사 — 그 시각을 지나는 자리에 한 번만 넣는다. 식당은 고르지 않는다.
        meal_after = False
        for m in meals:
            if m in eaten:
                continue
            if arrive <= m <= clock + to_next:
                eaten.add(m)
                clock += meal_len
                meal_after = True
                break

        clock += to_next
        legs.append(
            Leg(
                order=i + 1,
                place=s.place,
                arrive=arrive,
                dwell=stay,
                travel_to_next=to_next,
                meters_to_next=meters,
                meal_after=meal_after,
                titles=list(s.titles),
            )
        )
    return legs


# ── 날짜 간 교환 (Google 2 단계 대응) ─────────────────────────────────────────


def swap_between_days(
    groups: list[list[Scored]], cfg: dict[str, Any]
) -> list[list[Scored]]:
    """서로 다른 두 날의 장소를 맞바꿔 보고 총 이동거리가 줄면 채택한다.

    클러스터링만 하면 경계에 걸친 장소가 엉뚱한 날에 붙는다. 이 한 단계가
    「나눠만 놓은 일정」과 「말이 되는 일정」을 가른다.
    """
    rounds = int(cfg["optimize"]["max_swap_rounds"])
    best = [list(g) for g in groups]

    for _ in range(rounds):
        improved = False
        base = sum(route_meters(order_stops(g, None, cfg)) for g in best)
        for i in range(len(best)):
            for j in range(i + 1, len(best)):
                for a in list(best[i]):
                    for b in list(best[j]):
                        trial_i = [x for x in best[i] if x is not a] + [b]
                        trial_j = [x for x in best[j] if x is not b] + [a]
                        after = base
                        after -= route_meters(order_stops(best[i], None, cfg))
                        after -= route_meters(order_stops(best[j], None, cfg))
                        after += route_meters(order_stops(trial_i, None, cfg))
                        after += route_meters(order_stops(trial_j, None, cfg))
                        if after < base - 1.0:
                            best[i], best[j] = trial_i, trial_j
                            base = after
                            improved = True
        if not improved:
            break
    return best


# ── 엮기 ──────────────────────────────────────────────────────────────────────


def make_plan(
    book: PlaceSource, req: PlanRequest, cfg: dict[str, Any] | None = None
) -> Plan:
    """요청 하나를 일정으로 바꾼다. 이 함수 어디에도 LLM 이 없다."""
    cfg = dict(cfg or load_config())
    limits = cfg["limits"]

    if not req.titles:
        raise PlanError("어느 작품으로 돌지 받지 못했다")
    if req.days < 1 or req.days > int(limits["max_days"]):
        raise PlanError(
            f"일수는 1~{limits['max_days']} 사이여야 한다 (받은 값 {req.days})"
        )
    if len(req.titles) > int(limits["max_titles"]):
        raise PlanError(
            f"작품은 최대 {limits['max_titles']} 편까지다 (받은 값 {len(req.titles)})"
        )
    if req.pace not in cfg["pace"]:
        raise PlanError(
            f"모르는 여행 속도다: {req.pace} ({', '.join(cfg['pace'])} 중 하나)"
        )

    scored, found, missing = collect_candidates(book, req.titles, cfg)
    if not scored:
        raise PlanError(f"「{', '.join(req.titles)}」 로 찾은 촬영지가 없다")

    avoid = {norm(a) for a in req.avoid}
    if avoid:
        scored = [s for s in scored if norm(s.place.name) not in avoid]
    if not scored:
        raise PlanError("뺄 곳을 빼고 나니 남는 촬영지가 없다")

    # 꼭 넣어 달라고 한 곳은 점수를 올려 잘려 나가지 않게 한다.
    # **올린 뒤 다시 정렬해야 한다.** collect_candidates 가 정렬해 준 순서는 가산점을
    # 모르고, 아래에서 상위 N 개만 잘라 쓰기 때문에 재정렬을 빠뜨리면 「꼭 넣어 달라」
    # 고 한 곳이 후보 단계에서 통째로 탈락한다(2026-09-02 시험이 잡았다).
    must = {norm(m) for m in req.must}
    if must:
        for s in scored:
            if norm(s.place.name) in must:
                s.score += 10.0
        scored.sort(key=lambda s: (-s.score, s.place.name))

    located = [s for s in scored if s.place.has_coords()]
    if not located:
        raise PlanError("좌표가 있는 촬영지가 하나도 없어 동선을 만들 수 없다")

    pace = cfg["pace"][req.pace]
    keep = min(len(located), req.days * int(pace["max_stops"]))

    # **날짜에 나누기 전에 지역으로 먼저 좁힌다.** 이 줄이 없으면 1박 2일 일정의
    # 2일차가 제주도 한 곳이 된다 — region_pool 의 주석에 그 실측이 있다.
    located = region_pool(located, keep, cfg, req.start)

    # **작품마다 최소 한 곳은 확보한 뒤** 나머지를 점수순으로 채운다.
    #
    # 그냥 점수순 상위 N 개를 자르면 촬영지가 적거나 순위가 낮은 작품이 통째로
    # 사라진다 — 살아 있는 데이터로 돌린 평가에서 「도깨비 + 이태원 클라쓰」 이틀
    # 일정에 이태원 클라쓰가 한 곳도 안 나왔다(2026-09-02, evals 의 작품 커버리지
    # 지표가 잡았다). 두 작품을 말한 사람에게 한 작품만 보여 주는 것은 요청을
    # 절반만 들어준 것이다.
    pool: list[Scored] = []
    taken: set[str] = set()
    for title in found:
        if len(pool) >= keep:
            break
        best = next(
            (s for s in located if title in s.titles and s.key not in taken), None
        )
        if best is not None:
            pool.append(best)
            taken.add(best.key)
    for s in located:
        if len(pool) >= keep:
            break
        if s.key not in taken:
            pool.append(s)
            taken.add(s.key)

    groups = cluster_by_day(pool, req.days)
    groups = swap_between_days(groups, cfg)

    # 후보에 뽑히지 못한 나머지. 하루에 시간이 남으면 여기서 메운다.
    spare = [s for s in located if s.key not in taken]

    days: list[DayPlan] = []
    cursor = req.start
    for i, group in enumerate(groups, start=1):
        ordered = order_stops(group, cursor, cfg)
        day = build_day(i, ordered, cursor, cfg, req.pace)

        # 자르기만 하고 채우지 않으면 하루가 텅 빈다. 뺀 자리를 다음 후보로 메운다.
        kept = [s for s in ordered if any(leg.place is s.place for leg in day.legs)]
        day, used = fill_day(day, kept, spare, cursor, cfg, req.pace)
        spare = [s for s in spare if s not in used]

        days.append(day)
        if day.legs:
            last = day.legs[-1].place
            cursor = (last.lat, last.lng)

    notes = [
        "거리와 시간은 직선거리에 우회 계수를 곱한 **추정**이다. 실제 도보·대중교통 시간은 다르다.",
        "영업시간·휴무일 데이터가 없어 반영하지 못했다.",
    ]
    if missing:
        notes.append(f"촬영지 데이터가 없어 뺀 작품: {', '.join(missing)}")

    return Plan(
        request=req,
        days=days,
        considered=len(scored),
        signals={
            "순서": True,
            "언급량": any(s.place.mentions for s in scored),
            "등급": any(s.place.tier for s in scored),
        },
        notes=notes,
    )


# ── 모델에게 건네는 모양 ──────────────────────────────────────────────────────


# ── 대화로 고치기 (MZ2AZ-201) ─────────────────────────────────────────────────
#
# 티켓이 요구한 것은 "대화로 코스를 고칠 수 있게" 다. 고치는 방법은 둘 중 하나다 —
# 통째로 다시 짜거나(regenerate), 있는 것을 부분만 고치거나(patch).
#
# **부분 수정을 고른다.** 통째로 다시 짜면 사용자가 손대지 않은 1·3일차까지 조용히
# 바뀐다. "2일차에서 개뿔만 빼 줘" 라고 말한 사람에게 3일차가 달라진 일정을 주는 것은
# 요청을 넘어선 것이고, 무엇이 왜 바뀌었는지 설명할 수도 없다.


@dataclass
class Revision:
    """수정 한 번의 결과. 새 일정과 「무슨 일이 벌어졌는가」 를 함께 들고 온다."""

    plan: Plan
    day: int
    added: list[str]
    removed: list[str]
    minutes_used: int
    budget: int
    stops: int
    max_stops: int
    already: list[str] = field(default_factory=list)
    """넣으라고 했지만 이미 그 일차에 있던 곳. 실패가 아니라 이미 원하는 상태다.

    이것을 오류로 다루면 **같은 호출에 섞여 온 빼기까지 통째로 날아간다.** 실제
    주행에서 모델이 `remove=[A], add=[이미 있는 B]` 를 보냈고, B 때문에 전체가
    거절되어 A 가 빠지지 않았다(2026-09-03). 사용자는 「빼 줘」 라고 했는데 아무
    일도 일어나지 않았다.
    """

    drop_candidates: list[tuple[str, int]] = field(default_factory=list)
    """예산을 넘었을 때 「뺄 만한 곳과 그러면 아끼는 분」. 넘지 않았으면 비어 있다.

    **위반 사실만 알리는 것과 대안을 함께 주는 것은 결과가 다르다.** 고칠 대안
    목록을 함께 준 쪽이 수정 성공률이 크게 높았다는 통제 실험이 있다
    (Structured Feedback Improves Repair, arXiv 2607.14167 — 위치·관측값만 주면
    개선이 거의 없고, 「허용 가능한 대안값」을 더하면 28%→72%). 조사 정리는
    docs/design/chatbot-and-planner-survey.md §3-1.
    """

    moved_from: int | None = None
    """일차 간 이동이었다면 출발 일차. 아니면 None."""

    @property
    def over_budget(self) -> bool:
        return self.minutes_used > self.budget

    @property
    def over_stops(self) -> bool:
        return self.stops > self.max_stops


def revise_day(
    plan: Plan,
    day_no: int,
    *,
    add: list[Place] | None = None,
    remove: list[str] | None = None,
    cfg: dict[str, Any] | None = None,
) -> Revision:
    """이미 만든 일정의 한 일차에서 장소를 넣거나 뺀다. **여기에도 LLM 이 없다.**

    빼고 넣은 뒤 그 일차의 순서를 다시 잡고(2-opt) 시간표를 다시 계산한다. 순서를
    다시 잡는 이유는 `_minutes_used` 의 주석과 같다 — 한 곳을 빼면 남은 곳들의 최적
    동선이 달라진다.

    **원본 일정을 고치지 않는다.** 새 Plan 을 만들어 돌려준다. 그래서 예산을 넘었을
    때 부르는 쪽이 버릴 수 있다.

    **예산을 넘어도 여기서 자르지 않는다.** 넘었다는 사실만 Revision 에 담아 올린다.
    사용자가 직접 넣으라고 한 곳을 코드가 말없이 도로 빼면, 화면에 보이는 일정과
    사용자가 기억하는 일정이 갈린다. 자를지 물어볼지는 도구 층이 정한다.
    """
    cfg = dict(cfg or load_config())
    add = list(add or [])
    remove = list(remove or [])

    if not add and not remove:
        raise PlanError("무엇을 넣고 뺄지 받지 못했다")

    target = next((d for d in plan.days if d.day == day_no), None)
    if target is None:
        raise PlanError(f"{day_no}일차가 없다 (이 일정은 {len(plan.days)}일짜리다)")

    # 지금 그 일차에 있는 것들. Leg 는 점수를 들고 있지 않으므로 다시 감싼다 —
    # 순서 잡기와 시간표 계산은 점수를 보지 않는다(order_stops·_lay_out).
    working: list[Scored] = [
        Scored(
            place=leg.place,
            score=0.0,
            rank=0.0,
            mentions=0.0,
            tier=0.0,
            coverage=0.0,
            titles=list(leg.titles),
        )
        for leg in target.legs
    ]

    removed: list[str] = []
    for name in remove:
        key = norm(name)
        hit = next((s for s in working if norm(s.place.name) == key), None)
        if hit is None:
            raise PlanError(f"「{name}」 은 {day_no}일차에 없다")
        working.remove(hit)
        removed.append(hit.place.name)

    added: list[str] = []
    already: list[str] = []
    for place in add:
        if not place.has_coords():
            raise PlanError(f"{place.name} 에는 좌표가 없어 동선에 넣을 수 없다")
        key = norm(place.name)
        if any(norm(s.place.name) == key for s in working):
            # 이미 원하는 상태다. 거절하지 않고 건너뛴 뒤 그렇게 말해 준다.
            already.append(place.name)
            continue
        elsewhere = next(
            (
                d.day
                for d in plan.days
                if d.day != day_no
                and any(norm(leg.place.name) == key for leg in d.legs)
            ),
            None,
        )
        if elsewhere is not None:
            # 같은 곳을 두 번 가는 일정은 만들지 않는다. 먼저 빼라고 말해 준다.
            raise PlanError(
                f"{place.name} 은 이미 {elsewhere}일차에 있다 "
                f"(옮기려면 {elsewhere}일차에서 먼저 빼라)"
            )
        working.append(
            Scored(
                place=place,
                score=0.0,
                rank=0.0,
                mentions=0.0,
                tier=0.0,
                coverage=0.0,
                titles=[
                    t
                    for t in plan.request.titles
                    if any(norm(t) == norm(pt) for pt in place.titles)
                ],
            )
        )
        added.append(place.name)

    if not added and not removed and already:
        raise PlanError(
            f"{', '.join(already)} 은 이미 {day_no}일차에 있다 — 바뀐 것이 없다"
        )

    start = plan.request.start
    ordered = order_stops(working, start, cfg)
    legs = _lay_out(ordered, start, cfg)

    used = _minutes_used(working, start, cfg) or 0
    pace = cfg["pace"][plan.request.pace]

    # 사용자가 방금 넣은 곳은 「planner 가 뺀 곳」 목록에서 지운다. 넣어 놓고 뺐다고
    # 적어 두면 다음 턴에 모델이 그 문장을 그대로 읽는다.
    added_keys = {norm(n) for n in added}
    kept_dropped = [
        (p, why) for p, why in target.dropped if norm(p.name) not in added_keys
    ]

    revised = DayPlan(day=day_no, legs=legs, dropped=kept_dropped)
    days = [revised if d.day == day_no else d for d in plan.days]

    # 넘쳤을 때만 「무엇을 빼면 얼마가 빠지는지」 를 재 둔다. 사실만 알리고 끝내면
    # 모델도 사용자도 다음 수를 못 고른다.
    budget = int(pace["daily_minutes"])
    candidates: list[tuple[str, int]] = []
    if used > budget or len(legs) > int(pace["max_stops"]):
        for cand in working:
            rest = [x for x in working if x is not cand]
            after = _minutes_used(rest, start, cfg)
            if after is None:
                continue
            candidates.append((cand.place.name, used - after))
        candidates.sort(key=lambda x: (-x[1], x[0]))
        candidates = candidates[:3]

    return Revision(
        plan=Plan(
            request=plan.request,
            days=days,
            considered=plan.considered,
            signals=plan.signals,
            notes=plan.notes,
        ),
        day=day_no,
        added=added,
        removed=removed,
        minutes_used=used,
        budget=budget,
        stops=len(legs),
        max_stops=int(pace["max_stops"]),
        drop_candidates=candidates,
        already=already,
    )


def move_stop(
    plan: Plan,
    name: str,
    to_day: int,
    cfg: dict[str, Any] | None = None,
) -> Revision:
    """정지점 하나를 다른 일차로 옮긴다. **한 번의 호출로 끝난다.**

    「빼고 나서 넣어라」 로 두 번 시키지 않는 이유는 둘이다 —

    - 중간 상태가 사용자에게 보인다. 첫 호출이 끝난 순간 그 장소는 어느 일차에도
      없는 일정이 되고, 모델이 거기서 멈추면 사용자는 「옮겨 달랬더니 지워졌다」 를
      보게 된다.
    - 도구는 좁고 의도가 분명해야 한다. 「옮기기」 는 「빼기+넣기」 와 다른 의도이고,
      의도를 도구 이름으로 드러내면 모델이 덜 헤맨다.
      (조사: docs/design/chatbot-and-planner-survey.md §3-1)

    안은 `revise_day` 두 번이다. 첫 번째가 원본을 건드리지 않으므로 중간 결과를
    그대로 두 번째에 넘길 수 있고, 어느 쪽이든 실패하면 원본이 그대로 남는다.
    """
    cfg = dict(cfg or load_config())
    key = norm(name)

    src = next(
        (
            d.day
            for d in plan.days
            if any(norm(leg.place.name) == key for leg in d.legs)
        ),
        None,
    )
    if src is None:
        raise PlanError(f"「{name}」 은 이 일정에 없다")
    if src == to_day:
        raise PlanError(f"{name} 은 이미 {to_day}일차에 있다")
    if not any(d.day == to_day for d in plan.days):
        raise PlanError(f"{to_day}일차가 없다 (이 일정은 {len(plan.days)}일짜리다)")

    place = next(
        leg.place
        for d in plan.days
        if d.day == src
        for leg in d.legs
        if norm(leg.place.name) == key
    )

    pulled = revise_day(plan, src, remove=[place.name], cfg=cfg)
    landed = revise_day(pulled.plan, to_day, add=[place], cfg=cfg)

    return Revision(
        plan=landed.plan,
        day=to_day,
        added=[place.name],
        removed=[],
        minutes_used=landed.minutes_used,
        budget=landed.budget,
        stops=landed.stops,
        max_stops=landed.max_stops,
        drop_candidates=landed.drop_candidates,
        moved_from=src,
    )


def _clock(minute: int) -> str:
    return f"{minute // 60:02d}:{minute % 60:02d}"


def plan_to_dict(plan: Plan) -> dict[str, Any]:
    """일정을 모델이 읽을 사전으로 바꾼다.

    **좌표와 점수는 넣지 않는다.** 좌표를 주면 모델이 거리를 다시 계산하려 들고
    그 계산은 틀린다. 점수를 주면 나중에 그 숫자를 조금씩 바꿔 말한다 (tools.py
    머리말의 규칙 그대로). 시각과 거리는 이미 코드가 풀어 놓은 값만 준다.
    """
    days: list[dict[str, Any]] = []
    for d in plan.days:
        stops: list[dict[str, Any]] = []
        for leg in d.legs:
            item: dict[str, Any] = {
                "순서": leg.order,
                "이름": leg.place.name,
                "도착": _clock(leg.arrive),
                "머무는 시간": f"{leg.dwell}분",
            }
            if leg.place.address:
                item["주소"] = leg.place.address
            item["작품"] = leg.titles or leg.place.titles[:3]
            others = [t for t in leg.place.titles if t not in leg.titles][:2]
            if others:
                item["같이 나온 작품"] = others
            scene = next((s.description for s in leg.place.scenes if s.description), "")
            if scene:
                item["장면"] = scene
            if leg.meal_after:
                item["뒤에"] = "식사 시간 (식당은 고르지 않았다 — 맛집 데이터가 없다)"
            if leg.travel_to_next:
                item["다음까지"] = f"약 {leg.meters_to_next}m · {leg.travel_to_next}분"
            stops.append(item)

        entry: dict[str, Any] = {
            "일차": d.day,
            "정지점": len(d.legs),
            "총 이동": f"약 {d.total_meters}m",
            "마치는 시각": _clock(d.end_minute) if d.legs else "-",
            "동선": stops,
        }
        if d.dropped:
            entry["뺀 곳"] = [f"{p.name} ({why})" for p, why in d.dropped]
        days.append(entry)

    used = [k for k, on in plan.signals.items() if on]
    return {
        "일정": days,
        "근거": {
            "본 후보": plan.considered,
            "쓴 신호": used,
            "여행 속도": plan.request.pace,
        },
        "주의": plan.notes,
    }
