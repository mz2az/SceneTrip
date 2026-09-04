import json
from pathlib import Path

from poi_pipeline import grid


def test_cells_cover_box_and_clip_edges():
    boxes = list(grid.cells((0.0, 0.0, 1.0, 0.5), 0.4))
    assert boxes[0] == (0.0, 0.0, 0.4, 0.4)
    assert boxes[-1] == (0.8, 0.4, 1.0, 0.5)  # 가장자리는 상자에서 잘린다
    assert len(boxes) == 3 * 2


def test_cover_stays_under_tmap_limit_for_default_step():
    circle = grid.cover((37.4, 126.8, 37.8, 127.2))  # 0.4도 칸
    assert circle.radius_km <= grid.RADIUS_MAX
    assert not circle.too_big


def test_cover_marks_too_big_cells():
    circle = grid.cover((33.0, 125.0, 38.65, 129.65))  # 전국 통째
    assert circle.too_big
    assert circle.radius_km == grid.RADIUS_MAX


def test_split4_is_a_partition():
    cell = (0.0, 0.0, 1.0, 1.0)
    parts = grid.split4(cell)
    assert len(parts) == 4
    assert {p[0] for p in parts} == {0.0, 0.5}
    assert sum((p[2] - p[0]) * (p[3] - p[1]) for p in parts) == 1.0


def test_ledger_resumes_and_refreshes(tmp_path: Path):
    path = tmp_path / "cov.json"
    clock = [1_000_000]
    ledger = grid.Ledger(path, now=lambda: clock[0])
    cell = (0.0, 0.0, 0.4, 0.4)
    ledger.put("호텔", cell, "leaf", 12)
    ledger.save()
    assert (
        json.loads(path.read_text())["호텔|0.0000,0.0000,0.4000,0.4000"]["count"] == 12
    )

    again = grid.Ledger(path, now=lambda: clock[0])
    assert again.get("호텔", cell)["kind"] == "leaf"
    assert again.get("모텔", cell) is None

    clock[0] += 10 * 86400
    stale = grid.Ledger(path, refresh_days=3, now=lambda: clock[0])
    assert stale.get("호텔", cell) is None  # 3일보다 오래됐으면 다시 받는다
