-- 운영자가 내린 기록(MZ2AZ-569, docs/project/plans/moderation-operator.md §2).
-- 내리기는 지우지 않는다 — 글·댓글·리뷰의 removed_at(「운영자가 내린 때」) 을 채운다. 이 표는 누가 언제 무엇을 내렸는지만 남긴다.
-- 새로 내렸을 때만 한 줄. 되돌리기는 SQL 로 removed_at 을 비운다.

CREATE TABLE moderation_action (
    id          BIGSERIAL PRIMARY KEY,
    -- 내린 운영자. 계정을 지워도 기록은 남는다.
    operator_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    target_type TEXT NOT NULL
        CONSTRAINT moderation_action_target_ck CHECK (target_type IN ('review', 'post', 'comment')),
    target_id   BIGINT NOT NULL,
    action      TEXT NOT NULL DEFAULT 'remove'
        CONSTRAINT moderation_action_action_ck CHECK (action IN ('remove')),
    -- 내릴 때의 본문 앞 200 자. 글은 「제목 — 본문」.
    excerpt     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE moderation_action IS '운영자가 글·댓글·리뷰를 내린 기록(MZ2AZ-569). 내린 것 자체는 각 표의 removed_at';

CREATE INDEX moderation_action_target_idx ON moderation_action (target_type, target_id);
CREATE INDEX moderation_action_recent_idx ON moderation_action (created_at DESC);
