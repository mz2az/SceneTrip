"""가게 이름 — 명세(계획 §12)와 국어의 로마자 표기법만 보고 짠 시험.

구현을 보지 않고 docs/project/plans/poi-i18n-image.md §12 와 문화체육관광부 고시 제2014-42호에서 기대값을 뽑았다.
명세가 말하지 않는 부분을 짐작한 시험은 이름을 `test_assumed_` 로 시작한다.
"""

import pathlib
import unittest

from tools.poi import names as N

DATA_DIR = pathlib.Path("tools/poi/data")


def _tsv_rows(path, key_col, en_col):
    """사전 TSV 의 (ko, en) 줄 — 주석과 머리줄은 뺀다."""
    rows = []
    header_seen = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        if not header_seen:
            header_seen = True
            continue
        cells = line.split("\t")
        rows.append((cells[key_col], cells[en_col]))
    return rows


class SpecRomanizeExamples(unittest.TestCase):
    """§12-2 와 §12 본문이 직접 든 예."""

    def check(self, cases):
        for ko, want in cases:
            with self.subTest(ko=ko):
                self.assertEqual(N.romanize(ko), want)

    def test_spec_sound_change_examples(self):
        self.check(
            [
                ("왕십리", "Wangsimni"),  # 비음화
                ("신라", "Silla"),  # 유음화
                ("같이", "Gachi"),  # 구개음화, 첫 글자 대문자
                ("묵호", "Mukho"),  # 체언 ㄱ 뒤 ㅎ 은 적는다
            ]
        )

    def test_spec_loanword_examples(self):
        self.check([("스타벅스", "Seutabeokseu"), ("더테이블", "Deoteibeul")])

    def test_spec_latin_and_digits_kept_and_spaced(self):
        self.check([("K모텔", "K Motel"), ("7080라이브클럽", "7080 Raibeukeulleop")])

    def test_spec_no_word_boundary_guessing(self):
        self.check([("도시어부", "Dosieobu")])

    def test_spec_placeholder_name_is_empty(self):
        self.assertFalse(N.romanize("업소명없음"))


