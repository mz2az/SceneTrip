-- 편의시설 사진 적재 — tools/scripts/seed-poi-images.sh 가 입력을 /tmp/seed-poi-images.jsonl 로 놓은 뒤 psql 로 돌린다.
--
-- 입력 한 줄: {"source_id": "tour-123", "images": [{"url": "https://…", "credit": "…"}, …]}
-- (`just poi-tour-images` 가 만든다). 배열 순서가 정렬 순서다 — 첫 장이 대표, 10·20·30 으로 띄운다(poi_image 규칙).
-- 맞추는 규칙은 순수 SQL 인 poi_image.sql(image_sql 로 받는다). 계획: docs/project/plans/poi-source.md §6-1.

\set ON_ERROR_STOP on

BEGIN;

-- poi.sql 과 같은 방식 — CSV 로 읽되 인용·구분자를 자료에 없는 제어문자로 둬 한 줄을 한 칸에 담는다.
CREATE TEMP TABLE t_raw (doc TEXT) ON COMMIT DROP;
\copy t_raw FROM '/tmp/seed-poi-images.jsonl' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')

CREATE TEMP TABLE t_poi_image_in ON COMMIT DROP AS
SELECT btrim(d ->> 'source_id')                  AS source_id,
       btrim(img.value ->> 'url')                AS url,
       NULLIF(btrim(img.value ->> 'credit'), '') AS credit,
       (img.ordinality::int) * 10                AS sort_order
FROM (SELECT doc::jsonb AS d FROM t_raw WHERE btrim(doc) <> '') j
CROSS JOIN LATERAL jsonb_array_elements(j.d -> 'images') WITH ORDINALITY AS img(value, ordinality)
WHERE NULLIF(btrim(d ->> 'source_id'), '') IS NOT NULL
  AND NULLIF(btrim(img.value ->> 'url'), '') IS NOT NULL;

\i :image_sql

\echo ''
\echo '편의시설 사진 (tour_api):'
SELECT * FROM t_poi_image_summary;

\echo ''
\echo 'poi_image 출처별:'
SELECT coalesce(source, '(없음)') AS source, count(*) AS images, count(DISTINCT poi_id) AS pois
FROM poi_image GROUP BY 1 ORDER BY 1;

COMMIT;
