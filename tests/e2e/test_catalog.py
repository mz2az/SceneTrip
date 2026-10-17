"""작품·촬영지·검색·편의시설 e2e — 목록 → 상세 → 촬영지, q 검색, 추천어, Accept-Language, 없는 번호·범위 밖 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「작품·촬영지·검색」 「편의시설」. 기대값은 계약(contracts/openapi/scene-api-v1.yaml)에서 왔다.
시험 자료는 API 로 찾는다 — DB 번호를 박지 않는다. 분당 한도가 계정마다라 시험마다 새 설치 ID 로 부른다.
"""

import urllib.parse

from support import E2ETestCase, call, new_install_id

# 어느 표에도 있을 리 없는 번호(int64 안).
_MISSING_ID = 2**62


def _q(**params) -> str:
    return urllib.parse.urlencode(params)


class CatalogCase(E2ETestCase):
    def setUp(self):
        super().setUp()
        self.install = new_install_id()

    def get(self, path: str, lang: str | None = None):
        return call("GET", path, install_id=self.install, lang=lang)

    def ok(self, path: str, lang: str | None = None):
        r = self.get(path, lang)
        self.assertEqual(r.status, 200, r)
        return r

    def content_with_places(self, lang: str = "en") -> dict:
        items = self.ok("/contents?" + _q(limit=50), lang).json()["items"]
        for c in items:
            if c["placeCount"] > 0:
                return c
        self.fail("촬영지가 있는 작품이 목록 앞 50 개에 없다")

    def assert_api_error(self, r, status: int, code: str):
        self.assertEqual(r.status, status, r)
        body = r.json()
        self.assertEqual(body["code"], code, r)
        self.assertIsInstance(body["message"], str)
        self.assertTrue(body["message"])


class ContentTest(CatalogCase):
    def test_list_then_detail_then_places_agree(self):
        lst = self.ok("/contents?" + _q(limit=5), "en").json()
        self.assertEqual(lst["limit"], 5)
        self.assertEqual(lst["offset"], 0)
        self.assertTrue(lst["items"])
        self.assertLessEqual(len(lst["items"]), 5)
        self.assertGreaterEqual(lst["total"], len(lst["items"]))

        c = self.content_with_places()
        detail = self.ok(f"/contents/{c['id']}", "en").json()
        self.assertEqual(detail["id"], c["id"])
        self.assertEqual(detail["title"], c["title"])
        self.assertEqual(detail["category"], c["category"])
        self.assertEqual(detail["placeCount"], c["placeCount"])

        places = self.ok(f"/contents/{c['id']}/places?" + _q(limit=100), "en").json()
        self.assertTrue(places["items"])
        self.assertEqual(
            places["total"],
            c["placeCount"],
            "작품의 placeCount 와 촬영지 목록 total 이 다르다",
        )
        for p in places["items"]:
            self.assertIn(c["id"], [ref["contentId"] for ref in p["contents"]], p)

    def test_content_places_with_origin_fill_distance(self):
        c = self.content_with_places()
        places = self.ok(
            f"/contents/{c['id']}/places?" + _q(lat=37.5665, lng=126.978), "en"
        ).json()["items"]
        self.assertTrue(places)
        for p in places:
            self.assertIsInstance(p.get("distanceMeters"), int, p)
        without = self.ok(f"/contents/{c['id']}/places", "en").json()["items"]
        for p in without:
            self.assertIsNone(p.get("distanceMeters"), p)

    def test_unknown_content_is_404(self):
        self.assert_api_error(
            self.get(f"/contents/{_MISSING_ID}"), 404, "CONTENT_NOT_FOUND"
        )
        self.assert_api_error(
            self.get(f"/contents/{_MISSING_ID}/places"), 404, "CONTENT_NOT_FOUND"
        )


