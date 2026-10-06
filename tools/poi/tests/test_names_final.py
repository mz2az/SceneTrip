"""마지막 검증에서 고친 것의 회귀 시험 — docs/project/plans/poi-i18n-image.md §12-7.

구현을 보지 않고 계획 문서의 표와 사전(tools/poi/data/brands.tsv)만 보고 썼다. 명세가 정확한 값을 말하지 않는
부분은 이름을 `test_assumed_` 로 시작하고, 확실한 성질(틀린 뜻이 나오지 않는다)만 본다.
"""

import pathlib
import unittest

from tools.poi import names as N

_DATA = pathlib.Path("tools/poi/data")

# §12-7: 지점이 아닌 「…점」 낱말과, 지점으로 잘못 읽었을 때 나오던 로마자
_NOT_BRANCH = {
    "백화점": "Baekhwa",
    "대리점": "Daeri",
    "편의점": "Pyeoni",
    "할인점": "Harin",
    "전문점": "Jeonmun",
}

# §12-7 에서 더한 브랜드 표기 넷
_ADDED = {
    "파파존스피자": "Papa John's",
    "엔제리너스커피": "Angelinus",
    "던킨도넛": "Dunkin'",
    "본죽비빔밥": "Bonjuk & Bibimbap",
}


def _brand_rows():
    """brands.tsv 의 (ko, en) 줄 — 영어 칸이 빈 줄(브랜드 아님)도 그대로 둔다."""
    rows = []
    header_seen = False
    for line in (_DATA / "brands.tsv").read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        if not header_seen:
            header_seen = True
            continue
        # 칸: ko, en, source(근거 주소) — 영어 칸이 빈 줄은 source 도 비어 있다
        cells = line.split("\t")
        ko, en = cells[0], cells[1]
        rows.append((ko, en))
    return rows


class _Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.d = N.Dictionaries.load(_DATA)

    def en(self, name, category):
        return N.english_name(name, category, self.d)


class FinalNonBranchWords(_Base):
    """「`백화점`·`대리점`·`편의점`·`할인점`·`전문점` 은 지점이 아니다 — 끝에 오면 버린다」"""

    def assertNoFakeBranch(self, name, category):
        english, _ = self.en(name, category)
        english = english or ""
        self.assertNotIn("Branch", english, name)
        for roman in _NOT_BRANCH.values():
            self.assertNotIn(roman, english, name)

    def test_spaced_word_after_brand(self):
        # `스타벅스 현대백화점 → … Baekhwa Branch` 같은 것이 다시 나오지 않는다
        for word in _NOT_BRANCH:
            for name in (f"스타벅스 {word}", f"스타벅스 현대{word}"):
                with self.subTest(name=name):
                    self.assertNoFakeBranch(name, "카페")

    def test_assumed_bare_word_after_brand_is_dropped(self):
        # 띄어 쓴 브랜드 뒤에 그 낱말 하나만 있으면 버리고 브랜드만 남는다
        for word in _NOT_BRANCH:
            with self.subTest(word=word):
                self.assertEqual(
                    self.en(f"스타벅스 {word}", "카페"), ("Starbucks", "brand")
                )

    def test_glued_word_after_brand(self):
        # 붙여 쓴 경우: 브랜드로 걸리면 브랜드 영어만, 아니면 브랜드 영어가 없다 — 어느 쪽이든 가짜 지점은 없다
        for word in _NOT_BRANCH:
            name = "스타벅스" + word
            with self.subTest(name=name):
                self.assertNoFakeBranch(name, "카페")
                english, source = self.en(name, "카페")
                if source == "brand":
                    self.assertEqual(english, "Starbucks")

    def test_every_brand_with_department_store(self):
        for ko, en in _brand_rows():
            if not en:
                continue
            for name in (f"{ko} 롯데백화점", f"{ko} 가나대리점", f"{ko}할인점"):
                with self.subTest(name=name):
                    self.assertNoFakeBranch(name, "음식점")

    def test_generic_path_has_no_fake_branch(self):
        for name, category in (
            ("족발전문점", "한식"),
            ("치킨전문점", "치킨"),
            ("피자전문점", "피자"),
            ("커피전문점", "카페"),
            ("행복편의점", "음식점"),
        ):
            with self.subTest(name=name):
                self.assertNoFakeBranch(name, category)

    def test_near_miss_real_branch_still_branch(self):
        # 근접 사례: 같은 글자로 끝나도 다섯 낱말이 아니면 지점이다(동화·문 지점)
        self.assertEqual(
            self.en("스타벅스 동화점", "카페"), ("Starbucks Donghwa Branch", "brand")
        )
        self.assertEqual(
            self.en("스타벅스 강남점", "카페"), ("Starbucks Gangnam Branch", "brand")
        )
        self.assertEqual(
            self.en("스타벅스구리갈매역점", "카페"),
            ("Starbucks Gurigalmaeyeok Branch", "brand"),
        )

    def test_romanize_keeps_the_word(self):
        # 버리는 것은 영어 이름뿐 — 로마자 읽기는 이름 전부를 읽는다
        self.assertEqual(
            N.romanize("스타벅스 현대백화점"), "Seutabeokseu Hyeondaebaekhwajeom"
        )


