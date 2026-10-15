-- 편의시설 상세 적재 — tools/scripts/seed-poi-detail.sh 가 입력을 /tmp/seed-poi-detail.jsonl 로 놓은 뒤 psql 로 돌린다.
--
-- 입력 한 줄(`just poi-tour-detail` 이 만든다). 영문 줄은 source_id 가 null 이고 match_name·lat·lng 로 짝을 짓는다:
--   {"source_id": "tour-123", "match_name": null, "lat": null, "lng": null, "lang": "ko", "tour_content_id": "123", "tour_modified": "2026-08-31T15:01:17+09:00",
--    "overview": "…", "use_time": "…", "rest_date": "…", "parking": "…", "first_menu": null, "use_fee": null,
--    "homepage": "https://…", "images": [{"url": "https://…", "thumbnail_url": "…", "license": "kogl_type3",
--    "credit": "한국관광공사"}, …]}
-- 사진 배열 순서가 정렬 순서다 — 첫 장이 대표, 10·20·30 으로 띄운다(poi_image 규칙).
-- 맞추는 규칙은 순수 SQL 인 poi_detail.sql(detail_sql 로 받는다). 계획: docs/project/plans/poi-source.md §6-1.

\set ON_ERROR_STOP on

BEGIN;

-- poi.sql 과 같은 방식 — CSV 로 읽되 인용·구분자를 자료에 없는 제어문자로 둬 한 줄을 한 칸에 담는다.
CREATE TEMP TABLE t_raw (doc TEXT) ON COMMIT DROP;
\copy t_raw FROM '/tmp/seed-poi-detail.jsonl' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')

CREATE TEMP TABLE t_doc ON COMMIT DROP AS
SELECT doc::jsonb AS d FROM t_raw WHERE btrim(doc) <> '';

CREATE TEMP TABLE t_poi_detail_in ON COMMIT DROP AS
SELECT NULLIF(btrim(d ->> 'source_id'), '')          AS source_id,
       NULLIF(btrim(d ->> 'match_name'), '')         AS match_name,
       (d ->> 'lat')::float8                         AS lat,
       (d ->> 'lng')::float8                         AS lng,
       btrim(d ->> 'lang')                           AS lang,
       btrim(d ->> 'tour_content_id')                AS tour_content_id,
       (NULLIF(btrim(d ->> 'tour_modified'), ''))::timestamptz AS tour_modified,
       NULLIF(btrim(d ->> 'overview'), '')           AS overview,
       NULLIF(btrim(d ->> 'use_time'), '')           AS use_time,
       NULLIF(btrim(d ->> 'rest_date'), '')          AS rest_date,
       NULLIF(btrim(d ->> 'parking'), '')            AS parking,
       NULLIF(btrim(d ->> 'first_menu'), '')         AS first_menu,
       NULLIF(btrim(d ->> 'use_fee'), '')            AS use_fee,
       NULLIF(btrim(d ->> 'homepage'), '')           AS homepage
FROM t_doc
WHERE NULLIF(btrim(d ->> 'lang'), '') IS NOT NULL
  AND NULLIF(btrim(d ->> 'tour_content_id'), '') IS NOT NULL;

CREATE TEMP TABLE t_poi_detail_image_in ON COMMIT DROP AS
SELECT btrim(d ->> 'lang')                               AS lang,
       btrim(d ->> 'tour_content_id')                    AS tour_content_id,
       btrim(img.value ->> 'url')                        AS url,
       NULLIF(btrim(img.value ->> 'thumbnail_url'), '')  AS thumbnail_url,
       NULLIF(btrim(img.value ->> 'license'), '')        AS license,
       NULLIF(btrim(img.value ->> 'credit'), '')         AS credit,
       (img.ordinality::int) * 10                        AS sort_order
FROM t_doc
CROSS JOIN LATERAL jsonb_array_elements(coalesce(d -> 'images', '[]'::jsonb)) WITH ORDINALITY AS img(value, ordinality)
WHERE NULLIF(btrim(d ->> 'lang'), '') IS NOT NULL
  AND NULLIF(btrim(d ->> 'tour_content_id'), '') IS NOT NULL
  AND NULLIF(btrim(img.value ->> 'url'), '') IS NOT NULL;

\i :detail_sql

\echo ''
\echo '편의시설 상세 (관광공사):'
SELECT * FROM t_poi_detail_summary;

\echo ''
\echo 'poi_detail 언어별 · poi_image 출처별:'
SELECT lang::text AS lang, count(*) AS details FROM poi_detail GROUP BY 1 ORDER BY 1;
SELECT coalesce(source, '(없음)') AS source, count(*) AS images, count(DISTINCT poi_id) AS pois
FROM poi_image GROUP BY 1 ORDER BY 1;

COMMIT;
