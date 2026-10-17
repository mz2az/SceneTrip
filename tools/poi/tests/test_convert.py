"""`just poi-convert` — 공공데이터 원본 → 적재 파일. 명세(모듈 설명 · poi-load-cleanup.md §3·§5)와 저장소 밖 옛 변환
(`build_from_public.py`·`build_tour.py`·`build_transit.py`, 「줄을 만드는 규칙은 그대로다」)만 보고 쓴 시험. 진짜 원본은 쓰지 않는다.
"""

import csv
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from tools.poi.convert import (
    convert_transit,
    main,
    store_record,
    tour_hidden,
    tour_record,
    transit_records,
)

KST = timezone(timedelta(hours=9))

STORE_HEADER = [
    "상가업소번호",
    "상호명",
    "지점명",
    "상권업종대분류코드",
    "상권업종대분류명",
    "상권업종중분류코드",
    "상권업종중분류명",
    "상권업종소분류코드",
    "상권업종소분류명",
    "표준산업분류코드",
    "표준산업분류명",
    "시도코드",
    "시도명",
    "시군구코드",
    "시군구명",
    "행정동코드",
    "행정동명",
    "법정동코드",
    "법정동명",
    "지번코드",
    "대지구분코드",
    "대지구분명",
    "지번본번지",
    "지번부번지",
    "지번주소",
    "도로명코드",
    "도로명",
    "건물본번지",
    "건물부번지",
    "건물관리번호",
    "건물명",
    "도로명주소",
    "구우편번호",
    "신우편번호",
    "동정보",
    "층정보",
    "호정보",
    "경도",
    "위도",
]


def store_row(**over) -> dict:
    """상가정보 CSV 한 줄(공공데이터포털 2026-06 판 칸 이름). 기본은 대구 북구 한식집."""
    row = {k: "" for k in STORE_HEADER}
    row.update(
        {
            "상가업소번호": "MA0106202201A0666448",
            "상호명": "앤티앤스재원푸드",
            "지점명": "칠성점",
            "상권업종대분류명": "음식",
            "상권업종중분류명": "한식",
            "상권업종소분류명": "백반/한정식",
            "시도명": "대구광역시",
            "시군구명": "북구",
            "법정동명": "칠성동2가",
            "도로명": "대구광역시 북구 침산로",
            "건물본번지": "93",
            "건물부번지": "",
            "도로명주소": "대구광역시 북구 침산로 93",
            "경도": "128.589783799109",
            "위도": "35.8852247459674",
        }
    )
    row.update(over)
    return row


def tour_item(**over) -> dict:
    d = {
        "addr1": "충청남도 공주시 감영길 3 (반죽동)",
        "addr2": "",
        "contentid": "2750144",
        "contenttypeid": "38",
        "mapx": "127.1216584808",
        "mapy": "36.4528799330",
        "modifiedtime": "20260831150117",
        "tel": "041-000-0000",
        "title": "가가상점",
    }
    d.update(over)
    return d


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