class SpecRomanizeStandard(unittest.TestCase):
    """고시가 요구하는 그 밖의 규칙 — 예는 대부분 고시 본문의 예."""

    def check(self, cases):
        for ko, want in cases:
            with self.subTest(ko=ko):
                self.assertEqual(N.romanize(ko), want)

    def test_initial_vs_final_consonants(self):
        # 제1장 제2항 붙임 1: ㄱ·ㄷ·ㅂ 은 모음 앞 g·d·b, 자음 앞·끝 k·t·p
        self.check(
            [
                ("구미", "Gumi"),
                ("영동", "Yeongdong"),
                ("백암", "Baegam"),
                ("옥천", "Okcheon"),
                ("합덕", "Hapdeok"),
                ("호법", "Hobeop"),
                ("월곶", "Wolgot"),
                ("벚꽃", "Beotkkot"),
                ("한밭", "Hanbat"),
            ]
        )

    def test_rieul(self):
        # 붙임 2: 모음 앞 r, 자음 앞·끝 l, ㄹㄹ 은 ll
        self.check(
            [
                ("구리", "Guri"),
                ("설악", "Seorak"),
                ("칠곡", "Chilgok"),
                ("임실", "Imsil"),
                ("울릉", "Ulleung"),
                ("대관령", "Daegwallyeong"),
            ]
        )

    def test_nasalization(self):
        self.check(
            [
                ("백마", "Baengma"),
                ("종로", "Jongno"),
                ("독립", "Dongnip"),
                ("국물", "Gungmul"),
                ("강릉", "Gangneung"),
            ]
        )

    def test_lateralization(self):
        self.check([("별내", "Byeollae"), ("난로", "Nallo")])

    def test_palatalization(self):
        self.check([("해돋이", "Haedoji"), ("맏이", "Maji")])

    def test_hieut_aspiration(self):
        # 받침 ㅎ + ㄱ·ㄷ·ㅈ → ㅋ·ㅌ·ㅊ
        self.check([("좋고", "Joko"), ("놓다", "Nota"), ("낳지", "Nachi")])

    def test_hieut_after_nominal_stop_is_written(self):
        self.check([("집현전", "Jiphyeonjeon")])

    def test_hieut_final_before_vowel_is_silent(self):
        # 표준 발음법 제12항 4: 좋아 [조아]
        self.check([("좋아", "Joa")])

    def test_tensing_not_written(self):
        self.check(
            [
                ("압구정", "Apgujeong"),
                ("낙동강", "Nakdonggang"),
                ("죽변", "Jukbyeon"),
                ("낙성대", "Nakseongdae"),
                ("합정", "Hapjeong"),
                ("팔당", "Paldang"),
                ("울산", "Ulsan"),
            ]
        )

    def test_double_initials(self):
        self.check(
            [("짬뽕", "Jjamppong"), ("떡볶이", "Tteokbokki"), ("쌈밥", "Ssambap")]
        )

    def test_double_final(self):
        self.check([("닭갈비", "Dakgalbi")])

    def test_vowels(self):
        # ㅢ 는 ㅣ 로 소리 나더라도 ui
        self.check(
            [
                ("광희문", "Gwanghuimun"),
                ("의정부", "Uijeongbu"),
                ("외계인", "Oegyein"),
                ("뒤뜰", "Dwitteul"),
                ("왜관", "Waegwan"),
                ("원주", "Wonju"),
            ]
        )

    def test_each_spaced_word_capitalized(self):
        self.check([("도시 어부", "Dosi Eobu"), ("부산 냉면", "Busan Naengmyeon")])

    def test_latin_after_hangul_is_spaced(self):
        self.check([("카페K", "Kape K"), ("BBQ치킨", "BBQ Chikin")])

    def test_assumed_latin_case_kept(self):
        # §12-2 「원래 있던 영문자는 그대로」 — 소문자를 대문자로 바꾸지 않는다고 읽었다
        self.assertEqual(N.romanize("abc모텔"), "abc Motel")

    def test_assumed_no_sound_change_across_space(self):
        # 낱말마다 따로 적는다 — 띄어 쓴 경계 너머로 유음화·연음을 하지 않는다
        self.assertEqual(N.romanize("별 나라"), "Byeol Nara")
        self.assertEqual(N.romanize("옥 이"), "Ok I")

    def test_assumed_empty_name(self):
        self.assertFalse(N.romanize(""))


