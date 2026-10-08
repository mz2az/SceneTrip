-- 유료 API 사용량과 가이드 챗봇 멱등 키 (MZ2AZ-334, docs/project/plans/rate-limit.md §3·§5).

-- ───────────── 유료 API 사용량 ─────────────
--
-- (누가, 무엇을, 어느 창) 하나에 한 줄. 창은 그 창이 시작한 시각이다 — 1 분 창이면 그 분의 0 초, 하루 창이면 한국 시간
-- 자정. 늘리기는 원자적 UPSERT 한 번(INSERT … ON CONFLICT DO UPDATE SET count = count + 1 RETURNING count)이라 서버가
-- 여러 대여도 정확하다. 일반 요청의 분당 제한은 여기가 아니라 서버 메모리다 — 모든 요청마다 DB 에 쓰지 않는다.
CREATE TABLE usage_counter (
    subject      TEXT NOT NULL,
    feature      TEXT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    count        INT NOT NULL,
    PRIMARY KEY (subject, feature, window_start)
);

-- 오래된 창을 지우는 데 쓴다(이틀 지난 것).
CREATE INDEX usage_counter_window_idx ON usage_counter (window_start);

COMMENT ON TABLE usage_counter IS
    '유료 API(가이드 챗봇·길찾기)의 창별 사용량. subject 는 계정 id. 일반 분당 제한은 서버 메모리다';

-- ───────────── 가이드 챗봇 멱등 키 ─────────────
--
-- 같은 키의 턴은 한 번만 처리하고, 그 뒤에는 저장한 답을 그대로 준다 — 재시도해도 모델을 다시 부르지 않고 한도도 한 번만
-- 깎인다. 판정은 이 표에 (계정, 키) 를 넣어 보는 것 자체다(PK 충돌) — 먼저 조회하면 동시에 온 둘이 모두 「없음」 을 본다.
CREATE TABLE idempotency_key (
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    key          TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    state        TEXT NOT NULL,
    response     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key),
    CONSTRAINT idempotency_key_state_ck CHECK (state IN ('processing', 'completed'))
);

CREATE INDEX idempotency_key_created_idx ON idempotency_key (created_at);

COMMENT ON TABLE idempotency_key IS
    '가이드 챗봇 턴의 멱등 키(IETF draft-ietf-httpapi-idempotency-key-header). 24 시간 보관. 처리 실패면 지운다';
COMMENT ON COLUMN idempotency_key.request_hash IS '요청 본문의 해시 — 같은 키에 다른 내용이면 422';
