-- 편의시설 출처별 업데이트 — DB 가 기준이고, 새 판·동기화가 알려 준 변경만 반영한다 (MZ2AZ-492).
--
-- 계획: docs/project/plans/poi-load-cleanup.md §5(출처별) · §5-7(모든 출처에 같은 것). 번호 기록은 V38 poi_source.
-- 부르는 쪽: poi_update_load.sql(`just poi-update`, 원격 적재). 통합 시험이 이 파일을 그대로 JDBC 로 돌린다.
--
-- ── 이 파일은 순수 SQL 이다 ─────────────────────────────────────────────────────
--
-- 받는 것(임시 표):
--   t_ok(source_id, name, lat, lng, category, category_group, address, road, tel, region, city, addr_en, has_addr_en,
--        name_en, has_name_en, name_roman, has_name_roman)            — 넣을 줄(poi_filter.sql 이 만든다)
--   t_hidden(source_id)                                               — 출처가 표출을 멈춘 번호(관광공사 국문 tour-… · 영문 tour-en-…)
--   t_upd_param(source, full)                                         — 이번 출처(store·tour·transit), 판 전체인가(full)
--   t_excluded_category(category)                                     — 제외 분류(poi_filter.sql 이 만든다) — 숨긴 제외 분류를 다시 열지 않으려고
-- 남기는 것: t_upd_summary.
--
-- ── 번호마다 ─────────────────────────────────────────────────────────────────
--
-- 1. 기록에 있는 번호 → 그 편의시설. 대표 번호(poi.source_id)이면 이름·좌표·분류를 고친다. 살아 있음(active)으로.
-- 2. 기록에 없는 번호 → 같은 가게 판정. 거리(같은 출처 30 m · 다른 출처 100 m) → 갈래 → 이름(같음 / 한쪽이 다른 쪽에 포함, 3 글자
--    이상 / 같은 출처면 자모 아닌 글자 편집거리 비율 0.8 이상 — 포함·유사는 같은 출처면 세부 종류도 같아야) → 서로에게 최선인 짝만.
--    그 출처의 번호가 아직 살아 있는 편의시설은 상대가 될 수 없다 — 같은 이름의 다른 지점이다. 짝이 되면 번호만 기록하고, 아니면 새
--    편의시설. 새 줄끼리 같은 가게(같은 이름 · 같은 갈래 · 30 m)는 하나로 넣고 나머지 번호를 기록한다.
-- 3. 판 전체(full)인데 이번 줄에 없는 그 출처의 번호, 그리고 t_hidden 의 번호 → active = false. 행은 지우지 않는다.
--
-- ── 편의시설마다 ─────────────────────────────────────────────────────────────
--
-- 판단 출처: 상가정보 번호가 하나라도 있으면 상가정보, 아니면 관광공사·교통(관광공사 영문은 판단하지 않는다 — 상세만).
-- 판단 출처의 살아 있는 번호가 하나도 없으면 숨긴다(closed_at). 이번에 판단 출처 번호가 다시 살아난 숨긴 편의시설은 다시 연다 —
-- 단 제외 분류는 열지 않는다. 숨긴 편의시설이 다른 출처 줄로 되살아나지 않는다(그 번호도 이미 같은 편의시설에 기록돼 있다).
-- 대표 번호는 살아 있는 번호 중 상가정보 → 관광공사 → 교통 순(음식·숙박 이름·좌표는 상가정보를 믿는다).

