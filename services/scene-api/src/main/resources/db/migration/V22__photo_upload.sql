-- 사진 올리기 기록 — 누가 어떤 키로 올리겠다고 했는가(POST /uploads).
--
-- 계획: docs/project/plans/review.md §13. 앱은 서명된 주소로 저장소(S3)의 uploads/tmp/ 에 바로 올리고,
-- 리뷰를 저장할 때 그 키를 보낸다. 서버는 이 표로 「이 사용자가 하루 안에 받은 키인가」 를 확인하고, 리뷰에
-- 붙이는 순간 파일을 reviews/ 로 옮긴 뒤 이 행을 지운다. 붙이지 않은 파일은 버킷 수명 규칙이 하루 뒤 지운다.

CREATE TABLE photo_upload (
    storage_key  TEXT PRIMARY KEY,
    -- 탈퇴하면 함께 사라진다 — 아직 아무 데도 붙지 않은 사진이다.
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    purpose      TEXT NOT NULL,
    content_type TEXT NOT NULL,
    bytes        BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT photo_upload_purpose_ck CHECK (purpose IN ('review'))
);

CREATE INDEX photo_upload_user_idx ON photo_upload (user_id, created_at);

COMMENT ON TABLE photo_upload IS
    '받았지만 아직 붙이지 않은 사진. 붙이면 지운다. 하루 지난 것은 쓸 수 없다(버킷에서도 지워진다)';