class SpecEnglishName(unittest.TestCase):
    """§12-1·§12-6 — 브랜드 → 흔한 낱말, 확실하지 않으면 (None, None). 한식 메뉴명은 번역하지 않는다."""

    @classmethod
    def setUpClass(cls):
        cls.d = N.Dictionaries.load(DATA_DIR)

    def en(self, name, category):
        return N.english_name(name, category, self.d)

    # 1. 브랜드

    def test_brand_with_branch(self):
        self.assertEqual(
            self.en("스타벅스 구리갈매역점", "카페"),
            ("Starbucks Gurigalmaeyeok Branch", "brand"),
        )

    def test_brand_main_branch(self):
        self.assertEqual(
            self.en("스타벅스 본점", "카페"), ("Starbucks Main Branch", "brand")
        )

    def test_brand_alone(self):
        self.assertEqual(self.en("스타벅스", "카페"), ("Starbucks", "brand"))

    def test_brand_beats_generic_word(self):
        # 교촌치킨 은 끝이 「치킨」 이지만 브랜드가 먼저다
        self.assertEqual(
            self.en("교촌치킨 강남점", "치킨"),
            ("Kyochon Chicken Gangnam Branch", "brand"),
        )

    def test_brand_variant_spelling(self):
        self.assertEqual(
            self.en("파리바게트 역삼점", "제과점"),
            ("Paris Baguette Yeoksam Branch", "brand"),
        )

    def test_assumed_brand_longest_prefix(self):
        # 이디야 와 이디야커피 둘 다 사전에 있다 — 긴 쪽이 맞아야 「커피」 가 로마자로 붙지 않는다
        self.assertEqual(
            self.en("이디야커피 역삼점", "카페"),
            ("Ediya Coffee Yeoksam Branch", "brand"),
        )

    def test_assumed_brand_unspaced_branch(self):
        self.assertEqual(
            self.en("스타벅스구리갈매역점", "카페"),
            ("Starbucks Gurigalmaeyeok Branch", "brand"),
        )

    # 한식 메뉴명 — §12-6 에서 번역을 뺐다

    def test_dish_name_has_no_english(self):
        self.assertEqual(self.en("누리마을감자탕", "한식"), (None, None))
        self.assertEqual(self.en("할머니순대국밥", "한식"), (None, None))

    def test_dish_name_has_no_english_in_any_category(self):
        for category in ("한식", "중식", "일식", "카페", "치킨", None):
            with self.subTest(category=category):
                self.assertEqual(self.en("누리마을감자탕", category), (None, None))

    def test_tangsuyuk_not_translated(self):
        # 계획 예: 호호탕수육 — 분류와 상관없이 「수육」 을 번역하지 않는다
        for category in ("중식", "한식"):
            with self.subTest(category=category):
                self.assertEqual(self.en("호호탕수육", category), (None, None))

    # 2. 흔한 낱말

    def test_generic_word(self):
        self.assertEqual(
            self.en("행복식당", "한식"), ("Haengbok Restaurant", "generic")
        )
        self.assertEqual(self.en("행복카페", "카페"), ("Haengbok Cafe", "generic"))
        self.assertEqual(self.en("하나모텔", "모텔"), ("Hana Motel", "generic"))
        self.assertEqual(
            self.en("동구반점", "중식"), ("Donggu Chinese Restaurant", "generic")
        )

    def test_generic_word_needs_allowed_category(self):
        for name, category in [
            ("행복식당", "카페"),
            ("행복카페", "한식"),
            ("동구반점", "한식"),
            ("하나모텔", "호텔"),
        ]:
            with self.subTest(name=name, category=category):
                self.assertEqual(self.en(name, category), (None, None))

    def test_generic_without_category(self):
        self.assertEqual(self.en("행복식당", None), (None, None))

    def test_assumed_generic_spaced_name(self):
        self.assertEqual(
            self.en("행복 식당", "한식"), ("Haengbok Restaurant", "generic")
        )

    # 지점명 우선 · 주점 · 전문점

    def test_unspaced_branch_name_blocks_rule(self):
        # 아산인주점 은 「주점」 이 아니라 인주 지점
        for category in ("요리 주점", "생맥주 전문", "한식"):
            with self.subTest(category=category):
                self.assertEqual(self.en("아산인주점", category), (None, None))

    def test_unspaced_branch_after_generic_word(self):
        self.assertEqual(self.en("행복치킨역삼점", "치킨"), (None, None))

    def test_jujeom_is_not_a_generic_word(self):
        self.assertEqual(self.en("행복주점", "요리 주점"), (None, None))

    def test_assumed_jeonmunjeom_is_not_a_branch(self):
        # 「전문점 은 지점이 아니다」 — 번역하든 안 하든 Branch 로 옮기지 않는다
        name, _ = self.en("족발전문점", "한식")
        self.assertNotIn("Branch", name or "")

    # 3. 나머지

    def test_unknown_name_has_no_english(self):
        self.assertEqual(self.en("도시어부", "한식"), (None, None))

    def test_assumed_placeholder_name_has_no_english(self):
        self.assertEqual(self.en("업소명없음", "한식"), (None, None))

    def test_source_values(self):
        for name, category in [
            ("스타벅스", "카페"),
            ("누리마을감자탕", "한식"),
            ("행복식당", "한식"),
            ("도시어부", "한식"),
        ]:
            with self.subTest(name=name):
                en, source = self.en(name, category)
                self.assertIn(source, {"brand", "generic", None})
                self.assertEqual(en is None, source is None)