CREATE FUNCTION pg_temp.nk(t TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT regexp_replace(lower(regexp_replace(normalize(coalesce(t, ''), NFKC), '\[[^]]*\]', '', 'g')),
                         '[^0-9a-z가-힣]', '', 'g') $$;

CREATE FUNCTION pg_temp.src(id TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE WHEN id LIKE 'MA%' THEN 'store' WHEN id LIKE 'tour-en-%' THEN 'tour_en'
               WHEN id LIKE 'tour-%' THEN 'tour' ELSE 'transit' END $$;

-- 입력 줄이 이번 출처와 맞는지 — 관광공사 묶음에 상가정보 줄이 섞이면 「판 전체에서 빠진 번호」 계산이 틀린다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM t_ok o, t_upd_param p WHERE pg_temp.src(o.source_id) <> p.source) THEN
        RAISE EXCEPTION '입력에 이번 출처(%)가 아닌 줄이 있다', (SELECT source FROM t_upd_param);
    END IF;
END $$;

CREATE TEMP TABLE t_u_in ON COMMIT DROP AS
SELECT o.*, ST_SetSRID(ST_MakePoint(o.lng, o.lat), 4326)::geography AS geom, pg_temp.nk(o.name) AS name_key
FROM t_ok o;
CREATE UNIQUE INDEX ON t_u_in (source_id);
ANALYZE t_u_in;

-- ── 1·3. 기록에 있는 번호, 사라진 번호 ─────────────────────────────────────────

CREATE TEMP TABLE t_u_known ON COMMIT DROP AS
SELECT i.source_id, s.poi_id FROM t_u_in i JOIN poi_source s ON s.source_id = i.source_id;

CREATE TEMP TABLE t_u_gone ON COMMIT DROP AS
SELECT s.source_id, s.poi_id
FROM poi_source s, t_upd_param p
WHERE p.full AND s.source = p.source AND s.active
  AND NOT EXISTS (SELECT 1 FROM t_u_in i WHERE i.source_id = s.source_id)
UNION
SELECT s.source_id, s.poi_id
FROM poi_source s JOIN t_hidden h ON h.source_id = s.source_id
WHERE s.active;
CREATE INDEX ON t_u_gone (source_id);
ANALYZE t_u_gone;

-- 이번 실행 뒤에도 살아 있을 그 출처 번호를 가진 편의시설 — 같은 가게 판정의 상대가 될 수 없다.
CREATE TEMP TABLE t_u_alive ON COMMIT DROP AS
SELECT DISTINCT s.poi_id
FROM poi_source s, t_upd_param p
WHERE s.source = p.source AND s.active
  AND NOT EXISTS (SELECT 1 FROM t_u_gone g WHERE g.source_id = s.source_id);

-- ── 2. 기록에 없는 번호 — 같은 가게 판정 ──────────────────────────────────────

CREATE TEMP TABLE t_u_new ON COMMIT DROP AS
SELECT i.* FROM t_u_in i WHERE NOT EXISTS (SELECT 1 FROM poi_source s WHERE s.source_id = i.source_id);
CREATE INDEX ON t_u_new USING gist (geom);

CREATE TEMP TABLE t_u_pair ON COMMIT DROP AS
SELECT n.source_id, x.poi_id, x.dist, x.rule
FROM t_u_new n
CROSS JOIN LATERAL (
    SELECT p.id AS poi_id, ST_Distance(p.geom, n.geom) AS dist, same.v AS same_source,
           CASE
               WHEN pg_temp.nk(p.name) = n.name_key AND n.name_key <> '' THEN 3
               WHEN (NOT same.v OR p.category = n.category)
                    AND least(length(pg_temp.nk(p.name)), length(n.name_key)) >= 3
                    AND (position(pg_temp.nk(p.name) IN n.name_key) > 0 OR position(n.name_key IN pg_temp.nk(p.name)) > 0) THEN 2
               WHEN same.v AND p.category = n.category
                    AND greatest(length(pg_temp.nk(p.name)), length(n.name_key)) BETWEEN 1 AND 255
                    AND 1 - levenshtein(pg_temp.nk(p.name), n.name_key)::float
                            / greatest(length(pg_temp.nk(p.name)), length(n.name_key)) >= 0.8 THEN 1
               ELSE 0
           END AS rule
    FROM poi p
    CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM poi_source s2, t_upd_param pp
                                       WHERE s2.poi_id = p.id AND s2.source = pp.source) AS v) same
    WHERE p.closed_at IS NULL
      AND p.category_group = n.category_group
      AND ST_DWithin(p.geom, n.geom, 100)
      AND NOT EXISTS (SELECT 1 FROM t_u_alive a WHERE a.poi_id = p.id)
) x
WHERE x.rule > 0 AND x.dist <= CASE WHEN x.same_source THEN 30 ELSE 100 END;

