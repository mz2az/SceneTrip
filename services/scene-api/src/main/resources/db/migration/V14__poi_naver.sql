-- 편의시설 카드 — 바깥 출처(네이버 장소)에서 가져온 결과를 둔다.
--
-- 계획: docs/project/plans/poi-card.md. 결정: ADR 0011(데모 한정, 정식 전에 대체).
--
-- ── 표 하나인 이유 ────────────────────────────────────────────────────────
--
-- 프로토타입은 매칭 결과(naver_match.jsonl)와 상세(메모리)를 따로 뒀다. 우리는 매칭되면
-- 바로 상세까지 받아 같이 저장하므로 나눌 이유가 없다. 한 행 = 「이 POI 를 출처에서 찾아본
-- 결과 전부」.
--
-- ── 상태 셋이 서로 다르게 표현된다 ────────────────────────────────────────
--
--   안 물어봄 / 결과를 못 받음(타임아웃·차단)   행 없음     ← 다음에 다시 묻는다
--   물어봤는데 없음                           found=false, why
--   있음                                      found=true, 상세
--
-- 「못 받음」을 found=false 로 저장하면 「없다」로 굳어 버린다. 그래서 그 경우는 행을 만들지
-- 않는다. pending(줄에 서 있음)도 저장하지 않는다 — 줄은 메모리에 있고, 표에 두면 둘이
-- 어긋날 수 있다.
--
-- ── rule_version ──────────────────────────────────────────────────────────
--
-- 판정 규칙(match_ok)을 고치면 값을 올린다. 조회는 현재 판만 보므로 옛 판의 행은 없는
-- 것처럼 보여 다시 물어 덮어쓴다. 프로토타입이 캐시 키에 v2| 를 붙이던 것의 표 버전이다.
-- 표를 지울 필요가 없다.

CREATE TABLE poi_naver (
    poi_id        BIGINT PRIMARY KEY REFERENCES poi (id) ON DELETE CASCADE,

    found         BOOLEAN NOT NULL,
    why           TEXT,                 -- found=false 일 때 이유 한 줄
    rule_version  TEXT NOT NULL,
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- found=true 일 때만. 출처의 상세 응답을 그대로 눕힌 것. 출처에 없는 값은 NULL 이다 —
    -- 별점 없는 가게가 많고, 0 으로 채우면 「최악의 평점」으로 읽힌다.
    naver_id      TEXT,
    name          TEXT,
    category      TEXT,
    address       TEXT,
    phone         TEXT,
    hours         TEXT,
    score         NUMERIC(3, 2),
    review_count  INTEGER,
    blog_reviews  INTEGER,
    images        TEXT[] NOT NULL DEFAULT '{}',
    url           TEXT,

    -- 찾았다면서 id 가 없거나, 못 찾았다면서 id 가 있는 행은 못 들어온다.
    CONSTRAINT poi_naver_found_check CHECK (found = (naver_id IS NOT NULL))
);

COMMENT ON TABLE poi_naver IS '편의시설 카드 — 바깥 출처 조회 결과. 행 없음 = 아직 안 물어봄';
COMMENT ON COLUMN poi_naver.rule_version IS '판정 규칙의 판. 현재 판만 조회한다';
COMMENT ON COLUMN poi_naver.checked_at IS '언제 확인한 정보인가. 지금은 재확인하지 않는다';
