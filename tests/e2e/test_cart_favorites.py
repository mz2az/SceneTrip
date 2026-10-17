"""장바구니·작품 찜 e2e — 비회원 설치 ID 로 담기 → 조회 → 빼기, 작품 찜 → 목록 → 해제 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「장바구니·찜」. 계약: contracts/openapi/scene-api-v1.yaml 의
`/cart` · `/cart/items` · `/cart/items/{placeId}` · `/favorites/contents` · `/favorites/contents/{contentId}`.

- 비회원은 토큰 없이 `X-Install-Id` 만 보낸다. 설치 ID 가 계정을 정하므로 같은 ID 면 이어지고 다른 ID 와는 섞이지 않는다.
- 촬영지·작품 번호는 API 로 찾는다. 없는 번호는 카탈로그에 있을 수 없는 큰 수를 쓴다.
"""

from __future__ import annotations

import unittest

from support import E2ETestCase, call, new_install_id

MISSING_ID = 987654321098  # 카탈로그에 있을 수 없는 번호


def places_with_content(test: unittest.TestCase, n: int) -> list[dict]:
    r = call("GET", "/places?limit=50", lang="ko")
    test.assertEqual(r.status, 200, r)
    items = [p for p in r.json()["items"] if p.get("contents")]
    if len(items) < n:
        test.skipTest(f"작품이 붙은 촬영지가 {n} 곳 필요하다")
    return items[:n]


def content_ids(test: unittest.TestCase, n: int) -> list[dict]:
    r = call("GET", "/contents?limit=20", lang="ko")
    test.assertEqual(r.status, 200, r)
    items = r.json()["items"]
    if len(items) < n:
        test.skipTest(f"작품이 {n} 개 필요하다")
    return items[:n]


class GuestCartTest(E2ETestCase):
    def test_guest_cart_add_get_remove(self):
        """비회원 장바구니 — 담기 201(이름·출처 작품) → 조회(담은 순서) → 같은 ID 로 이어짐 → 빼기 204 → 비어 있음."""
        p1, p2 = places_with_content(self, 2)
        inst = new_install_id()

        r = call("GET", "/cart", install_id=inst, lang="ko")
        self.assertEqual(r.status, 200, r)
        self.assertEqual(
            (r.json()["items"], r.json()["totalCount"]),
            ([], 0),
            "새 설치 ID 의 장바구니가 비어 있지 않다",
        )

        src = p1["contents"][0]
        r = call(
            "POST",
            "/cart/items",
            install_id=inst,
            lang="ko",
            json_body={"placeId": p1["id"], "sourceContentId": src["contentId"]},
        )
        self.assertEqual(r.status, 201, r)
        item = r.json()
        self.assertEqual(item["placeId"], p1["id"])
        self.assertEqual(item["name"], p1["name"])
        self.assertEqual(item["sourceContentId"], src["contentId"])
        self.assertEqual(item["sourceContentTitle"], src["title"], item)
        self.assertTrue(item["addedAt"], item)

        r = call(
            "POST",
            "/cart/items",
            install_id=inst,
            lang="ko",
            json_body={"placeId": p2["id"]},
        )
        self.assertEqual(r.status, 201, r)

        # 다른 요청(같은 설치 ID) — 담은 순서(오래된 것부터)
        r = call("GET", "/cart", install_id=inst, lang="ko")
        self.assertEqual(r.status, 200, r)
        cart = r.json()
        self.assertEqual(
            [i["placeId"] for i in cart["items"]], [p1["id"], p2["id"]], cart
        )
        self.assertEqual(cart["totalCount"], 2, cart)
        self.assertEqual(cart["items"][0]["sourceContentTitle"], src["title"])

        # 같은 장소 두 번 → 409 DUPLICATE_CART_ITEM, 장바구니는 그대로
        r = call(
            "POST", "/cart/items", install_id=inst, json_body={"placeId": p1["id"]}
        )
        self.assertEqual(r.status, 409, r)
        self.assertEqual(r.json()["code"], "DUPLICATE_CART_ITEM", r)
        self.assertEqual(call("GET", "/cart", install_id=inst).json()["totalCount"], 2)

        # 빼기
        r = call("DELETE", f"/cart/items/{p1['id']}", install_id=inst)
        self.assertEqual(r.status, 204, r)
        r = call("GET", "/cart", install_id=inst)
        self.assertEqual([i["placeId"] for i in r.json()["items"]], [p2["id"]], r)
        r = call("DELETE", f"/cart/items/{p2['id']}", install_id=inst)
        self.assertEqual(r.status, 204, r)
        r = call("GET", "/cart", install_id=inst)
        self.assertEqual((r.json()["items"], r.json()["totalCount"]), ([], 0), r)

    def test_cart_is_isolated_per_install_id(self):
        """다른 설치 ID 의 장바구니에는 보이지 않고, 남의 장바구니 항목을 뺄 수 없다."""
        (p,) = places_with_content(self, 1)
        a, b = new_install_id(), new_install_id()
        r = call("POST", "/cart/items", install_id=a, json_body={"placeId": p["id"]})
        self.assertEqual(r.status, 201, r)

        r = call("GET", "/cart", install_id=b)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(
            r.json()["items"], [], "다른 설치 ID 에 남의 장바구니가 보인다"
        )

        # b 가 빼려 해도 a 의 장바구니는 그대로 — b 에는 그 항목이 없다
        r = call("DELETE", f"/cart/items/{p['id']}", install_id=b)
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "CART_ITEM_NOT_FOUND", r)
        r = call("GET", "/cart", install_id=a)
        self.assertEqual(
            [i["placeId"] for i in r.json()["items"]],
            [p["id"]],
            "남이 내 장바구니를 비웠다",
        )

        # b 는 같은 장소를 따로 담을 수 있다(409 가 아니다)
        r = call("POST", "/cart/items", install_id=b, json_body={"placeId": p["id"]})
        self.assertEqual(r.status, 201, r)

    def test_add_missing_place_is_404(self):
        """없는 촬영지 담기 → 404 PLACE_NOT_FOUND, 장바구니는 비어 있다."""
        inst = new_install_id()
        r = call(
            "POST", "/cart/items", install_id=inst, json_body={"placeId": MISSING_ID}
        )
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "PLACE_NOT_FOUND", r)
        self.assertEqual(call("GET", "/cart", install_id=inst).json()["totalCount"], 0)

    def test_cart_without_install_id_is_400(self):
        """설치 ID 도 토큰도 없으면 400 MISSING_INSTALL_ID."""
        r = call("GET", "/cart")
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "MISSING_INSTALL_ID", r)


