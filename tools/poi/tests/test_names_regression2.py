"""두 번째 검증의 결함을 막는 회귀 시험 — docs/project/plans/poi-i18n-image.md §12-4 「두 번째 검증」 표와
「의도한 동작으로 남긴 것」.

구현을 보지 않고 계획 문서의 문장과 예시만으로 썼다. 각 시험 위에 근거가 된 명세 구절을 적는다. 한식 메뉴명
결함(붙은 표기·고기 낱말·애매한 메뉴)의 시험은 §12-6 에서 메뉴명 번역을 빼며 지웠다.
"""

import unittest
from pathlib import Path

from tools.poi import names as N

DATA = Path("tools/poi/data")


class _Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.d = N.Dictionaries.load(DATA)

    def en(self, name, category):
        return N.english_name(name, category, self.d)


class Regression2BrandRest(_Base):
    """「브랜드 뒤 나머지에 `( ) [ ] ; /` 가 있으면 나머지를 버리고 브랜드(+지점)만」"""

    def test_documented_examples(self):
        self.assertEqual(
            self.en("처갓집양념치킨;명가치킨", "치킨"), ("Cheogajip", "brand")
        )
        self.assertEqual(self.en("비비큐치킨()", "치킨"), ("BBQ Chicken", "brand"))

    def test_each_separator(self):
        for rest in (
            "(구리점)",
            "[구리점]",
            ";교촌",
            "/교촌",
            " (갈매)",
            " 구리점(갈매)",
            " 구리점;2호",
        ):
            name = "비비큐치킨" + rest
            with self.subTest(name=name):
                en, source = self.en(name, "치킨")
                self.assertEqual(source, "brand")
                self.assertTrue(en.startswith("BBQ Chicken"), en)
                for ch in "()[];/":
                    self.assertNotIn(ch, en)
                self.assertNotIn("Gyochon", en)
                self.assertNotIn("Galmae", en)
                self.assertNotIn("2", en)

    def test_other_brand(self):
        en, source = self.en("교촌치킨[본점]", "치킨")
        self.assertEqual(source, "brand")
        self.assertTrue(en.startswith("Kyochon Chicken"))
        self.assertNotIn("[", en)

    def test_plain_branch_unaffected(self):
        # 근접 사례: 구분 기호가 없으면 지점은 그대로 Branch 가 된다.
        self.assertEqual(
            self.en("비비큐치킨 구리점", "치킨"), ("BBQ Chicken Guri Branch", "brand")
        )
        self.assertEqual(
            self.en("교촌치킨구리점", "치킨"), ("Kyochon Chicken Guri Branch", "brand")
        )


class Regression2IntendedBehaviour(_Base):
    """「의도한 동작으로 남긴 것」 — 지점 뒤 낱말은 버리고, 브랜드는 분류와 대조하지 않는다."""

    def test_text_after_branch_is_dropped(self):
        self.assertEqual(
            self.en("설빙수원청점 장안구", "카페"),
            ("Sulbing Suwoncheong Branch", "brand"),
        )
        self.assertEqual(
            self.en("스타벅스 구리갈매역점 2층", "카페"),
            ("Starbucks Gurigalmaeyeok Branch", "brand"),
        )

    def test_brand_ignores_category(self):
        self.assertEqual(self.en("던킨", "피자"), ("Dunkin'", "brand"))

    def test_romanize_keeps_the_dropped_part(self):
        # 버리는 것은 영어 이름뿐이다 — 로마자 읽기는 이름 전부를 읽는다.
        self.assertIn("Jangangu", N.romanize("설빙수원청점 장안구"))


if __name__ == "__main__":
    unittest.main()
