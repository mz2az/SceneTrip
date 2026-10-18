"""로그인·계정 e2e — 구글 로그인 → /me → 갱신 → 로그아웃, ID 토큰 검증 실패, 애플 미설정, 닉네임, 차단 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「로그인·계정」 「닉네임·차단」. 기대값은 계약(contracts/openapi/scene-api-v1.yaml)에서 왔다.
시험마다 새 구글 계정(sub)·새 설치 ID 를 쓴다 — 다른 시험·다른 실행과 섞이지 않게.
"""

import secrets

from support import (
    FAKE,
    GOOGLE_CLIENT_ID,
    E2ETestCase,
    RsaKey,
    call,
    google_id_token,
    new_install_id,
    sign_in_google,
)

# ID 토큰 서명 실패 시험용 — 가짜 JWKS 와 같은 kid 를 단 다른 키. RSA 키 만들기가 느려 한 번만 만든다.
_OTHER_KEY: RsaKey | None = None


def _other_key_same_kid() -> RsaKey:
    global _OTHER_KEY
    if _OTHER_KEY is None:
        _OTHER_KEY = RsaKey(kid=FAKE.google_key.kid)
    return _OTHER_KEY


def _new_sub() -> str:
    return "e2e-auth-" + secrets.token_hex(8)


def _new_nickname() -> str:
    # 2~16 자, 영문·숫자·_ — 실행마다 겹치지 않게 난수
    return "e2e_" + secrets.token_hex(5)


def _google(id_token: str, nonce: str, install_id: str | None = None):
    return call(
        "POST",
        "/auth/google",
        json_body={"idToken": id_token, "nonce": nonce},
        install_id=install_id or new_install_id(),
    )


class SignInTest(E2ETestCase):
    def test_google_sign_in_returns_session_and_me_is_that_account(self):
        sub = _new_sub()
        email = f"{sub}@e2e.example"
        nonce = secrets.token_urlsafe(16)
        r = _google(google_id_token(sub=sub, email=email, nonce=nonce), nonce)
        self.assertEqual(r.status, 200, r)
        s = r.json()
        self.assertTrue(s["accessToken"])
        self.assertTrue(s["refreshToken"])
        self.assertGreater(s["accessTokenExpiresIn"], 0)
        self.assertGreater(s["refreshTokenExpiresIn"], 0)
        self.assertIs(
            s["isNewUser"], True, "처음 보는 구글 계정인데 가입으로 표시되지 않았다"
        )
        self.assertIs(s["merged"], False)
        self.assertEqual([i["provider"] for i in s["user"]["identities"]], ["google"])
        # 가입할 때 서버가 자동 닉네임을 붙이고 아직 확정 전이다
        self.assertTrue(s["user"].get("nickname"))
        self.assertIs(s["user"].get("nicknameConfirmed"), False)

        me = call("GET", "/me", token=s["accessToken"])
        self.assertEqual(me.status, 200, me)
        body = me.json()
        self.assertEqual(body["id"], s["user"]["id"])
        self.assertEqual(body["email"], email)
        self.assertEqual(body["nickname"], s["user"]["nickname"])

    def test_same_google_account_from_another_install_is_same_user_not_new(self):
        sub = _new_sub()
        first = sign_in_google(sub=sub)
        second = sign_in_google(sub=sub)  # 다른 설치 ID
        self.assertEqual(second["user"]["id"], first["user"]["id"])
        self.assertIs(second["isNewUser"], False)

    def test_id_token_signed_by_other_key_is_401(self):
        nonce = secrets.token_urlsafe(16)
        token = google_id_token(nonce=nonce, key=_other_key_same_kid())
        r = _google(token, nonce)
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SOCIAL_TOKEN_INVALID")

    def test_nonce_mismatch_is_401(self):
        token = google_id_token(nonce=secrets.token_urlsafe(16))
        r = _google(token, secrets.token_urlsafe(16))
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SOCIAL_TOKEN_INVALID")

    def test_wrong_audience_is_401(self):
        nonce = secrets.token_urlsafe(16)
        token = google_id_token(
            nonce=nonce, aud="someone-else.apps.googleusercontent.com"
        )
        self.assertNotEqual("someone-else.apps.googleusercontent.com", GOOGLE_CLIENT_ID)
        r = _google(token, nonce)
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SOCIAL_TOKEN_INVALID")

    def test_expired_id_token_is_401(self):
        nonce = secrets.token_urlsafe(16)
        token = google_id_token(nonce=nonce, expires_in=-3600)
        r = _google(token, nonce)
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SOCIAL_TOKEN_INVALID")

    def test_apple_sign_in_without_configuration_is_501(self):
        r = call(
            "POST",
            "/auth/apple",
            json_body={
                "identityToken": "e2e.identity.token",
                "authorizationCode": "e2e-code",
                "nonce": secrets.token_urlsafe(16),
            },
            install_id=new_install_id(),
        )
        self.assertEqual(r.status, 501, r)
        # 창구가 닫혀 있으면 애플 쪽(가짜)으로 코드 교환을 하러 가지 않는다
        self.assertEqual(FAKE.requests("/apple"), [])


