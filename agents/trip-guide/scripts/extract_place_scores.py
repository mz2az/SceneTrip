"""수집 원본 CSV 의 `notes` 칸에서 성지점수를 뽑아 `config/place_scores.json` 을 만든다.

**왜 이 파일이 따로 있나.** 성지점수는 김태환 수집 CSV 의 `notes` 에
「성지점수 39.1 (페이지 64·도메인 50·…)」 형식으로만 들어 있고, 시드 적재
(`services/scene-api/seed/candidates.sql`)는 `notes` 를 버린다. 그래서 DB 와 API 에는
성지별 인기도가 없다 — `place.popularity_score` 는 작품 점수를 물려받은 값이라 한 작품의
성지가 전부 같다.

CSV 에 정식 컬럼이 생기고 scene-api 가 그 값을 응답에 실으면 이 파일과 JSON 은
지운다. 그때까지 AI 일정 생성(`src/llm_planner.py`)이 이 JSON 을 읽는다.

**점수는 작품 안에서만 비교한다.** 원본에 「표본 크기로 나누지 않는 식이라 작품을
가로질러 정렬하면 안 된다」 고 적혀 있다. 그래서 키를 (작품, 장소 이름) 쌍으로 둔다 —
청라호수공원은 도깨비 10.3, 더 글로리 16.4 다.

사용법:
    python3 scripts/extract_place_scores.py <수집 CSV 경로> [출력 경로]
"""

from __future__ import annotations

import csv
import json
import re
import sys
from pathlib import Path

_SCORE = re.compile(r"성지점수\s*([0-9]+(?:\.[0-9]+)?)")
_OUT = Path(__file__).absolute().parent.parent / "config" / "place_scores.json"


def extract(csv_path: Path) -> dict[str, dict[str, dict[str, float]]]:
    """{작품: {장소 이름: {score, lat, lng}}}. 점수를 못 찾은 행은 넣지 않는다.

    **좌표를 함께 남긴다.** 같은 장소가 작품마다 다른 이름으로 수집된 경우가 있다
    (「고창 학원농장」 · 「고창 학원농장 메밀밭」). DB 는 장소를 하나로 합치면서 이름을
    하나만 남기므로, 이름으로 못 찾으면 좌표로 찾는다(src/llm_planner.PlaceScores).
    """
    scores: dict[str, dict[str, dict[str, float]]] = {}
    with csv_path.open(encoding="utf-8-sig", newline="") as fh:
        for row in csv.DictReader(fh):
            title = (row.get("title") or "").strip()
            name = (row.get("place_name") or "").strip()
            match = _SCORE.search(row.get("notes") or "")
            if not title or not name or not match:
                continue
            entry: dict[str, float] = {"score": float(match.group(1))}
            try:
                entry["lat"] = round(float(row.get("place_latitude") or ""), 6)
                entry["lng"] = round(float(row.get("place_longitude") or ""), 6)
            except ValueError:
                pass
            scores.setdefault(title, {})[name] = entry
    return scores


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    src = Path(argv[1])
    out = Path(argv[2]) if len(argv) > 2 else _OUT
    scores = extract(src)
    total = sum(len(v) for v in scores.values())
    payload = {
        "_설명": (
            "수집 CSV notes 의 성지점수. scripts/extract_place_scores.py 가 만든다 — "
            "손으로 고치지 않는다. 작품 안에서만 비교한다. scene-api 가 성지 인기도를 "
            "응답에 실으면 지운다."
        ),
        "source": src.name,
        "scores": {t: dict(sorted(v.items())) for t, v in sorted(scores.items())},
    }
    out.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"{out}: 작품 {len(scores)} 편, 성지 {total} 곳")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
