"""명세에서만 끌어낸 시험 — docs/project/plans/poi-i18n-image.md §6-3·§11.

구현을 보지 않고 계획 문서의 문장과 예시만으로 썼다. 각 시험 위에 근거가 된 명세 구절을 적는다.
"""

import json
import tempfile
import unittest
from pathlib import Path

from tools.poi import addresses as A
from tools.poi.juso_api import JusoApi, accept


def eng_row(
    *,
    sido,
    sigungu,
    emd,
    road,
    main,
    sub="0",
    underground="0",
    road_code="111103100014",
    building_id="1111010100100010000000001",
):
    """영문도로명주소DB 한 줄(18 칸). 명세가 말하는 칸만 채운다."""
    cols = [""] * A.ENG_COLUMNS
    cols[A.ENG_LEGAL_DONG_CODE] = "1111010100"
    cols[A.ENG_SIDO] = sido
    cols[A.ENG_SIGUNGU] = sigungu
    cols[A.ENG_EUPMYEONDONG] = emd
    cols[A.ENG_SAN] = "0"
    cols[A.ENG_JIBUN_MAIN] = "1"
    cols[A.ENG_JIBUN_SUB] = "0"
    cols[A.ENG_ROAD_CODE] = road_code
    cols[A.ENG_ROAD] = road
    cols[A.ENG_UNDERGROUND] = underground
    cols[A.ENG_BUILDING_MAIN] = main
    cols[A.ENG_BUILDING_SUB] = sub
    cols[A.ENG_BUILDING_ID] = building_id
    cols[A.ENG_REPRESENTATIVE_JIBUN] = "1"
    return cols


class SpecFormatEnglish(unittest.TestCase):
    """§6-3: 표기는 `{본번[-부번]} {영문 도로명}, {읍·면이면 읍면}, {시군구}, {시도}`, 번지 뒤 쉼표 없음."""

    def test_column_count_is_18(self):
        # §6-3 「공개 「영문도로명주소DB 활용가이드」 의 18 칸」
        self.assertEqual(A.ENG_COLUMNS, 18)

    def test_official_form_drops_dong(self):
        # §6-3·§11 `55 Hangang-daero 23-gil, Yongsan-gu, Seoul`
        cols = eng_row(
            sido="Seoul",
            sigungu="Yongsan-gu",
            emd="Hangangno 2(i)-ga",
            road="Hangang-daero 23-gil",
            main="55",
        )
        self.assertEqual(
            A.format_english(cols), "55 Hangang-daero 23-gil, Yongsan-gu, Seoul"
        )

    def test_no_comma_after_number(self):
        cols = eng_row(
            sido="Seoul",
            sigungu="Jung-gu",
            emd="Taepyeongno 1(il)-ga",
            road="Sejong-daero",
            main="110",
        )
        self.assertTrue(A.format_english(cols).startswith("110 Sejong-daero, "))

    def test_sub_number_is_hyphenated(self):
        # §6-3 `{본번[-부번]}`
        cols = eng_row(
            sido="Seoul",
            sigungu="Jung-gu",
            emd="Taepyeongno 1(il)-ga",
            road="Sejong-daero",
            main="110",
            sub="1",
        )
        self.assertEqual(A.format_english(cols), "110-1 Sejong-daero, Jung-gu, Seoul")

    def test_sub_zero_is_omitted(self):
        cols = eng_row(
            sido="Seoul",
            sigungu="Jung-gu",
            emd="Myeong-dong",
            road="Myeongdong-gil",
            main="7",
            sub="0",
        )
        self.assertEqual(A.format_english(cols), "7 Myeongdong-gil, Jung-gu, Seoul")

    def test_underground_prefix_b(self):
        # API 확인 표기 `B396 Gangnam-daero, Gangnam-gu, Seoul`
        cols = eng_row(
            sido="Seoul",
            sigungu="Gangnam-gu",
            emd="Yeoksam-dong",
            road="Gangnam-daero",
            main="396",
            underground="1",
        )
        self.assertEqual(
            A.format_english(cols), "B396 Gangnam-daero, Gangnam-gu, Seoul"
        )

    def test_eup_kept(self):
        # §6-3 `{읍·면이면 읍면}` — `700 Sinsaembat-ro, Sinbuk-eup, Chuncheon-si, Gangwon-do`
        cols = eng_row(
            sido="Gangwon-do",
            sigungu="Chuncheon-si",
            emd="Sinbuk-eup",
            road="Sinsaembat-ro",
            main="700",
        )
        self.assertEqual(
            A.format_english(cols),
            "700 Sinsaembat-ro, Sinbuk-eup, Chuncheon-si, Gangwon-do",
        )

    def test_myeon_kept(self):
        # §6-3 「읍·면이면 읍면」 — 면도 읍과 같다
        cols = eng_row(
            sido="Gangwon-do",
            sigungu="Pyeongchang-gun",
            emd="Jinbu-myeon",
            road="Odaesan-ro",
            main="164",
        )
        self.assertEqual(
            A.format_english(cols),
            "164 Odaesan-ro, Jinbu-myeon, Pyeongchang-gun, Gangwon-do",
        )

    def test_no_sigungu_sejong(self):
        # `194 Jeoljae-ro, Sejong-si` — 시군구가 비면 빈 칸·이중 쉼표가 없다
        cols = eng_row(
            sido="Sejong-si",
            sigungu="",
            emd="Boram-dong",
            road="Jeoljae-ro",
            main="194",
        )
        self.assertEqual(A.format_english(cols), "194 Jeoljae-ro, Sejong-si")


