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
-- 분기 갱신(--update). update_sql 은 seed-poi.sh 가 넘긴다 — 파드에서는 표준 입력으로 들어와 상대
-- 경로가 풀리지 않아서 파일 경로를 따로 받는다.
\if :{?update}
\else
\set update 0
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
    -- 영어 화면용 칸 — `just poi-en` 이 덧붙인다(§5-2). 칸 자체가 없는 입력(원본 그대로)은 그 값을
    -- 건드리지 않는다. 칸이 있는데 null 이면 「없다」 라 비운다.
    NULLIF(btrim(d ->> 'addr_en'), '')                        AS addr_en,
    d ? 'addr_en'                                             AS has_addr_en,
    NULLIF(btrim(d ->> 'name_en'), '')                        AS name_en,
    d ? 'name_en'                                             AS has_name_en,
    NULLIF(btrim(d ->> 'name_roman'), '')                     AS name_roman,
    d ? 'name_roman'                                          AS has_name_roman,
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

-- ── 3-1. 제외 분류 ───────────────────────────────────────────────────────────
--
-- 큰 갈래(biz_middle)가 허용목록 안이어도 여행 앱에 보일 일이 없는 세부 분류는 넣지 않는다
-- (docs/project/plans/poi-i18n-image.md §7, 2026-10-05). 상가정보 원본은 대분류 「음식」·「숙박」 을
-- 거르지 않고 다 싣기 때문에 술집 안의 유흥주점, 숙박 안의 고시원이 같이 온다.
--
--   일반 유흥 주점 · 무도 유흥 주점   접객·무도 유흥업소 — 「Bar」 로 옮기면 일반 술집으로 읽힌다
--   구내식당                        회사·기관 직원 식당 — 외부인이 대개 못 들어간다
--   기숙사/고시원                    장기 거주 시설 — 여행 숙소가 아니다
--
-- 걸러내는 열쇠는 category(상권 소분류)다. 표준산업분류로 거르지 않는다 — 역전할머니맥주 918 곳은
-- 소분류가 전부 「생맥주 전문」 인데 그중 7 곳의 사업자 신고 업종이 「일반 유흥 주점업」 이다.
-- 같은 프랜차이즈가 신고만 다르게 한 것이라 소분류가 맞다.
--
-- 적재마다 이 파일이 돌므로 분기 갱신 때도 같은 규칙이 걸린다. 이미 들어 있는 행은 §5-1 이 지운다.
CREATE TEMP TABLE t_excluded_category (category TEXT PRIMARY KEY) ON COMMIT DROP;
INSERT INTO t_excluded_category VALUES
    ('일반 유흥 주점'), ('무도 유흥 주점'), ('구내식당'), ('기숙사/고시원');

\echo ''
\echo '제외 분류라 버린 행:'
SELECT i.category, count(*) AS rows
FROM t_in i JOIN t_group g USING (biz_middle) JOIN t_excluded_category USING (category)
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
       i.address, i.road, i.tel, i.region, i.city, i.origin, i.addr_en, i.has_addr_en,
       i.name_en, i.has_name_en, i.name_roman, i.has_name_roman,
       -- 자루 카테고리(~기타·전문음식점)가 아닌 것. 중복을 접을 때 남길 쪽을 고르는 첫 기준.
       (i.category NOT LIKE '%기타' AND i.category <> '전문음식점') AS concrete,
       -- 소수 5자리 ≈ 1 m. 좌표를 이 정밀도로 비교한다.
       round(i.lat::numeric, 5) AS lat5,
       round(i.lng::numeric, 5) AS lng5
FROM t_in i JOIN t_group g USING (biz_middle)
WHERE i.source_id IS NOT NULL AND i.name IS NOT NULL AND i.lat IS NOT NULL AND i.lng IS NOT NULL
  AND i.lat BETWEEN 33 AND 39 AND i.lng BETWEEN 124 AND 132
  AND NOT EXISTS (SELECT 1 FROM t_excluded_category x WHERE x.category = i.category);

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