class FinalEmptyEnglishIsNotBrand(_Base):
    """「브랜드 사전에 영어 칸이 빈 줄은 「브랜드 아님」 이다(`샐러디아`)」"""

    def test_dictionary_has_the_documented_row(self):
        self.assertIn(("샐러디아", ""), _brand_rows())

    def test_documented_example(self):
        # `샐러디아 사천점 → Saladdy A …` (34) — 사전의 샐러디 영어는 이제 Salady
        for name in (
            "샐러디아 사천점",
            "샐러디아사천점",
            "샐러디아",
            "샐러디아점",
            "샐러디아 본점",
            "샐러디아(사천점)",
        ):
            with self.subTest(name=name):
                english, source = self.en(name, "양식")
                self.assertNotEqual(source, "brand")
                self.assertNotIn("Salady", english or "")

    def test_every_empty_row_is_never_brand(self):
        rows = _brand_rows()
        empty = [ko for ko, en in rows if not en]
        self.assertTrue(empty)
        brand_en = {en for _, en in rows if en}
        for ko in empty:
            for name in (ko, ko + "점", ko + " 가나점", ko + "가나점", ko + "본점"):
                with self.subTest(name=name):
                    english, source = self.en(name, "음식점")
                    self.assertNotEqual(source, "brand")
                    for b in brand_en:
                        self.assertNotIn(b, english or "")

    def test_near_miss_real_brand_still_brand(self):
        # 근접 사례: 더 짧은 진짜 브랜드(샐러디)는 그대로 걸린다
        self.assertEqual(
            self.en("샐러디 사천점", "양식"), ("Salady Sacheon Branch", "brand")
        )
        self.assertEqual(
            self.en("샐러디사천점", "양식"), ("Salady Sacheon Branch", "brand")
        )
        self.assertEqual(self.en("샐러디", "양식"), ("Salady", "brand"))

    def test_romanization_still_present(self):
        self.assertTrue(N.romanize("샐러디아 사천점").startswith("Saelleodia"))


class FinalAddedSpellings(_Base):
    """「브랜드 다른 표기가 사전에 없음 — `파파존스피자 → Papa John's Pija…` — 표기 넷을 더했다」"""

    def test_rows_are_in_dictionary(self):
        rows = dict(_brand_rows())
        for ko, en in _ADDED.items():
            with self.subTest(ko=ko):
                self.assertEqual(rows.get(ko), en)

    def test_documented_example(self):
        english, source = self.en("파파존스피자", "피자")
        self.assertEqual((english, source), ("Papa John's", "brand"))
        self.assertNotIn("Pija", english)

    def test_spellings_resolve_with_branches(self):
        for ko, en in _ADDED.items():
            for name, want in (
                (ko, en),
                (f"{ko} 가나점", f"{en} Gana Branch"),
                (f"{ko}가나점", f"{en} Gana Branch"),
                (f"{ko}본점", f"{en} Main Branch"),
            ):
                with self.subTest(name=name):
                    english, source = self.en(name, "음식점")
                    self.assertEqual((english, source), (want, "brand"))


class FinalNumberedBranch(_Base):
    """「번호 지점 — `옥정 2호점 → 2 Ho Branch` (925) → `Okjeong No. 2 Branch`」"""

    def test_documented_example(self):
        self.assertEqual(
            self.en("스타벅스 옥정 2호점", "카페"),
            ("Starbucks Okjeong No. 2 Branch", "brand"),
        )

    def test_no_ho_branch_anywhere(self):
        for name in (
            "스타벅스 옥정 2호점",
            "스타벅스 2호점",
            "스타벅스 옥정2호점",
            "스타벅스옥정2호점",
            "스타벅스 옥정 12호점",
            "비비큐치킨 구리 3호점",
            "교촌치킨 1호점",
        ):
            with self.subTest(name=name):
                english, source = self.en(name, "음식점")
                self.assertEqual(source, "brand")
                self.assertNotIn(" Ho ", english)
                self.assertNotIn("Ho Branch", english)
                self.assertTrue(english.endswith(" Branch"), english)
                self.assertIn("No. ", english)

    def test_assumed_number_only(self):
        self.assertEqual(
            self.en("스타벅스 2호점", "카페"), ("Starbucks No. 2 Branch", "brand")
        )

    def test_assumed_multi_digit(self):
        self.assertEqual(
            self.en("스타벅스 옥정 12호점", "카페"),
            ("Starbucks Okjeong No. 12 Branch", "brand"),
        )

    def test_assumed_glued_number(self):
        self.assertEqual(
            self.en("스타벅스옥정2호점", "카페"),
            ("Starbucks Okjeong No. 2 Branch", "brand"),
        )

    def test_near_miss_ho_syllable_without_number(self):
        # 근접 사례: 숫자 없는 「…호점」 은 지명이다(신호 지점) — No. 를 붙이지 않는다
        self.assertEqual(
            self.en("스타벅스 신호점", "카페"), ("Starbucks Sinho Branch", "brand")
        )


class FinalSharedStore(_Base):
    """「공유 매장(`네네치킨&봉구스밥버거 → NeNe Chicken`)은 두 번째 브랜드를 버린다」"""

    def test_documented_example(self):
        english, source = self.en("네네치킨&봉구스밥버거", "치킨")
        self.assertEqual(source, "brand")
        self.assertEqual(english, "NeNe Chicken")


if __name__ == "__main__":
    unittest.main()
