-- 애플 로그인 — 탈퇴 때 애플 쪽 연결을 끊는 데 쓸 토큰 (MZ2AZ-337).
--
-- 계획: docs/project/plans/social-login.md §5·§6. 결정: ADR 0018.
--
-- ── 왜 저장하는가 ──────────────────────────────────────────────────────────
--
-- 애플 로그인을 쓰는 앱은 회원 탈퇴 때 애플에 연결 끊기(revoke)를 알려야 한다(App Store 요건).
-- revoke 에는 그 사용자의 애플 refresh token 을 실어야 하는데, 앱이 로그인 때 주는 것은 5 분짜리
-- 일회용 인가 코드뿐이다. 그래서 로그인할 때 서버가 그 코드를 애플과 교환해 받은 refresh token 을
-- 여기 둔다.
--
-- ── 왜 해시가 아니라 암호문인가 ────────────────────────────────────────────
--
-- 우리 리프레시 토큰(refresh_token 표)은 해시만 둔다 — 앱이 보낸 값과 같은지만 보면 되기 때문이다.
-- 애플 토큰은 탈퇴 때 **원문을 애플에 보내야** 하므로 되돌릴 수 있어야 한다. AES-256-GCM 으로
-- 암호화해 둔다(auth.TokenCipher). 키는 DB 가 아니라 비밀 저장소에 있다(SCENETRIP_AUTH_TOKEN_ENCRYPTION_KEY)
-- — 이 표가 새어도 키 없이는 풀 수 없다.
--
-- 애플로 로그인할 때마다 새 토큰을 받아 덮어쓴다. 키를 잃거나 바꿔도 다음 로그인에서 새 키로
-- 다시 저장되고, 탈퇴는 로그인한 상태에서만 할 수 있으므로 실제로 풀지 못하는 일은 드물다.
ALTER TABLE user_identity ADD COLUMN apple_refresh_token_enc BYTEA;

COMMENT ON COLUMN user_identity.apple_refresh_token_enc IS
    '애플만. 탈퇴 때 revoke 에 쓸 애플 refresh token 의 AES-256-GCM 암호문(12 바이트 nonce + 암호문). 원문은 두지 않는다';