CREATE TEMP TABLE t_u_link ON COMMIT DROP AS
SELECT a.source_id, a.poi_id, a.rule
FROM (SELECT DISTINCT ON (source_id) * FROM t_u_pair ORDER BY source_id, rule DESC, dist, poi_id) a
JOIN (SELECT DISTINCT ON (poi_id) * FROM t_u_pair ORDER BY poi_id, rule DESC, dist, source_id) b
  ON a.source_id = b.source_id AND a.poi_id = b.poi_id;

-- 새 줄끼리 같은 가게 — 같은 이름 · 같은 갈래 · 30 m 를 묶음으로 이어 가장 나은 하나만 새 편의시설로.
CREATE TEMP TABLE t_u_fresh ON COMMIT DROP AS
SELECT n.*, ROW_NUMBER() OVER (ORDER BY (n.category NOT LIKE '%기타') DESC, (n.tel IS NOT NULL) DESC,
                                        length(n.source_id), n.source_id) AS rnk
FROM t_u_new n
WHERE NOT EXISTS (SELECT 1 FROM t_u_link l WHERE l.source_id = n.source_id);
CREATE INDEX ON t_u_fresh USING gist (geom);

CREATE TEMP TABLE t_u_fresh_edge ON COMMIT DROP AS
SELECT a.source_id AS x, b.source_id AS y
FROM t_u_fresh a JOIN t_u_fresh b
  ON a.source_id <> b.source_id AND a.name_key = b.name_key AND a.name_key <> ''
 AND a.category_group = b.category_group AND ST_DWithin(a.geom, b.geom, 30);

CREATE TEMP TABLE t_u_fresh_map ON COMMIT DROP AS
WITH RECURSIVE reach(node, other) AS (
    SELECT x, y FROM t_u_fresh_edge
    UNION
    SELECT r.node, e.y FROM reach r JOIN t_u_fresh_edge e ON e.x = r.other WHERE e.y <> r.node
)
SELECT DISTINCT ON (r.node) r.node AS loser, best.source_id AS winner
FROM (SELECT node, other FROM reach UNION SELECT DISTINCT node, node FROM reach) r
JOIN t_u_fresh best ON best.source_id = r.other
ORDER BY r.node, best.rnk;
DELETE FROM t_u_fresh_map WHERE loser = winner;

-- ── 쓰기 ──────────────────────────────────────────────────────────────────────

-- 새 편의시설(묶음의 대표만).
CREATE TEMP TABLE t_u_inserted ON COMMIT DROP AS
WITH ins AS (
    INSERT INTO poi (source_id, name, geom, category, category_group, address, road, tel, region, city)
    SELECT f.source_id, f.name, f.geom, f.category, f.category_group, f.address, f.road, f.tel, f.region, f.city
    FROM t_u_fresh f
    WHERE NOT EXISTS (SELECT 1 FROM t_u_fresh_map m WHERE m.loser = f.source_id)
    ON CONFLICT (source_id) DO NOTHING
    RETURNING id, source_id
)
SELECT id, source_id FROM ins;

INSERT INTO poi_source (source_id, poi_id, source)
SELECT i.source_id, i.id, pg_temp.src(i.source_id) FROM t_u_inserted i
UNION ALL
SELECT m.loser, i.id, pg_temp.src(m.loser) FROM t_u_fresh_map m JOIN t_u_inserted i ON i.source_id = m.winner
UNION ALL
SELECT l.source_id, l.poi_id, pg_temp.src(l.source_id) FROM t_u_link l
ON CONFLICT (source_id) DO NOTHING;

-- 사라진 번호 · 표출 중단 번호를 끈다. 이번 줄에 있는 번호는 켠다.
UPDATE poi_source s SET active = false, updated_at = now()
FROM t_u_gone g WHERE s.source_id = g.source_id AND s.active;

UPDATE poi_source s SET active = true, updated_at = now()
FROM t_u_in i WHERE s.source_id = i.source_id AND NOT s.active;

