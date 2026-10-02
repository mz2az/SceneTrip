"""AI 일정 생성의 실측 평가 — **실제 모델을 부른다.** 비용이 들고 어떤 게이트에도 없다.

`src/llm_planner.make_llm_plan` 을 운영 시드 CSV 위에서 사례마다 한 번씩 돌리고, 같은
요청을 결정적 엔진(`planner.make_plan`)에도 넣어 나란히 잰다. 지표는 엔진과 코드를
나누지 않는다 — 엔진이 스스로를 재면 구조상 100% 가 나온다(연구 제안서 §2).

| 지표 | 뜻 |
| --- | --- |
| 응답 | 모델이 쓸 수 있는 일정을 줬는가 (아니면 운영에서는 엔진으로 돌아간다) |
| 퍼진 날 | 하루 안 두 성지 사이가 50km 를 넘는 날 수 |
| 하루 최대 | 하루에 넣은 성지 수의 최댓값 (널널 3 · 빡빡 7 을 넘으면 「넘침」) |
| 1위 포함 | 고른 작품마다 성지점수 1 위 성지가 들어갔는가 |
| 꼭 갈 곳 | 요청한 곳이 다 들어갔는가 |
| 이동 | 서울 출발부터 날짜·순서대로 이은 직선거리 합 |

사용법 (DEEPSEEK_API_KEY 필요):
    python3 -m evals.llm_plan_live [시드 CSV 경로]
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

from src.deepseek import DeepSeekClient
from src.llm_planner import SEOUL, LlmPlanError, PlaceScores, make_llm_plan
from src.places import CsvPlaceBook, haversine_m
from src.planner import PlanRequest, make_plan

_SEED = (
    Path(__file__).absolute().parents[3]
    / "services"
    / "scene-api"
    / "seed"
    / "candidates.csv"
)

# (작품들, 일수, 컨셉, 꼭 갈 곳). 앱 마법사는 relaxed·packed 두 값만 보낸다.
CASES: list[tuple[list[str], int, str, list[str]]] = [
    (["도깨비"], 1, "relaxed", []),
    (["도깨비"], 2, "packed", []),
    (["도깨비"], 3, "relaxed", []),
    (["도깨비"], 3, "packed", []),
    (["도깨비"], 2, "packed", ["강릉 주문진 방사제"]),
    (["도깨비"], 4, "packed", []),
    (["선재 업고 튀어"], 2, "relaxed", []),
    (["선재 업고 튀어"], 3, "packed", []),
    (["폭싹 속았수다"], 1, "packed", []),
    (["폭싹 속았수다"], 3, "relaxed", []),
    (["이상한 변호사 우영우"], 2, "packed", []),
    (["더 글로리"], 2, "relaxed", []),
    (["케이팝 데몬 헌터스"], 1, "packed", []),
    (["케이팝 데몬 헌터스"], 2, "relaxed", []),
    (["응답하라 1988"], 1, "relaxed", []),
    (["사랑의 불시착"], 2, "packed", []),
    (["반짝이는 워터멜론"], 2, "relaxed", []),
    (["사이코지만 괜찮아"], 2, "packed", []),
    (["도깨비", "케이팝 데몬 헌터스"], 3, "packed", []),
    (["도깨비", "선재 업고 튀어"], 3, "relaxed", []),
    (["이상한 변호사 우영우", "더 글로리"], 2, "packed", []),
]
CAP = {"relaxed": 3, "normal": 5, "packed": 7}


def measure(days, req, book, scores) -> dict:
    """날짜별 성지 목록을 엔진과 무관한 기준으로 잰다."""
    km = lambda a, b: haversine_m(a.lat, a.lng, b.lat, b.lng) / 1000.0
    spread = sum(1 for d in days if d and max(km(a, b) for a in d for b in d) > 50)
    names = {p.name for d in days for p in d}
    top_in = True
    for title in req.titles:
        matched, cands = book.by_title(title, 40)
        cands = [p for p in cands if p.has_coords()]
        if cands:
            top = max(cands, key=lambda p: scores.get(matched or title, p))
            top_in = top_in and top.name in names
    dist, here = 0.0, None
    for d in days:
        for p in d:
            dist += (
                haversine_m(SEOUL[0], SEOUL[1], p.lat, p.lng) / 1000.0
                if here is None
                else km(here, p)
            )
            here = p
    return {
        "per_day": [len(d) for d in days],
        "spread_days": spread,
        "over_cap": any(len(d) > CAP[req.pace] for d in days),
        "top1": top_in,
        "must": all(m in names for m in req.must),
        "km": round(dist),
        "days": [[p.name for p in d] for d in days],
    }


def main(argv: list[str]) -> int:
    if not os.environ.get("DEEPSEEK_API_KEY"):
        print(
            "DEEPSEEK_API_KEY 가 없다 — 실측 평가는 실제 모델을 부른다", file=sys.stderr
        )
        return 2
    book = CsvPlaceBook.load(Path(argv[1]) if len(argv) > 1 else _SEED)
    scores = PlaceScores.load()
    client = DeepSeekClient()
    rows = []
    for titles, days, pace, must in CASES:
        req = PlanRequest(titles=titles, days=days, pace=pace, must=must)
        name = f"{'+'.join(titles)} {days}일 {pace}" + (f" 꼭:{must}" if must else "")
        engine = make_plan(
            book,
            PlanRequest(titles=titles, days=days, pace=pace, start=SEOUL, must=must),
        )
        e = measure([[l.place for l in d.legs] for d in engine.days], req, book, scores)
        started = time.monotonic()
        try:
            plan = make_llm_plan(book, req, client, scores=scores)
            m = measure(
                [[l.place for l in d.legs] for d in plan.days], req, book, scores
            )
            m["ok"] = True
        except LlmPlanError as exc:
            m = {"ok": False, "error": str(exc)}
        m["seconds"] = round(time.monotonic() - started, 2)
        rows.append({"case": name, "llm": m, "engine": e})
        print(
            f"{name:34s} | AI {'응답' if m['ok'] else '실패'} {m.get('per_day')} 퍼짐{m.get('spread_days')} "
            f"1위{'O' if m.get('top1') else 'X'} {m.get('km')}km {m['seconds']}s "
            f"| 엔진 {e['per_day']} 퍼짐{e['spread_days']} 1위{'O' if e['top1'] else 'X'} {e['km']}km",
            flush=True,
        )

    def summary(key: str) -> str:
        ms = [r[key] for r in rows if r[key].get("ok", True)]
        n = len(rows)
        return (
            f"응답 {len(ms)}/{n} · 퍼진 날 {sum(m['spread_days'] for m in ms)} · "
            f"컨셉 넘침 {sum(m['over_cap'] for m in ms)} · 1위 포함 {sum(m['top1'] for m in ms)} · "
            f"꼭 갈 곳 {sum(m['must'] for m in ms)} · 평균 이동 {sum(m['km'] for m in ms) / max(len(ms), 1):.0f}km"
        )

    print("\nAI  :", summary("llm"))
    print("엔진:", summary("engine"))
    secs = sorted(r["llm"]["seconds"] for r in rows)
    print(f"AI 응답 시간 중앙 {secs[len(secs) // 2]}s · 최대 {secs[-1]}s")
    out = Path(os.environ.get("LLM_PLAN_LIVE_OUT", "/tmp/llm_plan_live.json"))
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"자세한 결과: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