class PlaceTest(CatalogCase):
    def test_places_without_q_lists_nationwide_and_q_narrows(self):
        all_places = self.ok("/places?" + _q(limit=200), "ko").json()
        self.assertTrue(all_places["items"])
        self.assertGreaterEqual(all_places["total"], len(all_places["items"]))

        c = self.content_with_places("ko")
        expected = {
            p["id"]
            for p in self.ok(
                f"/contents/{c['id']}/places?" + _q(limit=100), "ko"
            ).json()["items"]
        }
        # 작품 제목으로 찾으면 그 작품의 촬영지가 모두 걸린다
        found = self.ok("/places?" + _q(q=c["title"], limit=200), "ko").json()
        found_ids = {p["id"] for p in found["items"]}
        self.assertTrue(
            expected <= found_ids,
            f"q={c['title']} 결과에 촬영지가 빠졌다: {expected - found_ids}",
        )
        self.assertLess(
            found["total"], all_places["total"], "q 가 결과를 좁히지 않았다"
        )

        nothing = self.ok(
            "/places?" + _q(q="zzqxw_e2e_없는말_" + self.install[:8]), "ko"
        ).json()
        self.assertEqual(nothing["items"], [])
        self.assertEqual(nothing["total"], 0)

    def test_places_content_id_filter_matches_content_places(self):
        c = self.content_with_places()
        a = {
            p["id"]
            for p in self.ok("/places?" + _q(contentId=c["id"], limit=200)).json()[
                "items"
            ]
        }
        b = {
            p["id"]
            for p in self.ok(f"/contents/{c['id']}/places?" + _q(limit=100)).json()[
                "items"
            ]
        }
        self.assertEqual(a, b)

    def test_place_detail_matches_list_entry(self):
        c = self.content_with_places()
        p = self.ok(f"/contents/{c['id']}/places?" + _q(limit=1), "en").json()["items"][
            0
        ]
        d = self.ok(f"/places/{p['id']}", "en").json()
        self.assertEqual(d["id"], p["id"])
        self.assertEqual(d["name"], p["name"])
        self.assertAlmostEqual(d["latitude"], p["latitude"], places=5)
        self.assertAlmostEqual(d["longitude"], p["longitude"], places=5)
        self.assertIn(
            c["id"],
            [s["contentId"] for s in d["scenes"]],
            "상세의 장면에 그 작품이 없다",
        )
        for s in d["scenes"]:
            self.assertIsInstance(s["sceneImageUrls"], list)
        self.assertEqual(len(d["rating"]["distribution"]), 5)
        self.assertIsInstance(d["photos"], list)

    def test_bbox_returns_only_places_inside(self):
        p = self.ok("/places?" + _q(limit=1)).json()["items"][0]
        lng, lat = p["longitude"], p["latitude"]
        box = (lng - 0.05, lat - 0.05, lng + 0.05, lat + 0.05)
        r = self.ok(
            "/places?" + _q(bbox=",".join(f"{v:.6f}" for v in box), limit=200)
        ).json()
        ids = [x["id"] for x in r["items"]]
        self.assertIn(p["id"], ids)
        for x in r["items"]:
            self.assertTrue(
                box[0] <= x["longitude"] <= box[2]
                and box[1] <= x["latitude"] <= box[3],
                x,
            )

    def test_unknown_place_is_404(self):
        self.assert_api_error(
            self.get(f"/places/{_MISSING_ID}"), 404, "PLACE_NOT_FOUND"
        )

    def test_longitude_out_of_range_is_400(self):
        r = self.get("/places?" + _q(lat=37.5, lng=200))
        self.assert_api_error(r, 400, "INVALID_PARAMETER")

    def test_bbox_longitude_out_of_range_is_400(self):
        r = self.get("/places?" + _q(bbox="126.9,37.5,200,37.6"))
        self.assertEqual(r.status, 400, r)
        self.assertIn(r.json()["code"], ("INVALID_BBOX", "INVALID_PARAMETER"))

    def test_malformed_bbox_is_400(self):
        r = self.get("/places?" + _q(bbox="126.9,37.5"))
        self.assertEqual(r.status, 400, r)
        self.assertIn(r.json()["code"], ("INVALID_BBOX", "INVALID_PARAMETER"))

    def test_bbox_with_radius_is_400(self):
        r = self.get(
            "/places?"
            + _q(bbox="126.9,37.5,127.1,37.6", lat=37.55, lng=127.0, radiusMeters=1000)
        )
        self.assert_api_error(r, 400, "CONFLICTING_AREA_FILTER")


