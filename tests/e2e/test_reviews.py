"""리뷰 e2e — 촬영지·편의시설 리뷰 쓰기 → 내 리뷰 → 목록 → 고치기 → 지우기, 사진 올리기(서명 주소 → MinIO PUT → 붙이기) (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「리뷰」. 계약: contracts/openapi/scene-api-v1.yaml 의
`/places/{placeId}/reviews…` · `/pois/{poiId}/reviews…` · `/me/reviews` · `/uploads` · `/places/{placeId}/photos`.

- 시험 자료(촬영지·편의시설 번호)는 API 로 찾는다 — DB 번호를 박지 않는다.
- 시험마다 새 사용자(`sign_in_google()`)·새 설치 ID 를 쓴다. 한 사람이 한 곳에 리뷰 하나라 다른 시험과 섞이지 않는다.
- 사진은 코드로 만든 1×1 PNG 를 진짜 MinIO 에 PUT 하고, 리뷰 응답의 사진 주소로 다시 받아 같은 바이트인지 본다.
"""

from __future__ import annotations

import secrets
import struct
import unittest
import urllib.error
import urllib.request
import zlib

from support import E2ETestCase, call, new_install_id, sign_in_google

# ── 도우미 ───────────────────────────────────────────────────────────────────


def tiny_png() -> bytes:
    """1×1 PNG — 표준 라이브러리로 만든다. 색을 무작위로 해 시험마다 바이트가 달라지게 한다."""

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)  # 1×1, 8비트, RGB
    raw = b"\x00" + secrets.token_bytes(3)  # 필터 0 + 픽셀 하나
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def raw_request(
    method: str, url: str, *, data: bytes | None = None, headers: dict | None = None
):
    """우리 서버가 아닌 곳(MinIO)에 보낸다 — Authorization·Accept 를 붙이지 않는다. (status, body)."""
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def upload_photo(
    test: unittest.TestCase, token: str, purpose: str = "review"
) -> tuple[str, bytes]:
    """POST /uploads → 받은 서명 주소로 MinIO 에 PUT. (key, 올린 바이트) 를 돌려준다."""
    png = tiny_png()
    r = call(
        "POST",
        "/uploads",
        token=token,
        json_body={"purpose": purpose, "contentType": "image/png", "bytes": len(png)},
    )
    test.assertEqual(r.status, 201, r)
    ticket = r.json()
    for field in ("key", "uploadUrl", "requiredHeaders", "expiresAt"):
        test.assertIn(field, ticket, ticket)
    test.assertTrue(ticket["key"], ticket)
    status, body = raw_request(
        "PUT", ticket["uploadUrl"], data=png, headers=dict(ticket["requiredHeaders"])
    )
    test.assertIn(
        status,
        (200, 201, 204),
        f"MinIO PUT 실패 {status}: {body[:300]!r} — {ticket['uploadUrl']}",
    )
    return ticket["key"], png


def any_place_id(test: unittest.TestCase) -> int:
    r = call("GET", "/places?limit=20")
    test.assertEqual(r.status, 200, r)
    items = r.json()["items"]
    if not items:
        test.skipTest("촬영지가 하나도 없다 — 카탈로그가 비었다")
    # 시험마다 다른 곳을 고르면 같은 곳 목록에 리뷰가 쌓이는 것을 덜 탄다
    return secrets.choice(items)["id"]


def standalone_poi_id(test: unittest.TestCase) -> int:
    """촬영지와 이어지지 않은(placeId 없는) 편의시설 — 편의시설 리뷰로 저장되는 곳."""
    r = call("GET", "/pois?bbox=126.95,37.54,127.02,37.59&limit=100")
    test.assertEqual(r.status, 200, r)
    items = [p for p in r.json()["items"] if not p.get("placeId")]
    if not items:
        test.skipTest("서울 도심 범위에 촬영지와 따로인 편의시설이 없다")
    return secrets.choice(items)["id"]


