"""브랜드 사전의 근거(source) 칸과 브랜드 후보 — 명세만 보고 짠 시험.

명세: brands.tsv 의 영어가 있는 줄은 source 가 http:// 또는 https:// 로 시작해야 하고, 아니면 사전을 읽을 때
ValueError. 영어가 빈 줄(「브랜드 아님」)은 근거가 필요 없다. brand_candidates 는 띄어 쓴 이름의 첫 낱말을
세어(NFKC 정규화 뒤) min_count(기본 BRAND_CANDIDATE_MIN = 100) 곳 이상을 많은 순으로 돌려준다.
"""

import pathlib
import shutil
import tempfile
import unittest

from tools.poi import names as N

DATA_DIR = pathlib.Path("tools/poi/data")
_HEADER = "ko\ten\tsource\n"


def _brand_rows_with_source(path):
    """brands.tsv 의 (ko, en, source) 줄 — 주석과 머리줄은 뺀다."""
    rows = []
    header_seen = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        if not header_seen:
            header_seen = True
            continue
        cells = line.split("\t") + ["", ""]
        rows.append((cells[0], cells[1], cells[2]))
    return rows


class _TempData(unittest.TestCase):
    """generic.tsv 는 진짜를 복사하고 brands.tsv 만 시험마다 쓴다."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self._tmp.name)
        shutil.copy(DATA_DIR / "generic.tsv", self.dir / "generic.tsv")

    def tearDown(self):
        self._tmp.cleanup()

    def write_brands(self, body, header=_HEADER, comment="# 시험용 사전\n"):
        (self.dir / "brands.tsv").write_text(comment + header + body, encoding="utf-8")

    def load(self):
        return N.Dictionaries.load(self.dir)


class BrandSourceShipped(unittest.TestCase):
    """실제로 싣는 brands.tsv 는 규칙을 지키고 오류 없이 읽힌다."""

    def test_shipped_file_loads(self):
        d = N.Dictionaries.load(DATA_DIR)
        self.assertTrue(d.brands)

    def test_shipped_header(self):
        lines = [
            ln
            for ln in (DATA_DIR / "brands.tsv").read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.startswith("#")
        ]
        self.assertEqual(lines[0].split("\t"), ["ko", "en", "source"])

    def test_every_shipped_english_row_has_http_source(self):
        rows = _brand_rows_with_source(DATA_DIR / "brands.tsv")
        self.assertTrue(rows)
        for ko, en, source in rows:
            if en.strip():
                with self.subTest(ko=ko):
                    self.assertTrue(
                        source.startswith(("http://", "https://")), (ko, source)
                    )

    def test_shipped_not_brand_rows_load_as_empty(self):
        d = N.Dictionaries.load(DATA_DIR)
        rows = _brand_rows_with_source(DATA_DIR / "brands.tsv")
        empty = [ko for ko, en, _ in rows if not en.strip()]
        self.assertTrue(empty)
        for ko in empty:
            with self.subTest(ko=ko):
                self.assertEqual(d.brands.get(ko), "")


class BrandSourceRequired(_TempData):
    """영어가 있는 줄은 http·https 근거가 없으면 사전을 읽을 때 ValueError."""

    def test_https_source_loads(self):
        self.write_brands("가나치킨\tGana Chicken\thttps://gana.example/\n")
        self.assertEqual(self.load().brands["가나치킨"], "Gana Chicken")

    def test_http_source_loads(self):
        self.write_brands("가나치킨\tGana Chicken\thttp://gana.example/\n")
        self.assertEqual(self.load().brands["가나치킨"], "Gana Chicken")

    def test_empty_source_raises(self):
        self.write_brands("가나치킨\tGana Chicken\t\n")
        with self.assertRaises(ValueError):
            self.load()

    def test_whitespace_only_source_raises(self):
        self.write_brands("가나치킨\tGana Chicken\t   \n")
        with self.assertRaises(ValueError):
            self.load()

    def test_missing_source_cell_raises(self):
        # 줄에 source 칸 자체가 없다(두 칸)
        self.write_brands("가나치킨\tGana Chicken\n")
        with self.assertRaises(ValueError):
            self.load()

    def test_non_http_sources_raise(self):
        for source in (
            "www.gana.example",
            "gana.example",
            "ftp://gana.example/",
            "file:///tmp/gana",
            "공식 사이트",
            "http//gana.example",
            "https:gana.example",
        ):
            with self.subTest(source=source):
                self.write_brands(f"가나치킨\tGana Chicken\t{source}\n")
                with self.assertRaises(ValueError):
                    self.load()

    def test_one_bad_row_among_good_rows_raises(self):
        self.write_brands(
            "가나치킨\tGana Chicken\thttps://gana.example/\n"
            "다라커피\tDara Coffee\t\n"
            "마바피자\tMaba Pizza\thttp://maba.example/\n"
        )
        with self.assertRaises(ValueError):
            self.load()

    def test_bad_row_after_not_brand_row_raises(self):
        self.write_brands("가나치킨아\t\t\n다라커피\tDara Coffee\tnone\n")
        with self.assertRaises(ValueError):
            self.load()

    def test_not_brand_row_needs_no_source(self):
        self.write_brands(
            "가나치킨\tGana Chicken\thttps://gana.example/\n"
            "가나치킨아\t\t\n"
            "다라커피아\t\n"
        )
        d = self.load()
        self.assertEqual(d.brands["가나치킨"], "Gana Chicken")
        self.assertEqual(d.brands["가나치킨아"], "")
        self.assertEqual(d.brands["다라커피아"], "")

    def test_not_brand_row_with_non_http_source_is_fine(self):
        # 영어가 빈 줄은 근거를 보지 않는다
        self.write_brands("가나치킨아\t\t메모\n")
        self.assertEqual(self.load().brands["가나치킨아"], "")

    def test_comment_lines_are_ignored(self):
        self.write_brands(
            "# 주석\tNot A Brand\tnot-a-url\n"
            "가나치킨\tGana Chicken\thttps://gana.example/\n",
        )
        d = self.load()
        self.assertEqual(d.brands, {"가나치킨": "Gana Chicken"})


class _CandidateBase(_TempData):
    def setUp(self):
        super().setUp()
        self.write_brands(
            "가나치킨\tGana Chicken\thttps://gana.example/\n"  # 브랜드
            "다라커피\t\t\n"  # 브랜드 아님
        )
        self.d = self.load()

    def cands(self, rows, **kw):
        return N.brand_candidates(rows, self.d, **kw)


class BrandCandidates(_CandidateBase):
    def test_default_minimum_is_100(self):
        self.assertEqual(N.BRAND_CANDIDATE_MIN, 100)
        rows99 = [(f"새로운치킨 {i}호점", None) for i in range(99)]
        self.assertEqual(self.cands(rows99), [])
        rows100 = rows99 + [("새로운치킨 마지막점", None)]
        self.assertEqual(self.cands(rows100), [("새로운치킨", 100)])

    def test_min_count_parameter(self):
        rows = [("새로운치킨 강남점", None)] * 3 + [("행복커피 역삼점", None)] * 2
        self.assertEqual(self.cands(rows, min_count=3), [("새로운치킨", 3)])
        self.assertEqual(
            self.cands(rows, min_count=2), [("새로운치킨", 3), ("행복커피", 2)]
        )

    def test_counts_first_token_of_multi_token_names(self):
        rows = [
            ("새로운치킨 강남점", None),
            ("새로운치킨 역삼 2호점", "generic"),
            ("새로운치킨 본점", None),
        ]
        self.assertEqual(self.cands(rows, min_count=1), [("새로운치킨", 3)])

    def test_ordered_by_count_descending(self):
        rows = (
            [("가가 1점", None)] * 2
            + [("나나 1점", None)] * 5
            + [("다다 1점", None)] * 3
            + [("라라 1점", None)] * 1
        )
        self.assertEqual(
            self.cands(rows, min_count=1),
            [("나나", 5), ("다다", 3), ("가가", 2), ("라라", 1)],
        )

    def test_result_is_list_of_pairs(self):
        out = self.cands([("새로운치킨 강남점", None)], min_count=1)
        self.assertIsInstance(out, list)
        self.assertEqual(out, [("새로운치킨", 1)])

    def test_empty_input(self):
        self.assertEqual(self.cands([], min_count=1), [])
        self.assertEqual(self.cands(iter([]), min_count=1), [])

    def test_accepts_generator(self):
        rows = (("새로운치킨 강남점", None) for _ in range(2))
        self.assertEqual(self.cands(rows, min_count=2), [("새로운치킨", 2)])

    def test_skips_rows_already_matched_as_brand(self):
        rows = [("새로운치킨 강남점", "brand")] * 5 + [("새로운치킨 역삼점", None)]
        self.assertEqual(self.cands(rows, min_count=1), [("새로운치킨", 1)])

    def test_non_brand_sources_are_counted(self):
        rows = [
            ("새로운치킨 강남점", None),
            ("새로운치킨 역삼점", "generic"),
        ]
        self.assertEqual(self.cands(rows, min_count=2), [("새로운치킨", 2)])

    def test_skips_single_token_names(self):
        rows = [("새로운치킨", None)] * 5 + [("새로운치킨강남점", None)] * 5
        self.assertEqual(self.cands(rows, min_count=1), [])

    def test_skips_short_first_token(self):
        rows = [("김 밥", None)] * 5 + [("A 마트", None)] * 5
        self.assertEqual(self.cands(rows, min_count=1), [])

    def test_two_character_first_token_is_counted(self):
        rows = [("행복 마트", None)] * 2
        self.assertEqual(self.cands(rows, min_count=1), [("행복", 2)])

    def test_skips_first_token_ending_in_jeom(self):
        rows = [("강남점 새로운치킨", None)] * 5 + [("본점 카페", None)] * 5
        self.assertEqual(self.cands(rows, min_count=1), [])

    def test_skips_tokens_already_in_dictionary(self):
        rows = (
            [("가나치킨 강남점", None)] * 5  # 사전의 브랜드
            + [("다라커피 역삼점", None)] * 5  # 사전의 「브랜드 아님」
            + [("새로운치킨 강남점", None)]
        )
        self.assertEqual(self.cands(rows, min_count=1), [("새로운치킨", 1)])

    def test_nfkc_normalization(self):
        # 전각 영문·숫자(ＡＢ)는 반각과 같은 낱말로 센다
        rows = [("ＡＢ치킨 강남점", None), ("AB치킨 역삼점", None)]
        self.assertEqual(self.cands(rows, min_count=1), [("AB치킨", 2)])

    def test_nfkc_ideographic_space_separates_tokens(self):
        rows = [("새로운치킨　강남점", None)]
        self.assertEqual(self.cands(rows, min_count=1), [("새로운치킨", 1)])

    def test_extra_whitespace(self):
        rows = [("  새로운치킨   강남점  ", None)]
        self.assertEqual(self.cands(rows, min_count=1), [("새로운치킨", 1)])

    def test_shipped_dictionary_brands_are_not_candidates(self):
        d = N.Dictionaries.load(DATA_DIR)
        rows = [(f"{ko} 가나점", None) for ko in d.brands if ko] * 2
        self.assertEqual(N.brand_candidates(rows, d, min_count=1), [])


if __name__ == "__main__":
    unittest.main()
