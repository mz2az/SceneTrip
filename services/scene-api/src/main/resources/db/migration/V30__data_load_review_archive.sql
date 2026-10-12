-- 원격 데이터 적재의 기록과, 겹친 리뷰의 보관 (MZ2AZ-414, docs/project/plans/remote-data-load.md §3).

-- ── 적재 기록 ────────────────────────────────────────────────────────────────
--
-- 적재 Job(seed/remote-load.sh)이 끝날 때 한 줄 — 무엇이(커밋·CSV sha256·편의시설 판) 언제 들어갔고 건수가 얼마였나.
-- PRD 에 무엇이 들어 있는지 DB 에서 바로 본다. 모양은 Job 이 정하는 JSON 이라 칸을 늘리지 않고 summary 하나로 둔다.
CREATE TABLE data_load (
    id        BIGSERIAL PRIMARY KEY,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary   JSONB NOT NULL
);

COMMENT ON TABLE data_load IS '원격 데이터 적재 기록 — 커밋·CSV sha256·편의시설 판·건수·걸린 시간(summary)';

-- ── 겹친 리뷰 보관 ───────────────────────────────────────────────────────────
--
-- 같은 곳 연결이 새로 생겨 한 사람의 리뷰가 한 곳에 둘이 되면 place_poi_link.sql 이 오래된 쪽을 지운다(「한 사람 한 곳
-- 하나」, 유일 제약). 지우기 **전에** 그 행과 사진 키를 여기 복사한다 — 사용자 글이 말없이 사라지지 않게. 숨김(removed_at)으로
-- 바꾸지 않은 이유: 유일 제약이 숨긴 행까지 세어 제약·쓰기 쿼리를 함께 고쳐야 한다(권호 2026-10-12, 계획 §3).
--
-- 외래 키를 걸지 않는다 — 보관한 리뷰가 가리키던 촬영지·편의시설·사용자가 나중에 사라져도 보관은 남아야 한다.
CREATE TABLE review_archive (
    id           BIGSERIAL PRIMARY KEY,
    archived_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason       TEXT NOT NULL,
    review_id    BIGINT NOT NULL,
    place_id     BIGINT,
    poi_id       BIGINT,
    user_id      UUID,
    rating       SMALLINT NOT NULL,
    body         TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    photo_keys   TEXT[] NOT NULL DEFAULT '{}',
    kept_review_id BIGINT
);

CREATE INDEX review_archive_user_idx ON review_archive (user_id);

COMMENT ON TABLE review_archive IS '지운 리뷰의 사본. 같은 곳 연결로 한 사람의 리뷰가 겹쳐 오래된 쪽을 지울 때 남긴다';
COMMENT ON COLUMN review_archive.photo_keys IS '그 리뷰 사진의 저장소 키 — 행은 지워져도 파일은 저장소에 남아 있다';
COMMENT ON COLUMN review_archive.kept_review_id IS '대신 남은 리뷰(같은 사람·같은 곳의 최근 것)';