class SearchTest(CatalogCase):
    def test_suggestions_find_content_by_title_prefix(self):
        c = self.content_with_places("ko")
        prefix = c["title"][:2]
        r = self.ok("/search/suggestions?" + _q(q=prefix, limit=20), "ko").json()
        hits = [(s["type"], s["id"]) for s in r["items"]]
        self.assertIn(
            ("content", c["id"]), hits, f"q={prefix} 제안에 {c['title']} 가 없다: {r}"
        )
        self.assertLessEqual(len(r["items"]), 20)

    def test_suggestions_limit_is_respected(self):
        r = self.ok("/search/suggestions?" + _q(q="a", limit=1)).json()
        self.assertLessEqual(len(r["items"]), 1)

    def test_suggestions_no_match_is_empty_list(self):
        r = self.ok(
            "/search/suggestions?" + _q(q="zzqxw없는말" + self.install[:8])
        ).json()
        self.assertEqual(r["items"], [])

    def test_suggestions_without_q_is_400(self):
        self.assertEqual(self.get("/search/suggestions").status, 400)


class LanguageTest(CatalogCase):
    def _content_with_distinct_titles(self) -> tuple[int, str, str]:
        ko = self.ok("/contents?" + _q(limit=50), "ko").json()["items"]
        en = {
            c["id"]: c["title"]
            for c in self.ok("/contents?" + _q(limit=50), "en").json()["items"]
        }
        for c in ko:
            if c["id"] in en and en[c["id"]] != c["title"]:
                return c["id"], c["title"], en[c["id"]]
        self.skipTest(
            "앞 50 개 작품 중 영어 제목이 한국어와 다른 작품이 없다 — 영어 자료가 없어 언어 확인을 건너뛴다"
        )

    def test_accept_language_switches_content_title_and_content_language(self):
        cid, ko_title, en_title = self._content_with_distinct_titles()
        ko = self.ok(f"/contents/{cid}", "ko")
        en = self.ok(f"/contents/{cid}", "en")
        self.assertEqual(ko.json()["title"], ko_title)
        self.assertEqual(en.json()["title"], en_title)
        self.assertEqual(ko.headers.get("content-language"), "ko")
        self.assertEqual(en.headers.get("content-language"), "en")

    def test_unsupported_or_missing_language_falls_back_to_en(self):
        cid, _, en_title = self._content_with_distinct_titles()
        for lang in ("fr", None):
            r = self.ok(f"/contents/{cid}", lang)
            self.assertEqual(r.json()["title"], en_title, f"Accept-Language={lang}")
            self.assertEqual(
                r.headers.get("content-language"), "en", f"Accept-Language={lang}"
            )

    def test_place_name_follows_language(self):
        ko = {
            p["id"]: p["name"]
            for p in self.ok("/places?" + _q(limit=50), "ko").json()["items"]
        }
        en = {
            p["id"]: p["name"]
            for p in self.ok("/places?" + _q(limit=50), "en").json()["items"]
        }
        pid = next((i for i in ko if i in en and ko[i] != en[i]), None)
        if pid is None:
            self.skipTest("영어 이름이 한국어와 다른 촬영지가 앞 50 개에 없다")
        self.assertEqual(self.ok(f"/places/{pid}", "en").json()["name"], en[pid])
        self.assertEqual(self.ok(f"/places/{pid}", "ko").json()["name"], ko[pid])


