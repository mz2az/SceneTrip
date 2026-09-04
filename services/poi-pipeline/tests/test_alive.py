import collections

from poi_pipeline import alive


def make_grid(*shops):
    grid = collections.defaultdict(list)
    for lat, lng, name in shops:
        alive.add_to_grid(grid, lat, lng, name, "한식")
    return grid


def test_name_key_strips_markup_and_symbols():
    assert alive.name_key("<b>명동교자</b> [본점]") == "명동교자"
    assert alive.name_key("Cafe-Onion 123") == "cafeonion123"


def test_exact_name_nearby_is_alive():
    grid = make_grid((37.5000, 127.0000, "명동교자"))
    assert alive.find(grid, 37.5001, 127.0001, "명동교자 본점") is not None


def test_short_partial_name_far_away_is_not_matched():
    grid = make_grid((37.5000, 127.0000, "커피"))  # 두 글자 — 아무 데나 붙는 이름
    assert alive.find(grid, 37.5010, 127.0000, "커피나무") is None  # 110 m, 짧다


def test_long_partial_name_within_far_band_is_matched():
    grid = make_grid((37.5000, 127.0000, "스타벅스 더현대서울"))
    hit = alive.find(
        grid, 37.5008, 127.0000, "스타벅스더현대서울점"
    )  # ≈ 90 m, 포함 관계
    assert hit is not None and not hit[3]


def test_beyond_far_band_is_gone():
    grid = make_grid((37.5000, 127.0000, "명동교자"))
    assert alive.find(grid, 37.5030, 127.0000, "명동교자") is None  # ≈ 330 m


def test_alive_rows_keeps_only_hits():
    grid = make_grid((37.5, 127.0, "명동교자"))
    rows = [
        {
            "id": "1",
            "name": "명동교자",
            "lat": "37.5",
            "lng": "127.0",
            "region": "서울",
        },
        {"id": "2", "name": "없는집", "lat": "37.5", "lng": "127.0", "region": "서울"},
        {"id": "3", "name": "좌표없음", "lat": "", "lng": ""},
    ]
    kept = [r["id"] for r in alive.alive_rows(rows, grid)]
    assert kept == ["1"]