-- 대표·숨김을 다시 볼 편의시설 — 번호가 꺼졌거나 새 번호가 붙었거나 새로 넣었거나, 숨겨져 있는데 번호가 다시 온 곳.
-- 번호가 그대로 살아 있는 대부분은 볼 필요가 없다(같은 판을 다시 넣으면 0 이어야 한다).
CREATE TEMP TABLE t_u_touched ON COMMIT DROP AS
SELECT poi_id FROM t_u_gone
UNION SELECT poi_id FROM t_u_link
UNION SELECT id FROM t_u_inserted
UNION SELECT k.poi_id FROM t_u_known k JOIN poi p ON p.id = k.poi_id WHERE p.closed_at IS NOT NULL;
CREATE UNIQUE INDEX ON t_u_touched (poi_id);
ANALYZE t_u_touched;

-- 대표 번호 — 살아 있는 번호 중 상가정보 → 관광공사 → 교통, 같은 출처면 이번 줄에 있는 것 → 짧은 → 작은.
CREATE TEMP TABLE t_u_rep ON COMMIT DROP AS
SELECT DISTINCT ON (s.poi_id) s.poi_id, s.source_id
FROM poi_source s JOIN t_u_touched t ON t.poi_id = s.poi_id
LEFT JOIN t_u_in i ON i.source_id = s.source_id
WHERE s.active AND s.source <> 'tour_en'
ORDER BY s.poi_id,
         CASE s.source WHEN 'store' THEN 0 WHEN 'tour' THEN 1 ELSE 2 END,
         (i.source_id IS NOT NULL) DESC,
         length(s.source_id), s.source_id;

-- 같은 번호를 숨긴 다른 편의시설이 대표로 들고 있으면(같은 가게 정리로 숨긴 쪽 — 기록은 남는 쪽으로 옮겨졌다) 바꾸지 않는다 — poi.source_id 는 유일하다.
UPDATE poi p SET source_id = r.source_id, updated_at = now()
FROM t_u_rep r
WHERE p.id = r.poi_id AND p.source_id <> r.source_id
  AND NOT EXISTS (SELECT 1 FROM poi q WHERE q.source_id = r.source_id AND q.id <> r.poi_id);

-- 대표 번호의 줄이 이번 입력에 있으면 그 값으로 고친다(바뀐 것만).
CREATE TEMP TABLE t_u_updated ON COMMIT DROP AS
WITH up AS (
    UPDATE poi p
    SET name = i.name, geom = i.geom, category = i.category, category_group = i.category_group,
        address = i.address, road = i.road, tel = i.tel, region = i.region, city = i.city, updated_at = now()
    FROM t_u_in i
    JOIN poi_source s ON s.source_id = i.source_id
    WHERE p.source_id = i.source_id AND s.poi_id = p.id
      AND NOT EXISTS (SELECT 1 FROM t_u_inserted x WHERE x.id = p.id)
      AND (p.name, ST_AsBinary(p.geom), p.category, p.category_group, p.address, p.road, p.tel, p.region, p.city)
          IS DISTINCT FROM
          (i.name, ST_AsBinary(i.geom), i.category, i.category_group, i.address, i.road, i.tel, i.region, i.city)
    RETURNING p.id
)
SELECT id FROM up;

-- 숨김 · 다시 열기 — 판단 출처의 살아 있는 번호로.
CREATE TEMP TABLE t_u_verdict ON COMMIT DROP AS
SELECT t.poi_id,
       EXISTS (SELECT 1 FROM poi_source s WHERE s.poi_id = t.poi_id AND s.source = 'store') AS has_store,
       EXISTS (SELECT 1 FROM poi_source s WHERE s.poi_id = t.poi_id AND s.active
                 AND s.source = CASE WHEN EXISTS (SELECT 1 FROM poi_source s3 WHERE s3.poi_id = t.poi_id AND s3.source = 'store')
                                     THEN 'store' ELSE s.source END
                 AND s.source <> 'tour_en') AS alive
FROM t_u_touched t;