class ConvertStoreRecord(unittest.TestCase):
    def test_basic_fields(self):
        r = store_record(store_row())
        self.assertIsNotNone(r)
        self.assertEqual(r["id"], "MA0106202201A0666448")
        self.assertEqual(r["name"], "앤티앤스재원푸드 칠성점")  # 상호명 + 지점명
        self.assertAlmostEqual(float(r["lat"]), 35.8852247459674, places=6)
        self.assertAlmostEqual(float(r["lng"]), 128.589783799109, places=6)
        self.assertEqual(r["region"], "대구")  # 시도 약칭
        self.assertEqual(r["city"], "북구")

    def test_name_without_branch_has_no_trailing_space(self):
        r = store_record(store_row(지점명=""))
        self.assertEqual(r["name"], "앤티앤스재원푸드")

    def test_road_addr_is_full_road_address(self):
        r = store_record(store_row())
        self.assertEqual(r["road_addr"], "대구광역시 북구 침산로 93")

    def test_bld_no_main_only(self):
        self.assertEqual(
            store_record(store_row(건물본번지="93", 건물부번지=""))["bld_no"], "93"
        )

    def test_bld_no_main_and_sub(self):
        self.assertEqual(
            store_record(store_row(건물본번지="166", 건물부번지="2"))["bld_no"], "166-2"
        )

    def test_bld_no_empty_when_missing(self):
        r = store_record(store_row(건물본번지="", 건물부번지=""))
        self.assertIn("bld_no", r)
        self.assertFalse(r["bld_no"])

    def test_kind_from_small_category_table(self):
        cases = [
            ("비알코올", "카페", "카페"),
            ("중식", "중국집", "중식"),
            ("분식", "김밥/만두/분식", "분식"),
            ("서양식", "파스타/스테이크", "양식"),
        ]
        for mid, low, kind in cases:
            with self.subTest(low=low):
                r = store_record(store_row(상권업종중분류명=mid, 상권업종소분류명=low))
                self.assertEqual(r["kind"], kind)

    def test_kind_from_middle_category(self):
        self.assertEqual(
            store_record(store_row())["kind"], "한식"
        )  # 한식 · 백반/한정식
        r = store_record(
            store_row(상권업종중분류명="동남아시아", 상권업종소분류명="베트남식")
        )
        self.assertEqual(r["kind"], "세계요리")

    def test_biz_middle_mapping(self):
        cases = [
            ("음식", "한식", "백반/한정식", "음식점"),
            ("음식", "비알코올", "카페", "카페"),
            ("음식", "주점", "요리 주점", "술집"),
            ("숙박", "숙박", "여관/모텔", "숙박"),
        ]
        for big, mid, low, middle in cases:
            with self.subTest(mid=mid, low=low):
                r = store_record(
                    store_row(
                        상권업종대분류명=big, 상권업종중분류명=mid, 상권업종소분류명=low
                    )
                )
                self.assertEqual(r["biz_middle"], middle)

    def test_stay_kind(self):
        r = store_record(
            store_row(
                상권업종대분류명="숙박",
                상권업종중분류명="숙박",
                상권업종소분류명="펜션",
            )
        )
        self.assertEqual((r["kind"], r["biz_middle"]), ("펜션", "숙박"))

    def test_not_food_or_stay_is_none(self):
        self.assertIsNone(
            store_record(
                store_row(상권업종대분류명="소매", 상권업종중분류명="오락용품 소매")
            )
        )

    def test_bad_coordinates_is_none(self):
        self.assertIsNone(store_record(store_row(위도="")))
        self.assertIsNone(store_record(store_row(경도="abc")))

    def test_excluded_categories_are_not_filtered_here(self):
        # 거르기는 적재 SQL 몫 — 유흥 주점도 줄이 된다.
        r = store_record(
            store_row(상권업종중분류명="주점", 상권업종소분류명="일반 유흥 주점")
        )
        self.assertIsNotNone(r)

    def test_convert_store_writes_food_and_stay_files(self):
        with tempfile.TemporaryDirectory() as td:
            src, out = Path(td) / "csv", Path(td) / "out"
            src.mkdir()
            rows = [
                store_row(),
                store_row(
                    상가업소번호="MA2",
                    상권업종대분류명="숙박",
                    상권업종중분류명="숙박",
                    상권업종소분류명="펜션",
                ),
                store_row(상가업소번호="MA3", 상권업종대분류명="소매"),
            ]
            with (src / "a.csv").open("w", encoding="utf-8-sig", newline="") as fh:
                w = csv.DictWriter(fh, fieldnames=STORE_HEADER, quoting=csv.QUOTE_ALL)
                w.writeheader()
                w.writerows(rows)
            self.assertEqual(
                main(["store", str(src), str(out)]), 0
            )  # 출력 폴더가 없어도 CLI 가 만든다
            food = read_jsonl(out / "poi_food.jsonl")
            stay = read_jsonl(out / "poi_stay.jsonl")
            self.assertEqual([r["id"] for r in food], ["MA0106202201A0666448"])
            self.assertEqual([r["id"] for r in stay], ["MA2"])
            for r in food + stay:
                for k in (
                    "id",
                    "name",
                    "lat",
                    "lng",
                    "addr",
                    "road",
                    "tel",
                    "kind",
                    "biz_middle",
                    "biz_lower",
                    "region",
                    "city",
                    "road_addr",
                    "bld_no",
                    "src",
                ):
                    self.assertIn(k, r)


