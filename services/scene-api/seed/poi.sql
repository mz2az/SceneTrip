-- POI(편의시설) JSON Lines 를 poi 표로 옮긴다.
--
-- tools/scripts/seed-poi.sh 가 입력을 파드 안 /tmp/seed-poi-input.jsonl 로 풀어 둔 뒤 이
-- 파일을 psql 에 먹인다. `just seed-poi` 가 그 둘을 묶는다. 계획은
-- docs/project/plans/poi.md §5, 표는 V12__poi.sql.
--
-- ── 출처 — 공공데이터 (2026-09-07 판) ─────────────────────────────────────────
--
-- TMAP 을 쓰지 않는다(2026-09-05 결정). 음식·숙박은 소상공인 상가정보(id `MA…`), 관광·
-- 음식·숙박 일부는 관광공사 TourAPI(id `tour-…`), 교통은 공항·철도·터미널 공공자료다.
-- 파일은 여섯(food·stay·sight·tour_food·tour_stay·transit)이고 열두 키 모양은 TMAP 판과
-- 같아 읽는 칸은 안 바뀐다. `src` 에 원본 전 칸이 붙어 오지만 읽지 않는다.
-- 상가정보에는 전화가 없다 — tel 이 음식·숙박 전부 빈 값이다(2026-09-09 실측).
--
-- ── candidates.sql 과 다른 점: 지우지 않고 UPSERT 한다. 지우려면 -v prune=1 ───────
--
-- POI 에는 자연키가 있다 — 출처가 준 source_id. 그래서 ON CONFLICT 로 멱등이 된다.
-- 있는 행은 갱신하고 없는 행은 더한다. 기본으로 TRUNCATE 를 하지 않는 이유는 course_item
-- 이 poi 를 참조하게 되면(poi.md §4-2) 그것이 사용자 코스를 지우는 일이 되기 때문이다.
--
-- 출처를 통째로 바꿀 때(TMAP → 공공데이터)는 옛 행이 남으면 안 된다. 그때만 `-v prune=1`
-- 로 「이번 입력에 없는 source_id 를 지운다」. 표본 23 행으로는 절대 켜지 말 것 — 나머지
-- 전부가 지워진다. poi_naver 는 ON DELETE CASCADE 라 그 카드도 같이 사라진다.

\set ON_ERROR_STOP on
\if :{?prune}
\else
\set prune 0
\endif

BEGIN;

-- ── 1. 한 줄을 한 칸에 담는다 ────────────────────────────────────────────────
--
-- \copy 의 기본 TEXT 형식은 역슬래시를 탈출 문자로 읽어 `여행\/레저` 를 깨뜨린다. CSV
-- 형식으로 하되 인용·구분자를 자료에 절대 없는 제어문자로 지정해 파서가 아무것도
-- 쪼개지 않게 한다. 그 뒤 jsonb 로 파싱하면 `\/` 는 JSON 규칙대로 `/` 가 된다 —
-- 역슬래시 정규화를 따로 할 필요가 없다.
CREATE TEMP TABLE t_raw (doc TEXT) ON COMMIT DROP;
\copy t_raw FROM '/tmp/seed-poi-input.jsonl' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')

-- ── 2. 칸을 꺼낸다 ───────────────────────────────────────────────────────────
--
-- 전 칸 TEXT 로 받고 좌표만 형식이 맞을 때 숫자로 만든다. 무턱대고 캐스팅하면 한 행의
-- 오타가 적재 전체를 실패시킨다. 형식이 틀린 행은 아래에서 세어 버린다.
--
-- origin 은 id 의 생김새로 안다 — 관광공사(tour-…)와 상가정보(MA…)를 겹침 처리(§4-1)에서
-- 가려야 해서다. 그 밖(교통 공공자료, TMAP 판 표본)은 other 로 두고 겹침 처리를 안 한다.
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
             NULLIF(btrim(d ->> 'biz_lower'), ''),
             NULLIF(btrim(d ->> 'biz_middle'), ''))           AS category,
    NULLIF(btrim(d ->> 'addr'), '')                           AS address,
    NULLIF(btrim(d ->> 'road'), '')                           AS road,
    NULLIF(btrim(d ->> 'tel'), '')                            AS tel,
    NULLIF(btrim(d ->> 'region'), '')                         AS region,
    NULLIF(btrim(d ->> 'city'), '')                           AS city,
    CASE WHEN d ->> 'id' LIKE 'tour-%' THEN 'tour'
         WHEN d ->> 'id' LIKE 'MA%'    THEN 'store'
         ELSE 'other' END                                     AS origin
