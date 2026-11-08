-- 커뮤니티 보강 — 게시판·좋아요·댓글·댓글 신고 (MZ2AZ-538, 계약 1.24.0, 계획 docs/project/plans/community-social.md).
--
-- 2026-10-03 팀 회의의 「여행후기 하나」 를 뒤집어 자유게시판을 다시 연다. 기존 글은 전부 여행후기다.
-- 옛 앱은 게시판을 모르므로 GET /posts 는 board 를 주지 않으면 여행후기만 준다(PostStore).

ALTER TABLE community_post
    ADD COLUMN board TEXT NOT NULL DEFAULT 'review',
    ADD CONSTRAINT community_post_board_ck CHECK (board IN ('review', 'free'));

COMMENT ON COLUMN community_post.board IS '게시판 — review 여행후기, free 자유게시판(계약 TripPostBoard)';

-- 게시판별 최신순 목록(커서 페이지의 (created_at, id) 비교도 이 색인을 탄다).
CREATE INDEX community_post_board_recent_idx
    ON community_post (board, created_at DESC, id DESC) WHERE removed_at IS NULL;

-- 좋아요 — 한 사람이 한 글에 하나(멱등). 글이나 사람이 지워지면 함께 지운다(탈퇴는 app_user 를 지운다 — UserStore.delete).
CREATE TABLE community_post_like (
    post_id    BIGINT NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX community_post_like_user_idx ON community_post_like (user_id);

-- 댓글 — 오래된 순으로 읽는다. 탈퇴하면 그 사람의 댓글도 지운다(글과 같다, MZ2AZ-466).
CREATE TABLE community_comment (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    body       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    CONSTRAINT community_comment_body_ck CHECK (char_length(body) BETWEEN 1 AND 500)
);

COMMENT ON COLUMN community_comment.removed_at IS '운영자가 내린 때. 찬 댓글은 어디에도 나오지 않는다';

CREATE INDEX community_comment_post_idx
    ON community_comment (post_id, created_at, id) WHERE removed_at IS NULL;
CREATE INDEX community_comment_user_idx ON community_comment (user_id);

-- 신고 대상에 댓글(계약 ReportTargetType 1.24.0).
ALTER TABLE content_report DROP CONSTRAINT content_report_target_ck;
ALTER TABLE content_report
    ADD CONSTRAINT content_report_target_ck CHECK (target_type IN ('review', 'post', 'comment'));
