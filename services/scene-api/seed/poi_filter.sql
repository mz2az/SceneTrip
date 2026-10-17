-- 편의시설 입력 거르기 — 한 줄씩 보고 넣을 줄만 남긴다 (MZ2AZ-492). 출처별 업데이트(poi_update_load.sql)가 부른다.
--
-- 계획: docs/project/plans/poi-load-cleanup.md §5-1 「줄 거르기」. 규칙은 처음 적재(poi.sql §2·§3·§3-1)와 같다 — 3 단계에서 poi.sql 도
-- 이 파일을 쓰게 바꾼다.
--
-- ── 이 파일은 순수 SQL 이다 ─────────────────────────────────────────────────────
--
-- 받는 것: 임시 표 t_raw(doc TEXT) — 입력 한 줄을 한 칸에(JSON).
-- 남기는 것: t_ok — 넣을 줄. t_filter_summary — 버린 줄을 이유별로.
--
--   갈래    biz_middle 아홉 종을 food·stay·sight·transit 로(밖이면 버림 — 공공데이터 판은 0 행)
--   제외    일반 유흥 주점 · 무도 유흥 주점 · 구내식당 · 기숙사/고시원
--   필수    번호·이름·좌표
--   좌표    북위 33~39 · 동경 124~132 밖이면 버림

CREATE TEMP TABLE t_in ON COMMIT DROP AS
SELECT
    NULLIF(btrim(d ->> 'id'), '')                             AS source_id,
    NULLIF(btrim(d ->> 'name'), '')                           AS name,
    CASE WHEN d ->> 'lat' ~ '^-?[0-9]+(\.[0-9]+)?$'
         THEN (d ->> 'lat')::double precision END             AS lat,
    CASE WHEN d ->> 'lng' ~ '^-?[0-9]+(\.[0-9]+)?$'
         THEN (d ->> 'lng')::double precision END             AS lng,
    NULLIF(btrim(d ->> 'biz_middle'), '')                     AS biz_middle,
    COALESCE(NULLIF(btrim(d ->> 'kind'), ''),
             NULLIF(btrim(d ->> 'biz_middle'), ''))           AS category,
    NULLIF(btrim(d ->> 'addr'), '')                           AS address,
    NULLIF(btrim(d ->> 'road'), '')                           AS road,
    NULLIF(btrim(d ->> 'tel'), '')                            AS tel,
    NULLIF(btrim(d ->> 'region'), '')                         AS region,
    NULLIF(btrim(d ->> 'city'), '')                           AS city,
    NULLIF(btrim(d ->> 'addr_en'), '')                        AS addr_en,
    d ? 'addr_en'                                             AS has_addr_en,
    NULLIF(btrim(d ->> 'name_en'), '')                        AS name_en,
    d ? 'name_en'                                             AS has_name_en,
    NULLIF(btrim(d ->> 'name_roman'), '')                     AS name_roman,
    d ? 'name_roman'                                          AS has_name_roman
FROM (SELECT doc::jsonb AS d FROM t_raw WHERE doc IS NOT NULL AND btrim(doc) <> '') AS j;

CREATE TEMP TABLE t_group (biz_middle TEXT PRIMARY KEY, category_group TEXT NOT NULL) ON COMMIT DROP;
INSERT INTO t_group VALUES
    ('음식점', 'food'), ('카페', 'food'), ('술집', 'food'),
    ('숙박', 'stay'),
    ('관광명소', 'sight'), ('종교', 'sight'), ('문화생활시설', 'sight'), ('레저/스포츠', 'sight'),
    ('교통시설', 'transit');

CREATE TEMP TABLE t_excluded_category (category TEXT PRIMARY KEY) ON COMMIT DROP;
INSERT INTO t_excluded_category VALUES
    ('일반 유흥 주점'), ('무도 유흥 주점'), ('구내식당'), ('기숙사/고시원');

CREATE TEMP TABLE t_ok ON COMMIT DROP AS
SELECT DISTINCT ON (i.source_id)
       i.source_id, i.name, i.lat, i.lng, i.category, g.category_group,
       i.address, i.road, i.tel, i.region, i.city,
       i.addr_en, i.has_addr_en, i.name_en, i.has_name_en, i.name_roman, i.has_name_roman
FROM t_in i JOIN t_group g USING (biz_middle)
WHERE i.source_id IS NOT NULL AND i.name IS NOT NULL AND i.lat IS NOT NULL AND i.lng IS NOT NULL
  AND i.lat BETWEEN 33 AND 39 AND i.lng BETWEEN 124 AND 132
  AND NOT EXISTS (SELECT 1 FROM t_excluded_category x WHERE x.category = i.category)
ORDER BY i.source_id;

CREATE TEMP TABLE t_filter_summary ON COMMIT DROP AS
SELECT
    (SELECT count(*) FROM t_in)                                                          AS input_rows,
    (SELECT count(*) FROM t_in i LEFT JOIN t_group g USING (biz_middle) WHERE g.biz_middle IS NULL) AS outside_groups,
    (SELECT count(*) FROM t_in i JOIN t_group g USING (biz_middle)
       JOIN t_excluded_category USING (category))                                        AS excluded_category,
    (SELECT count(*) FROM t_in i JOIN t_group g USING (biz_middle)
       WHERE i.source_id IS NULL OR i.name IS NULL OR i.lat IS NULL OR i.lng IS NULL)    AS missing_fields,
    (SELECT count(*) FROM t_in i JOIN t_group g USING (biz_middle)
       WHERE i.lat IS NOT NULL AND i.lng IS NOT NULL
         AND NOT (i.lat BETWEEN 33 AND 39 AND i.lng BETWEEN 124 AND 132))                AS outside_korea,
    (SELECT count(*) FROM t_ok)                                                          AS kept;
