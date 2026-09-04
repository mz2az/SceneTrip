"""JSONL 읽고 쓰기 — 한 줄에 하나. 통째로 읽으면 37.8만 건에서 1.2 GB 를 먹는다."""

from __future__ import annotations

import json
from collections.abc import Iterable, Iterator
from pathlib import Path


def read_rows(path: Path) -> Iterator[dict]:
    if not path.exists():
        return
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue  # 깨진 한 줄이 전체를 막지 않는다


def write_rows(
    path: Path, rows: Iterable[dict], fields: list[str] | None = None
) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            record = {key: row.get(key, "") for key in fields} if fields else row
            handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")
            count += 1
    return count
