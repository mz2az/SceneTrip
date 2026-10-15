"""여행 범위 필터 — 무관한 질문은 모델을 부르기 **전에** 거절한다 (MZ2AZ-398).

명백한 것만 여기서 자른다: 무관한 갈래의 낱말(주가·코드·지시문 빼내기)이 있고 여행 낱말이 하나도 없을 때.
여행 낱말이 섞인 질문(「도깨비 촬영지 알려주고 주가도」)은 모델에게 넘긴다 — 여행 부분을 버리면 안 된다.
애매한 나머지도 모델이 지시문(`prompts/system.txt` 「밖의 일」)으로 판단한다. 낱말과 문구는 `config/scope.json`.
설계와 기능 테스트 목록: docs/design/scope-filter.md
"""

from __future__ import annotations

import json
from functools import cache
from pathlib import Path
from typing import Any

_CONFIG = Path(__file__).absolute().parent.parent / "config" / "scope.json"


@cache
def load_config(path: Path | None = None) -> dict[str, Any]:
    return json.loads((path or _CONFIG).read_text(encoding="utf-8"))


def off_topic(text: str, cfg: dict[str, Any] | None = None) -> str | None:
    """모델 없이 거절할 질문이면 그 갈래 이름(「금융」 등), 아니면 None."""
    cfg = cfg or load_config()
    low = text.lower()
    if any(w.lower() in low for w in cfg["travel"]):
        return None
    for group, words in cfg["off_topic"].items():
        if any(w.lower() in low for w in words):
            return group
    return None


def refusal(language: str, cfg: dict[str, Any] | None = None) -> str:
    """사용자가 쓴 말(ko·en·ja·zh)로 된 거절 문구. 모르는 말이면 영어."""
    cfg = cfg or load_config()
    return cfg["refusal"].get(language, cfg["refusal"]["en"])
