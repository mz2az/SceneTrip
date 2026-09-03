-- 성지후보 CSV(김태환 수집, 10작품)를 스키마로 옮긴다.
--
-- tools/scripts/seed.sh 가 CSV 를 파드 안 /tmp/seed-input.csv 로 옮겨 둔 뒤 이 파일을
-- psql 에 먹인다. `just seed` 가 그 둘을 묶는다.
--
-- v6.sql(승길 수집 V6, 25컬럼)을 이 형식(30컬럼)으로 다시 쓴 것이다. \copy 의 HEADER 는
-- 첫 줄을 건너뛸 뿐 이름으로 맞추지 않으므로 컬럼이 다르면 변환도 달라야 한다.
--
-- ── 이 파일이 마이그레이션이 아닌 이유 ────────────────────────────────────────
--
-- 스키마가 아니라 데이터다. Flyway 마이그레이션에 INSERT 를 넣으면 데이터가 바뀔
-- 때마다 마이그레이션이 하나씩 쌓이고, 이미 적용된 것은 고칠 수 없어 "잘못 넣은 것을
-- 지우는 마이그레이션" 을 또 붙여야 한다. 다음 수집분이 나와도 명령 한 번으로 갈아
-- 끼우려고 분리했다 (docs/project/plans/scene-api-database.md §3).
--
-- ── 다시 돌릴 수 있게 만드는 방법: 지우고 다시 넣는다 ────────────────────────
--
-- content 와 person 에는 자연키가 없다. 같은 작품을 두 번 넣어도 DB 는 그것이 같은
-- 작품인지 알 방법이 없어 ON CONFLICT 로는 멱등을 만들 수 없다. 그래서 **시드
-- 데이터를 통째로 갈아 끼운다.** TRUNCATE 가 이 파일의 첫 동작이다.
--
-- 이것은 로컬 개발 DB 라서 성립하는 방식이다. seed.sh 가 kind 컨텍스트가 아니면
-- 실행을 거부한다.

\set ON_ERROR_STOP on

BEGIN;

-- ── 0. 기존 시드 데이터 제거 ─────────────────────────────────────────────────
--
-- CASCADE 가 i18n·별칭·이미지·연결 테이블까지 따라간다. RESTART IDENTITY 로
-- 시퀀스를 되돌려, 몇 번을 돌려도 같은 id 가 나오게 한다.
TRUNCATE place, content, person RESTART IDENTITY CASCADE;

-- ── 1. 스테이징 ──────────────────────────────────────────────────────────────
--
-- 전 컬럼 TEXT 다. 빈 문자열·형식 오류가 섞여 있을 수 있는데, 적재 시점에 타입을
-- 강제하면 어느 행이 왜 틀렸는지 모른 채 COPY 가 통째로 실패한다. 일단 다 받고,
-- 아래 변환에서 NULLIF 와 캐스팅으로 걸러 낸다.
--
-- 컬럼 순서가 CSV 헤더와 정확히 같아야 한다 — \copy 는 자리로 맞춘다.
CREATE TEMP TABLE seed_staging (
    id                  TEXT,
    title               TEXT,
    title_tmdb_url      TEXT,
    title_aliases       TEXT,
    title_en            TEXT,
    title_ja            TEXT,
    title_zh_hant       TEXT,
    title_category      TEXT,
    title_cast          TEXT,
    place_name          TEXT,
    place_name_en       TEXT,
    place_name_ja       TEXT,
    place_name_zh_hant  TEXT,
    place_aliases       TEXT,
    place_type          TEXT,
    place_address       TEXT,
    place_latitude      TEXT,
    place_longitude     TEXT,
    place_image_url     TEXT,
    place_naver_url     TEXT,
    scene_description   TEXT,
    source_url          TEXT,
    last_updated        TEXT,
    famous_rank         TEXT,
    recent_rank         TEXT,
    audience_acc        TEXT,
    award               TEXT,
    director            TEXT,
    poster_url          TEXT,
    notes               TEXT
) ON COMMIT DROP;

