-- 편의시설 상세 맞추기 — 관광공사 상세(poi_detail)와 관광공사 사진(poi_image, source = 'tour_api')을 입력과 같게.
-- `just seed-poi-detail` 이 poi_detail_load.sql 안에서 부른다.
--
-- 계획: docs/project/plans/poi-source.md §6 · §6-1. 표: V37.
--
-- ── 이 파일은 순수 SQL 이다 ─────────────────────────────────────────────────────
--
-- psql 메타 명령을 쓰지 않는다. 통합 시험이 이 파일을 그대로 JDBC 로 돌려 검증한다. 결과 수는 t_poi_detail_summary 에
-- 남기고 보여 주는 일은 부른 쪽이 한다.
--
-- 받는 것(임시 표 둘):
--   t_poi_detail_in(source_id, lang, tour_content_id, tour_modified, overview, use_time, rest_date, parking, first_menu,
--                   use_fee, homepage)                                   — 장소·언어마다 한 줄
--   t_poi_detail_image_in(source_id, tour_content_id, url, thumbnail_url, license, credit, sort_order) — 사진마다 한 줄
-- POI 는 source_id 로 찾는다(`tour-<번호>` — 관광공사에서 바로 들어온 POI). 못 찾은 줄은 세기만 한다.
--
-- ── 규칙 ────────────────────────────────────────────────────────────────────────
--
-- 상세: (poi_id, lang) 한 줄을 입력과 같게 — 없으면 넣고, 다르면 고치고, 같으면 두다. 입력에 없는 POI·언어는 건드리지
--   않는다 — 일부만 넣어도 나머지가 사라지지 않는다. 몇 번을 돌려도 같다.
-- 사진: 입력에 든 POI 마다, **이번 입력의 관광공사 번호에서 온** tour_api 사진을 입력과 같게 한다 — 입력에 없는 주소는
--   지우고(관광공사가 사진을 바꾼 경우), 있는 주소는 넣거나 고친다. 다른 관광공사 번호(뒤에 붙일 영문 등)에서 온 사진과
--   다른 출처 사진은 건드리지 않는다. 번호가 비어 있는 tour_api 사진은 예전 대표 이미지 적재가 넣은 것(같은 POI 의 같은
--   번호)이라 이번 번호의 것으로 본다. 사진 행을 지우는 것은 POI 를 지우지 않는다는 결정(ADR 0022)과 다르다 — poi_image 를
--   참조하는 표가 없다.

CREATE TEMP TABLE t_poi_detail_target ON COMMIT DROP AS
SELECT DISTINCT ON (p.id, i.lang)
       p.id AS poi_id, i.lang::lang_code AS lang, i.tour_content_id, i.tour_modified,
       i.overview, i.use_time, i.rest_date, i.parking, i.first_menu, i.use_fee, i.homepage
FROM t_poi_detail_in i
JOIN poi p ON p.source_id = i.source_id
ORDER BY p.id, i.lang, i.tour_modified DESC NULLS LAST;

CREATE TEMP TABLE t_poi_detail_image_want ON COMMIT DROP AS
SELECT DISTINCT ON (p.id, i.url)
       p.id AS poi_id, i.tour_content_id, i.url, i.thumbnail_url, i.license, i.credit, i.sort_order
FROM t_poi_detail_image_in i
JOIN poi p ON p.source_id = i.source_id
ORDER BY p.id, i.url, i.sort_order;

-- 사진을 맞출 범위 — 입력에 든 POI 와 그 POI 에 이번에 들어온 관광공사 번호.
CREATE TEMP TABLE t_poi_detail_image_scope ON COMMIT DROP AS
SELECT DISTINCT p.id AS poi_id, i.tour_content_id
FROM t_poi_detail_in i
JOIN poi p ON p.source_id = i.source_id;

