"""여행후기 e2e — 글 쓰기 → 목록 → 상세 → 내 글 → 코스 담기 → 지우기, 신고·차단 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「여행후기」 · 「신고」 · 「닉네임·차단」. 계약: contracts/openapi/scene-api-v1.yaml 의
`/posts` · `/posts/{postId}` · `/posts/{postId}/saves` · `/me/posts` · `/reports` · `/me/blocks`.

- 계획 표는 「남의 글 지우기 403」 이라 적었지만 계약(`deletePost`)은 **404 POST_NOT_FOUND**(있다는 것을 알리지 않는다)다.
  시험은 계약을 따른다.
- `/posts/{postId}/saves` 는 「저장(북마크)」 이 아니라 **후기의 코스를 내 코스로 복사**하는 창구다. 풀기(unsave)는 계약에 없다.
- 시험마다 새 사용자를 만든다. 코스·장소 번호는 API 로 찾는다.
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
    """1×1 PNG — 색을 무작위로 해 시험마다 바이트가 달라지게 한다."""

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    raw = b"\x00" + secrets.token_bytes(3)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def raw_request(
    method: str, url: str, *, data: bytes | None = None, headers: dict | None = None
):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def write_post(test: unittest.TestCase, session: dict, **extra) -> dict:
    """글 하나를 쓰고 201 응답(TripPostDetail)을 돌려준다."""
    tag = secrets.token_hex(4)
    body = {
        "title": f"e2e 후기 {tag}",
        "body": f"e2e 본문 {tag}\n둘째 줄",
        "photoKeys": [],
        **extra,
    }
    r = call("POST", "/posts", token=session["accessToken"], json_body=body)
    test.assertEqual(r.status, 201, r)
    return r.json()


def list_ids(path: str, token: str | None = None) -> list[int]:
    r = call("GET", f"{path}?limit=100", token=token)
    assert r.status == 200, r
    return [p["id"] for p in r.json()["items"]]


# ── 시험 ─────────────────────────────────────────────────────────────────────


class PostLifecycleTest(E2ETestCase):
    def test_write_list_detail_mine_delete(self):
        """글 하나의 일생 — 쓰기 201 → 목록(최신 맨 앞 부근)·내 글·상세에 같은 제목·본문 → 지우기 → 404."""
        s = sign_in_google()
        tok = s["accessToken"]
        post = write_post(self, s)
        pid = post["id"]
        self.assertTrue(post["isMine"], post)
        self.assertEqual(post["photoUrls"], [], post)
        self.assertEqual(post["author"]["nickname"], s["user"]["nickname"], post)
        self.assertTrue(post["title"].startswith("e2e 후기 "), post)

        # 목록 — 토큰이 있으면 isMine true, 없으면 false
        r = call("GET", "/posts?limit=100", token=tok)
        self.assertEqual(r.status, 200, r)
        summary = next((p for p in r.json()["items"] if p["id"] == pid), None)
        self.assertIsNotNone(summary, f"방금 쓴 글 {pid} 이 목록에 없다")
        self.assertEqual(summary["title"], post["title"])
        self.assertTrue(summary["isMine"], summary)
        self.assertEqual(summary["photoCount"], 0)
        self.assertNotIn(
            "\n", summary["excerpt"], "excerpt 의 줄바꿈은 공백이어야 한다"
        )
        self.assertTrue(
            summary["excerpt"].startswith(post["body"].split("\n")[0]), summary
        )
        anon = call("GET", "/posts?limit=100")
        anon_summary = next((p for p in anon.json()["items"] if p["id"] == pid), None)
        self.assertIsNotNone(anon_summary, "비로그인 목록에 글이 없다")
        self.assertFalse(anon_summary["isMine"], "토큰 없는 목록에 isMine true")

        # 내 글
        r = call("GET", "/me/posts", token=tok)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["total"], 1, r)
        self.assertEqual(r.json()["items"][0]["id"], pid)

        # 상세 — 누구나
        r = call("GET", f"/posts/{pid}")
        self.assertEqual(r.status, 200, r)
        self.assertEqual(
            (r.json()["title"], r.json()["body"]), (post["title"], post["body"])
        )
        self.assertFalse(r.json()["isMine"])

        # 지우기
        r = call("DELETE", f"/posts/{pid}", token=tok)
        self.assertEqual(r.status, 204, r)
        r = call("GET", f"/posts/{pid}")
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "POST_NOT_FOUND", r)
        self.assertNotIn(pid, list_ids("/posts"))
        r = call("GET", "/me/posts", token=tok)
        self.assertEqual(r.json()["total"], 0, r)

    def test_post_with_photo(self):
        """사진 붙인 글 — /uploads(목적 post) → MinIO PUT → 상세 photoUrls·목록 coverPhotoUrl 이 올린 사진을 준다."""
        s = sign_in_google()
        png = tiny_png()
        r = call(
            "POST",
            "/uploads",
            token=s["accessToken"],
            json_body={
                "purpose": "post",
                "contentType": "image/png",
                "bytes": len(png),
            },
        )
        self.assertEqual(r.status, 201, r)
        t = r.json()
        status, body = raw_request(
            "PUT", t["uploadUrl"], data=png, headers=dict(t["requiredHeaders"])
        )
        self.assertIn(
            status, (200, 201, 204), f"MinIO PUT 실패 {status}: {body[:300]!r}"
        )

        post = write_post(self, s, photoKeys=[t["key"]])
        self.assertEqual(len(post["photoUrls"]), 1, post)
        self.assertEqual(
            raw_request("GET", post["photoUrls"][0]),
            (200, png),
            "후기 사진 주소가 올린 사진을 주지 않는다",
        )
        r = call("GET", "/posts?limit=100")
        summary = next(p for p in r.json()["items"] if p["id"] == post["id"])
        self.assertEqual(summary["photoCount"], 1, summary)
        self.assertEqual(
            raw_request("GET", summary["coverPhotoUrl"]),
            (200, png),
            "목록 대표 사진이 올린 사진이 아니다",
        )
        call("DELETE", f"/posts/{post['id']}", token=s["accessToken"])

    def test_guest_cannot_write(self):
        """비회원(설치 ID 만)은 글을 못 쓴다 — 401 SIGN_IN_REQUIRED. 목록은 읽힌다."""
        guest = new_install_id()
        r = call(
            "POST",
            "/posts",
            install_id=guest,
            json_body={"title": "비회원", "body": "본문", "photoKeys": []},
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED", r)
        r = call("GET", "/posts", install_id=guest)
        self.assertEqual(r.status, 200, r)

    def test_blank_title_is_400(self):
        """제목이 공백뿐이면 400 INVALID_PARAMETER."""
        s = sign_in_google()
        r = call(
            "POST",
            "/posts",
            token=s["accessToken"],
            json_body={"title": "   ", "body": "본문", "photoKeys": []},
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "INVALID_PARAMETER", r)

    def test_other_user_cannot_delete(self):
        """남의 글 지우기 → 404 POST_NOT_FOUND(계약: 있다는 것을 알리지 않는다). 글은 그대로 남는다."""
        author = sign_in_google()
        post = write_post(self, author)
        other = sign_in_google()
        r = call("DELETE", f"/posts/{post['id']}", token=other["accessToken"])
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "POST_NOT_FOUND", r)
        r = call("GET", f"/posts/{post['id']}")
        self.assertEqual(r.status, 200, f"남이 지우려 한 글이 사라졌다: {r!r}")
        call("DELETE", f"/posts/{post['id']}", token=author["accessToken"])


class PostCourseSaveTest(E2ETestCase):
    def test_save_post_course_copies_into_my_courses(self):
        """코스를 붙인 후기를 담으면 내 코스가 새로 생긴다 — 같은 장소·순서, origin market. 코스 없는 후기는 404."""
        places = call("GET", "/places?limit=10")
        self.assertEqual(places.status, 200, places)
        ids = [p["id"] for p in places.json()["items"][:2]]
        if len(ids) < 2:
            self.skipTest("촬영지가 둘 이상 필요하다")

        author = sign_in_google()
        atok, ainst = author["accessToken"], author["installId"]
        r = call(
            "POST",
            "/courses",
            token=atok,
            install_id=ainst,
            json_body={"dayCount": 1, "title": "e2e 후기 코스", "origin": "self"},
        )
        self.assertEqual(r.status, 201, r)
        course_id = r.json()["id"]
        r = call(
            "PUT",
            f"/courses/{course_id}",
            token=atok,
            install_id=ainst,
            json_body={
                "title": "e2e 후기 코스",
                "days": [{"items": [{"placeId": ids[0]}, {"placeId": ids[1]}]}],
            },
        )
        self.assertEqual(r.status, 200, r)

        post = write_post(self, author, courseId=course_id)
        self.assertIn("course", post, post)
        stops = [st.get("placeId") for st in post["course"]["days"][0]["stops"]]
        self.assertEqual(stops, ids, post["course"])

        reader = sign_in_google()
        rtok, rinst = reader["accessToken"], reader["installId"]
        r = call("POST", f"/posts/{post['id']}/saves", token=rtok, install_id=rinst)
        self.assertEqual(r.status, 201, r)
        copied = r.json()
        self.assertEqual(copied["origin"], "market", copied)
        self.assertNotEqual(copied["id"], course_id)
        self.assertIsNone(
            copied.get("startDate"), "담은 코스의 날짜는 비어 있어야 한다"
        )
        self.assertEqual(
            [i.get("placeId") for i in copied["days"][0]["items"]], ids, copied
        )
        r = call("GET", "/courses", token=rtok, install_id=rinst)
        self.assertEqual(r.status, 200, r)
        self.assertIn(
            copied["id"],
            [c["id"] for c in r.json()["items"]],
            "담은 코스가 내 코스 목록에 없다",
        )

        # 두 번 담으면 또 새 코스
        r = call("POST", f"/posts/{post['id']}/saves", token=rtok, install_id=rinst)
        self.assertEqual(r.status, 201, r)
        self.assertNotEqual(r.json()["id"], copied["id"])

        # 코스 없는 후기는 404 POST_NOT_FOUND
        plain = write_post(self, author)
        r = call("POST", f"/posts/{plain['id']}/saves", token=rtok, install_id=rinst)
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "POST_NOT_FOUND", r)

        for pid in (post["id"], plain["id"]):
            call("DELETE", f"/posts/{pid}", token=atok)


class ReportAndBlockTest(E2ETestCase):
    def test_report_post(self):
        """신고 — 남의 글 204, 두 번 204, 비회원 204, 내 글 400 CANNOT_REPORT_OWN, 없는 글 404 REPORT_TARGET_NOT_FOUND."""
        author = sign_in_google()
        post = write_post(self, author)
        reporter = sign_in_google()
        report = {
            "targetType": "post",
            "targetId": post["id"],
            "reason": "spam",
            "detail": "e2e 신고",
        }

        r = call(
            "POST",
            "/reports",
            token=reporter["accessToken"],
            install_id=reporter["installId"],
            json_body=report,
        )
        self.assertEqual(r.status, 204, r)
        r = call(
            "POST",
            "/reports",
            token=reporter["accessToken"],
            install_id=reporter["installId"],
            json_body=report,
        )
        self.assertEqual(r.status, 204, r)
        r = call(
            "POST",
            "/reports",
            install_id=new_install_id(),
            json_body={**report, "reason": "other"},
        )
        self.assertEqual(r.status, 204, r)

        r = call(
            "POST",
            "/reports",
            token=author["accessToken"],
            install_id=author["installId"],
            json_body=report,
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "CANNOT_REPORT_OWN", r)

        call("DELETE", f"/posts/{post['id']}", token=author["accessToken"])
        r = call("POST", "/reports", install_id=new_install_id(), json_body=report)
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "REPORT_TARGET_NOT_FOUND", r)

    def test_blocked_author_posts_disappear_for_blocker_only(self):
        """차단 — 차단한 사람의 목록·상세에서 그 작성자의 글이 빠지고(상세 404), 남에게는 그대로. 풀면 다시 보인다."""
        author = sign_in_google()
        post = write_post(self, author)
        blocker = sign_in_google()
        btok = blocker["accessToken"]

        self.assertIn(post["id"], list_ids("/posts", btok), "차단 전에는 보여야 한다")

        r = call(
            "POST",
            "/me/blocks",
            token=btok,
            json_body={"targetType": "post", "targetId": post["id"]},
        )
        self.assertEqual(r.status, 201, r)
        block = r.json()
        self.assertEqual(block["nickname"], author["user"]["nickname"], block)

        # 이미 차단한 사람 — 200, 같은 줄
        r = call(
            "POST",
            "/me/blocks",
            token=btok,
            json_body={"targetType": "post", "targetId": post["id"]},
        )
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["id"], block["id"])

        r = call("GET", "/me/blocks", token=btok)
        self.assertEqual(r.status, 200, r)
        self.assertEqual([b["id"] for b in r.json()["items"]], [block["id"]], r)

        self.assertNotIn(
            post["id"], list_ids("/posts", btok), "차단한 작성자의 글이 목록에 남았다"
        )
        r = call("GET", f"/posts/{post['id']}", token=btok)
        self.assertEqual(r.status, 404, f"차단한 작성자의 글 상세가 열린다: {r!r}")
        # 남(비로그인)에게는 그대로
        self.assertIn(
            post["id"], list_ids("/posts"), "차단이 다른 사람의 목록까지 지웠다"
        )

        # 풀기
        r = call("DELETE", f"/me/blocks/{block['id']}", token=btok)
        self.assertEqual(r.status, 204, r)
        self.assertIn(
            post["id"], list_ids("/posts", btok), "차단을 풀었는데 글이 돌아오지 않았다"
        )
        r = call("DELETE", f"/me/blocks/{block['id']}", token=btok)
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "BLOCK_NOT_FOUND", r)

        call("DELETE", f"/posts/{post['id']}", token=author["accessToken"])

    def test_cannot_block_self_and_guest_cannot_block(self):
        """자기 글 차단 400 CANNOT_BLOCK_SELF, 비회원 차단 401 SIGN_IN_REQUIRED."""
        s = sign_in_google()
        post = write_post(self, s)
        r = call(
            "POST",
            "/me/blocks",
            token=s["accessToken"],
            json_body={"targetType": "post", "targetId": post["id"]},
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "CANNOT_BLOCK_SELF", r)
        r = call(
            "POST",
            "/me/blocks",
            install_id=new_install_id(),
            json_body={"targetType": "post", "targetId": post["id"]},
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED", r)
        call("DELETE", f"/posts/{post['id']}", token=s["accessToken"])


if __name__ == "__main__":
    unittest.main()
