"""주소 쪼개기와 영문 조립 — 실측(계획 §6-3)에서 만난 꼴을 그대로 시험한다."""

import unittest

from tools.poi import addresses as A


def eng(**over):
    """영문 DB 한 줄(18 칸). 기본은 서울 용산구 한강대로23길 55."""
    cols = [""] * A.ENG_COLUMNS
    cols[A.ENG_SIDO] = "Seoul"
    cols[A.ENG_SIGUNGU] = "Yongsan-gu"
    cols[A.ENG_EUPMYEONDONG] = "Hangangno 3(sam)-ga"
    cols[A.ENG_ROAD] = "Hangang-daero 23-gil"
    cols[A.ENG_UNDERGROUND] = "0"
    cols[A.ENG_BUILDING_MAIN] = "55"
    cols[A.ENG_BUILDING_SUB] = "0"
    for k, v in over.items():
        cols[getattr(A, "ENG_" + k.upper())] = v
    return cols


class FormatEnglish(unittest.TestCase):
    def test_official_form_has_no_comma_after_number(self):
        # API 응답과 같은 꼴 — 「55 Hangang-daero 23-gil, Yongsan-gu, Seoul」.
        self.assertEqual(
            A.format_english(eng()), "55 Hangang-daero 23-gil, Yongsan-gu, Seoul"
        )

    def test_sub_number(self):
        self.assertEqual(A.format_english(eng(building_sub="19"))[:5], "55-19")

    def test_underground_gets_b(self):
        self.assertEqual(
            A.format_english(
                eng(
                    underground="1",
                    building_main="396",
                    road="Gangnam-daero",
                    sigungu="Gangnam-gu",
                )
            ),
            "B396 Gangnam-daero, Gangnam-gu, Seoul",
        )

    def test_eup_myeon_is_kept_dong_is_not(self):
        e = eng(
            sido="Gangwon-do",
            sigungu="Chuncheon-si",
            eupmyeondong="Sinbuk-eup",
            road="Sinsaembat-ro",
            building_main="700",
        )
        self.assertEqual(
            A.format_english(e),
            "700 Sinsaembat-ro, Sinbuk-eup, Chuncheon-si, Gangwon-do",
        )

    def test_no_sigungu_in_sejong(self):
        e = eng(
            sido="Sejong-si",
            sigungu="",
            eupmyeondong="Eojin-dong",
            road="Jeoljae-ro",
            building_main="194",
        )
        self.assertEqual(A.format_english(e), "194 Jeoljae-ro, Sejong-si")


class StoreKeys(unittest.TestCase):
    def test_road_key_and_building_id(self):
        road, building = A.store_keys(
            {
                "도로명코드": "517303226016",
                "건물본번지": "66",
                "건물부번지": "",
                "건물관리번호": "4273033027104120011031432",
            }
        )
        self.assertEqual(road, A.RoadKey("517303226016", "66", "0"))
        self.assertEqual(building, "4273033027104120011031432")

    def test_missing_fields(self):
        self.assertEqual(A.store_keys({}), (None, None))


class RoadRefs(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(
            A.road_refs("충청남도 공주시 감영길 3 (반죽동)"),
            [A.RoadRef("감영길", "3", "0")],
        )

    def test_spaced_gil_is_joined(self):
        self.assertEqual(
            A.road_refs("서울특별시 용산구 한강대로 23길 55"),
            [A.RoadRef("한강대로23길", "55", "0")],
        )

    def test_underground_word_is_dropped(self):
        self.assertEqual(
            A.road_refs("서울특별시 강남구 강남대로 지하 396"),
            [A.RoadRef("강남대로", "396", "0")],
        )
        self.assertEqual(
            A.road_refs("수성구 달구벌대로 지하3290 (신매동)"),
            [A.RoadRef("달구벌대로", "3290", "0")],
        )

    def test_glued_number_is_split(self):
        self.assertEqual(
            A.road_refs("경남 창원시 마산회원구 마산역광장로18"),
            [A.RoadRef("마산역광장로", "18", "0")],
        )

    def test_numbered_road_name_is_not_split(self):
        # 「지리산대로1478번길」 은 도로명 자체다 — 쪼개면 안 된다(실측에서 한 번 깨졌다).
        self.assertEqual(
            A.road_refs("경상남도 산청군 시천면 지리산대로1478번길 31-10"),
            [A.RoadRef("지리산대로1478번길", "31", "10")],
        )

    def test_beonji_suffix_and_nbsp(self):
        self.assertEqual(
            A.road_refs("부산광역시 강서구 공항진입로 108번지"),
            [A.RoadRef("공항진입로", "108", "0")],
        )
        self.assertEqual(
            A.road_refs("인천광역시\xa0중구\xa0백운로414번길 75"),
            [A.RoadRef("백운로414번길", "75", "0")],
        )

    def test_jibun_address_has_no_road(self):
        self.assertEqual(A.road_refs("충청북도 청주시 흥덕구 가경동 1438"), [])


class JibunRefs(unittest.TestCase):
    def test_known_dong(self):
        self.assertEqual(
            A.jibun_refs("경상북도 경주시 남산동 산36-4", {"남산동"}),
            [A.JibunRef("남산동", "1", "36", "4")],
        )

    def test_unknown_dong_is_ignored(self):
        self.assertEqual(
            A.jibun_refs("충청북도 청주시 흥덕구 가경동 1438", {"남산동"}), []
        )


class CutToBuildingNumber(unittest.TestCase):
    def test_trailing_words_are_cut(self):
        self.assertEqual(
            A.cut_to_building_number(
                "서울특별시 서초구 강남대로 623 (잠원동) 우일빌딩 4층-5층"
            ),
            "서울특별시 서초구 강남대로 623",
        )

    def test_no_road_returns_cleaned_text(self):
        self.assertEqual(
            A.cut_to_building_number("부산광역시 강서구 천성동"),
            "부산광역시 강서구 천성동",
        )


if __name__ == "__main__":
    unittest.main()