def linked_poi(test: unittest.TestCase) -> tuple[int, int]:
    """촬영지와 같은 곳인 편의시설(poiId, placeId) — 촬영지 좌표 둘레에서 찾는다."""
    r = call("GET", "/places?limit=100")
    test.assertEqual(r.status, 200, r)
    for p in r.json()["items"]:
        q = call(
            "GET",
            f"/pois?lat={p['latitude']}&lng={p['longitude']}&radiusMeters=300&limit=50",
        )
        if q.status != 200:
            continue
        for poi in q.json()["items"]:
            if poi.get("placeId"):
                return poi["id"], poi["placeId"]
    test.skipTest("촬영지와 이어진 편의시설을 찾지 못했다")


def find(items: list, review_id: int):
    return next((i for i in items if i["id"] == review_id), None)


# ── 시험 ─────────────────────────────────────────────────────────────────────


class PlaceReviewTest(E2ETestCase):
    def test_place_review_write_read_edit_delete(self):
        """촬영지 리뷰 하나의 일생 — 쓰기 → 내 리뷰 → 목록·내 리뷰 목록 → 고치기 → 지우기."""
        place_id = any_place_id(self)
        s = sign_in_google()
        tok = s["accessToken"]
        body = f"e2e 리뷰 {secrets.token_hex(4)} — 좋은 곳"

        # 쓰기 전에는 내 리뷰가 없다
        r = call("GET", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "REVIEW_NOT_FOUND", r)

        # 쓰기
        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            token=tok,
            json_body={"rating": 4, "body": body, "photoKeys": []},
        )
        self.assertEqual(r.status, 200, r)
        written = r.json()
        self.assertEqual(written["rating"], 4)
        self.assertEqual(written["body"], body)
        self.assertEqual(written["photos"], [])
        self.assertTrue(written["isMine"], written)
        self.assertEqual(written["author"]["nickname"], s["user"]["nickname"], written)
        rid = written["id"]

        # 내 리뷰
        r = call("GET", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["id"], rid)
        self.assertEqual(r.json()["body"], body)

        # 목록(최신순) — 토큰이 있으면 isMine true, 없으면 false
        r = call("GET", f"/places/{place_id}/reviews?sort=recent&limit=100", token=tok)
        self.assertEqual(r.status, 200, r)
        listed = r.json()
        mine = find(listed["items"], rid)
        self.assertIsNotNone(mine, f"방금 쓴 리뷰 {rid} 가 목록에 없다: {listed}")
        self.assertTrue(mine["isMine"], mine)
        self.assertEqual(mine["body"], body)
        self.assertGreaterEqual(listed["summary"]["count"], 1, listed["summary"])
        self.assertEqual(len(listed["summary"]["distribution"]), 5, listed["summary"])
        self.assertGreaterEqual(
            listed["summary"]["distribution"][3],
            1,
            "4 점 칸에 내 리뷰가 세어지지 않았다",
        )
        anon = call("GET", f"/places/{place_id}/reviews?sort=recent&limit=100")
        self.assertEqual(anon.status, 200, anon)
        anon_mine = find(anon.json()["items"], rid)
        self.assertIsNotNone(anon_mine, "비로그인 목록에 리뷰가 없다")
        self.assertFalse(anon_mine["isMine"], "토큰 없는 요청에 isMine 이 true 다")

        # 내가 쓴 리뷰 — target 이 그 촬영지
        r = call("GET", "/me/reviews", token=tok)
        self.assertEqual(r.status, 200, r)
        mr = r.json()
        self.assertEqual(mr["total"], 1, mr)
        self.assertEqual(mr["items"][0]["id"], rid)
        self.assertEqual(mr["items"][0]["target"]["type"], "place")
        self.assertEqual(mr["items"][0]["target"]["id"], place_id)
        self.assertTrue(mr["items"][0]["target"]["name"], mr)

        # 고치기 — 같은 리뷰(id 같음), 별점·글이 바뀐다
        new_body = body + " (고침)"
        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            token=tok,
            json_body={"rating": 2, "body": new_body, "photoKeys": []},
        )
        self.assertEqual(r.status, 200, r)
        edited = r.json()
        self.assertEqual(
            edited["id"], rid, "고쳤는데 새 리뷰가 생겼다(한 사람 한 곳 하나)"
        )
        self.assertEqual(edited["rating"], 2)
        self.assertEqual(edited["body"], new_body)
        self.assertGreaterEqual(edited["updatedAt"], edited["createdAt"])
        r = call("GET", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual((r.json()["rating"], r.json()["body"]), (2, new_body), r)
        r = call("GET", "/me/reviews", token=tok)
        self.assertEqual(r.json()["total"], 1, "고치기가 리뷰를 하나 더 만들었다")

        # 지우기 → 내 리뷰 404, 목록·내 리뷰 목록에서 빠짐. 다시 지워도 204
        r = call("DELETE", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 204, r)
        r = call("GET", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "REVIEW_NOT_FOUND", r)
        r = call("GET", f"/places/{place_id}/reviews?sort=recent&limit=100")
        self.assertIsNone(find(r.json()["items"], rid), "지운 리뷰가 목록에 남았다")
        r = call("GET", "/me/reviews", token=tok)
        self.assertEqual(r.json()["total"], 0, r)
        r = call("DELETE", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 204, r)

    def test_review_photo_upload_attach_and_gallery(self):
        """사진 — 서명 주소 받기 → MinIO PUT → 리뷰에 붙이기 → 리뷰·사진첩에 그 사진이 나오고 같은 바이트로 받아진다."""
        place_id = any_place_id(self)
        tok = sign_in_google()["accessToken"]
        key, png = upload_photo(self, tok)

        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            token=tok,
            json_body={"rating": 5, "body": "사진 리뷰", "photoKeys": [key]},
        )
        self.assertEqual(r.status, 200, r)
        review = r.json()
        # 붙인 뒤의 키는 올릴 때의 키와 다를 수 있다(계약은 「Review.photos[].key 를 그대로 보내면 남는다」만 약속한다)
        self.assertEqual(len(review["photos"]), 1, review)
        self.assertTrue(review["photos"][0]["key"], review)
        status, got = raw_request("GET", review["photos"][0]["url"])
        self.assertEqual(
            status, 200, f"리뷰 사진 주소가 열리지 않는다: {review['photos'][0]['url']}"
        )
        self.assertEqual(got, png, "리뷰 사진 주소로 받은 바이트가 올린 것과 다르다")

        # 사진첩 — 리뷰 사진(source review, reviewId 가 내 리뷰)
        r = call("GET", f"/places/{place_id}/photos?limit=100")
        self.assertEqual(r.status, 200, r)
        mine = [
            p
            for p in r.json()["items"]
            if p["source"] == "review" and p.get("reviewId") == review["id"]
        ]
        self.assertEqual(
            len(mine), 1, f"사진첩에 내 리뷰 사진이 한 장 있어야 한다: {r.json()}"
        )
        status, got = raw_request("GET", mine[0]["url"])
        self.assertEqual(
            (status, got),
            (200, png),
            "사진첩의 내 사진 주소가 올린 사진을 돌려주지 않는다",
        )

        # 받은 키를 그대로 보내 고치면 사진이 남는다
        kept_key = review["photos"][0]["key"]
        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            token=tok,
            json_body={"rating": 4, "body": "사진 둠", "photoKeys": [kept_key]},
        )
        self.assertEqual(r.status, 200, r)
        self.assertEqual([p["key"] for p in r.json()["photos"]], [kept_key], r)
        self.assertEqual(
            raw_request("GET", r.json()["photos"][0]["url"]),
            (200, png),
            "고친 뒤 사진이 올린 것이 아니다",
        )

        # 사진을 빼고 고치면(PUT 전체 교체) 리뷰와 사진첩에서 빠진다
        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            token=tok,
            json_body={"rating": 5, "body": "사진 뺌", "photoKeys": []},
        )
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["photos"], [], r)
        r = call("GET", f"/places/{place_id}/photos?limit=100")
        self.assertFalse(
            [p for p in r.json()["items"] if p.get("reviewId") == review["id"]],
            "뺀 사진이 사진첩에 남았다",
        )
        call("DELETE", f"/places/{place_id}/reviews/me", token=tok)

    def test_photo_key_not_mine_is_rejected(self):
        """남이 올린 키를 붙이면 400 REVIEW_PHOTO_INVALID — 리뷰는 저장되지 않는다."""
        place_id = any_place_id(self)
        other_key, _ = upload_photo(self, sign_in_google()["accessToken"])
        tok = sign_in_google()["accessToken"]
        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            token=tok,
            json_body={"rating": 3, "photoKeys": [other_key]},
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "REVIEW_PHOTO_INVALID", r)
        r = call("GET", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 404, f"거절된 리뷰가 저장됐다: {r!r}")

    def test_guest_cannot_write_review_or_upload(self):
        """비회원(설치 ID 만) — 리뷰 쓰기·지우기·사진 주소 받기는 401 SIGN_IN_REQUIRED, 목록 읽기는 된다."""
        place_id = any_place_id(self)
        guest = new_install_id()
        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            install_id=guest,
            json_body={"rating": 5, "body": "비회원", "photoKeys": []},
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED", r)
        r = call("DELETE", f"/places/{place_id}/reviews/me", install_id=guest)
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED", r)
        r = call(
            "POST",
            "/uploads",
            install_id=guest,
            json_body={"purpose": "review", "contentType": "image/png", "bytes": 100},
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED", r)
        r = call("GET", f"/places/{place_id}/reviews", install_id=guest)
        self.assertEqual(r.status, 200, r)

    def test_review_on_missing_place_is_404(self):
        """없는 촬영지에 쓰기 → 404 PLACE_NOT_FOUND."""
        tok = sign_in_google()["accessToken"]
        r = call(
            "PUT",
            "/places/987654321098/reviews/me",
            token=tok,
            json_body={"rating": 5, "photoKeys": []},
        )
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "PLACE_NOT_FOUND", r)