class ConvertTourRecord(unittest.TestCase):
    def test_type_mapping(self):
        cases = [
            ("32", "숙박"),
            ("39", "음식점"),
            ("12", "관광명소"),
            ("14", "문화생활시설"),
            ("38", "관광명소"),
        ]
        for ctype, middle in cases:
            with self.subTest(ctype=ctype):
                r = tour_record(tour_item(contenttypeid=ctype))
                self.assertEqual(r["biz_middle"], middle)
        self.assertEqual(tour_record(tour_item(contenttypeid="32"))["kind"], "숙박")
        self.assertEqual(tour_record(tour_item(contenttypeid="39"))["kind"], "음식점")
        self.assertEqual(tour_record(tour_item(contenttypeid="12"))["kind"], "관광지")

    def test_id_name_coords(self):
        r = tour_record(tour_item())
        self.assertEqual(r["id"], "tour-2750144")
        self.assertEqual(r["name"], "가가상점")
        self.assertAlmostEqual(float(r["lat"]), 36.452879933, places=6)
        self.assertAlmostEqual(float(r["lng"]), 127.1216584808, places=6)
        self.assertEqual(r["region"], "충남")

    def test_road_addr_strips_trailing_parenthetical(self):
        r = tour_record(tour_item())
        self.assertEqual(r["road_addr"], "충청남도 공주시 감영길 3")

    def test_src_is_korean_item_only(self):
        item = tour_item()
        r = tour_record(item)
        # 국문 원본만 — 영·일문·상세는 붙이지 않는다(감싸는 꼴 {"kor": …} 도 허용).
        for k in ("eng", "jpn", "detail"):
            self.assertNotIn(k, r["src"])
        kor = r["src"].get("kor", r["src"])
        self.assertEqual(kor, item)

    def test_hidden_record_for_showflag_zero(self):
        h = tour_hidden(tour_item(showflag="0"))
        self.assertIsNotNone(h)
        self.assertEqual(h["source_id"], "tour-2750144")
        t = datetime.fromisoformat(h["modified"])
        if t.tzinfo is None:
            t = t.replace(tzinfo=KST)  # 시간대가 없으면 관광공사 시각(KST)으로 본다
        self.assertEqual(t, datetime(2026, 8, 31, 15, 1, 17, tzinfo=KST))

    def test_no_hidden_record_when_shown_or_no_flag(self):
        self.assertIsNone(tour_hidden(tour_item(showflag="1")))
        self.assertIsNone(tour_hidden(tour_item()))

    def test_convert_tour_splits_files(self):
        with tempfile.TemporaryDirectory() as td:
            src, out = Path(td) / "list.jsonl", Path(td) / "out"
            items = [
                tour_item(contentid="1", contenttypeid="32", showflag="1"),
                tour_item(contentid="2", contenttypeid="39"),  # showflag 없음 → 표출
                tour_item(contentid="3", contenttypeid="12", showflag="1"),
                tour_item(contentid="4", contenttypeid="39", showflag="0"),
            ]
            src.write_text(
                "\n".join(json.dumps(i, ensure_ascii=False) for i in items) + "\n",
                encoding="utf-8",
            )
            self.assertEqual(main(["tour", str(src), str(out)]), 0)
            self.assertEqual(
                [r["id"] for r in read_jsonl(out / "poi_tour_stay.jsonl")], ["tour-1"]
            )
            self.assertEqual(
                [r["id"] for r in read_jsonl(out / "poi_tour_food.jsonl")], ["tour-2"]
            )
            self.assertEqual(
                [r["id"] for r in read_jsonl(out / "poi_sight.jsonl")], ["tour-3"]
            )
            hidden = read_jsonl(out / "tour_hidden.jsonl")
            self.assertEqual([h["source_id"] for h in hidden], ["tour-4"])
            self.assertIn("modified", hidden[0])


def write_csv(path: Path, header: list[str], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=header)
        w.writeheader()
        w.writerows(rows)