-- ── 4-2. 분기 갱신 — update=1 일 때만 ──────────────────────────────────────────
--
-- UPSERT 바로 앞이어야 한다. 이어진 행은 여기서 source_id 가 새 번호로 바뀌므로 아래 UPSERT 가 그 행을
-- 「있는 행」 으로 갱신한다(poi.id 유지). 이어지지 않은 행은 폐업 표시된다. 규칙은 poi_update.sql —
-- 통합 시험과 서버 적재가 같은 파일을 쓰도록 psql 메타 명령 없는 순수 SQL 로 떼어 두었다.
\if :update
\i :update_sql
\echo ''
\echo '분기 갱신 — 사라진 번호를 같은 가게로 잇거나 폐업 표시:'
SELECT * FROM t_update_summary;
\endif

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
        -- 입력에 다시 나왔다 — 폐업 표시가 있었으면 지운다(다시 문을 연 가게, 또는 잘못 판정된 가게).
        closed_at = NULL,
        updated_at = now()
    WHERE (poi.name, ST_AsBinary(poi.geom), poi.category, poi.category_group,
           poi.address, poi.road, poi.tel, poi.region, poi.city)
          IS DISTINCT FROM
          (EXCLUDED.name, ST_AsBinary(EXCLUDED.geom), EXCLUDED.category, EXCLUDED.category_group,
           EXCLUDED.address, EXCLUDED.road, EXCLUDED.tel, EXCLUDED.region, EXCLUDED.city)
       OR poi.closed_at IS NOT NULL
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

-- ── 5-1. 이미 들어 있는 제외 분류를 지운다 — prune 과 상관없이 ─────────────────
--
-- §3-1 은 이번 입력만 거른다. 그 전에 들어온 행은 기본(prune=0) 적재에서 「입력에 없는 행」 이라
-- 건드려지지 않으므로 여기서 직접 지운다. 번역·사진·네이버 카드는 CASCADE 로 함께 사라진다.
\echo ''
\echo '이미 들어 있던 제외 분류를 지운 행:'
WITH gone AS (
    DELETE FROM poi p
    USING t_excluded_category x
    WHERE p.category = x.category
    RETURNING p.category
)
SELECT category, count(*) AS rows FROM gone GROUP BY 1 ORDER BY 2 DESC, 1;

-- ── 5-2. 영어 화면용 칸 — 영문 주소, 영어 이름, 로마자 읽기 ─────────────────────
--
-- 영문 주소는 번역하지 않는다 — 행정안전부 영문도로명주소DB·영문주소 API 의 공식 표기다(계획 §6-3·§11).
-- 영어 이름은 확실할 때만(브랜드 사전·보수적 규칙 번역, §12) 있고 대부분 비어 있다. 로마자 읽기는 번역이 아니라
-- 읽는 법이라 poi 에 둔다. 주소도 이름도 없는 영어 행은 남기지 않는다. 칸이 없는 입력은 그 값을 바꾸지 않는다.
CREATE TEMP TABLE t_en ON COMMIT DROP AS
SELECT p.id, l.addr_en, l.has_addr_en, l.name_en, l.has_name_en, l.name_roman, l.has_name_roman
FROM t_load l
JOIN poi p ON p.source_id = l.source_id
WHERE l.has_addr_en OR l.has_name_en OR l.has_name_roman;

UPDATE poi p
SET name_roman = e.name_roman
FROM t_en e
WHERE p.id = e.id AND e.has_name_roman AND p.name_roman IS DISTINCT FROM e.name_roman;

-- 영문 주소와 영어 이름은 따로 넣고 따로 비운다 — 한쪽 칸만 있는 입력도 다른 칸을 건드리지 않게.
INSERT INTO poi_i18n (poi_id, lang, address)
SELECT id, 'en', addr_en FROM t_en WHERE has_addr_en AND addr_en IS NOT NULL
ON CONFLICT (poi_id, lang) DO UPDATE SET address = EXCLUDED.address
WHERE poi_i18n.address IS DISTINCT FROM EXCLUDED.address;

INSERT INTO poi_i18n (poi_id, lang, name)
SELECT id, 'en', name_en FROM t_en WHERE has_name_en AND name_en IS NOT NULL
ON CONFLICT (poi_id, lang) DO UPDATE SET name = EXCLUDED.name
WHERE poi_i18n.name IS DISTINCT FROM EXCLUDED.name;

UPDATE poi_i18n t SET address = NULL
FROM t_en e
WHERE t.poi_id = e.id AND t.lang = 'en' AND e.has_addr_en AND e.addr_en IS NULL AND t.address IS NOT NULL;

UPDATE poi_i18n t SET name = NULL
FROM t_en e
WHERE t.poi_id = e.id AND t.lang = 'en' AND e.has_name_en AND e.name_en IS NULL AND t.name IS NOT NULL;

DELETE FROM poi_i18n WHERE lang = 'en' AND name IS NULL AND address IS NULL AND road IS NULL;