class SessionTest(E2ETestCase):
    def test_refresh_rotates_both_tokens_and_new_access_token_works(self):
        s = sign_in_google()
        r = call("POST", "/auth/refresh", json_body={"refreshToken": s["refreshToken"]})
        self.assertEqual(r.status, 200, r)
        n = r.json()
        self.assertNotEqual(
            n["refreshToken"], s["refreshToken"], "리프레시 토큰을 갈아 주지 않았다"
        )
        self.assertTrue(n["accessToken"])
        self.assertIs(n["isNewUser"], False)
        self.assertIs(n["merged"], False)
        self.assertEqual(n["user"]["id"], s["user"]["id"])
        me = call("GET", "/me", token=n["accessToken"])
        self.assertEqual(me.status, 200, me)
        self.assertEqual(me.json()["id"], s["user"]["id"])

    def test_reused_refresh_token_is_rejected_and_revokes_the_new_one(self):
        s = sign_in_google()
        first = call(
            "POST", "/auth/refresh", json_body={"refreshToken": s["refreshToken"]}
        )
        self.assertEqual(first.status, 200, first)
        rotated = first.json()["refreshToken"]

        reuse = call(
            "POST", "/auth/refresh", json_body={"refreshToken": s["refreshToken"]}
        )
        self.assertEqual(reuse.status, 401, reuse)
        self.assertEqual(reuse.json()["code"], "REFRESH_TOKEN_INVALID")
        # 재사용은 탈취로 본다 — 그 계정의 리프레시 토큰이 전부 폐기된다
        after = call("POST", "/auth/refresh", json_body={"refreshToken": rotated})
        self.assertEqual(after.status, 401, after)
        self.assertEqual(after.json()["code"], "REFRESH_TOKEN_INVALID")

    def test_sign_out_revokes_refresh_token_and_gives_install_a_new_guest(self):
        s = sign_in_google()
        install = s["installId"]
        # 가입한 계정은 설치 UUID 만으로는 열리지 않는다
        before = call("GET", "/cart", install_id=install)
        self.assertEqual(before.status, 401, before)
        self.assertEqual(before.json()["code"], "SESSION_REQUIRED")

        out = call(
            "POST",
            "/auth/sign-out",
            json_body={"refreshToken": s["refreshToken"]},
            install_id=install,
        )
        self.assertEqual(out.status, 204, out)

        refresh = call(
            "POST", "/auth/refresh", json_body={"refreshToken": s["refreshToken"]}
        )
        self.assertEqual(refresh.status, 401, refresh)
        self.assertEqual(refresh.json()["code"], "REFRESH_TOKEN_INVALID")
        # 로그아웃한 설치본은 새 비회원 계정에 짝지어진다
        after = call("GET", "/cart", install_id=install)
        self.assertEqual(after.status, 200, after)
        self.assertEqual(after.json()["totalCount"], 0)

    def test_sign_out_with_unknown_refresh_token_is_204(self):
        r = call(
            "POST",
            "/auth/sign-out",
            json_body={"refreshToken": "e2e-unknown-" + secrets.token_hex(16)},
            install_id=new_install_id(),
        )
        self.assertEqual(r.status, 204, r)

    def test_me_without_token_is_401_access_token_invalid(self):
        r = call("GET", "/me")
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "ACCESS_TOKEN_INVALID")


class NicknameTest(E2ETestCase):
    def test_nickname_change_is_reflected_in_me(self):
        s = sign_in_google()
        nick = _new_nickname()
        r = call(
            "PUT", "/me/nickname", json_body={"nickname": nick}, token=s["accessToken"]
        )
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["nickname"], nick)
        self.assertIs(r.json()["nicknameConfirmed"], True)
        me = call("GET", "/me", token=s["accessToken"]).json()
        self.assertEqual(me["nickname"], nick)
        self.assertIs(me["nicknameConfirmed"], True)

    def test_nickname_trims_surrounding_spaces(self):
        s = sign_in_google()
        nick = _new_nickname()
        r = call(
            "PUT",
            "/me/nickname",
            json_body={"nickname": f"  {nick}  "},
            token=s["accessToken"],
        )
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["nickname"], nick)

    def test_nickname_taken_by_other_user_is_409_and_unchanged(self):
        a, b = sign_in_google(), sign_in_google()
        nick = _new_nickname()
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
        self.assertEqual(r.status, 409, r)
        self.assertEqual(r.json()["code"], "NICKNAME_TAKEN")
        self.assertEqual(
            call("GET", "/me", token=b["accessToken"]).json()["nickname"],
            b["user"]["nickname"],
        )

    def test_nickname_taken_ignores_letter_case(self):
        a, b = sign_in_google(), sign_in_google()
        nick = _new_nickname()  # 소문자
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
            "PUT",
            "/me/nickname",
            json_body={"nickname": nick.upper()},
            token=b["accessToken"],
        )
        self.assertEqual(r.status, 409, r)
        self.assertEqual(r.json()["code"], "NICKNAME_TAKEN")

    def test_too_short_nickname_is_400(self):
        s = sign_in_google()
        r = call(
            "PUT", "/me/nickname", json_body={"nickname": "a"}, token=s["accessToken"]
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "NICKNAME_INVALID")


