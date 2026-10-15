-- 사용자 차단(MZ2AZ-438) — App Store 심사 지침 1.2. 계획: docs/project/plans/community-moderation.md §4.
--
-- 로그인한 사람이 「이 리뷰·후기의 작성자」 를 차단한다. 차단한 사람이 볼 때 차단당한 사람의 리뷰·후기·사진이 목록에서 빠진다
-- (서버에서 거른다 — 앱은 작성자를 모르고, 앱에서 빼면 쪽 나누기가 깨진다). 한쪽 방향이고, 차단당한 사람에게 알리지 않는다.
-- 어느 쪽이 탈퇴하면 그 줄은 함께 지운다(CASCADE).

CREATE TABLE user_block (
    -- 풀 때 쓰는 번호. 앱에 사용자 번호를 내보내지 않으려고 줄 번호로 푼다.
    id          BIGSERIAL PRIMARY KEY,
    blocker_id  UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    blocked_id  UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_block_pair_uk UNIQUE (blocker_id, blocked_id),
    CONSTRAINT user_block_not_self_ck CHECK (blocker_id <> blocked_id)
);

COMMENT ON TABLE user_block IS '사용자 차단. 차단한 사람이 볼 때 차단당한 사람의 글을 목록에서 뺀다';

-- 목록을 거를 때 「이 사람이 차단한 사람들」 을 찾는다 — 짝 UNIQUE 의 앞 칸이 blocker_id 라 그 인덱스를 탄다.
