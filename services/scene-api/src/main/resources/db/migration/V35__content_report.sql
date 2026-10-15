-- 커뮤니티 신고(MZ2AZ-438) — App Store 심사 지침 1.2. 계획: docs/project/plans/community-moderation.md §3.
--
-- 리뷰·여행후기를 신고한다. 사용자(부적절한 닉네임)는 「이 글의 작성자」 로 — 이유가 `inappropriate_nickname` 이다. 앱에
-- 사용자 번호가 없어 글을 기준으로 받는다.
--
-- 대상 글이 나중에 지워져도 무엇이 신고됐는지 알 수 있게, 신고 당시의 작성자와 본문 앞부분을 함께 적는다. 대상 표와 외래 키를
-- 걸지 않는다 — 리뷰·후기 두 표를 가리키고, 글이 지워져도 신고는 남아야 한다.
--
-- 처리(글 내리기·작성자 제한)는 아직 없다 — 쌓인 신고는 SQL 로 본다:
--   SELECT * FROM content_report ORDER BY created_at DESC;

CREATE TABLE content_report (
    id              BIGSERIAL PRIMARY KEY,
    -- 신고한 사람. 비회원도 신고할 수 있다(설치 id 로 찾은 계정). 탈퇴해도 신고는 남는다.
    reporter_id     UUID REFERENCES app_user (id) ON DELETE SET NULL,
    target_type     TEXT NOT NULL,
    target_id       BIGINT NOT NULL,
    reason          TEXT NOT NULL,
    detail          TEXT,
    -- 신고 당시의 작성자와 본문 앞부분(200 자). 글이 지워지거나 고쳐져도 남는다.
    author_id       UUID,
    excerpt         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT content_report_target_ck CHECK (target_type IN ('review', 'post')),
    CONSTRAINT content_report_reason_ck CHECK (reason IN (
        'spam', 'abuse', 'sexual', 'personal_info', 'inappropriate_nickname', 'other')),
    CONSTRAINT content_report_detail_ck CHECK (detail IS NULL OR char_length(detail) <= 500),
    -- 같은 사람이 같은 글을 두 번 신고하면 한 번으로 센다. 탈퇴로 reporter_id 가 빈 줄끼리는 겹쳐도 된다(NULL).
    CONSTRAINT content_report_once_uk UNIQUE (reporter_id, target_type, target_id)
);

COMMENT ON TABLE content_report IS '커뮤니티 신고. 처리 도구는 아직 없다 — SQL 로 본다';
COMMENT ON COLUMN content_report.author_id IS '신고 당시 작성자. 탈퇴했으면 NULL';
COMMENT ON COLUMN content_report.excerpt IS '신고 당시 본문 앞부분(200 자). 후기는 제목과 본문';

CREATE INDEX content_report_target_idx ON content_report (target_type, target_id);
CREATE INDEX content_report_recent_idx ON content_report (created_at DESC);
