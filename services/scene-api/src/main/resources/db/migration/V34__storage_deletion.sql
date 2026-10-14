-- 지울 저장소 키 목록(MZ2AZ-406) — 글을 지우면 사진 파일도 지운다. 계획: docs/project/plans/post-photo-deletion.md.
--
-- 글을 지운 뒤 저장소를 바로 부르기만 하면, 저장소가 실패했을 때 어떤 파일이었는지 기록이 글과 함께 사라져 다시 지울
-- 길이 없다. 그래서 글을 지우는 트랜잭션 안에서 사진 키를 여기에 먼저 적는다(outbox). 저장소에서 지우는 일은 1 분마다
-- 도는 작업 하나가 맡고, 지운 키는 뺀다. 실패한 키는 남아 다음 차례에 다시 지워진다.
--
-- 글과 외래 키를 걸지 않는다 — 글은 이미 지워진 뒤다. 이름이 storage_deletion 인 것은 리뷰 사진도 같은 길로 올 수
-- 있게 하려는 것이다(지금은 후기만).

CREATE TABLE storage_deletion (
    storage_key TEXT PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts    INT NOT NULL DEFAULT 0,
    last_error  TEXT
);

COMMENT ON TABLE storage_deletion IS
    '지울 사용자 사진 키. 지우면 줄이 빠진다 — 남은 줄은 아직 저장소에서 지우지 못한 것';
COMMENT ON COLUMN storage_deletion.attempts IS '지우기에 실패한 횟수';
COMMENT ON COLUMN storage_deletion.last_error IS '마지막 실패 내용';

-- 지우기 작업이 오래된 것부터 읽는다.
CREATE INDEX storage_deletion_created_idx ON storage_deletion (created_at);