class BlockTest(E2ETestCase):
    def _a_place_id(self, install_id: str) -> int:
        r = call("GET", "/places?limit=1", install_id=install_id)
        self.assertEqual(r.status, 200, r)
        items = r.json()["items"]
        self.assertTrue(items, "촬영지 목록이 비어 리뷰 쓸 곳이 없다")
        return items[0]["id"]

    def _write_review(self, session: dict, place_id: int) -> int:
        r = call(
            "PUT",
            f"/places/{place_id}/reviews/me",
            json_body={"rating": 4, "body": "e2e 차단 시험용 리뷰", "photoKeys": []},
            token=session["accessToken"],
        )
        self.assertEqual(r.status, 200, r)
        self.addCleanup(
            call,
            "DELETE",
            f"/places/{place_id}/reviews/me",
            token=session["accessToken"],
        )
        return r.json()["id"]

    def _visible_review_ids(self, place_id: int, token: str) -> set[int]:
        r = call(
            "GET", f"/places/{place_id}/reviews?limit=100&sort=recent", token=token
        )
        self.assertEqual(r.status, 200, r)
        return {item["id"] for item in r.json()["items"]}

    def test_block_author_of_review_then_list_then_unblock(self):
        a, b = sign_in_google(), sign_in_google()
        place_id = self._a_place_id(a["installId"])
        review_id = self._write_review(b, place_id)
        self.assertIn(review_id, self._visible_review_ids(place_id, a["accessToken"]))

        created = call(
            "POST",
            "/me/blocks",
            json_body={"targetType": "review", "targetId": review_id},
            token=a["accessToken"],
        )
        self.assertEqual(created.status, 201, created)
        block = created.json()
        self.assertEqual(
            block["nickname"],
            b["user"]["nickname"],
            "차단한 사람이 리뷰 작성자가 아니다",
        )
        self.assertTrue(block["blockedAt"])

        listed = call("GET", "/me/blocks", token=a["accessToken"])
        self.assertEqual(listed.status, 200, listed)
        self.assertEqual([x["id"] for x in listed.json()["items"]], [block["id"]])
        # 차단하면 내가 볼 때 그 사람의 리뷰가 빠진다
        self.assertNotIn(
            review_id, self._visible_review_ids(place_id, a["accessToken"])
        )

        again = call(
            "POST",
            "/me/blocks",
            json_body={"targetType": "review", "targetId": review_id},
            token=a["accessToken"],
        )
        self.assertEqual(again.status, 200, again)
        self.assertEqual(again.json()["id"], block["id"])

        deleted = call("DELETE", f"/me/blocks/{block['id']}", token=a["accessToken"])
        self.assertEqual(deleted.status, 204, deleted)
        self.assertEqual(
            call("GET", "/me/blocks", token=a["accessToken"]).json()["items"], []
        )
        self.assertIn(review_id, self._visible_review_ids(place_id, a["accessToken"]))

        gone = call("DELETE", f"/me/blocks/{block['id']}", token=a["accessToken"])
        self.assertEqual(gone.status, 404, gone)
        self.assertEqual(gone.json()["code"], "BLOCK_NOT_FOUND")

    def test_block_does_not_hide_review_from_other_users(self):
        a, b, c = sign_in_google(), sign_in_google(), sign_in_google()
        place_id = self._a_place_id(a["installId"])
        review_id = self._write_review(b, place_id)
        r = call(
            "POST",
            "/me/blocks",
            json_body={"targetType": "review", "targetId": review_id},
            token=a["accessToken"],
        )
        self.assertEqual(r.status, 201, r)
        # 한쪽 방향 — 차단하지 않은 사람에게는 그대로 보인다
        self.assertIn(review_id, self._visible_review_ids(place_id, c["accessToken"]))

    def test_blocking_own_review_is_400(self):
        a = sign_in_google()
        place_id = self._a_place_id(a["installId"])
        review_id = self._write_review(a, place_id)
        r = call(
            "POST",
            "/me/blocks",
            json_body={"targetType": "review", "targetId": review_id},
            token=a["accessToken"],
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "CANNOT_BLOCK_SELF")

    def test_blocks_without_sign_in_is_401(self):
        r = call("GET", "/me/blocks", install_id=new_install_id())
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED")