class ConvertTransit(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        raw = Path(self._td.name) / "교통"
        raw.mkdir()
        write_csv(
            raw / "공항위치.csv",
            ["공항명", "위도(WGS84좌표)", "경도(WGS84좌표)", "행정구역"],
            [
                {
                    "공항명": "김포",
                    "위도(WGS84좌표)": "37.558",
                    "경도(WGS84좌표)": "126.794",
                    "행정구역": "서울특별시 강서구",
                }
            ],
        )
        write_csv(
            raw / "철도역정보.csv",
            ["역이름", "위도좌표", "경도좌표", "주소"],
            [
                {
                    "역이름": "서울역",
                    "위도좌표": "37.554",
                    "경도좌표": "126.970",
                    "주소": "서울특별시 용산구 한강대로 405",
                },
                {"역이름": "좌표없음역", "위도좌표": "", "경도좌표": "", "주소": ""},
            ],
        )
        write_csv(
            raw / "도시철도역사.csv",
            [
                "역번호",
                "역사명",
                "노선번호",
                "역위도",
                "역경도",
                "역사도로명주소",
                "역사전화번호",
            ],
            [
                {
                    "역번호": "100",
                    "역사명": "월곶역",
                    "노선번호": "L1",
                    "역위도": "37.39",
                    "역경도": "126.74",
                    "역사도로명주소": "경기도 시흥시 월곶중앙로 1",
                    "역사전화번호": "031-000-0000",
                },
                {
                    "역번호": "200",
                    "역사명": "강남",
                    "노선번호": "L2",
                    "역위도": "37.49",
                    "역경도": "127.02",
                    "역사도로명주소": "서울특별시 강남구 강남대로 396",
                    "역사전화번호": "",
                },
            ],
        )
        write_csv(
            raw / "전국대중교통환승센터표준데이터.csv",
            [
                "시군구코드",
                "환승센터명",
                "여객자동차터미널명",
                "위도",
                "경도",
                "소재지지번주소",
                "소재지도로명주소",
            ],
            [
                {
                    "시군구코드": "41000",
                    "환승센터명": "어느환승센터",
                    "여객자동차터미널명": "",
                    "위도": "37.3",
                    "경도": "127.1",
                    "소재지지번주소": "경기도 어느시 1",
                    "소재지도로명주소": "경기도 어느시 어느로 1",
                }
            ],
        )
        (raw / "버스터미널목록.jsonl").write_text(
            json.dumps({"terminalNm": "동서울"}, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        (raw / "버스터미널정류소.jsonl").write_text(
            "\n".join(
                json.dumps(x, ensure_ascii=False)
                for x in [
                    {
                        "nodeid": "N1",
                        "nodenm": "동서울터미널",
                        "gpslati": "37.534",
                        "gpslong": "127.094",
                        "_cityName": "서울특별시",
                    },
                    {
                        "nodeid": "N2",
                        "nodenm": "시골정류소",
                        "gpslati": "36.0",
                        "gpslong": "128.0",
                        "_cityName": "경상북도",
                    },
                    {
                        "nodeid": "N3",
                        "nodenm": "좌표없음",
                        "gpslati": "",
                        "gpslong": "",
                    },
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        self.raw = raw
        self.rows = {r["id"]: r for r in transit_records(raw)}

    def tearDown(self):
        self._td.cleanup()

    def test_air(self):
        r = self.rows["air-김포"]
        self.assertEqual(r["name"], "김포공항")
        self.assertEqual(r["kind"], "공항")
        self.assertEqual(r["biz_middle"], "교통시설")
        self.assertEqual(r["region"], "서울")

    def test_train(self):
        r = self.rows["train-서울역"]
        self.assertEqual((r["name"], r["kind"]), ("서울역", "기차역"))
        self.assertNotIn("train-좌표없음역", self.rows)

    def test_metro_station_suffix_not_doubled(self):
        self.assertEqual(self.rows["metro-100_L1"]["name"], "월곶역")
        self.assertEqual(self.rows["metro-200_L2"]["name"], "강남역")
        self.assertEqual(self.rows["metro-200_L2"]["kind"], "지하철역")

    def test_hub(self):
        r = self.rows["hub-41000-어느환승센터"]
        self.assertEqual(r["kind"], "환승센터")

    def test_busstop(self):
        self.assertEqual(
            self.rows["busstop-N1"]["kind"], "버스터미널"
        )  # 공식 터미널 이름과 닿음
        self.assertEqual(self.rows["busstop-N2"]["kind"], "터미널정류소")
        self.assertNotIn("busstop-N3", self.rows)

    def test_road_addr_field_present(self):
        for r in self.rows.values():
            self.assertIn("road_addr", r)

    def test_convert_transit_writes_file(self):
        out = Path(self._td.name) / "out"
        out.mkdir()
        convert_transit(self.raw, out)
        ids = {r["id"] for r in read_jsonl(out / "poi_transit.jsonl")}
        self.assertEqual(ids, set(self.rows))


if __name__ == "__main__":
    unittest.main()
