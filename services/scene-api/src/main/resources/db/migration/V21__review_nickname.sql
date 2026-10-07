-- 리뷰·별점(촬영지·편의시설 상세)과 작성자로 보일 닉네임.
--
-- 계획: docs/project/plans/review.md (§3 표, §12 닉네임). 계약: scene-api 1.4.0.

-- ───────────── 닉네임 ─────────────
--
-- 소셜 이름(user_identity.display_name)은 실명일 수 있어 남에게 보이지 않는다. 가입할 때 서버가
-- 「여행자 + 번호」 를 붙이고(nickname_confirmed = false), 사용자가 PUT /me/nickname 으로 정하면 true.
-- 번호는 시퀀스라 자동 닉네임끼리 겹치지 않는다 — 사용자가 「여행자 + 숫자」 꼴을 고르는 것은 서버가 막는다.

ALTER TABLE app_user
    ADD COLUMN nickname           TEXT,
    ADD COLUMN nickname_confirmed BOOLEAN NOT NULL DEFAULT false;

CREATE SEQUENCE app_user_nickname_seq START WITH 10000;

-- 이미 가입한 계정에도 붙인다. 비회원은 닉네임이 없다 — 리뷰를 쓸 수 없고 보일 곳이 없다.
UPDATE app_user
SET nickname = '여행자' || nextval('app_user_nickname_seq')
WHERE registered_at IS NOT NULL AND nickname IS NULL;

-- 영문 대소문자를 가리지 않고 유일하다(계약 PUT /me/nickname).
CREATE UNIQUE INDEX app_user_nickname_uk ON app_user (lower(nickname));

COMMENT ON COLUMN app_user.nickname IS
    '리뷰·커뮤니티에 작성자로 보이는 이름. 가입할 때 「여행자 + 번호」 를 붙인다. 비회원은 NULL';
COMMENT ON COLUMN app_user.nickname_confirmed IS
    '사용자가 직접 정했는가. false 면 앱이 로그인 뒤 닉네임 정하기 화면을 띄운다';

-- ───────────── 리뷰 ─────────────

CREATE TABLE review (
    id         BIGSERIAL PRIMARY KEY,
    -- 촬영지 리뷰와 편의시설 리뷰는 따로 모인다(같은 곳이어도 합치지 않는다, 계획 §2). 한 줄은 둘 중 하나다.
    place_id   BIGINT REFERENCES place (id) ON DELETE CASCADE,
    poi_id     BIGINT REFERENCES poi (id) ON DELETE CASCADE,
    -- 탈퇴해도 리뷰는 남는다 — 작성자만 끊는다(「탈퇴한 사용자」). 다른 사용자 데이터는 CASCADE 로 지워진다.
    user_id    UUID REFERENCES app_user (id) ON DELETE SET NULL,
    rating     SMALLINT NOT NULL,
    body       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    CONSTRAINT review_one_target_ck CHECK ((place_id IS NULL) <> (poi_id IS NULL)),
    CONSTRAINT review_rating_ck     CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT review_body_ck       CHECK (body IS NULL OR char_length(body) <= 2000),
    -- 한 사람이 한 곳에 하나. 탈퇴로 user_id 가 빈 줄끼리는 겹쳐도 된다(NULL).
    CONSTRAINT review_user_place_uk UNIQUE (user_id, place_id),
    CONSTRAINT review_user_poi_uk   UNIQUE (user_id, poi_id)
);

COMMENT ON TABLE review IS
    '촬영지·편의시설 리뷰. place_id 와 poi_id 중 하나만 찬다. 촬영지·POI 는 지우지 않고 숨기므로(V18·V20) CASCADE 로 사라질 일은 출처를 갈 때뿐';
COMMENT ON COLUMN review.user_id IS '작성자. 탈퇴하면 NULL — 글·별점·사진은 남는다';
COMMENT ON COLUMN review.removed_at IS '운영자가 내린 때. 찬 리뷰는 목록·평균·사진첩 어디에도 나오지 않는다';

CREATE INDEX review_place_idx ON review (place_id, created_at DESC) WHERE removed_at IS NULL AND place_id IS NOT NULL;
CREATE INDEX review_poi_idx   ON review (poi_id, created_at DESC)   WHERE removed_at IS NULL AND poi_id IS NOT NULL;
CREATE INDEX review_user_idx  ON review (user_id, created_at DESC);

CREATE TABLE review_image (
    id          BIGSERIAL PRIMARY KEY,
    review_id   BIGINT NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    -- 저장소(S3)의 키. 보여 줄 주소는 읽을 때 서명해 만든다 — 주소가 한 시간마다 바뀌어 저장할 수 없다.
    storage_key TEXT NOT NULL,
    sort_order  SMALLINT NOT NULL,
    CONSTRAINT review_image_order_ck CHECK (sort_order BETWEEN 0 AND 9),
    CONSTRAINT review_image_order_uk UNIQUE (review_id, sort_order),
    CONSTRAINT review_image_key_uk   UNIQUE (storage_key)
);

COMMENT ON TABLE review_image IS
    '리뷰 사진, 리뷰당 최대 10 장(sort_order 0~9). 상세 사진첩에서는 우리 사진(place_image · poi_image) 뒤에 온다';