class PoiReviewTest(E2ETestCase):
    def test_poi_review_write_read_edit_delete(self):
        """편의시설 리뷰 — 쓰기 → 내 리뷰 → 목록·상세 별점 → 내 리뷰 목록(target poi) → 고치기 → 지우기."""
        poi_id = standalone_poi_id(self)
        s = sign_in_google()
        tok = s["accessToken"]
        body = f"e2e 편의시설 리뷰 {secrets.token_hex(4)}"

        r = call(
            "PUT",
            f"/pois/{poi_id}/reviews/me",
            token=tok,
            json_body={"rating": 5, "body": body, "photoKeys": []},
        )
        self.assertEqual(r.status, 200, r)
        rid = r.json()["id"]
        self.assertEqual(r.json()["body"], body)
        self.assertTrue(r.json()["isMine"])

        r = call("GET", f"/pois/{poi_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 200, r)
        self.assertEqual((r.json()["id"], r.json()["rating"]), (rid, 5))

        r = call("GET", f"/pois/{poi_id}/reviews?limit=100", token=tok)
        self.assertEqual(r.status, 200, r)
        mine = find(r.json()["items"], rid)
        self.assertIsNotNone(mine, r.json())
        self.assertEqual(mine["body"], body)
        self.assertGreaterEqual(
            r.json()["summary"]["distribution"][4],
            1,
            "5 점 칸에 내 리뷰가 세어지지 않았다",
        )

        d = call("GET", f"/pois/{poi_id}")
        self.assertEqual(d.status, 200, d)
        self.assertGreaterEqual(
            d.json()["rating"]["count"],
            1,
            "편의시설 상세 별점에 리뷰가 세어지지 않았다",
        )

        r = call("GET", "/me/reviews", token=tok)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["total"], 1, r)
        target = r.json()["items"][0]["target"]
        self.assertEqual((target["type"], target["id"]), ("poi", poi_id), target)

        r = call(
            "PUT",
            f"/pois/{poi_id}/reviews/me",
            token=tok,
            json_body={"rating": 1, "body": None, "photoKeys": []},
        )
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["id"], rid)
        self.assertEqual(r.json()["rating"], 1)
        # body 는 nullable·선택 칸 — 값이 없으면 null 이거나 빠진다
        self.assertIsNone(
            r.json().get("body"), "body 를 null 로 보냈는데 남았다(PUT 전체 교체)"
        )

        r = call("DELETE", f"/pois/{poi_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 204, r)
        r = call("GET", f"/pois/{poi_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "REVIEW_NOT_FOUND", r)
        r = call("GET", f"/pois/{poi_id}/reviews?limit=100")
        self.assertIsNone(find(r.json()["items"], rid), "지운 리뷰가 목록에 남았다")

    def test_poi_review_with_photo(self):
        """편의시설 리뷰에 사진 — 리뷰와 편의시설 사진첩에 나온다."""
        poi_id = standalone_poi_id(self)
        tok = sign_in_google()["accessToken"]
        key, png = upload_photo(self, tok)
        r = call(
            "PUT",
            f"/pois/{poi_id}/reviews/me",
            token=tok,
            json_body={"rating": 4, "photoKeys": [key]},
        )
        self.assertEqual(r.status, 200, r)
        review = r.json()
        self.assertEqual(len(review["photos"]), 1, review)
        self.assertEqual(raw_request("GET", review["photos"][0]["url"]), (200, png))
        r = call("GET", f"/pois/{poi_id}/photos?limit=100")
        self.assertEqual(r.status, 200, r)
        mine = [p for p in r.json()["items"] if p.get("reviewId") == review["id"]]
        self.assertEqual(len(mine), 1, r.json())
        self.assertEqual(mine[0]["source"], "review")
        self.assertEqual(raw_request("GET", mine[0]["url"]), (200, png))
        call("DELETE", f"/pois/{poi_id}/reviews/me", token=tok)

    def test_poi_linked_to_place_writes_place_review(self):
        """촬영지와 같은 곳인 편의시설로 쓰면 그 촬영지의 리뷰로 저장된다 — 촬영지 쪽 내 리뷰에 같은 리뷰."""
        poi_id, place_id = linked_poi(self)
        tok = sign_in_google()["accessToken"]
        body = f"이어진 곳 {secrets.token_hex(4)}"
        r = call(
            "PUT",
            f"/pois/{poi_id}/reviews/me",
            token=tok,
            json_body={"rating": 3, "body": body, "photoKeys": []},
        )
        self.assertEqual(r.status, 200, r)
        rid = r.json()["id"]
        r = call("GET", f"/places/{place_id}/reviews/me", token=tok)
        self.assertEqual(r.status, 200, r)
        self.assertEqual((r.json()["id"], r.json()["body"]), (rid, body))
        r = call("GET", "/me/reviews", token=tok)
        self.assertEqual(r.json()["items"][0]["target"]["type"], "place", r.json())
        self.assertEqual(r.json()["items"][0]["target"]["id"], place_id, r.json())
        call("DELETE", f"/places/{place_id}/reviews/me", token=tok)

    def test_guest_cannot_write_poi_review(self):
        poi_id = standalone_poi_id(self)
        r = call(
            "PUT",
            f"/pois/{poi_id}/reviews/me",
            install_id=new_install_id(),
            json_body={"rating": 5, "photoKeys": []},
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED", r)


if __name__ == "__main__":
    unittest.main()