-- 수를 먼저 센다 — 바꾸고 나면 무엇이 새로 들어갔는지 구분할 수 없다.
CREATE TEMP TABLE t_poi_detail_summary ON COMMIT DROP AS
SELECT
    (SELECT count(*) FROM t_poi_detail_in)                                                      AS input_rows,
    (SELECT count(*) FROM t_poi_detail_target)                                                  AS matched_rows,
    (SELECT count(*) FROM t_poi_detail_in i
       WHERE NOT EXISTS (SELECT 1 FROM poi p WHERE p.source_id = i.source_id))                  AS unmatched_rows,
    (SELECT count(*) FROM t_poi_detail_target t
       WHERE NOT EXISTS (SELECT 1 FROM poi_detail d WHERE d.poi_id = t.poi_id AND d.lang = t.lang)) AS details_inserted,
    (SELECT count(*) FROM t_poi_detail_target t
       JOIN poi_detail d ON d.poi_id = t.poi_id AND d.lang = t.lang
       WHERE (d.overview, d.use_time, d.rest_date, d.parking, d.first_menu, d.use_fee, d.homepage,
              d.tour_content_id, d.tour_modified)
             IS DISTINCT FROM
             (t.overview, t.use_time, t.rest_date, t.parking, t.first_menu, t.use_fee, t.homepage,
              t.tour_content_id, t.tour_modified))                                              AS details_updated,
    (SELECT count(*) FROM t_poi_detail_image_want w
       WHERE NOT EXISTS (SELECT 1 FROM poi_image pi WHERE pi.poi_id = w.poi_id AND pi.url = w.url)) AS images_inserted,
    (SELECT count(*) FROM poi_image pi
       JOIN t_poi_detail_image_scope s ON s.poi_id = pi.poi_id
            AND (pi.tour_content_id = s.tour_content_id OR pi.tour_content_id IS NULL)
       WHERE pi.source = 'tour_api'
         AND NOT EXISTS (SELECT 1 FROM t_poi_detail_image_want w
                         WHERE w.poi_id = pi.poi_id AND w.url = pi.url))                        AS images_removed;

INSERT INTO poi_detail (poi_id, lang, overview, use_time, rest_date, parking, first_menu, use_fee, homepage,
                        tour_content_id, tour_modified)
SELECT poi_id, lang, overview, use_time, rest_date, parking, first_menu, use_fee, homepage,
       tour_content_id, tour_modified
FROM t_poi_detail_target
ON CONFLICT (poi_id, lang) DO UPDATE
SET overview        = EXCLUDED.overview,
    use_time        = EXCLUDED.use_time,
    rest_date       = EXCLUDED.rest_date,
    parking         = EXCLUDED.parking,
    first_menu      = EXCLUDED.first_menu,
    use_fee         = EXCLUDED.use_fee,
    homepage        = EXCLUDED.homepage,
    tour_content_id = EXCLUDED.tour_content_id,
    tour_modified   = EXCLUDED.tour_modified,
    updated_at      = now()
WHERE (poi_detail.overview, poi_detail.use_time, poi_detail.rest_date, poi_detail.parking, poi_detail.first_menu,
       poi_detail.use_fee, poi_detail.homepage, poi_detail.tour_content_id, poi_detail.tour_modified)
      IS DISTINCT FROM
      (EXCLUDED.overview, EXCLUDED.use_time, EXCLUDED.rest_date, EXCLUDED.parking, EXCLUDED.first_menu,
       EXCLUDED.use_fee, EXCLUDED.homepage, EXCLUDED.tour_content_id, EXCLUDED.tour_modified);

DELETE FROM poi_image pi
USING t_poi_detail_image_scope s
WHERE pi.poi_id = s.poi_id
  AND pi.source = 'tour_api'
  AND (pi.tour_content_id = s.tour_content_id OR pi.tour_content_id IS NULL)
  AND NOT EXISTS (SELECT 1 FROM t_poi_detail_image_want w WHERE w.poi_id = pi.poi_id AND w.url = pi.url);

-- 같은 POI 에 같은 주소가 다른 출처로 이미 있으면 그 행은 그대로 둔다(WHERE source = 'tour_api').
INSERT INTO poi_image (poi_id, url, sort_order, source, credit, thumbnail_url, license, tour_content_id)
SELECT poi_id, url, sort_order, 'tour_api', credit, thumbnail_url, license, tour_content_id
FROM t_poi_detail_image_want
ON CONFLICT (poi_id, url) DO UPDATE
SET sort_order      = EXCLUDED.sort_order,
    credit          = EXCLUDED.credit,
    thumbnail_url   = EXCLUDED.thumbnail_url,
    license         = EXCLUDED.license,
    tour_content_id = EXCLUDED.tour_content_id
WHERE poi_image.source = 'tour_api'
  AND (poi_image.sort_order, poi_image.credit, poi_image.thumbnail_url, poi_image.license, poi_image.tour_content_id)
      IS DISTINCT FROM
      (EXCLUDED.sort_order, EXCLUDED.credit, EXCLUDED.thumbnail_url, EXCLUDED.license, EXCLUDED.tour_content_id);
