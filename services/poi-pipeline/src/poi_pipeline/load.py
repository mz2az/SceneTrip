"""`poi` 표 적재 — 살아 있는 행을 모아 `tools/scripts/seed-poi.sh` 에 넘긴다.

적재 SQL(`services/scene-api/seed/poi.sql`)이 `source_id` 로 ON CONFLICT 하므로 멱등이다.
파이프라인은 DB 드라이버를 들이지 않는다 — 붙는 길(직접 psql · kind 파드)은 그 스크립트가
이미 둘 다 알고 있고(ADR 0005), 여기서 다시 구현하면 두 벌이 된다.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

from poi_pipeline import store

SEED_SCRIPT = Path("tools/scripts/seed-poi.sh")


def select_alive(raw: Path, alive_ids: set[str] | None, out: Path) -> int:
    """원본에서 살아 있는 id 만 골라 적재 입력을 만든다. `alive_ids` 가 None 이면 전부."""
    rows = store.read_rows(raw)
    if alive_ids is not None:
        rows = (row for row in rows if str(row.get("id", "")) in alive_ids)
    return store.write_rows(out, rows)


def run_seed(files: list[Path], repo_root: Path, runner=subprocess.run) -> int:
    script = repo_root / SEED_SCRIPT
    if not script.exists():
        raise FileNotFoundError(f"적재 스크립트가 없다: {script}")
    result = runner([str(script), *map(str, files)], cwd=repo_root, check=False)
    return int(result.returncode)