def store_row(
    road_code="111102005001", main="55", sub="", building_id="1117012900100550000000001"
):
    return {
        "도로명코드": road_code,
        "건물본번지": main,
        "건물부번지": sub,
        "건물관리번호": building_id,
    }


class SpecStoreKeys(unittest.TestCase):
    """§6-3·§11: 상가정보는 도로명코드 + 건물번호가 먼저, 건물관리번호는 보조."""

    def test_both_keys(self):
        key, building = A.store_keys(store_row(sub="3"))
        self.assertEqual(key, A.RoadKey("111102005001", "55", "3"))
        self.assertEqual(building, "1117012900100550000000001")

    def test_missing_sub_is_zero(self):
        key, _ = A.store_keys(store_row(sub=""))
        self.assertEqual(key, A.RoadKey("111102005001", "55", "0"))

    def test_no_road_code_still_gives_building_id(self):
        # 보조 열쇠는 도로명 열쇠가 없어도 쓸 수 있어야 한다
        key, building = A.store_keys(store_row(road_code=""))
        self.assertIsNone(key)
        self.assertEqual(building, "1117012900100550000000001")

    def test_no_building_id(self):
        key, building = A.store_keys(store_row(building_id=""))
        self.assertEqual(key, A.RoadKey("111102005001", "55", "0"))
        self.assertIsNone(building)