FROM (SELECT doc::jsonb AS d FROM t_raw WHERE doc IS NOT NULL AND btrim(doc) <> '') AS j;

-- ── 3. 갈래 허용목록 ─────────────────────────────────────────────────────────
--
-- 원본 biz_middle 아홉 종을 네 갈래로 접는다 (poi.md §3-4·§4-0). 여기 없는 값은
-- 넣지 않는다 — TMAP 판에서는 수집기가 keyword=음식점 으로 긁다 담아 온 정육점(음식료)·
-- 꽃집(생활서비스)이 걸렸다. 공공데이터 판은 전부 허용목록 안이라 0 행이어야 정상이다.
-- 파일 이름으로 갈래를 정하지 않는다 — 파일에 다른 갈래가 섞여 있을 수 있다.
CREATE TEMP TABLE t_group (biz_middle TEXT PRIMARY KEY, category_group TEXT NOT NULL) ON COMMIT DROP;
INSERT INTO t_group VALUES
    ('음식점', 'food'), ('카페', 'food'), ('술집', 'food'),
    ('숙박', 'stay'),
    ('관광명소', 'sight'), ('종교', 'sight'), ('문화생활시설', 'sight'), ('레저/스포츠', 'sight'),
    ('교통시설', 'transit');

\echo ''
\echo '허용목록 밖이라 버린 행 (biz_middle 별):'
SELECT coalesce(i.biz_middle, '(빈값)') AS biz_middle, count(*) AS rows
FROM t_in i LEFT JOIN t_group g USING (biz_middle)
WHERE g.biz_middle IS NULL
GROUP BY 1 ORDER BY 2 DESC, 1;

\echo ''
\echo 'id·이름·좌표 중 하나가 없거나 형식이 틀려 버린 행:'
SELECT count(*) AS rows
FROM t_in i JOIN t_group g USING (biz_middle)
WHERE i.source_id IS NULL OR i.name IS NULL OR i.lat IS NULL OR i.lng IS NULL;

-- 좌표가 한국 밖(33~39N, 124~132E)이면 버린다. 공공데이터 판에 69 행이 있었다 — 위·경도가
-- 바뀌었거나 0 이거나 해외 지사다. 지도에 찍히면 엉뚱한 바다에 핀이 선다.
\echo ''
\echo '좌표가 한국 밖이라 버린 행:'
SELECT count(*) AS rows
FROM t_in i JOIN t_group g USING (biz_middle)
WHERE i.lat IS NOT NULL AND i.lng IS NOT NULL
  AND NOT (i.lat BETWEEN 33 AND 39 AND i.lng BETWEEN 124 AND 132);

CREATE TEMP TABLE t_ok ON COMMIT DROP AS
SELECT i.source_id, i.name, i.lat, i.lng, i.category, g.category_group,
       i.address, i.road, i.tel, i.region, i.city, i.origin,
       -- 자루 카테고리(~기타·전문음식점)가 아닌 것. 중복을 접을 때 남길 쪽을 고르는 첫 기준.
       (i.category NOT LIKE '%기타' AND i.category <> '전문음식점') AS concrete,
       -- 소수 5자리 ≈ 1 m. 좌표를 이 정밀도로 비교한다.
       round(i.lat::numeric, 5) AS lat5,
       round(i.lng::numeric, 5) AS lng5
FROM t_in i JOIN t_group g USING (biz_middle)
WHERE i.source_id IS NOT NULL AND i.name IS NOT NULL AND i.lat IS NOT NULL AND i.lng IS NOT NULL
  AND i.lat BETWEEN 33 AND 39 AND i.lng BETWEEN 124 AND 132;