class SpecNoDishTranslation(unittest.TestCase):
    """§12-6 — 한식 메뉴명 번역은 뺐다. 앞선 검증들이 찾은 오역은 모두 영어 이름이 없어야 한다."""

    @classmethod
    def setUpClass(cls):
        cls.d = N.Dictionaries.load(DATA_DIR)

    def en(self, name, category="한식"):
        return N.english_name(name, category, self.d)

    def test_documented_mistranslations_have_no_english(self):
        for name in [
            # §12-4
            "관악곱창볶음",
            "돈수육",
            "돼지곰탕",
            "돼지갈비막창구이",
            "횡성한우생삼겹살",
            "곱창구이",
            "막창구이",
            # §12-5
            "시장국밥",
            "명태간장조림",
            "행복튀김",
            "콩나물",
            "닭목살구이",
            "흑돼지떡갈비",
            "시장칼국수",
            "평양곰탕",
            # §12-6
            "매운등갈비찜",
            "연어육회",
            "꼬꼬닭매운탕",
            "닭보쌈",
            "마을교육회",
        ]:
            with self.subTest(name=name):
                self.assertEqual(self.en(name), (None, None))

    def test_every_hansik800_dish_is_not_translated(self):
        # 800 메뉴 어느 것으로 끝나도 메뉴 영어가 나오지 않는다. 메뉴 이름이 우연히 흔한 낱말로 끝나면
        # 흔한 낱말 경로는 탈 수 있으므로, 출처가 hansik 이 아니고 메뉴 영어가 들어가지 않는지만 본다.
        rows = _tsv_rows(DATA_DIR / "hansik800.tsv", 2, 4)
        self.assertGreater(len(rows), 700)
        for ko, dish_en in rows:
            for name in (ko.replace(" ", ""), "가나" + ko.replace(" ", "")):
                with self.subTest(name=name):
                    english, source = self.en(name)
                    self.assertIn(source, {"generic", None})
                    if english is not None:
                        self.assertNotIn(dish_en, english)

    def test_menu_words_on_generic_path_still_work(self):
        # 근접 사례: 흔한 낱말 경로는 남는다 — 음식 이름이 앞에 있어도 끝이 흔한 낱말이면 번역된다.
        self.assertEqual(self.en("감자탕식당"), ("Gamjatang Restaurant", "generic"))
        self.assertEqual(
            self.en("황해도마라탕", "중식"), ("Hwanghaedo Malatang", "generic")
        )


