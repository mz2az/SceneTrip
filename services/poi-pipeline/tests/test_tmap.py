import json
import urllib.error

from poi_pipeline import grid, tmap


def response(total, pois=()):
    return json.dumps(
        {"searchPoiInfo": {"totalCount": total, "pois": {"poi": list(pois)}}}
    ).encode()


def poi(i, name="가게", lower="모텔\\/여관"):
    return {
        "id": str(i), "name": f"{name}{i}", "noorLat": "37.5", "noorLon": "127.0",
        "frontLat": "37.51", "frontLon": "127.01", "lowerBizName": lower,
        "middleBizName": "숙박", "upperBizName": "숙박", "upperAddrName": "서울",
        "middleAddrName": "중구", "lowerAddrName": "명동", "roadName": "명동길",
    }  # fmt: skip


def test_norm_strips_tmap_double_escape():
    assert tmap.norm("모텔\\/여관") == "모텔/여관"


def test_row_prefers_building_coordinate_and_keeps_front():
    r = tmap.row(poi(1), "모텔", "서울")
    assert (r["lat"], r["lng"]) == ("37.5", "127.0")
    assert (r["front_lat"], r["front_lng"]) == ("37.51", "127.01")
    assert r["addr"] == "서울 중구 명동"
    assert r["kind"] == "모텔/여관"
    assert set(tmap.FIELDS) >= set(r)


def test_sweep_pages_filters_and_records_leaf(tmp_path):
    calls = []

    def fetch(url, key):
        calls.append(url)
        if "count=1&" in url or url.endswith("count=1"):
            return response(2)
        if "page=1&" in url:
            return response(2, [poi(1), poi(2, lower="호텔")])
        return response(2, [])

    runner = tmap.Runner("k", sleep_seconds=0, fetch=fetch, sleep=lambda s: None)
    ledger = grid.Ledger(tmp_path / "cov.json")
    rows, seen = [], set()
    cell = (37.4, 126.8, 37.8, 127.2)
    tmap.sweep(runner, "모텔", {"모텔/여관"}, cell, "서울", seen, rows, ledger)
    assert [r["id"] for r in rows] == ["1"]  # 호텔은 keep 밖
    assert ledger.get("모텔", cell)["kind"] == "leaf"
    assert runner.calls == 2  # 세어 보기 1 + 페이지 1 (150 미만이라 멈춤)

    tmap.sweep(runner, "모텔", {"모텔/여관"}, cell, "서울", seen, rows, ledger)
    assert runner.skipped == 1 and runner.calls == 2  # 이어받기 — 안 부른다


def test_sweep_splits_when_over_cap(tmp_path):
    def fetch(url, key):
        return (
            response(grid.CAP + 1)
            if "count=1" in url.split("&page=")[1]
            else response(0)
        )

    runner = tmap.Runner("k", sleep_seconds=0, fetch=fetch, sleep=lambda s: None)
    ledger = grid.Ledger(tmp_path / "cov.json")
    cell = (37.4, 126.8, 37.8, 127.2)
    tmap.sweep(
        runner,
        "음식점",
        None,
        cell,
        "서울",
        set(),
        [],
        ledger,
        depth=grid.MAX_DEPTH - 1,
    )
    assert ledger.get("음식점", cell)["kind"] == "split"
    assert all(ledger.get("음식점", sub) is not None for sub in grid.split4(cell))


def test_runner_retries_429_then_gives_up():
    attempts = []

    def fetch(url, key):
        attempts.append(1)
        raise urllib.error.HTTPError(url, 429, "slow", {}, None)

    runner = tmap.Runner("k", sleep_seconds=0, fetch=fetch, sleep=lambda s: None)
    assert runner.go("호텔", 37.5, 127.0, 3) == ([], -1)
    assert len(attempts) == 3 and runner.errors


def test_runner_honours_quota():
    runner = tmap.Runner(
        "k", quota=0, fetch=lambda u, k: response(1), sleep=lambda s: None
    )
    assert runner.go("호텔", 37.5, 127.0, 3) == ([], -1)
    assert runner.exhausted
