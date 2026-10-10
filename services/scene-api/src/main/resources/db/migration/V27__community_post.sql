-- 커뮤니티 여행후기 — 글·사진·코스 사본 (MZ2AZ-352).
--
-- 계획: docs/project/plans/community-post.md. 계약: scene-api 1.10.0 (/posts).
--
-- 코스는 **글 쓴 순간의 사본**이다 — 장소 목록(촬영지·편의시설, 일차·순서·체류)을 떠 둔다. 글쓴이가 코스를 고치거나 지워도
-- 후기는 그대로다. 직접 찍은 핀은 떠 두지 않는다(개인 숙소 위치 — 마켓과 같다). 이름·주소는 떠 두지 않고 읽을 때 지금의
-- 촬영지·편의시설 자료에서 가져온다.

CREATE TABLE community_post (
    id               BIGSERIAL PRIMARY KEY,
    -- 탈퇴해도 글은 남는다 — 글쓴이만 끊는다(「탈퇴한 사용자」, 리뷰와 같다).
    user_id          UUID REFERENCES app_user (id) ON DELETE SET NULL,
    title            TEXT NOT NULL,
    body             TEXT NOT NULL,
    -- 코스를 붙였을 때만. 붙인 순간의 코스 이름·일수.
    course_title     TEXT,
    course_day_count INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at       TIMESTAMPTZ,
    CONSTRAINT community_post_title_ck CHECK (char_length(title) BETWEEN 1 AND 100),
    CONSTRAINT community_post_body_ck  CHECK (char_length(body) BETWEEN 1 AND 5000),
    CONSTRAINT community_post_course_ck
        CHECK ((course_title IS NULL) = (course_day_count IS NULL)
               AND (course_day_count IS NULL OR course_day_count BETWEEN 1 AND 15))
);

COMMENT ON COLUMN community_post.removed_at IS '운영자가 내린 때. 찬 글은 목록·상세 어디에도 나오지 않는다';

CREATE INDEX community_post_recent_idx ON community_post (created_at DESC, id DESC) WHERE removed_at IS NULL;
CREATE INDEX community_post_user_idx ON community_post (user_id, created_at DESC);

CREATE TABLE community_post_photo (
    post_id     BIGINT NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    -- 저장소(S3)의 키 posts/…. 보여 줄 주소는 읽을 때 서명해 만든다(한 시간짜리).
    storage_key TEXT NOT NULL,
    sort_order  SMALLINT NOT NULL,
    PRIMARY KEY (post_id, sort_order),
    CONSTRAINT community_post_photo_order_ck CHECK (sort_order BETWEEN 0 AND 7),
    CONSTRAINT community_post_photo_key_uk   UNIQUE (storage_key)
);

CREATE TABLE community_post_item (
    post_id           BIGINT NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    day_no            INT NOT NULL,
    sort_order        INT NOT NULL,
    place_id          BIGINT REFERENCES place (id) ON DELETE CASCADE,
    poi_id            BIGINT REFERENCES poi (id) ON DELETE CASCADE,
    dwell_min         INT NOT NULL,
    source_content_id BIGINT REFERENCES content (id) ON DELETE SET NULL,
    PRIMARY KEY (post_id, day_no, sort_order),
    CONSTRAINT community_post_item_target_ck CHECK (num_nonnulls(place_id, poi_id) = 1)
);

-- 사진 올리기의 목적에 여행후기(post)를 더한다 — 계약 UploadPurpose 1.10.0. V22 는 review 만 받았다.
ALTER TABLE photo_upload DROP CONSTRAINT photo_upload_purpose_ck;
ALTER TABLE photo_upload ADD CONSTRAINT photo_upload_purpose_ck CHECK (purpose IN ('review', 'post'));