class FavoriteContentTest(E2ETestCase):
    def test_favorite_add_list_remove(self):
        """작품 찜 — 찜 204 → 목록(최근 것부터) → 다시 찜 204(중복 없음) → 해제 204 → 다시 해제 204."""
        c1, c2 = content_ids(self, 2)
        inst = new_install_id()

        r = call("GET", "/favorites/contents", install_id=inst)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["items"], [], r)
        self.assertEqual(r.json()["total"], 0, r)

        for c in (c1, c2):
            r = call(
                "POST",
                "/favorites/contents",
                install_id=inst,
                json_body={"contentId": c["id"]},
            )
            self.assertEqual(r.status, 204, r)

        r = call("GET", "/favorites/contents", install_id=inst, lang="ko")
        self.assertEqual(r.status, 200, r)
        fav = r.json()
        self.assertEqual(
            [i["id"] for i in fav["items"]],
            [c2["id"], c1["id"]],
            "찜 목록은 최근 찜부터여야 한다",
        )
        self.assertEqual(fav["total"], 2)
        self.assertEqual(fav["items"][1]["title"], c1["title"])

        # 이미 찜한 것 다시 → 204, 목록은 두 개 그대로
        r = call(
            "POST",
            "/favorites/contents",
            install_id=inst,
            json_body={"contentId": c1["id"]},
        )
        self.assertEqual(r.status, 204, r)
        self.assertEqual(
            call("GET", "/favorites/contents", install_id=inst).json()["total"],
            2,
            "다시 찜했더니 중복이 생겼다",
        )

        # 다른 설치 ID 에는 없다
        r = call("GET", "/favorites/contents", install_id=new_install_id())
        self.assertEqual(r.json()["items"], [], "다른 설치 ID 에 남의 찜이 보인다")

        r = call("DELETE", f"/favorites/contents/{c1['id']}", install_id=inst)
        self.assertEqual(r.status, 204, r)
        r = call("GET", "/favorites/contents", install_id=inst)
        self.assertEqual([i["id"] for i in r.json()["items"]], [c2["id"]], r)
        r = call("DELETE", f"/favorites/contents/{c1['id']}", install_id=inst)
        self.assertEqual(r.status, 204, r)

    def test_favorite_missing_content_is_404(self):
        """없는 작품 찜 → 404 CONTENT_NOT_FOUND."""
        inst = new_install_id()
        r = call(
            "POST",
            "/favorites/contents",
            install_id=inst,
            json_body={"contentId": MISSING_ID},
        )
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "CONTENT_NOT_FOUND", r)
        self.assertEqual(
            call("GET", "/favorites/contents", install_id=inst).json()["total"], 0
        )


if __name__ == "__main__":
    unittest.main()
