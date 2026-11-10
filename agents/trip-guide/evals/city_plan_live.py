"""도시 AI 일정의 실측 평가 — **실제 모델을 부른다.** 비용이 들고 어떤 게이트에도 없다(MZ2AZ-548).

로컬 scene-api 의 도시 촬영지로 사례마다 세 가지를 나란히 잰다(계획 docs/project/plans/city-ai-course.md §4).

| 방식 | 무엇 |
| --- | --- |
| 계산 | 앱의 지금 규칙 — 인기순 2배를 잡아 가까운 곳 잇기(`city_planner.chain_plan`) |
| 추림 | 이 계획 — 인기순 앞에서 (하루 곳 수 × 일수) × 3, 적어도 15곳만 모델에게 |
| 전부 | 도시 촬영지 전부를 모델에게 — 서울만 |

| 지표 | 뜻 |
| --- | --- |
| 대표 | 도시 인기 상위 5곳 가운데 일정에 든 수 |
| 하루 이동 | 그날 장소를 차례로 이은 직선 거리의 최댓값(km) |
| 작품 | 일정에 나온 서로 다른 작품 수 |
| 곳 | 날마다 넣은 곳 수 |
| 시간 | 요청에서 답까지(초) |

경우마다 `REPEAT` 번(2) 돌린다 — 모델 답이 매번 달라 한 번은 우연일 수 있다.
| 대신 | 모델이 실패해 계산으로 짠 사례 |

사용법 (로컬 scene-api 와 모델 키 필요): `just agent-city-eval-live` — 표를 stdout 에, 자세한 결과를 JSON 으로 남긴다.
"""

from __future__ import annotations

import json
import os
import sys
import time
from itertools import pairwise, product
from pathlib import Path

from src.city_planner import chain_plan, plan_city_with_model
from src.llm_planner import load_config
from src.model_client import ModelClient, key_env
from src.places import Place, haversine_m
from src.planner import PlanRequest
from src.sceneapi import SceneApiPlaceBook

CITIES = ["서울", "부산", "제주"]
DAYS = [1, 2, 3]
PACES = ["relaxed", "packed"]
# 경우마다 몇 번 — 모델 답이 매번 달라 한 번은 우연일 수 있다.
REPEAT = 2
TOP = 5


def _day_km(day: list[Place]) -> float:
    return sum(haversine_m(a.lat, a.lng, b.lat, b.lng) for a, b in pairwise(day)) / 1000


def measure(days: list[list[Place]], top_ids: set[str]) -> dict:
    ids = {p.place_id for d in days for p in d}
    works = {t for d in days for p in d for t in p.titles}
    return {
        "top": len(ids & top_ids),
        "max_day_km": round(max((_day_km(d) for d in days), default=0.0), 1),
        "works": len(works),
        "per_day": [len(d) for d in days],
    }


def _model_days(plan) -> list[list[Place]]:
    return [[leg.place for leg in d.legs] for d in plan.days]


def main(argv: list[str]) -> int:
    if not os.environ.get(key_env()):
        print(f"{key_env()} 가 없다 — 실측 평가는 실제 모델을 부른다", file=sys.stderr)
        return 2
    base = os.environ.get("SCENE_API_BASE_URL", "http://localhost:8081/v1")
    book = SceneApiPlaceBook(base)
    client = ModelClient()
    cfg = load_config()
    everything = json.loads(json.dumps(cfg))
    everything["city"]["candidates_factor"] = 1000  # 「전부」 — 도시 촬영지를 다 넘긴다

    rows: list[dict] = []
    for city in CITIES:
        places = [
            p
            for p in book.by_city(city, int(cfg["city"]["fetch_limit"]))
            if p.has_coords()
        ]
        top_ids = {p.place_id for p in places[:TOP]}
        for days, pace, run in product(DAYS, PACES, range(1, REPEAT + 1)):
            req = PlanRequest(titles=[], days=days, pace=pace, city=city)
            row = {"city": city, "days": days, "pace": pace, "run": run}
            row["계산"] = measure(chain_plan(places, days, pace, cfg), top_ids)
            variants = [("추림", cfg)] + (
                [("전부", everything)] if city == "서울" else []
            )
            for name, use in variants:
                started = time.monotonic()
                plan = plan_city_with_model(book, req, client, cfg=use)
                took = round(time.monotonic() - started, 1)
                result = measure(_model_days(plan), top_ids)
                result.update(
                    seconds=took,
                    fallback=not plan.signals.get("llm"),
                    considered=plan.considered,
                )
                row[name] = result
                time.sleep(1.2)  # scene-api 한도(분당 60)
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)

    out = (
        Path(argv[0])
        if argv
        else Path(os.environ.get("TMPDIR", "/tmp")) / "city_plan_live.json"
    )
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"자세한 결과: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
