-- 편의시설 출처 번호 기록 — 가게 하나 = 편의시설 하나, 출처 번호는 여러 장 (MZ2AZ-491).
--
-- 계획: docs/project/plans/poi-load-cleanup.md §3. 결정: ADR 0022(출처 번호 여럿 · 지우지 않는다).
--
-- 지금은 poi.source_id 하나뿐이라, 처음 적재 때 다른 편의시설에 합쳐진 줄(관광공사 → 상가정보 11,664 등 22,818)의 번호가
-- DB 어디에도 없다. 그래서 그 번호로 오는 관광공사 상세를 붙일 수 없고, 다음 판에 그 번호가 다시 오면 같은 가게인 줄 모른다.
-- 이 표가 「이 번호 → 이 편의시설」 을 남긴다. poi.source_id 는 대표 번호로 그대로 둔다 — 그 칸을 읽는 코드가 깨지지 않게.
--
-- ── 채우기 ───────────────────────────────────────────────────────────────────
--
-- 여기서는 DB 안의 정보만으로 채운다 — 운영·로컬이 각자 자기 데이터로 채운다(편의시설 번호 poi.id 는 DB 마다 다를 수 있다).
--   1. 모든 편의시설의 대표 번호
--   2. 관광공사 영문 상세의 짝 — 영문은 번호 체계가 국문과 따로라(같은 번호 0) 편의시설을 만들지 않고, 지금 poi_detail(en) 에
--      이미 짝지어진 결과를 `tour-en-<번호>` 로 남긴다. 다음부터는 이름·거리로 다시 찾지 않고 번호로 찾는다.
-- 합쳐진 줄의 번호(22,818)는 재구성 파일이 필요해 데이터 적재(seed/poi_source_merged.sql)가 넣는다.
--
-- ── active ──────────────────────────────────────────────────────────────────
--
-- 그 출처가 이 번호를 아직 내보내나. 판에서 빠지거나 관광공사가 표출을 멈추면(showflag=0) false — 행은 지우지 않는다.
-- 상세·사진은 active 가 false 인 번호의 것을 내보내지 않는다(아래 두 뷰). 기록이 없는 번호는 막지 않는다 — 막는 것은 「멈췄다」 고
-- 기록된 번호뿐이다.

CREATE TABLE poi_source (
    source_id  TEXT PRIMARY KEY,
    poi_id     BIGINT NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    source     TEXT NOT NULL
        CONSTRAINT poi_source_source_check CHECK (source IN ('store', 'tour', 'tour_en', 'transit')),
    active     BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX poi_source_poi_idx ON poi_source (poi_id);

COMMENT ON TABLE poi_source IS
    '편의시설 출처 번호. 한 편의시설에 여럿(상가정보·관광공사 국문·관광공사 영문·교통). poi.source_id 는 그중 대표';
COMMENT ON COLUMN poi_source.source IS
    'store(상가정보 MA…) · tour(관광공사 국문 tour-…) · tour_en(관광공사 영문 tour-en-…, 상세만 붙는다) · transit(교통)';
COMMENT ON COLUMN poi_source.active IS
    '출처가 아직 내보내나. 판에서 빠지거나 표출 중단이면 false. 행은 지우지 않는다';

-- 1. 대표 번호. 접두어로 출처를 안다 — 적재(poi.sql)의 origin 과 같은 규칙.
INSERT INTO poi_source (source_id, poi_id, source)
SELECT source_id,
       id,
       CASE WHEN source_id LIKE 'MA%'   THEN 'store'
            WHEN source_id LIKE 'tour-%' THEN 'tour'
            ELSE 'transit' END
FROM poi;

-- 2. 관광공사 영문 상세의 짝.
INSERT INTO poi_source (source_id, poi_id, source)
SELECT 'tour-en-' || tour_content_id, poi_id, 'tour_en'
FROM poi_detail
WHERE lang = 'en'
ON CONFLICT (source_id) DO NOTHING;

ANALYZE poi_source;

-- ── 내보낼 상세·사진 ─────────────────────────────────────────────────────────
--
-- 서버가 poi_detail·poi_image 대신 이 뷰를 읽는다. 관광공사가 표출을 멈춘 번호(active = false)의 상세·사진만 빠진다.
-- 다시 표출되면 그대로 다시 보인다(행을 지우지 않으니까). 관광공사 번호가 없는 사진(우리가 모은 것 등)은 늘 보인다.

CREATE VIEW poi_detail_visible AS
SELECT d.*
FROM poi_detail d
WHERE NOT EXISTS (
    SELECT 1 FROM poi_source s
    WHERE s.source_id = CASE WHEN d.lang = 'ko' THEN 'tour-' ELSE 'tour-en-' END || d.tour_content_id
      AND NOT s.active);

CREATE VIEW poi_image_visible AS
SELECT i.*
FROM poi_image i
WHERE i.tour_content_id IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM poi_source s
       WHERE s.source_id IN ('tour-' || i.tour_content_id, 'tour-en-' || i.tour_content_id)
         AND NOT s.active);

COMMENT ON VIEW poi_detail_visible IS '내보낼 관광공사 상세 — 표출 중단된 관광공사 번호의 것만 뺀다';
COMMENT ON VIEW poi_image_visible IS '내보낼 편의시설 사진 — 표출 중단된 관광공사 번호의 것만 뺀다';
