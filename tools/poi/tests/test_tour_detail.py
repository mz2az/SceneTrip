"""`just poi-tour-detail` — 관광공사 상세 → poi_detail · poi_image 적재 파일. 명세만 보고 쓴 시험.

입력 파일을 임시 폴더에 쓰고 `main()` 으로 돌린 뒤 결과 줄을 읽어 본다(CLI 와 같은 길).
"""

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from typing import ClassVar

from tools.poi.tour_detail import main

KEYS = {
    "source_id",
    "lang",
    "tour_content_id",
    "tour_modified",
    "overview",
    "use_time",
    "rest_date",
    "parking",
    "first_menu",
    "use_fee",
    "homepage",
    "images",
    "match_name",
    "lat",
    "lng",
}
TEXT = ("use_time", "rest_date", "parking", "first_menu", "use_fee")


def place(cid="126508", ctype="12", common=None, intro=None, images=None):
    d = {
        "contenttypeid": ctype,
        "common": [common or {}],
        "intro": [intro or {}],
        "info": [],
    }
    if cid is not None:
        d["contentid"] = cid
    d["images"] = images if images is not None else []
    return d


class TmpRun(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.tmp = Path(self._td.name)

    def tearDown(self):
        self._td.cleanup()

    def run_lines(self, items, out_name="tour_detail.jsonl", raw=None, extra=()):
        src = self.tmp / "detail.jsonl"
        if raw is not None:
            src.write_text(raw, encoding="utf-8")
        else:
            src.write_text(
                "".join(json.dumps(i, ensure_ascii=False) + "\n" for i in items),
                encoding="utf-8",
            )
        out = self.tmp / out_name
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            rc = main([str(src), str(out), *extra])
        self.stdout = buf.getvalue()
        self.rc = rc
        if rc != 0 or not out.exists():
            return None
        return [
            json.loads(x)
            for x in out.read_text(encoding="utf-8").splitlines()
            if x.strip()
        ]

    def one(self, item, extra=()):
        rows = self.run_lines([item], extra=extra)
        self.assertEqual(self.rc, 0)
        self.assertEqual(len(rows), 1)
        return rows[0]


class TourDetailIdentity(TmpRun):
    def test_basic_fields(self):
        r = self.one(place("126508"))
        self.assertEqual(set(r), KEYS)
        self.assertEqual(r["source_id"], "tour-126508")
        self.assertEqual(r["lang"], "ko")
        self.assertEqual(r["tour_content_id"], "126508")
        self.assertIsInstance(r["tour_content_id"], str)
        self.assertIsNone(r["match_name"])
        self.assertIsNone(r["lat"])
        self.assertIsNone(r["lng"])

    def test_ko_ignores_title_and_coords(self):
        r = self.one(
            place(common={"title": "추암조각공원", "mapx": "129.16", "mapy": "37.47"})
        )
        self.assertEqual(r["source_id"], "tour-126508")
        self.assertIsNone(r["match_name"])
        self.assertIsNone(r["lat"])
        self.assertIsNone(r["lng"])

    def test_explicit_lang_ko_same_as_default(self):
        r = self.one(place("5"), extra=("--lang", "ko"))
        self.assertEqual(r["lang"], "ko")
        self.assertEqual(r["source_id"], "tour-5")

    def test_numeric_contentid_becomes_string(self):
        r = self.one(place(126508))
        self.assertEqual(r["tour_content_id"], "126508")
        self.assertEqual(r["source_id"], "tour-126508")

    def test_no_contentid_skipped(self):
        rows = self.run_lines([place(None), place("1")])
        self.assertEqual(self.rc, 0)
        self.assertEqual([r["source_id"] for r in rows], ["tour-1"])

    def test_first_line_per_contentid_wins(self):
        rows = self.run_lines(
            [
                place("7", common={"overview": "처음"}),
                place("8"),
                place("7", common={"overview": "나중"}),
            ]
        )
        self.assertEqual([r["source_id"] for r in rows], ["tour-7", "tour-8"])
        self.assertEqual(rows[0]["overview"], "처음")


class TourDetailModified(TmpRun):
    def test_iso_kst(self):
        r = self.one(place(common={"modifiedtime": "20240115093005"}))
        self.assertEqual(r["tour_modified"], "2024-01-15T09:30:05+09:00")

    def test_invalid_or_missing(self):
        for v in ("", "2024", "20241315093000", "abcdefghijklmn", None):
            with self.subTest(v=v):
                c = {} if v is None else {"modifiedtime": v}
                self.assertIsNone(self.one(place(common=c))["tour_modified"])


class TourDetailClean(TmpRun):
    def overview(self, text):
        return self.one(place(common={"overview": text}))["overview"]

    def test_br_variants(self):
        self.assertEqual(self.overview("가<br>나<br/>다<BR />라"), "가\n나\n다\n라")

    def test_tags_removed_and_entities(self):
        self.assertEqual(self.overview("<p>A &amp; <b>B</b></p>"), "A & B")

    def test_spaces_collapse_and_trim(self):
        self.assertEqual(self.overview("  가 \t  나  다  <br>  라  "), "가 나 다\n라")

    def test_many_newlines_become_two(self):
        self.assertEqual(self.overview("가<br><br><br><br>나"), "가\n\n나")
        self.assertEqual(self.overview("가<br><br>나"), "가\n\n나")

    def test_overall_trim(self):
        self.assertEqual(self.overview("<br><br>가<br><br>"), "가")

    def test_empty_is_null(self):
        for v in ("", "   ", "<br><br>", "<p> </p>"):
            with self.subTest(v=v):
                self.assertIsNone(self.overview(v))
        self.assertIsNone(self.one(place(common={}))["overview"])

    def test_intro_fields_cleaned(self):
        r = self.one(
            place(ctype="12", intro={"usetime": " 09:00~18:00<br>연중무휴 &amp; 야간 "})
        )
        self.assertEqual(r["use_time"], "09:00~18:00\n연중무휴 & 야간")


class TourDetailIntroByType(TmpRun):
    ALL: ClassVar[dict[str, str]] = {
        "usetime": "a1",
        "restdate": "a2",
        "parking": "a3",
        "usetimeculture": "b1",
        "restdateculture": "b2",
        "parkingculture": "b3",
        "usefee": "b4",
        "playtime": "c1",
        "usetimefestival": "c4",
        "usetimeleports": "d1",
        "restdateleports": "d2",
        "parkingleports": "d3",
        "usefeeleports": "d4",
        "parkinglodging": "e3",
        "opentime": "f1",
        "restdateshopping": "f2",
        "parkingshopping": "f3",
        "opentimefood": "g1",
        "restdatefood": "g2",
        "parkingfood": "g3",
        "firstmenu": "g5",
    }
    # (use_time, rest_date, parking, first_menu, use_fee)
    EXPECT: ClassVar[dict[str, tuple]] = {
        "12": ("a1", "a2", "a3", None, None),
        "14": ("b1", "b2", "b3", None, "b4"),
        "15": ("c1", None, None, None, "c4"),
        "28": ("d1", "d2", "d3", None, "d4"),
        "32": (None, None, "e3", None, None),
        "38": ("f1", "f2", "f3", None, None),
        "39": ("g1", "g2", "g3", "g5", None),
        "25": (None, None, None, None, None),
    }

    def test_each_type(self):
        for ctype, expect in self.EXPECT.items():
            with self.subTest(ctype=ctype):
                r = self.one(place(cid="9" + ctype, ctype=ctype, intro=dict(self.ALL)))
                self.assertEqual(tuple(r[k] for k in TEXT), expect)

    def test_missing_intro_field_is_null(self):
        r = self.one(place(ctype="39", intro={"opentimefood": "11:00"}))
        self.assertEqual(r["use_time"], "11:00")
        for k in ("rest_date", "parking", "first_menu", "use_fee"):
            self.assertIsNone(r[k])


class TourDetailHomepage(TmpRun):
    def hp(self, text):
        return self.one(place(common={"homepage": text}))["homepage"]

    def test_href(self):
        self.assertEqual(
            self.hp(
                '<a href="https://www.example.go.kr/x" target="_blank">www.example.go.kr</a>'
            ),
            "https://www.example.go.kr/x",
        )

    def test_href_non_http_falls_back_to_text(self):
        self.assertEqual(
            self.hp('<a href="mailto:a@b.kr">메일</a> 홈페이지 http://site.kr/'),
            "http://site.kr/",
        )

    def test_first_address_in_text(self):
        self.assertEqual(
            self.hp("홈페이지 https://a.kr/one 또는 https://b.kr/two"),
            "https://a.kr/one",
        )

    def test_trailing_punct(self):
        for tail in (".", ",", ")", ";", ").", ");"):
            with self.subTest(tail=tail):
                self.assertEqual(self.hp(f"(https://a.kr/p{tail}"), "https://a.kr/p")

    def test_no_address(self):
        for v in ("", "www.example.com", "없음", None):
            with self.subTest(v=v):
                c = {} if v is None else {"homepage": v}
                self.assertIsNone(self.one(place(common=c))["homepage"])

    def test_korean_glued_to_path(self):
        self.assertEqual(
            self.hp("https://www.instagram.com/abc네이버"),
            "https://www.instagram.com/abc",
        )

    def test_korean_glued_to_host(self):
        self.assertEqual(self.hp("http://gnhcc.co.kr공식"), "http://gnhcc.co.kr")

    def test_korean_domain_kept(self):
        self.assertEqual(self.hp("http://가로림.com/"), "http://가로림.com/")


class TourDetailImages(TmpRun):
    def test_order_https_thumbnail_license_credit(self):
        r = self.one(
            place(
                common={
                    "firstimage": "http://tong.visitkorea.or.kr/a.jpg",
                    "firstimage2": "http://tong.visitkorea.or.kr/a_s.jpg",
                    "cpyrhtDivCd": "Type3",
                },
                images=[
                    {
                        "originimgurl": "http://tong.visitkorea.or.kr/b.jpg",
                        "smallimageurl": "http://tong.visitkorea.or.kr/b_s.jpg",
                        "cpyrhtDivCd": "Type1",
                    },
                    {
                        "originimgurl": "https://tong.visitkorea.or.kr/c.jpg",
                        "smallimageurl": "",
                        "cpyrhtDivCd": "Type2",
                    },
                ],
            )
        )
        self.assertEqual(
            r["images"],
            [
                {
                    "url": "https://tong.visitkorea.or.kr/a.jpg",
                    "thumbnail_url": "https://tong.visitkorea.or.kr/a_s.jpg",
                    "license": "kogl_type3",
                    "credit": "한국관광공사",
                },
                {
                    "url": "https://tong.visitkorea.or.kr/b.jpg",
                    "thumbnail_url": "https://tong.visitkorea.or.kr/b_s.jpg",
                    "license": "kogl_type1",
                    "credit": "한국관광공사",
                },
                {
                    "url": "https://tong.visitkorea.or.kr/c.jpg",
                    "thumbnail_url": None,
                    "license": None,
                    "credit": "한국관광공사",
                },
            ],
        )

    def test_empty_urls_skipped(self):
        r = self.one(
            place(
                common={"firstimage": "", "firstimage2": "http://x.kr/s.jpg"},
                images=[
                    {"originimgurl": "", "smallimageurl": "x"},
                    {"originimgurl": "http://x.kr/d.jpg"},
                ],
            )
        )
        self.assertEqual([i["url"] for i in r["images"]], ["https://x.kr/d.jpg"])

    def test_duplicate_after_conversion_first_wins(self):
        r = self.one(
            place(
                common={"firstimage": "http://x.kr/a.jpg", "cpyrhtDivCd": "Type3"},
                images=[
                    {"originimgurl": "https://x.kr/a.jpg", "cpyrhtDivCd": "Type1"},
                    {"originimgurl": "http://x.kr/b.jpg"},
                    {"originimgurl": "http://x.kr/b.jpg"},
                ],
            )
        )
        self.assertEqual(
            [i["url"] for i in r["images"]],
            ["https://x.kr/a.jpg", "https://x.kr/b.jpg"],
        )
        self.assertEqual(r["images"][0]["license"], "kogl_type3")

    def test_no_images(self):
        self.assertEqual(self.one(place())["images"], [])


class TourDetailMain(TmpRun):
    def test_missing_input(self):
        out = self.tmp / "tour_detail.jsonl"
        with (
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            rc = main([str(self.tmp / "nope.jsonl"), str(out)])
        self.assertEqual(rc, 1)
        self.assertFalse(out.exists())

    def test_poi_prefixed_output_rejected(self):
        rows = self.run_lines([place("1")], out_name="poi_tour.jsonl")
        self.assertEqual(self.rc, 1)
        self.assertIsNone(rows)
        self.assertFalse((self.tmp / "poi_tour.jsonl").exists())

    def test_success_prints_summary(self):
        rows = self.run_lines([place("1"), place("2")])
        self.assertEqual(self.rc, 0)
        self.assertEqual(len(rows), 2)
        self.assertTrue(self.stdout.strip())


EN = ("--lang", "en")


class TourDetailEnglish(TmpRun):
    def en(self, item):
        return self.one(item, extra=EN)

    def test_identity(self):
        r = self.en(place("2465071", ctype="76", common={"title": "Seoul Tower"}))
        self.assertEqual(set(r), KEYS)
        self.assertIsNone(r["source_id"])
        self.assertEqual(r["lang"], "en")
        self.assertEqual(r["tour_content_id"], "2465071")

    def test_match_name_examples(self):
        cases = {
            "Chuam Sculpture Park (추암조각공원)": "추암조각공원",
            "1004 (Cheonsa) Bridge (천사대교)": "천사대교",
            "8 Seconds [Tax Refund Shop](에잇세컨즈 제주중앙점)": "에잇세컨즈 제주중앙점",
            "추암조각공원 (Chuam Sculpture Park)": "추암조각공원",
            "Seoul Tower": None,
            "경복궁": "경복궁",
            "Gyeongbokgung (Palace)": None,
            "Café (Cafe (Seoul) 카페)": "Cafe (Seoul) 카페",
        }
        for title, expect in cases.items():
            with self.subTest(title=title):
                self.assertEqual(
                    self.en(place(common={"title": title}))["match_name"], expect
                )

    def test_missing_title(self):
        self.assertIsNone(self.en(place(common={}))["match_name"])

    def test_coords(self):
        r = self.en(place(common={"mapx": "129.1612", "mapy": "37.4775"}))
        self.assertEqual(r["lat"], 37.4775)
        self.assertEqual(r["lng"], 129.1612)
        self.assertIsInstance(r["lat"], float)

    def test_bad_coords_null(self):
        for v in ("", "abc", None):
            with self.subTest(v=v):
                c = {} if v is None else {"mapx": v, "mapy": v}
                r = self.en(place(common=c))
                self.assertIsNone(r["lat"])
                self.assertIsNone(r["lng"])

    def test_type_mapping(self):
        mapping = {
            "76": "12",
            "78": "14",
            "85": "15",
            "75": "28",
            "80": "32",
            "79": "38",
            "82": "39",
            "77": "25",
        }
        for en_type, ko_type in mapping.items():
            with self.subTest(en_type=en_type):
                r = self.en(
                    place(
                        cid="8" + en_type,
                        ctype=en_type,
                        intro=dict(TourDetailIntroByType.ALL),
                    )
                )
                self.assertEqual(
                    tuple(r[k] for k in TEXT), TourDetailIntroByType.EXPECT[ko_type]
                )

    def test_korean_type_numbers_not_mapped_for_en(self):
        # 국문 번호(12)는 영문 매핑표에 없다 — 「다른 유형」 이라 다섯 칸 모두 null
        r = self.en(place(ctype="12", intro={"usetime": "9am"}))
        self.assertIsNone(r["use_time"])

    def test_text_cleaning_and_images_same_rules(self):
        r = self.en(
            place(
                ctype="76",
                common={
                    "overview": "A &amp; B<br><br><br>C",
                    "homepage": '<a href="https://english.visitkorea.or.kr/x">site</a>',
                    "firstimage": "http://x.kr/a.jpg",
                    "cpyrhtDivCd": "Type3",
                },
            )
        )
        self.assertEqual(r["overview"], "A & B\n\nC")
        self.assertEqual(r["homepage"], "https://english.visitkorea.or.kr/x")
        self.assertEqual(r["images"][0]["url"], "https://x.kr/a.jpg")
        self.assertEqual(r["images"][0]["license"], "kogl_type3")

    def test_dedupe_by_content_id(self):
        rows = self.run_lines(
            [
                place("1", common={"overview": "first"}),
                place("2"),
                place("1", common={"overview": "second"}),
                place(None),
            ],
            extra=EN,
        )
        self.assertEqual(self.rc, 0)
        self.assertEqual([r["tour_content_id"] for r in rows], ["1", "2"])
        self.assertEqual(rows[0]["overview"], "first")


if __name__ == "__main__":
    unittest.main()