class SpecRoadRefs(unittest.TestCase):
    """§6-3 1: 문장에서 「도로명 + 건물번호」 를 뽑는다(띄어쓰기 변형을 먼저 붙인다)."""

    def assertHas(self, address, ref):
        self.assertIn(ref, A.road_refs(address), msg=address)

    def test_plain(self):
        self.assertHas(
            "서울특별시 용산구 한강대로23길 55", A.RoadRef("한강대로23길", "55", "0")
        )

    def test_spaced_gil(self):
        # `한강대로 23길 55`
        self.assertHas(
            "서울특별시 용산구 한강대로 23길 55", A.RoadRef("한강대로23길", "55", "0")
        )

    def test_underground_word(self):
        # `지하 396`
        self.assertHas(
            "서울특별시 강남구 강남대로 지하 396", A.RoadRef("강남대로", "396", "0")
        )

    def test_number_glued_to_road(self):
        # `마산역광장로18`
        self.assertHas(
            "경상남도 창원시 마산회원구 마산역광장로18",
            A.RoadRef("마산역광장로", "18", "0"),
        )

    def test_sub_number(self):
        self.assertHas(
            "서울특별시 중구 세종대로 110-1", A.RoadRef("세종대로", "110", "1")
        )

    def test_trailing_building_and_floor(self):
        # §6-3 3 의 예 `… 623 (잠원동) 우일빌딩 4층`
        self.assertHas(
            "서울특별시 서초구 강남대로 623 (잠원동) 우일빌딩 4층",
            A.RoadRef("강남대로", "623", "0"),
        )

    def test_beon_gil(self):
        # §6-3 예 `25 Gumi-ro 9beon-gil` — 「번길」 도로명
        self.assertHas(
            "경기도 성남시 분당구 구미로9번길 25", A.RoadRef("구미로9번길", "25", "0")
        )

    def test_jibun_only_has_no_road(self):
        self.assertEqual(A.road_refs("서울특별시 종로구 청운동 12-3"), [])


class SpecJibunRefs(unittest.TestCase):
    """§6-3 1: 도로명이 없는 지번 주소는 영문 DB 의 지번 열쇠(법정동코드·산·본번·부번)로."""

    def test_plain_jibun(self):
        refs = A.jibun_refs("서울특별시 종로구 청운동 12-3", frozenset({"청운동"}))
        self.assertIn(A.JibunRef("청운동", "0", "12", "3"), refs)

    def test_no_sub(self):
        refs = A.jibun_refs("서울특별시 종로구 청운동 12", frozenset({"청운동"}))
        self.assertIn(A.JibunRef("청운동", "0", "12", "0"), refs)

    def test_san(self):
        refs = A.jibun_refs("서울특별시 종로구 청운동 산 12-3", frozenset({"청운동"}))
        self.assertIn(A.JibunRef("청운동", "1", "12", "3"), refs)

    def test_san_glued(self):
        refs = A.jibun_refs("서울특별시 종로구 청운동 산12", frozenset({"청운동"}))
        self.assertIn(A.JibunRef("청운동", "1", "12", "0"), refs)

    def test_unknown_dong(self):
        # 법정동명 → 법정동코드 사전에 없으면 열쇠를 만들 수 없다
        self.assertEqual(
            A.jibun_refs("서울특별시 종로구 청운동 12-3", frozenset({"효자동"})), []
        )


class SpecCutToBuildingNumber(unittest.TestCase):
    """§6-3 3: `… 623 (잠원동) 우일빌딩 4층` → `… 623`."""

    def test_spec_example(self):
        self.assertEqual(
            A.cut_to_building_number(
                "서울특별시 서초구 강남대로 623 (잠원동) 우일빌딩 4층"
            ),
            "서울특별시 서초구 강남대로 623",
        )

    def test_keeps_sub_number(self):
        self.assertEqual(
            A.cut_to_building_number("서울특별시 중구 세종대로 110-1, 2층"),
            "서울특별시 중구 세종대로 110-1",
        )

    def test_already_cut(self):
        self.assertEqual(
            A.cut_to_building_number("서울특별시 서초구 강남대로 623"),
            "서울특별시 서초구 강남대로 623",
        )


class SpecAccept(unittest.TestCase):
    """§6-3 2·§11: 결과 1 건이거나 같은 주소가 겹친 것만 받는다."""

    def test_zero(self):
        self.assertIsNone(accept([]))

    def test_one(self):
        self.assertEqual(
            accept(["55 Hangang-daero 23-gil, Yongsan-gu, Seoul"]),
            "55 Hangang-daero 23-gil, Yongsan-gu, Seoul",
        )

    def test_duplicates_of_same_address(self):
        a = "55 Hangang-daero 23-gil, Yongsan-gu, Seoul"
        self.assertEqual(accept([a, a, a]), a)

    def test_different_addresses_rejected(self):
        # §6-3 3: 경주 `용담로 107` 이 제주로 나온 것 같은 다른 결과는 받지 않는다
        self.assertIsNone(
            accept(
                [
                    "107 Yongdam-ro, Gyeongju-si, Gyeongsangbuk-do",
                    "107 Yongdam-ro, Jeju-si, Jeju-do",
                ]
            )
        )