-- \copy 는 **psql 이 도는 쪽**의 파일을 읽는다. 파드 안에서 돌면 파드의 /tmp 이고,
-- 직접 접속이면 이 노트북이나 CI 러너의 /tmp 다. seed.sh 가 어느 쪽이든 그 자리에
-- 놓아 준다. 변수로 받지 않는 이유: psql 은 \copy 인자에 변수 치환을 하지 않는다.
-- 파일 머리의 BOM 은 HEADER 가 첫 줄째 버리므로 문제없고, CRLF 는 CSV 모드가 다룬다.
\copy seed_staging FROM '/tmp/seed-input.csv' WITH (FORMAT csv, HEADER true)

-- 넣지 않는 컬럼과 이유:
--   title_tmdb_url    전량 비어 있다. 채워지면 content 에 외부 id 컬럼을 두고 받는다
--   source_url        앱이 읽지 않는다. 값은 수집 CSV 에 보존된다
--   recent_rank       전량 비어 있다
--   audience_acc      전량 비어 있다
--   award             전량 비어 있다
--   notes             수집 판정 메모(「성지점수 20.7 …」). 사용자에게 보일 것이 아니다

-- ── 1-1. 걸러 낸 행 ─────────────────────────────────────────────────────────
--
-- 좌표가 없는 행은 place.geom NOT NULL 을 만족할 수 없다. 그 한 행 때문에 적재 전체가
-- 롤백되면 나머지 86행이 볼모가 된다 — 건너뛰고 몇 행인지 찍는다. 좌표가 채워진
-- 파일로 다시 `just seed` 하면 그때 들어온다.
CREATE TEMP TABLE seed_rows ON COMMIT DROP AS
SELECT
    s.*,
    -- 장소 중복 키. naver_place_url 이 1차 키(MZ2AZ-111)인데 9행이 비어 있다. 빈 채로
    -- DISTINCT ON 을 걸면 그 9곳이 한 곳으로 뭉개진다 — 없으면 이름+주소로 대신한다.
    COALESCE(NULLIF(btrim(s.place_naver_url), ''), s.place_name || '|' || s.place_address) AS place_key
FROM seed_staging s
WHERE NULLIF(btrim(s.place_latitude), '') IS NOT NULL
  AND NULLIF(btrim(s.place_longitude), '') IS NOT NULL;

\echo ''
\echo '좌표가 없어 건너뛴 행 (place.geom 이 NOT NULL 이라 넣을 수 없다):'
SELECT id, place_name
FROM seed_staging
WHERE NULLIF(btrim(place_latitude), '') IS NULL OR NULLIF(btrim(place_longitude), '') IS NULL
ORDER BY id;

-- ── 2. 작품 ──────────────────────────────────────────────────────────────────
--
-- CSV 는 "장소 한 곳 × 작품 하나" 가 한 행이라 같은 작품이 여러 번 나온다. 제목으로
-- 하나만 남긴다. ORDER BY 에 id 를 넣어 어느 행이 남는지 고정한다.
--
-- id 를 nextval 로 미리 뽑는 이유: content 에는 title 컬럼이 없어(다국어라
-- content_i18n 에 있다) INSERT ... RETURNING 으로는 "이 id 가 어느 작품인지" 를 되
-- 가져올 수 없다. 먼저 번호를 매겨 두면 아래 모든 삽입이 이 표를 조인해 쓴다.
--
-- 이 형식에는 방송사·방영 연도·장르 컬럼이 없다. broadcaster·release_year 는 NULL,
-- genres 는 NOT NULL 이라 빈 배열이다.
CREATE TEMP TABLE t_content ON COMMIT DROP AS
SELECT
    nextval('content_id_seq') AS content_id,
    s.title,
    s.title_aliases,
    NULLIF(btrim(s.title_en), '')      AS title_en,
    NULLIF(btrim(s.title_ja), '')      AS title_ja,
    NULLIF(btrim(s.title_zh_hant), '') AS title_zh_hant,
    s.title_category,
    NULLIF(s.poster_url, '') AS poster_url,
    -- 인기도는 지금 임의값이다. 사용자 행동(user_event)이 쌓이면 배치가 계산한다.
    -- famous_rank 는 순위라 작을수록 유명하다 — 점수로 뒤집는다. 비어 있으면 중간값.
    CASE
        WHEN NULLIF(s.famous_rank, '') IS NOT NULL THEN GREATEST(100 - s.famous_rank::INT, 0)
        ELSE 50
    END AS popularity_score,
    s.title_cast,
    s.director
FROM (
    SELECT DISTINCT ON (title) * FROM seed_rows ORDER BY title, id
) s;

