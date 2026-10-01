-- 로그인 — 소셜 신분과 리프레시 토큰 (MZ2AZ-331).
--
-- 계획: docs/project/plans/social-login.md §4. 결정: ADR 0018.
-- 여기 주석은 "왜 이렇게 썼는가" 만 적는다.
--
-- ── V15 를 쓴다 — 「코스 담기」 의 예약을 가져온다 ─────────────────────────────
--
-- docs/project/plans/poi.md 가 V15 를 「코스 담기」(V15__course_item_poi.sql)에 잡아 두었는데
-- 그 작업은 시작 전이다. 예약을 피해 V16 으로 가면 V15 가 빈 채로 남고, 나중에 V15 가 들어올
-- 때 이미 V16 이 적용된 DB 에서 Flyway 검증이 실패해 기동이 멈춘다(outOfOrder 를 켜지 않는 한).
-- 빈 번호를 남기는 쪽이 더 위험하므로 V15 를 쓰고, 코스 담기는 그때 비어 있는 다음 번호를 쓴다.

-- ── app_user 는 건드리지 않는다 ─────────────────────────────────────────────
--
-- 가입 표시(registered_at)와 합쳐진 흔적(merged_into)이 V8 에 이미 있다. 로그인은 그 행에
-- 신분을 붙이는 일이라 새 표 둘이면 된다.

-- ───────────── 소셜 신분 ─────────────
--
-- 기본키가 (provider, subject) 인 것이 이 표의 전부다. 같은 구글 계정이 두 계정에 붙는 것을
-- DB 가 막는다 — 같은 소셜 계정으로 두 설치본이 동시에 첫 로그인하면 늦은 쪽이 여기서
-- 기본키 위반으로 떨어지고, 애플리케이션이 그것을 잡아 합치기 경로로 다시 간다.
--
-- 이메일로 사람을 가르지 않는다. 애플은 가리기를 고르면 대리 주소를 준다. 그래서 email 에는
-- UNIQUE 도 인덱스도 없다.
--
-- 표를 app_user 에서 떼어 둔 것은 한 계정에 구글과 애플을 함께 붙일 자리를 남기려는 것이다.
-- 지금 그 창구는 없다(계획 §11).
CREATE TABLE user_identity (
    provider     TEXT NOT NULL
        CONSTRAINT user_identity_provider_check CHECK (provider IN ('google', 'apple')),
    subject      TEXT NOT NULL,
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    email        TEXT,
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);

COMMENT ON TABLE user_identity IS
    '계정에 붙은 소셜 신분. 사람의 구분은 (provider, subject) 이지 이메일이 아니다';
COMMENT ON COLUMN user_identity.subject IS '구글·애플 ID 토큰의 sub. 그 제공자 안에서 사람마다 고정이다';
COMMENT ON COLUMN user_identity.email IS '참고용. 애플은 @privaterelay.appleid.com 대리 주소일 수 있다';
COMMENT ON COLUMN user_identity.display_name IS '애플은 첫 로그인에만 이름을 준다 — 그때 채우고 이후 비어 와도 덮어쓰지 않는다';

-- 탈퇴(DELETE /me)와 /me 가 계정에서 신분을 찾는다.
CREATE INDEX user_identity_user_idx ON user_identity (user_id);

-- 애플 refresh token(탈퇴 때 애플 쪽 연결을 끊는 데 쓴다)은 여기 두지 않는다. 애플 로그인이
-- 들어올 때 그 칸을 함께 더한다 — 지금 만들면 무엇으로 암호화할지 정하지 않은 채 칸만 생긴다.

-- ───────────── 리프레시 토큰 ─────────────
--
-- **원문을 저장하지 않는다.** SHA-256 만 둔다. 이 표가 새어도 그 값으로는 갱신할 수 없다.
-- 난수가 32 바이트라 무차별 대입이 의미가 없으므로 솔트도 필요 없다 — 해시가 같으면 같은
-- 토큰이라는 성질을 그대로 조회 열쇠로 쓴다.
--
-- 쓸 때마다 새 것으로 바꾼다. 쓴 것은 지우지 않고 used_at 을 채워 남긴다 — 그것이 다시 오면
-- 탈취로 판정해야 하기 때문이다. 지우면 「처음 보는 토큰」 과 구분이 안 된다.
--
-- family_id 는 한 번의 로그인에서 이어진 교체 사슬이다. 로그아웃이 그 사슬만 끊는다(다른
-- 설치본의 로그인은 그대로). 재사용을 잡으면 사슬이 아니라 그 계정의 토큰 전부를 끊는다 —
-- 도둑과 주인 중 누가 먼저 썼는지 서버는 모른다.
CREATE TABLE refresh_token (
    id           UUID PRIMARY KEY,
    token_hash   BYTEA NOT NULL,
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id    UUID NOT NULL,
    install_uuid UUID,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT refresh_token_hash_uk UNIQUE (token_hash)
);

COMMENT ON TABLE refresh_token IS
    '일회용 리프레시 토큰. 원문은 없고 SHA-256 만 있다. 쓴 것은 used_at 을 채워 남긴다 — 다시 오면 재사용(탈취)이다';
COMMENT ON COLUMN refresh_token.family_id IS '한 번의 로그인에서 이어진 교체 사슬. 로그아웃은 이 사슬만 끊는다';
COMMENT ON COLUMN refresh_token.install_uuid IS '어느 설치본의 로그인인가. 기록용 — 판정에는 쓰지 않는다';

-- 재사용을 잡았을 때와 탈퇴 때 계정의 토큰을 한꺼번에 끊는다.
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
-- 로그아웃이 사슬을 끊는다.
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);

-- ───────────── 합쳐진 계정 찾기 ─────────────
--
-- 「이 계정으로 합쳐진 빈 비회원 행」 을 찾는 일이 둘 생긴다. 합치기가 끝난 뒤 그 빈 행에 늦게
-- 들어온 쓰기를 쓸어 오는 일(계획 §5, 토큰 갱신 때마다)과, 탈퇴할 때 그 행들을 먼저 치우는 일
-- (merged_into 의 FK 에 CASCADE 가 없다)이다. app_user 는 비회원이 쌓이는 표라 순차 탐색은 갈수록
-- 무거워진다. 합쳐진 행은 소수이므로 그것만 담는 부분 인덱스로 둔다.
CREATE INDEX app_user_merged_into_idx ON app_user (merged_into) WHERE merged_into IS NOT NULL;

-- ───────────── V8 주석 바로잡기 ─────────────
--
-- 헤더 이름이 X-Device-Id 에서 X-Install-Id 로 바뀌었다(MZ2AZ-328). V8 을 고치면 적용된 DB 에서
-- 체크섬이 어긋나므로 여기서 다시 단다.
COMMENT ON COLUMN user_device.install_uuid IS '앱이 최초 실행에 만들어 보관하는 값. X-Install-Id 헤더로 온다';