class PoiTest(CatalogCase):
    def _box_around_a_place(
        self, half: float = 0.01
    ) -> tuple[float, float, float, float, float, float]:
        p = self.ok("/places?" + _q(limit=1)).json()["items"][0]
        lng, lat = p["longitude"], p["latitude"]
        return lng - half, lat - half, lng + half, lat + half, lat, lng

    @staticmethod
    def _bbox(box) -> str:
        return ",".join(f"{v:.6f}" for v in box[:4])

    def _first_poi(self) -> tuple[dict, tuple]:
        for half in (0.01, 0.05, 0.2):
            box = self._box_around_a_place(half)
            r = self.ok("/pois?" + _q(bbox=self._bbox(box), limit=30)).json()
            if r["items"]:
                return r["items"][0], box
        self.skipTest("촬영지 곁에 편의시설이 없다 — 편의시설 자료가 적재되지 않았다")

    def test_bbox_list_then_detail_then_photos(self):
        box = self._box_around_a_place(0.02)
        lst = self.ok("/pois?" + _q(bbox=self._bbox(box), limit=30), "en").json()
        if not lst["items"]:
            self.skipTest(
                "촬영지 곁에 편의시설이 없다 — 편의시설 자료가 적재되지 않았다"
            )
        self.assertLessEqual(len(lst["items"]), 30)
        self.assertEqual(lst["limit"], 30)
        self.assertGreaterEqual(lst["total"], len(lst["items"]))
        for x in lst["items"]:
            self.assertTrue(
                box[0] <= x["longitude"] <= box[2]
                and box[1] <= x["latitude"] <= box[3],
                x,
            )
            self.assertIn(x["categoryGroup"], ("food", "stay", "sight", "transit"))

        poi = lst["items"][0]
        d = self.ok(f"/pois/{poi['id']}", "en").json()
        self.assertEqual(d["id"], poi["id"])
        self.assertEqual(d["name"], poi["name"])
        self.assertEqual(d["category"], poi["category"])
        self.assertIsInstance(d["images"], list)
        self.assertEqual(len(d["rating"]["distribution"]), 5)

        photos = self.ok(f"/pois/{poi['id']}/photos").json()
        self.assertIsInstance(photos["items"], list)
        self.assertEqual(
            photos["total"],
            d["photoCount"],
            "상세의 photoCount 와 사진첩 total 이 다르다",
        )

    def test_category_group_filter(self):
        box = self._box_around_a_place(0.05)
        r = self.ok(
            "/pois?" + _q(bbox=self._bbox(box), categoryGroup="food", limit=50)
        ).json()
        if not r["items"]:
            self.skipTest("촬영지 곁에 음식점이 없다")
        self.assertTrue(all(x["categoryGroup"] == "food" for x in r["items"]), r)

    def test_origin_sorts_by_distance(self):
        box = self._box_around_a_place(0.02)
        lat, lng = box[4], box[5]
        r = self.ok(
            "/pois?" + _q(bbox=self._bbox(box), lat=lat, lng=lng, limit=30)
        ).json()["items"]
        if len(r) < 2:
            self.skipTest("거리 정렬을 볼 만큼 편의시설이 없다")
        dist = [x["distanceMeters"] for x in r]
        self.assertTrue(all(isinstance(v, int) for v in dist), dist)
        self.assertEqual(dist, sorted(dist), "기준점이 있는데 가까운 순이 아니다")

    def test_korean_name_stays_while_category_label_follows_language(self):
        poi, _ = self._first_poi()
        ko = self.ok(f"/pois/{poi['id']}", "ko").json()
        en = self.ok(f"/pois/{poi['id']}", "en").json()
        # name·address·category 는 언제나 한국어 원본
        self.assertEqual(en["name"], ko["name"])
        self.assertEqual(en["category"], ko["category"])
        self.assertEqual(en.get("address"), ko.get("address"))
        # 한국어를 요청하면 categoryLabel 은 category 와 같고 displayName·displayAddress 는 null
        self.assertEqual(ko["categoryLabel"], ko["category"])
        self.assertIsNone(ko.get("displayName"))
        self.assertIsNone(ko.get("displayAddress"))
        if en["categoryLabel"] == en["category"]:
            self.skipTest(f"분류 {en['category']} 의 영어 이름이 사전에 없다")
        self.assertNotEqual(en["categoryLabel"], ko["categoryLabel"])

    def test_unknown_poi_is_404(self):
        self.assert_api_error(self.get(f"/pois/{_MISSING_ID}"), 404, "POI_NOT_FOUND")
        self.assert_api_error(
            self.get(f"/pois/{_MISSING_ID}/photos"), 404, "POI_NOT_FOUND"
        )

    def test_pois_without_area_is_400(self):
        self.assert_api_error(self.get("/pois"), 400, "MISSING_AREA_FILTER")

    def test_pois_bbox_out_of_range_is_400(self):
        r = self.get("/pois?" + _q(bbox="126.9,37.5,200,37.6"))
        self.assertEqual(r.status, 400, r)
        self.assertIn(r.json()["code"], ("INVALID_BBOX", "INVALID_PARAMETER"))