-- ── 4. 중복을 접는다 (poi.md §5-2) ───────────────────────────────────────────
--
-- 두 종류다.
--   같은 source_id 가 두 번 — 같은 장소가 두 파일에 든 것. 한 줄만 남긴다.
--   source_id 는 다른데 이름·좌표(1 m)가 같음 — 출처가 같은 가게를 두 번 등록한 것. 합친다.
--   상가정보는 인허가 기록이라 같은 가게가 시점을 달리해(업소번호에 날짜가 있다) 또는
--   업종을 둘로 두 줄이 된다 — 음식 9,476 그룹(2026-09-09 실측). 가게가 둘이 아니라
--   기록이 둘이다.
--
-- 남길 줄을 고르는 순서 — 위에서 걸리면 거기서 멈춘다.
--   1. 자루 카테고리가 아닌 쪽   2. 전화번호가 있는 쪽   3. source_id 가 작은 쪽
-- 3번이 있어야 어느 자료로 몇 번을 돌려도 같은 줄이 남는다.
--
-- 합치지 않는 예외 — 「같은 건물의 다른 가게가 우연히 같은 이름」일 수 있다. 전화번호가
-- 둘 다 있으면서 다르고 카테고리도 둘 다 구체적이면서 다르면 다른 가게로 보고 둘 다 넣는다.
-- (상가정보는 전화가 없어 이 예외가 안 걸린다 — 층이 다른 같은 상호는 접힌다. 문제가 보이면
-- 층을 키에 넣는다.)
CREATE TEMP TABLE t_one ON COMMIT DROP AS
SELECT DISTINCT ON (source_id) *
FROM t_ok
ORDER BY source_id, concrete DESC, (tel IS NOT NULL) DESC;

CREATE TEMP TABLE t_dup ON COMMIT DROP AS
SELECT name, lat5, lng5, count(*) AS n,
       (count(DISTINCT tel) > 1
        AND count(DISTINCT category) FILTER (WHERE concrete) > 1) AS looks_distinct
FROM t_one
GROUP BY 1, 2, 3
HAVING count(*) > 1;

CREATE TEMP TABLE t_final ON COMMIT DROP AS
SELECT DISTINCT ON (o.name, o.lat5, o.lng5, CASE WHEN d.looks_distinct THEN o.source_id ELSE '' END) o.*
FROM t_one o LEFT JOIN t_dup d USING (name, lat5, lng5)
ORDER BY o.name, o.lat5, o.lng5, CASE WHEN d.looks_distinct THEN o.source_id ELSE '' END,
         o.concrete DESC, (o.tel IS NOT NULL) DESC, length(o.source_id), o.source_id;

\echo ''
\echo '중복 처리:'
SELECT
    (SELECT count(*) FROM t_ok)  - (SELECT count(*) FROM t_one)   AS same_source_id_dropped,
    (SELECT count(*) FROM t_dup WHERE NOT looks_distinct)          AS merged_groups,
    (SELECT count(*) FROM t_one) - (SELECT count(*) FROM t_final)  AS merged_rows_dropped,
    (SELECT count(*) FROM t_dup WHERE looks_distinct)              AS kept_apart_groups;

-- ── 4-1. 관광공사 음식·숙박 중 상가정보에 이미 있는 가게는 뺀다 ──────────────
--
-- 같은 가게가 두 출처에 있으면 좌표가 몇 m 씩 어긋나 §4 의 1 m 규칙에 안 걸린다 — 관광공사
-- 음식점 13,495 곳 중 12,921 곳이 상가정보 행과 100 m 안이고 10,291 곳은 이름도 겹친다
-- (2026-09-09 실측). 둘 다 넣으면 핀이 두 개 선다.
--
-- 규칙: 관광공사 음식·숙박 행은, 같은 갈래의 상가정보 행이 100 m 안에 있고 이름이 서로
-- 포함(공백 무시)이면 넣지 않는다. 상가정보에 없는 가게만 보태는 것이다. 관광(sight)과
-- 교통은 상가정보에 없는 갈래라 이 규칙을 안 탄다. 남는 것은 음식 약 3,200 · 숙박 약 1,360.
CREATE TEMP TABLE t_geo ON COMMIT DROP AS
SELECT f.*, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326)::geography AS g,
       replace(f.name, ' ', '') AS name_key
FROM t_final f;
CREATE INDEX ON t_geo USING gist (g);