INSERT INTO content (id, category, broadcaster, poster_url, release_year, genres, popularity_score)
SELECT content_id, title_category, NULL, poster_url, NULL, '{}', popularity_score
FROM t_content;

INSERT INTO content_i18n (content_id, lang, title)
SELECT content_id, 'ko', title FROM t_content;

-- ── 3. 작품 다국어 제목과 별칭 ──────────────────────────────────────────────
--
-- 이 형식에는 title_en·title_ja·title_zh_hant 컬럼이 있다. 채워져 있으면 그것이
-- 정본이다. 지금 파일은 셋 다 비어 있지만 다음 수집분이 채우면 그대로 들어간다.
INSERT INTO content_i18n (content_id, lang, title)
SELECT content_id, 'en', title_en FROM t_content WHERE title_en IS NOT NULL
UNION ALL
SELECT content_id, 'ja', title_ja FROM t_content WHERE title_ja IS NOT NULL
UNION ALL
SELECT content_id, 'zh-Hant', title_zh_hant FROM t_content WHERE title_zh_hant IS NOT NULL;

-- title_aliases 는 ';' 로 나뉜 목록인데 영문 제목과 한국어 별칭이 섞여 있다.
--   도깨비 → 'Guardian: The Lonely and Great God'
--   폭싹 속았수다 → 'When Life Gives You Tangerines'
--
-- **title_en 이 비어 있으면 라틴 문자로 시작하는 첫 별칭을 en 제목으로 승격한다.**
-- 그래야 Accept-Language: en 인 사용자가 'Goblin' 을 검색했을 때 결과도 영어로 나온다.
-- 승격하지 않으면 검색은 걸려도 제목이 한국어로 나온다.
--
-- 문자 범위 대신 '^[A-Za-z]' 로 판별하는 이유: 로케일과 무관하게 "라틴으로 시작하는가"
-- 만 본다.
CREATE TEMP TABLE t_alias ON COMMIT DROP AS
SELECT
    c.content_id,
    btrim(u.alias) AS alias,
    u.ord
FROM t_content c
CROSS JOIN unnest(string_to_array(c.title_aliases, ';')) WITH ORDINALITY AS u(alias, ord)
WHERE btrim(u.alias) <> '';

INSERT INTO content_i18n (content_id, lang, title)
SELECT DISTINCT ON (a.content_id) a.content_id, 'en', a.alias
FROM t_alias a
JOIN t_content c ON c.content_id = a.content_id
WHERE a.alias ~ '^[A-Za-z]'
  AND c.title_en IS NULL
ORDER BY a.content_id, a.ord;

-- en 제목으로 들어간 것은 별칭에서 뺀다. 같은 값이 제목과 별칭에 겹쳐 들어가면
-- search_term 이 같은 작품에 같은 표기를 두 번 담게 된다.
INSERT INTO content_alias (content_id, alias, lang)
SELECT
    a.content_id,
    a.alias,
    -- 라틴 표기는 lang 을 비운다 (DBML: 한글 ko / 라틴 NULL)
    CASE WHEN a.alias ~ '^[A-Za-z]' THEN NULL ELSE 'ko' END
FROM t_alias a
WHERE NOT EXISTS (
    SELECT 1 FROM content_i18n ci
    WHERE ci.content_id = a.content_id AND ci.title = a.alias
);

-- ── 4. 인물 ──────────────────────────────────────────────────────────────────
--
-- title_cast 는 배우, director 는 감독이다. 둘 다 ';' 로 나뉘고 나열 순서가 곧
-- 비중이라 sort_order 로 보존한다. 같은 사람이 여러 작품에 나오므로 이름으로 합친다.
--
-- 이름만으로 사람을 식별하는 것은 동명이인을 구분하지 못한다. 사람을 식별할 다른
-- 값(wikidata_qid 등)이 CSV 에 없다.
CREATE TEMP TABLE t_cast ON COMMIT DROP AS
SELECT c.content_id, btrim(u.name) AS name, 'actor' AS role_type, u.ord::INT AS sort_order
FROM t_content c
CROSS JOIN unnest(string_to_array(c.title_cast, ';')) WITH ORDINALITY AS u(name, ord)
WHERE btrim(u.name) <> ''
UNION ALL
SELECT c.content_id, btrim(u.name), 'director', u.ord::INT
FROM t_content c
CROSS JOIN unnest(string_to_array(c.director, ';')) WITH ORDINALITY AS u(name, ord)
WHERE btrim(u.name) <> '';