class SpecBrandBoundary(unittest.TestCase):
    """§12-6 「브랜드 경계」 — 브랜드 바로 뒤가 이름 끝·띄어쓰기·붙여 쓴 `…점`·구분 기호일 때만 브랜드.
    뒤 낱말이 `…점` 이면 첫 낱말에 붙은 나머지와 함께 지점으로 본다."""

    @classmethod
    def setUpClass(cls):
        cls.d = N.Dictionaries.load(DATA_DIR)
        # §12-7: 영어 칸이 빈 줄은 「브랜드 아님」 이다(샐러디아) — 브랜드로 걸리는 줄만 본다
        cls.brands = [
            (ko, en) for ko, en in _tsv_rows(DATA_DIR / "brands.tsv", 0, 1) if en
        ]

    def en(self, name, category):
        return N.english_name(name, category, self.d)

    def assertNotBrand(self, name, category):
        _, source = self.en(name, category)
        self.assertNotEqual(source, "brand", name)

    # 브랜드가 다른 가게 이름의 머리와 겹칠 뿐인 경우

    def test_documented_non_brands(self):
        for name, category in [
            ("커피빈스", "카페"),
            ("샐러디아 강남", "양식"),
            ("맥도날드빌딩", "패스트푸드"),
        ]:
            with self.subTest(name=name):
                self.assertEqual(self.en(name, category), (None, None))

    def test_glued_branch_without_jeom_has_no_english(self):
        # 대가로 「점」 없이 붙여 쓴 지점은 영어 이름이 없다
        for name in (
            "파리바게뜨평촌귀인",
            "파리바게뜨원주센트럴파크",
            "메가엠지씨커피야당",
        ):
            with self.subTest(name=name):
                self.assertEqual(self.en(name, "제과점"), (None, None))

    def test_glued_rest_followed_by_non_branch_word(self):
        # 뒤 낱말이 `…점` 이 아니면 경계가 아니다
        for name in (
            "파리바게뜨평촌귀인 2층",
            "메가엠지씨커피야당 중앙",
            "커피빈스 커피",
        ):
            with self.subTest(name=name):
                self.assertNotBrand(name, "카페")

    def test_failed_brand_falls_through_to_generic(self):
        # 브랜드가 걸리지 않으면 다음 갈래(흔한 낱말)로 간다 — 브랜드 영어는 섞이지 않는다
        self.assertEqual(
            self.en("샐러디아카페", "카페"), ("Saelleodia Cafe", "generic")
        )
        self.assertEqual(
            self.en("커피빈스커피", "카페"), ("Keopibinseu Coffee", "generic")
        )

    def test_every_brand_glued_to_other_letters_is_not_brand(self):
        for ko, _ in self.brands:
            if "&" in ko:
                # 본죽&비빔밥스 는 짧은 브랜드 본죽 뒤가 구분 기호 `&` 라 본죽으로 경계가 맞는다 — 이 시험의 대상이 아니다
                continue
            for rest in ("스", "아", "빌딩", "가나다"):
                with self.subTest(name=ko + rest):
                    self.assertNotBrand(ko + rest, "음식점")

    # 경계가 맞는 경우

    def test_every_brand_at_each_boundary(self):
        for ko, en in self.brands:
            for name, want in [
                (ko, en),
                (ko + " 가나점", en + " Gana Branch"),
                (ko + "가나점", en + " Gana Branch"),
                (ko + "본점", en + " Main Branch"),
                (ko + "(가나점)", en),
                (ko + ";가나", en),
            ]:
                with self.subTest(name=name):
                    self.assertEqual(self.en(name, "음식점"), (want, "brand"))

    def test_branch_spanning_two_words(self):
        self.assertEqual(
            self.en("메가엠지씨커피야당 중앙점", "카페"),
            ("Mega MGC Coffee Yadang Jungang Branch", "brand"),
        )

    def test_glued_branch_with_jeom(self):
        name, source = self.en("파리바게뜨평촌귀인점", "제과점")
        self.assertEqual(source, "brand")
        self.assertTrue(name.startswith("Paris Baguette "), name)
        self.assertTrue(name.endswith(" Branch"), name)

    def test_separator_ends_the_shop_name(self):
        self.assertEqual(
            self.en("처갓집양념치킨;명가치킨", "치킨"), ("Cheogajip Chicken", "brand")
        )
        self.assertEqual(
            self.en("비비큐치킨(산척점)", "치킨"), ("BBQ Chicken", "brand")
        )
        for sep in ("(", ")", "[", "]", ";", "/", ",", "&", "·", "+", "."):
            with self.subTest(sep=sep):
                self.assertEqual(
                    self.en(f"스타벅스{sep}가나", "카페"), ("Starbucks", "brand")
                )

    def test_separator_inside_a_brand_name_keeps_the_longer_brand(self):
        # 본죽&비빔밥 은 그 자체가 사전의 브랜드 — `&` 에서 끊어 Bonjuk 으로 줄이지 않는다
        self.assertEqual(
            self.en("본죽&비빔밥 가나점", "한식"),
            ("Bonjuk & Bibimbap Gana Branch", "brand"),
        )

    def test_spaced_brand_followed_by_word(self):
        # 띄어쓰기는 경계다 — 뒤 낱말이 무엇이든 브랜드
        name, source = self.en("샐러디 강남", "양식")
        self.assertEqual(source, "brand")
        self.assertTrue(name.startswith("Saladdy"), name)

    def test_brand_appearing_later_in_name_is_not_brand(self):
        # 브랜드는 이름 머리에서만 본다
        self.assertNotBrand("가나스타벅스", "카페")
        self.assertNotBrand("우리동네 스타벅스", "카페")