\echo ''
\echo '영어 화면용 칸 (이번 입력):'
SELECT
    count(*) FILTER (WHERE has_addr_en)                         AS rows_with_addr_column,
    count(*) FILTER (WHERE addr_en IS NOT NULL)                 AS with_english_address,
    count(*) FILTER (WHERE name_en IS NOT NULL)                 AS with_english_name,
    count(*) FILTER (WHERE name_roman IS NOT NULL)              AS with_roman_reading
FROM t_en;

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

-- ── 7. 분류 사전 — 영어 ─────────────────────────────────────────────────────────
--
-- 적재마다 같이 채운다. 표기를 고치면 다음 적재에 반영되고, 서버 적재도 이 파일 하나로 사전까지 선다.
-- 초안·검토: docs/project/plans/poi-i18n-image.md §9. 제외 분류 넷(§3-1)은 들어오지 않으므로 사전에도 없다.
-- 같은 영어를 두 분류가 나눠 쓰는 것은 일부러다 — 원본의 「음식점」 과 「음식점기타」 는 화면에서 구분할
-- 까닭이 없다.
INSERT INTO poi_category_i18n (ko, lang, name) VALUES
    -- 음식
    ('한식', 'en', 'Korean'),
    ('카페', 'en', 'Cafe'),
    ('요리 주점', 'en', 'Gastropub'),
    ('분식', 'en', 'Korean Snacks'),
    ('치킨', 'en', 'Fried Chicken'),
    ('중식', 'en', 'Chinese'),
    ('제과점', 'en', 'Bakery'),
    ('일식', 'en', 'Japanese'),
    ('양식', 'en', 'Western'),
    ('피자', 'en', 'Pizza'),
    ('생맥주 전문', 'en', 'Beer Pub'),
    ('떡/한과', 'en', 'Rice Cakes & Sweets'),
    ('음식점기타', 'en', 'Restaurant'),
    ('음식점', 'en', 'Restaurant'),
    ('세계요리', 'en', 'International'),
    ('패스트푸드', 'en', 'Fast Food'),
    ('다이어트/샐러드', 'en', 'Sandwiches & Salads'),   -- 원본 소분류는 「토스트/샌드위치/샐러드」
    ('부페', 'en', 'Buffet'),
    ('아이스크림전문점', 'en', 'Ice Cream & Bingsu'),  -- 원본 소분류는 「아이스크림/빙수」
    ('패밀리레스토랑', 'en', 'Family Restaurant'),
    -- 숙박
    ('펜션', 'en', 'Pension'),
    ('모텔', 'en', 'Motel'),
    ('캠핑장', 'en', 'Campsite'),
    ('호텔', 'en', 'Hotel & Resort'),                   -- 원본 소분류는 「호텔/리조트」
    ('숙박', 'en', 'Accommodation'),
    ('숙박기타', 'en', 'Accommodation'),
    -- 관광
    ('관광지', 'en', 'Attraction'),
    ('쇼핑', 'en', 'Shopping'),
    ('레포츠', 'en', 'Leisure Sports'),
    ('문화시설', 'en', 'Culture'),
    ('여행코스', 'en', 'Travel Route'),
    ('축제공연행사', 'en', 'Festival & Event'),
    -- 교통
    ('지하철역', 'en', 'Subway Station'),
    ('터미널정류소', 'en', 'Bus Stop'),
    ('버스터미널', 'en', 'Bus Terminal'),
    ('기차역', 'en', 'Train Station'),
    ('환승센터', 'en', 'Transit Center'),
    ('공항', 'en', 'Airport')
ON CONFLICT (ko, lang) DO UPDATE SET name = EXCLUDED.name
WHERE poi_category_i18n.name IS DISTINCT FROM EXCLUDED.name;

-- 새 판에 새 분류가 오면 여기 보인다 — 영어 화면에서 한국어로 폴백되고 있다는 뜻이다. 위 목록에 한 줄 더한다.
\echo ''
\echo '영어 분류가 없는 분류 (영업 중 POI 기준, 비어 있어야 정상):'
SELECT p.category, count(*) AS rows
FROM poi p
LEFT JOIN poi_category_i18n t ON t.ko = p.category AND t.lang = 'en'
WHERE t.ko IS NULL AND p.closed_at IS NULL
GROUP BY 1 ORDER BY 2 DESC, 1;

COMMIT;

-- 통계를 새로 잰다. 수십만 행이 한 번에 들어오면 플래너의 추정치가 낡아 첫 질의들이
-- 엉뚱한 계획을 탄다.
ANALYZE poi;

\echo ''
\echo 'poi 표 전체:'
SELECT category_group, count(*) AS rows FROM poi GROUP BY 1 ORDER BY 1;