CREATE TEMP TABLE t_person ON COMMIT DROP AS
SELECT nextval('person_id_seq') AS person_id, name
FROM (SELECT DISTINCT name FROM t_cast) d;

INSERT INTO person (id) SELECT person_id FROM t_person;

-- 라틴 표기로 수집된 사람을 ko 로 넣으면 한국어 사용자에게 라틴 이름이 한국어인 척
-- 나온다.
INSERT INTO person_i18n (person_id, lang, name)
SELECT person_id, CASE WHEN name ~ '^[A-Za-z]' THEN 'en' ELSE 'ko' END, name
FROM t_person;

-- PK 가 (content_id, person_id, role_type) 라 한 작품에서 연출·주연을 겸해도 두 행이
-- 남는다. 같은 역할로 두 번 나온 경우만 앞의 것을 남긴다.
INSERT INTO content_cast (content_id, person_id, role_type, sort_order)
SELECT DISTINCT ON (r.content_id, p.person_id, r.role_type)
    r.content_id, p.person_id, r.role_type, r.sort_order
FROM t_cast r
JOIN t_person p ON p.name = r.name
ORDER BY r.content_id, p.person_id, r.role_type, r.sort_order;

-- ── 5. 장소 ──────────────────────────────────────────────────────────────────
--
-- place_key(§1-1)로 중복을 접는다. 같은 장소가 여러 작품에 나오면 CSV 에 여러 행으로
-- 있는데, 장소로는 하나여야 한다 — 그것이 place_content 가 흡수하는 N:M 이다. 이
-- 파일에서는 청라호수공원·중앙고가 두 작품에 나온다.
--
-- 같은 장소인데 행마다 place_type 이 다를 수 있다. ORDER BY 로 어느 행이 이기는지
-- 고정해 둔다.
CREATE TEMP TABLE t_place ON COMMIT DROP AS
SELECT nextval('place_id_seq') AS place_id, s.*
FROM (
    SELECT DISTINCT ON (place_key) * FROM seed_rows ORDER BY place_key, id
) s;

INSERT INTO place (id, type, geom, naver_place_url)
SELECT
    place_id,
    NULLIF(place_type, ''),
    -- ST_MakePoint 는 (경도, 위도) 순이다. 뒤집으면 오류 없이 엉뚱한 곳에 찍힌다 —
    -- 위도 37 · 경도 127 을 뒤집으면 대한민국이 아니라 인도양이 된다.
    ST_SetSRID(
        ST_MakePoint(place_longitude::DOUBLE PRECISION, place_latitude::DOUBLE PRECISION),
        4326
    )::geography,
    NULLIF(btrim(place_naver_url), '')
FROM t_place;

-- place_i18n.description 은 비운다. CSV 의 scene_description 은 "이 작품의 이 장면"
-- 설명이라 장소 자체의 설명이 아니다 — place_content_i18n 으로 간다.
INSERT INTO place_i18n (place_id, lang, name, address)
SELECT place_id, 'ko', place_name, NULLIF(place_address, '')
FROM t_place;

-- 이 형식에는 place_name_en·ja·zh_hant 가 있다. 채워진 것만 넣는다 — 지금 파일은 전부
-- 비어 있지만 다음 수집분이 채우면 영어 사용자가 장소명을 영어로 본다. 주소는 한국어뿐이라
-- 다른 언어 행에도 한국어 주소를 준다 — 없는 것보다 낫다.
INSERT INTO place_i18n (place_id, lang, name, address)
SELECT place_id, 'en', btrim(place_name_en), NULLIF(place_address, '')
FROM t_place WHERE NULLIF(btrim(place_name_en), '') IS NOT NULL
UNION ALL
SELECT place_id, 'ja', btrim(place_name_ja), NULLIF(place_address, '')
FROM t_place WHERE NULLIF(btrim(place_name_ja), '') IS NOT NULL
UNION ALL
SELECT place_id, 'zh-Hant', btrim(place_name_zh_hant), NULLIF(place_address, '')
FROM t_place WHERE NULLIF(btrim(place_name_zh_hant), '') IS NOT NULL;

