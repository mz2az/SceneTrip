"""e2e 바탕이 붙어 있는지 — 가짜 구글 키로 서명한 ID 토큰으로 진짜 로그인이 되고 /me 가 그 사용자를 돌려준다 (MZ2AZ-496).

가짜 JWKS 를 받으러 왔는지는 보지 않는다 — 서명 키를 실행 사이에 고정해(support.RsaKey.persistent) 서버가 캐시한 공개키로 검증하기
때문이다. 그 키는 가짜 JWKS 만 내므로 로그인이 되면 서버가 가짜를 본 것이다.
"""

from support import E2ETestCase, call, sign_in_google


class HarnessTest(E2ETestCase):
    def test_google_sign_in_with_fake_key_and_me_returns_user(self):
        session = sign_in_google()
        me = call("GET", "/me", token=session["accessToken"])
        self.assertEqual(me.status, 200, me)
        self.assertEqual(me.json()["id"], session["user"]["id"])
