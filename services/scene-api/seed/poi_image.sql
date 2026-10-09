-- 편의시설 사진 맞추기 — 관광공사(tour_api) 사진을 입력 파일과 같게. `just seed-poi-images` 가 poi_image_load.sql 안에서 부른다.
--
-- 계획: docs/project/plans/poi-source.md §6-1.
--
-- ── 이 파일은 순수 SQL 이다 ─────────────────────────────────────────────────────
--
-- psql 메타 명령을 쓰지 않는다. 통합 시험(PoiImageSeedIntegrationTest)이 이 파일을 그대로 JDBC 로 돌려 검증한다.
-- 결과 수는 t_poi_image_summary 에 남기고 보여 주는 일은 부른 쪽이 한다.
--
-- 받는 것: 임시 표 t_poi_image_in(source_id, url, credit, sort_order) — 이번에 맞출 사진 전부.
-- 바꾸는 것: poi_image 의 source = 'tour_api' 행 — **입력에 든 POI 것만.**
--
-- ── 규칙 ────────────────────────────────────────────────────────────────────────
--
-- 입력에 든 POI(source_id 로 찾는다)마다 그 POI 의 tour_api 사진을 입력과 같게 한다 — 입력에 없는 주소는 지우고
-- (관광공사가 사진을 바꾼 경우), 있는 주소는 넣거나 문구·순서를 갱신한다. 다른 출처(own 등) 사진은 건드리지
-- 않는다. 입력에 없는 POI 도 건드리지 않는다 — 일부만 넣어도 나머지 사진이 사라지지 않는다. 몇 번을 돌려도 같다.
-- 사진 행을 지우는 것은 POI 를 지우지 않는다는 결정(ADR 0022)과 다르다 — poi_image 를 참조하는 표가 없다.

CREATE TEMP TABLE t_poi_image_target ON COMMIT DROP AS
SELECT DISTINCT p.id AS poi_id, i.source_id
FROM t_poi_image_in i
JOIN poi p ON p.source_id = i.source_id;

CREATE TEMP TABLE t_poi_image_want ON COMMIT DROP AS
SELECT DISTINCT ON (t.poi_id, i.url) t.poi_id, i.url, i.credit, i.sort_order
FROM t_poi_image_in i
JOIN t_poi_image_target t USING (source_id)
ORDER BY t.poi_id, i.url, i.sort_order;

-- 수를 먼저 센다 — 바꾸고 나면 무엇이 새로 들어갔는지 구분할 수 없다.
CREATE TEMP TABLE t_poi_image_summary ON COMMIT DROP AS
SELECT
    (SELECT count(DISTINCT source_id) FROM t_poi_image_in)                                    AS input_pois,
    (SELECT count(*) FROM t_poi_image_target)                                                AS matched_pois,
    (SELECT count(DISTINCT i.source_id) FROM t_poi_image_in i
       WHERE NOT EXISTS (SELECT 1 FROM t_poi_image_target t WHERE t.source_id = i.source_id)) AS unmatched_pois,
    (SELECT count(*) FROM t_poi_image_want w
       WHERE NOT EXISTS (SELECT 1 FROM poi_image pi WHERE pi.poi_id = w.poi_id AND pi.url = w.url)) AS inserted,
    (SELECT count(*) FROM t_poi_image_want w
       JOIN poi_image pi ON pi.poi_id = w.poi_id AND pi.url = w.url AND pi.source = 'tour_api'
       WHERE pi.credit IS DISTINCT FROM w.credit OR pi.sort_order <> w.sort_order)            AS updated,
    (SELECT count(*) FROM poi_image pi
       JOIN t_poi_image_target t ON t.poi_id = pi.poi_id
       WHERE pi.source = 'tour_api'
         AND NOT EXISTS (SELECT 1 FROM t_poi_image_want w
                         WHERE w.poi_id = pi.poi_id AND w.url = pi.url))                     AS removed;

DELETE FROM poi_image pi
USING t_poi_image_target t
WHERE pi.poi_id = t.poi_id
  AND pi.source = 'tour_api'
  AND NOT EXISTS (SELECT 1 FROM t_poi_image_want w WHERE w.poi_id = pi.poi_id AND w.url = pi.url);

-- 같은 POI 에 같은 주소가 다른 출처로 이미 있으면 그 행은 그대로 둔다(WHERE source = 'tour_api').
INSERT INTO poi_image (poi_id, url, sort_order, source, credit)
SELECT poi_id, url, sort_order, 'tour_api', credit
FROM t_poi_image_want
ON CONFLICT (poi_id, url) DO UPDATE
SET credit = EXCLUDED.credit, sort_order = EXCLUDED.sort_order
WHERE poi_image.source = 'tour_api'
  AND (poi_image.credit IS DISTINCT FROM EXCLUDED.credit OR poi_image.sort_order <> EXCLUDED.sort_order);