CREATE TEMP TABLE t_shadowed ON COMMIT DROP AS
SELECT t.source_id, t.name, s.source_id AS store_id, s.name AS store_name
FROM t_geo t
JOIN LATERAL (
    SELECT s.source_id, s.name
    FROM t_geo s
    WHERE s.origin = 'store'
      AND s.category_group = t.category_group
      AND ST_DWithin(s.g, t.g, 100)
      AND (position(t.name_key IN s.name_key) > 0 OR position(s.name_key IN t.name_key) > 0)
    ORDER BY ST_Distance(s.g, t.g)
    LIMIT 1
) s ON TRUE
WHERE t.origin = 'tour' AND t.category_group IN ('food', 'stay');

\echo ''
\echo '관광공사 음식·숙박 중 상가정보에 이미 있어 뺀 행 (갈래 별):'
SELECT g.category_group, count(*) AS rows
FROM t_shadowed x JOIN t_geo g USING (source_id)
GROUP BY 1 ORDER BY 1;

CREATE TEMP TABLE t_load ON COMMIT DROP AS
SELECT f.* FROM t_final f
WHERE NOT EXISTS (SELECT 1 FROM t_shadowed x WHERE x.source_id = f.source_id);

-- ── 5. UPSERT ────────────────────────────────────────────────────────────────
--
-- 바뀐 것이 없으면 건드리지 않는다(WHERE ... IS DISTINCT FROM) — updated_at 이 헛되이
-- 움직이지 않고, 아래 셈에서 「갱신」이 실제로 값이 바뀐 행만 뜻하게 된다.
CREATE TEMP TABLE t_result (inserted BOOLEAN) ON COMMIT DROP;
WITH up AS (
    INSERT INTO poi (source_id, name, geom, category, category_group, address, road, tel, region, city)
    SELECT source_id, name,
           ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography,
           category, category_group, address, road, tel, region, city
    FROM t_load
    ON CONFLICT (source_id) DO UPDATE SET
        name = EXCLUDED.name, geom = EXCLUDED.geom,
        category = EXCLUDED.category, category_group = EXCLUDED.category_group,
        address = EXCLUDED.address, road = EXCLUDED.road, tel = EXCLUDED.tel,
        region = EXCLUDED.region, city = EXCLUDED.city,
        updated_at = now()
    WHERE (poi.name, ST_AsBinary(poi.geom), poi.category, poi.category_group,
           poi.address, poi.road, poi.tel, poi.region, poi.city)
          IS DISTINCT FROM
          (EXCLUDED.name, ST_AsBinary(EXCLUDED.geom), EXCLUDED.category, EXCLUDED.category_group,
           EXCLUDED.address, EXCLUDED.road, EXCLUDED.tel, EXCLUDED.region, EXCLUDED.city)
    RETURNING (xmax = 0) AS inserted
)
INSERT INTO t_result SELECT inserted FROM up;

\echo ''
\echo '적재 결과 (이번 입력):'
SELECT
    (SELECT count(*) FROM t_load)                           AS candidates,
    (SELECT count(*) FROM t_result WHERE inserted)          AS inserted,
    (SELECT count(*) FROM t_result WHERE NOT inserted)      AS updated,
    (SELECT count(*) FROM t_load) - (SELECT count(*) FROM t_result) AS unchanged;

-- ── 6. 이번 입력에 없는 행을 지운다 — prune=1 일 때만 ─────────────────────────
--
-- 출처를 통째로 바꿀 때 쓴다. 지워진 poi 의 네이버 카드(poi_naver)는 CASCADE 로 같이
-- 사라진다 — 새 행의 카드는 누를 때 다시 채워진다(PoiCardService).
\if :prune
\echo ''
\echo '이번 입력에 없어 지운 행 (갈래 별):'
WITH gone AS (
    DELETE FROM poi p
    WHERE NOT EXISTS (SELECT 1 FROM t_load l WHERE l.source_id = p.source_id)
    RETURNING p.category_group
)
SELECT category_group, count(*) AS rows FROM gone GROUP BY 1 ORDER BY 1;
\else
\echo ''
\echo '지우지 않았다 (prune=0). 출처를 바꿨다면 --prune 으로 다시 돌릴 것.'
\endif

COMMIT;

-- 통계를 새로 잰다. 수십만 행이 한 번에 들어오면 플래너의 추정치가 낡아 첫 질의들이
-- 엉뚱한 계획을 탄다.
ANALYZE poi;

\echo ''
\echo 'poi 표 전체:'
SELECT category_group, count(*) AS rows FROM poi GROUP BY 1 ORDER BY 1;