def api_response(*road_addrs, code="0"):
    return {
        "results": {
            "common": {
                "errorCode": code,
                "errorMessage": "정상",
                "totalCount": str(len(road_addrs)),
            },
            "juso": [{"roadAddr": a} for a in road_addrs],
        }
    }


class SpecJusoApiCache(unittest.TestCase):
    """§11: API 결과는 결과 폴더의 캐시 파일에 남겨 다시 돌릴 때 부르지 않는다."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.cache = Path(self.dir.name) / "cache.json"

    def tearDown(self):
        self.dir.cleanup()

    def test_search_returns_road_addrs(self):
        api = JusoApi(
            "k", self.cache, fetch=lambda url: api_response("A 1", "B 2"), pause=0
        )
        self.assertEqual(api.search("서울 어딘가 1"), ["A 1", "B 2"])
        self.assertEqual(api.calls, 1)

    def test_zero_results(self):
        api = JusoApi("k", self.cache, fetch=lambda url: api_response(), pause=0)
        self.assertEqual(api.search("없는 주소 1"), [])

    def test_same_keyword_is_not_fetched_twice(self):
        api = JusoApi("k", self.cache, fetch=lambda url: api_response("A 1"), pause=0)
        api.search("서울 어딘가 1")
        self.assertEqual(api.search("서울 어딘가 1"), ["A 1"])
        self.assertEqual(api.calls, 1)

    def test_cache_survives_restart(self):
        first = JusoApi("k", self.cache, fetch=lambda url: api_response("A 1"), pause=0)
        first.search("서울 어딘가 1")
        first.save()

        def boom(url):
            raise AssertionError("캐시에 있는 검색어로 API 를 불렀다")

        second = JusoApi("k", self.cache, fetch=boom, pause=0)
        self.assertEqual(second.search("서울 어딘가 1"), ["A 1"])
        self.assertEqual(second.calls, 0)

    def test_zero_result_is_cached_too(self):
        # 다시 돌릴 때 부르지 않는다 — 0 건도 결과다
        first = JusoApi("k", self.cache, fetch=lambda url: api_response(), pause=0)
        first.search("없는 주소 1")
        first.save()

        def boom(url):
            raise AssertionError("캐시에 있는 검색어로 API 를 불렀다")

        second = JusoApi("k", self.cache, fetch=boom, pause=0)
        self.assertEqual(second.search("없는 주소 1"), [])

    def test_different_keywords_are_separate(self):
        seen = []

        def fetch(url):
            seen.append(url)
            return api_response(f"R{len(seen)}")

        api = JusoApi("k", self.cache, fetch=fetch, pause=0)
        self.assertEqual(api.search("하나 1"), ["R1"])
        self.assertEqual(api.search("둘 2"), ["R2"])
        self.assertEqual(api.calls, 2)

    def test_key_is_not_written_to_cache(self):
        # §6-3 「API 키는 저장소에 두지 않는다(환경변수 `JUSO_ENG_API_KEY`)」 — 캐시 파일에도 남기지 않는다(추론)
        secret = "devSECRETKEY0123456789"
        api = JusoApi(
            secret, self.cache, fetch=lambda url: api_response("A 1"), pause=0
        )
        api.search("서울 어딘가 1")
        api.save()
        self.assertTrue(self.cache.exists())
        self.assertNotIn(secret, self.cache.read_text(encoding="utf-8"))
        json.loads(
            self.cache.read_text(encoding="utf-8")
        )  # 사람이 열어 볼 수 있는 JSON


if __name__ == "__main__":
    unittest.main()
