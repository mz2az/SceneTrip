"""사용 기록·사진 올리기 e2e — `/events` 와 `/uploads` → MinIO 서명 주소 PUT (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「신고·업로드·이벤트」(신고는 다른 파일). 명세: contracts/openapi/scene-api-v1.yaml 의
`POST /events`(`UsageEvent`)·`POST /uploads`(`UploadCreate`·`UploadTicket`), 오류 코드는 docs/api/errors.md.

- 이벤트: `view`·`search` 는 204, 칸이 빠지거나 모르는 `type` 은 400 `INVALID_PARAMETER`.
- 업로드: 가입자는 10 분짜리 서명 주소를 받아 MinIO 에 바로 PUT 한다(서명에 크기가 들어가 다른 크기는 거절).
  비회원 401, 큰 파일·모르는 형식 400.
- 작품 번호는 API(`GET /contents`)에서 찾는다.
"""

from __future__ import annotations

import datetime as dt
import urllib.error
import urllib.request

from support import E2ETestCase, call, new_install_id, sign_in_google

# 1×1 투명 PNG
TINY_PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
    "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082"
)


def _put(url: str, body: bytes, headers: dict[str, str]) -> tuple[int, bytes]:
    req = urllib.request.Request(url, data=body, method="PUT", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


class EventsTest(E2ETestCase):
    def test_view_and_search_events_are_204(self):
        r = call("GET", "/contents?limit=1")
        self.assertEqual(r.status, 200, r)
        content_id = r.json()["items"][0]["id"]
        install_id = new_install_id()

        r = call(
            "POST",
            "/events",
            json_body={"type": "view", "entityType": "content", "entityId": content_id},
            install_id=install_id,
            lang="ko",
        )
        self.assertEqual(r.status, 204, r)
        self.assertEqual(r.body, b"")

        r = call(
            "POST",
            "/events",
            json_body={"type": "search", "query": "  도깨비 ", "resultCount": 0},
            install_id=install_id,
        )
        self.assertEqual(r.status, 204, r)

        # 가입자 토큰으로도
        session = sign_in_google()
        r = call(
            "POST",
            "/events",
            json_body={"type": "view", "entityType": "content", "entityId": content_id},
            token=session["accessToken"],
            install_id=session["installId"],
        )
        self.assertEqual(r.status, 204, r)

    def test_invalid_events_are_400(self):
        install_id = new_install_id()
        bad = [
            {"type": "view", "entityType": "content"},  # entityId 없음
            {"type": "view", "entityId": 1},  # entityType 없음
            {"type": "search", "resultCount": 3},  # query 없음
            {"type": "search", "query": "x" * 101, "resultCount": 1},  # 100 자 넘음
            {"type": "search", "query": "도깨비", "resultCount": -1},  # 음수
            {"type": "click", "entityType": "content", "entityId": 1},  # 모르는 type
        ]
        for payload in bad:
            with self.subTest(payload=payload):
                r = call("POST", "/events", json_body=payload, install_id=install_id)
                self.assertEqual(r.status, 400, r)
                self.assertEqual(r.json()["code"], "INVALID_PARAMETER", r)


class UploadsTest(E2ETestCase):
    def test_signed_url_put_to_storage(self):
        session = sign_in_google()
        r = call(
            "POST",
            "/uploads",
            json_body={
                "purpose": "review",
                "contentType": "image/png",
                "bytes": len(TINY_PNG),
            },
            token=session["accessToken"],
        )
        self.assertEqual(r.status, 201, r)
        ticket = r.json()
        for k in ("key", "uploadUrl", "requiredHeaders", "expiresAt"):
            self.assertTrue(ticket.get(k) is not None, f"{k} 가 없다: {ticket}")
        self.assertTrue(ticket["key"])
        self.assertTrue(ticket["uploadUrl"].startswith("http"), ticket)
        self.assertNotIn(
            "/v1/", ticket["uploadUrl"], "올릴 주소가 우리 서버다 — 저장소여야 한다"
        )
        self.assertEqual(
            ticket["requiredHeaders"].get("Content-Type"), "image/png", ticket
        )

        expires = dt.datetime.fromisoformat(ticket["expiresAt"].replace("Z", "+00:00"))
        left = (expires - dt.datetime.now(dt.timezone.utc)).total_seconds()
        self.assertTrue(
            0 < left <= 11 * 60,
            f"10 분짜리 주소여야 한다: expiresAt={ticket['expiresAt']} (남은 {left:.0f}초)",
        )

        # 크기가 다르면 저장소가 거절한다(서명에 크기가 들어간다). requiredHeaders 의 Content-Length 를 그대로 붙이면
        # urllib 가 그 값만큼만 보내 몸체가 잘린다 — 실제 크기를 싣도록 그 헤더만 뺀다.
        wrong = {
            k: v
            for k, v in ticket["requiredHeaders"].items()
            if k.lower() != "content-length"
        }
        status, body = _put(ticket["uploadUrl"], TINY_PNG + b"\x00", wrong)
        self.assertGreaterEqual(
            status,
            400,
            f"요청과 다른 크기인데 저장소가 받았다: {status} {body[:300]!r}",
        )

        # 정확한 크기·헤더면 받는다
        status, body = _put(
            ticket["uploadUrl"], TINY_PNG, dict(ticket["requiredHeaders"])
        )
        self.assertEqual(status, 200, f"MinIO PUT 실패: {status} {body[:500]!r}")

    def test_guest_upload_is_401(self):
        r = call(
            "POST",
            "/uploads",
            json_body={"purpose": "review", "contentType": "image/png", "bytes": 100},
            install_id=new_install_id(),
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED")

    def test_too_large_and_unsupported_type_are_400(self):
        session = sign_in_google()
        token = session["accessToken"]
        r = call(
            "POST",
            "/uploads",
            json_body={
                "purpose": "review",
                "contentType": "image/jpeg",
                "bytes": 10_485_761,
            },
            token=token,
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "UPLOAD_TOO_LARGE")
        r = call(
            "POST",
            "/uploads",
            json_body={"purpose": "review", "contentType": "image/gif", "bytes": 100},
            token=token,
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "UPLOAD_TYPE_UNSUPPORTED")
        # 경계 — 정확히 10 MB 는 된다
        r = call(
            "POST",
            "/uploads",
            json_body={
                "purpose": "post",
                "contentType": "image/webp",
                "bytes": 10_485_760,
            },
            token=token,
        )
        self.assertEqual(r.status, 201, r)


if __name__ == "__main__":
    import unittest

    unittest.main()