-- sort_order 를 10 부터 매긴다. 대표 이미지는 첫 번째이고, 나중에 사이에 끼워 넣을
-- 여지를 둔다. 지금은 장소당 한 장뿐이다.
INSERT INTO place_image (place_id, url, sort_order)
SELECT place_id, place_image_url, 10
FROM t_place
WHERE NULLIF(place_image_url, '') IS NOT NULL;

-- 장소 별칭. v6 에는 이 컬럼이 없어 place_alias 가 비어 있었다 — 이 형식은 30행에
-- 있다(관덕정·김녕해수욕장…). ';' 로 나뉜다. 라틴 표기는 lang 을 비운다.
INSERT INTO place_alias (place_id, alias, lang)
SELECT
    p.place_id,
    btrim(u.alias),
    CASE WHEN btrim(u.alias) ~ '^[A-Za-z]' THEN NULL ELSE 'ko' END
FROM t_place p
CROSS JOIN unnest(string_to_array(p.place_aliases, ';')) AS u(alias)
WHERE btrim(u.alias) <> ''
  AND btrim(u.alias) <> p.place_name;

-- ── 6. 장소 × 작품 ───────────────────────────────────────────────────────────
--
-- 여기가 CSV 한 행에 해당한다. 같은 (장소, 작품) 이 두 번 나오면 하나로 접는다.
-- 이 형식에는 scene_image_url 이 없다 — place_content.scene_image_url 은 NULL 이다.
CREATE TEMP TABLE t_place_content ON COMMIT DROP AS
SELECT
    nextval('place_content_id_seq') AS place_content_id,
    p.place_id,
    c.content_id,
    s.scene_description,
    s.last_updated
FROM (
    SELECT DISTINCT ON (place_key, title) * FROM seed_rows
    ORDER BY place_key, title, id
) s
JOIN t_place   p ON p.place_key = s.place_key
JOIN t_content c ON c.title = s.title;

INSERT INTO place_content (id, place_id, content_id, scene_image_url, updated_at)
SELECT
    place_content_id, place_id, content_id,
    NULL,
    COALESCE(NULLIF(last_updated, '')::TIMESTAMPTZ, now())
FROM t_place_content;

INSERT INTO place_content_i18n (place_content_id, lang, relation_description)
SELECT place_content_id, 'ko', scene_description
FROM t_place_content
WHERE NULLIF(scene_description, '') IS NOT NULL;

-- ── 7. 장소 인기도 ───────────────────────────────────────────────────────────
--
-- CSV 에 장소별 지표가 없다. 지금은 그 장소가 나온 작품 중 가장 인기 있는 것의
-- 점수를 물려받는다 — 유명한 작품의 촬영지가 지도에서 먼저 보이는 편이 낫다.
-- 작품과 마찬가지로 임시값이고, user_event 가 쌓이면 배치가 다시 계산한다.
UPDATE place p
SET popularity_score = agg.score
FROM (
    SELECT pc.place_id, max(c.popularity_score) AS score
    FROM place_content pc
    JOIN content c ON c.id = pc.content_id
    GROUP BY pc.place_id
) agg
WHERE p.id = agg.place_id;

COMMIT;

-- ── 8. 검색 색인 갱신 ────────────────────────────────────────────────────────
--
-- 트랜잭션 밖이다. CONCURRENTLY 는 트랜잭션 블록 안에서 실행할 수 없다.
REFRESH MATERIALIZED VIEW CONCURRENTLY search_term;

-- ── 결과 ─────────────────────────────────────────────────────────────────────

\echo ''
\echo '적재 결과'
SELECT 'content' AS 테이블, count(*) FROM content
UNION ALL SELECT 'content_i18n',       count(*) FROM content_i18n
UNION ALL SELECT 'content_alias',      count(*) FROM content_alias
UNION ALL SELECT 'person',             count(*) FROM person
UNION ALL SELECT 'content_cast',       count(*) FROM content_cast
UNION ALL SELECT 'place',              count(*) FROM place
UNION ALL SELECT 'place_i18n',         count(*) FROM place_i18n
UNION ALL SELECT 'place_alias',        count(*) FROM place_alias
UNION ALL SELECT 'place_image',        count(*) FROM place_image
UNION ALL SELECT 'place_content',      count(*) FROM place_content
UNION ALL SELECT 'place_content_i18n', count(*) FROM place_content_i18n
UNION ALL SELECT 'search_term',        count(*) FROM search_term;