CREATE TEMP TABLE t_u_hidden ON COMMIT DROP AS
WITH h AS (
    UPDATE poi p SET closed_at = now(), updated_at = now()
    FROM t_u_verdict v
    WHERE p.id = v.poi_id AND NOT v.alive AND p.closed_at IS NULL
    RETURNING p.id
)
SELECT id FROM h;

CREATE TEMP TABLE t_u_reopened ON COMMIT DROP AS
WITH o AS (
    UPDATE poi p SET closed_at = NULL, updated_at = now()
    FROM t_u_verdict v
    WHERE p.id = v.poi_id AND v.alive AND p.closed_at IS NOT NULL
      AND EXISTS (SELECT 1 FROM poi_source s JOIN t_u_in i ON i.source_id = s.source_id
                  WHERE s.poi_id = p.id AND s.source <> 'tour_en')
      AND NOT EXISTS (SELECT 1 FROM t_excluded_category x WHERE x.category = p.category)
    RETURNING p.id
)
SELECT id FROM o;

-- 영어 칸 — 대표 번호의 줄만(다른 출처 줄이 대표의 영어를 덮지 않게). 칸이 없는 입력은 그 값을 바꾸지 않는다.
CREATE TEMP TABLE t_u_en ON COMMIT DROP AS
SELECT p.id, i.addr_en, i.has_addr_en, i.name_en, i.has_name_en, i.name_roman, i.has_name_roman
FROM t_u_in i
JOIN poi_source s ON s.source_id = i.source_id
JOIN poi p ON p.id = s.poi_id AND p.source_id = i.source_id
WHERE i.has_addr_en OR i.has_name_en OR i.has_name_roman;

UPDATE poi p SET name_roman = e.name_roman
FROM t_u_en e WHERE p.id = e.id AND e.has_name_roman AND p.name_roman IS DISTINCT FROM e.name_roman;

INSERT INTO poi_i18n (poi_id, lang, address)
SELECT id, 'en', addr_en FROM t_u_en WHERE has_addr_en AND addr_en IS NOT NULL
ON CONFLICT (poi_id, lang) DO UPDATE SET address = EXCLUDED.address
WHERE poi_i18n.address IS DISTINCT FROM EXCLUDED.address;

INSERT INTO poi_i18n (poi_id, lang, name)
SELECT id, 'en', name_en FROM t_u_en WHERE has_name_en AND name_en IS NOT NULL
ON CONFLICT (poi_id, lang) DO UPDATE SET name = EXCLUDED.name
WHERE poi_i18n.name IS DISTINCT FROM EXCLUDED.name;

UPDATE poi_i18n t SET address = NULL
FROM t_u_en e WHERE t.poi_id = e.id AND t.lang = 'en' AND e.has_addr_en AND e.addr_en IS NULL AND t.address IS NOT NULL;

UPDATE poi_i18n t SET name = NULL
FROM t_u_en e WHERE t.poi_id = e.id AND t.lang = 'en' AND e.has_name_en AND e.name_en IS NULL AND t.name IS NOT NULL;

DELETE FROM poi_i18n t USING t_u_en e
WHERE t.poi_id = e.id AND t.lang = 'en' AND t.name IS NULL AND t.address IS NULL AND t.road IS NULL;

-- ── 요약 ──────────────────────────────────────────────────────────────────────

CREATE TEMP TABLE t_upd_summary ON COMMIT DROP AS
SELECT
    (SELECT source FROM t_upd_param)                       AS source,
    (SELECT count(*) FROM t_u_in)                          AS input_rows,
    (SELECT count(*) FROM t_u_known)                       AS known_numbers,
    (SELECT count(*) FROM t_u_updated)                     AS updated,
    (SELECT count(*) FROM t_u_link)                        AS linked_same_store,
    (SELECT count(*) FROM t_u_inserted)                    AS inserted,
    (SELECT count(*) FROM t_u_fresh_map)                   AS collapsed_new,
    (SELECT count(*) FROM t_u_gone)                        AS deactivated_numbers,
    (SELECT count(*) FROM t_u_hidden)                      AS hidden,
    (SELECT count(*) FROM t_u_reopened)                    AS reopened;
