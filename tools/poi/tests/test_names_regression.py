"""§12-4 「검증에서 찾은 결함」 회귀 시험 — 계획 문서와 사전만 보고 짰다(구현은 보지 않았다).

계획: docs/project/plans/poi-i18n-image.md §12-4. 한식 메뉴명 결함(주석·고기 대조)의 시험은 §12-6 에서
메뉴명 번역을 빼며 지웠다 — 남은 것은 브랜드·구분 기호 결함이다.
"""

import pathlib
import unittest

from tools.poi import names as N

_DATA = pathlib.Path("tools/poi/data")


def _brand_rows():
    rows = []
    lines = (_DATA / "brands.tsv").read_text(encoding="utf-8").splitlines()
    header_seen = False
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if not header_seen:
            header_seen = True
            continue
        # 칸: ko, en, source(근거 주소)
        cells = line.split("\t")
        ko, en = cells[0], cells[1]
        if not en:
            # §12-7: 영어 칸이 빈 줄은 「브랜드 아님」 이다(샐러디아) — 브랜드 시험의 대상이 아니다
            continue
        rows.append((ko, en))
    return rows


class _Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.d = N.Dictionaries.load(_DATA)

    def en(self, name, category):
        return N.english_name(name, category, self.d)


class RegressionBrandBareJeom(_Base):
    """결함 3 — 브랜드 뒤 「점」 한 글자는 로마자(Jeom)로 두지 않고 버린다."""

    def test_bbq_chicken_jeom(self):
        self.assertEqual(self.en("비비큐치킨점", "치킨"), ("BBQ Chicken", "brand"))

    def test_starbucks_jeom(self):
        self.assertEqual(self.en("스타벅스점", "카페"), ("Starbucks", "brand"))

    def test_every_brand_plus_bare_jeom(self):
        for ko, en in _brand_rows():
            with self.subTest(brand=ko):
                english, source = self.en(ko + "점", "음식점")
                self.assertEqual(source, "brand")
                self.assertFalse(english.endswith("Jeom"), english)
                self.assertNotIn("Jeom", english)
                self.assertEqual(english, en)

    def test_branch_still_works(self):
        self.assertEqual(
            self.en("스타벅스구리갈매역점", "카페"),
            ("Starbucks Gurigalmaeyeok Branch", "brand"),
        )

    def test_main_branch_still_works(self):
        english, source = self.en("스타벅스본점", "카페")
        self.assertEqual(source, "brand")
        self.assertEqual(english, "Starbucks Main Branch")

    def test_bare_brand(self):
        self.assertEqual(self.en("스타벅스", "카페"), ("Starbucks", "brand"))


class RegressionSeparatorSpacing(_Base):
    """결함 4 — 앞부분이 / ; , & · + 로 끝나면 영어 낱말 앞을 띄우지 않는다."""

    def test_yeogwan_slash_motel(self):
        self.assertEqual(self.en("여관/모텔", "모텔"), ("Yeogwan/Motel", "generic"))

    def test_each_separator_generic(self):
        for sep in ("/", ";", ",", "&", "·", "+"):
            with self.subTest(sep=sep):
                self.assertEqual(
                    self.en(f"여관{sep}모텔", "모텔"),
                    (f"Yeogwan{sep}Motel", "generic"),
                )

    def test_each_separator_dish_pair_has_no_english(self):
        # §12-6: 한식 메뉴명은 번역하지 않는다 — 구분 기호가 있어도 영어 이름이 없다.
        for sep in ("/", ";", ",", "&", "·", "+"):
            with self.subTest(sep=sep):
                self.assertEqual(self.en(f"국수{sep}김밥", "한식"), (None, None))

    def test_separator_cafe_bakery(self):
        self.assertEqual(self.en("카페&베이커리", "카페"), ("Kape&Bakery", "generic"))

    def test_separator_pizza_chicken(self):
        self.assertEqual(self.en("피자+치킨", "치킨"), ("Pija+Chicken", "generic"))

    def test_no_separator_keeps_space(self):
        self.assertEqual(self.en("여관모텔", "모텔"), ("Yeogwan Motel", "generic"))


if __name__ == "__main__":
    unittest.main()
