"""공통 e2e — 깨진 JSON 400, 없는 경로 404, 오류 응답 모양, 믿을 수 없는 액세스 토큰 401 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「공통」. 기대값은 계약의 `ApiError`(contracts/openapi/scene-api-v1.yaml)와
docs/api/errors.md 에서 왔다. `traceId` 는 500·503 에만 실리고 400·404·409 에는 키가 아예 없다(계약 `ApiError.traceId`).
"""

import base64
import json
import secrets
import time

from support import (
    E2ETestCase,
    RsaKey,
    call,
    jwt_rs256,
    new_install_id,
    sign_in_google,
)


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


class CommonTest(E2ETestCase):
    def assert_client_error_shape(self, r, status: int, code: str):
        """ApiError 모양 — code·message 는 있고, 4xx 라 traceId 키는 없다."""
        self.assertEqual(r.status, status, r)
        self.assertTrue(
            r.headers.get("content-type", "").startswith("application/json"), r.headers
        )
        body = r.json()
        self.assertEqual(body["code"], code, r)
        self.assertIsInstance(body["message"], str)
        self.assertTrue(body["message"])
        self.assertNotIn("traceId", body, "계약상 4xx 에는 traceId 키가 없어야 한다")

    def test_malformed_json_body_is_400(self):
        r = call(
            "POST",
            "/auth/refresh",
            raw_body=b'{"refreshToken": ',
            headers={"Content-Type": "application/json"},
        )
        self.assert_client_error_shape(r, 400, "INVALID_PARAMETER")

    def test_malformed_json_on_authenticated_endpoint_is_400(self):
        s = sign_in_google()
        r = call(
            "PUT",
            "/me/nickname",
            raw_body=b"{nickname: oops",
            token=s["accessToken"],
            headers={"Content-Type": "application/json"},
        )
        self.assert_client_error_shape(r, 400, "INVALID_PARAMETER")
        # 깨진 요청이 닉네임을 바꾸지 않았다
        self.assertEqual(
            call("GET", "/me", token=s["accessToken"]).json()["nickname"],
            s["user"]["nickname"],
        )

    def test_missing_required_body_field_is_400(self):
        r = call("POST", "/auth/refresh", json_body={})
        self.assert_client_error_shape(r, 400, "INVALID_PARAMETER")

    def test_unknown_path_is_404_endpoint_not_found(self):
        r = call("GET", "/no-such-endpoint-e2e", install_id=new_install_id())
        self.assert_client_error_shape(r, 404, "ENDPOINT_NOT_FOUND")

    def test_not_found_entity_error_shape(self):
        r = call("GET", f"/places/{2**62}", install_id=new_install_id())
        self.assert_client_error_shape(r, 404, "PLACE_NOT_FOUND")

    def test_conflict_error_shape_has_no_trace_id(self):
        a, b = sign_in_google(), sign_in_google()
        nick = "e2e_" + secrets.token_hex(5)
        self.assertEqual(
            call(
                "PUT",
                "/me/nickname",
                json_body={"nickname": nick},
                token=a["accessToken"],
            ).status,
            200,
        )
        r = call(
            "PUT", "/me/nickname", json_body={"nickname": nick}, token=b["accessToken"]
        )
        self.assert_client_error_shape(r, 409, "NICKNAME_TAKEN")

    def test_garbage_bearer_token_is_401_access_token_invalid(self):
        r = call("GET", "/me", token="not-a-jwt")
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "ACCESS_TOKEN_INVALID")

    def test_forged_expired_looking_jwt_is_401_access_token_invalid(self):
        # 우리 서버가 서명하지 않은 JWT — 이미 만료된 것처럼 보여도 위조이므로 INVALID(EXPIRED 가 아니다)
        now = int(time.time())
        forged = jwt_rs256(
            RsaKey(), {"sub": "e2e-forged", "iat": now - 7200, "exp": now - 3600}
        )
        r = call("GET", "/me", token=forged)
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "ACCESS_TOKEN_INVALID")

    def test_tampered_access_token_is_401_access_token_invalid(self):
        # 몸통의 sub 를 "다른 진짜 사용자" 의 id 로 바꾼다 — 계정 id 모양은 맞으므로
        # 서버가 서명을 확인하지 않으면 B 의 계정이 열린다. 401 은 서명 탓이어야만 나온다.
        a = sign_in_google()
        b = sign_in_google()
        self.assertNotEqual(a["user"]["id"], b["user"]["id"])
        header, payload, sig = a["accessToken"].split(".")
        claims = json.loads(
            base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        )
        self.assertEqual(claims["sub"], a["user"]["id"], claims)
        claims["sub"] = b["user"]["id"]
        # A 의 머리·서명은 그대로, 몸통만 B 로
        tampered = ".".join([header, _b64url(json.dumps(claims).encode()), sig])
        r = call("GET", "/me", token=tampered)
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "ACCESS_TOKEN_INVALID", r)
        # B 의 자기 토큰은 그대로 통한다 — 401 이 바꿔치기 탓임을 보인다
        me = call("GET", "/me", token=b["accessToken"])
        self.assertEqual(me.status, 200, me)
        self.assertEqual(me.json()["id"], b["user"]["id"])

    def test_invalid_token_is_rejected_even_on_public_endpoint(self):
        # 토큰이 있으면 토큰의 계정이다 — 깨진 토큰을 비회원으로 조용히 넘기지 않는다
        r = call("GET", "/cart", token="not-a-jwt", install_id=new_install_id())
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "ACCESS_TOKEN_INVALID")
