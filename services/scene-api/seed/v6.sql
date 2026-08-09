-- V6 수집 CSV 를 스키마로 옮긴다.
--
-- tools/scripts/seed.sh 가 CSV 를 파드 안 /tmp/seed-input.csv 로 옮겨 둔 뒤 이 파일을
-- psql 에 먹인다. `just seed` 가 그 둘을 묶는다.
--
-- ── 이 파일이 마이그레이션이 아닌 이유 ────────────────────────────────────────
--
-- 스키마가 아니라 데이터다. Flyway 마이그레이션에 INSERT 를 넣으면 데이터가 바뀔
-- 때마다 마이그레이션이 하나씩 쌓이고, 이미 적용된 것은 고칠 수 없어 "잘못 넣은 것을
-- 지우는 마이그레이션" 을 또 붙여야 한다. 정제된 V7·V8 이 나와도 명령 한 번으로
-- 갈아 끼우려고 분리했다 (docs/project/plans/scene-api-database.md §3).
--
-- ── 다시 돌릴 수 있게 만드는 방법: 지우고 다시 넣는다 ────────────────────────
--
-- content 와 person 에는 자연키가 없다. 같은 작품을 두 번 넣어도 DB 는 그것이 같은
-- 작품인지 알 방법이 없어 ON CONFLICT 로는 멱등을 만들 수 없다. 그래서 **시드
-- 데이터를 통째로 갈아 끼운다.** TRUNCATE 가 이 파일의 첫 동작이다.
--
-- 이것은 로컬 개발 DB 의 표본 데이터라서 성립하는 방식이다. seed.sh 가 kind
-- 컨텍스트가 아니면 실행을 거부한다.

\set ON_ERROR_STOP on

BEGIN;

-- ── 0. 기존 시드 데이터 제거 ─────────────────────────────────────────────────
--
-- CASCADE 가 i18n·별칭·이미지·연결 테이블까지 따라간다. RESTART IDENTITY 로
-- 시퀀스를 되돌려, 몇 번을 돌려도 같은 id 가 나오게 한다.
TRUNCATE place, content, person RESTART IDENTITY CASCADE;

-- ── 1. 스테이징 ──────────────────────────────────────────────────────────────
--
-- 전 컬럼 TEXT 다. CSV 는 정제 전이고 빈 문자열·형식 오류가 섞여 있을 수 있는데,
-- 적재 시점에 타입을 강제하면 어느 행이 왜 틀렸는지 모른 채 COPY 가 통째로 실패한다.
-- 일단 다 받고, 아래 변환에서 NULLIF 와 캐스팅으로 걸러 낸다.
CREATE TEMP TABLE seed_staging (
    id                TEXT,
    title             TEXT,
    title_aliases     TEXT,
    title_category    TEXT,
    title_network     TEXT,
    title_year        TEXT,
    title_genre       TEXT,
    title_cast        TEXT,
    place_name        TEXT,
    place_type        TEXT,
    place_address     TEXT,
    place_latitude    TEXT,
    place_longitude   TEXT,
    place_image_url   TEXT,
    place_naver_url   TEXT,
    scene_description TEXT,
    scene_image_url   TEXT,
    source_url        TEXT,
    last_updated      TEXT,
    famous_rank       TEXT,
    recent_rank       TEXT,
    audience_acc      TEXT,
    award             TEXT,
    director          TEXT,
    poster_url        TEXT
) ON COMMIT DROP;

-- 경로가 박혀 있는 이유:
--
-- \copy 는 **psql 이 도는 쪽**의 파일을 읽는다. 파드 안에서 돌면 파드의 /tmp 이고,
-- 노트북이나 CI 러너에서 돌면 그쪽의 /tmp 다. seed.sh 가 어느 쪽이든 psql 이 도는
-- 기계의 이 경로에 CSV 를 놓아 준다 (ADR 0005).
--
-- 변수로 받지 않는 이유: psql 은 \copy 인자에 변수 치환을 하지 않는다(문서에 명시).
-- 표준입력으로 받을 수도 없다 — -f 로 준 스크립트에서 \copy FROM STDIN 은 그 스크립트
-- 파일의 다음 줄들을 데이터로 읽는다. 둘 다 실측으로 확인했다.
\copy seed_staging FROM '/tmp/seed-input.csv' WITH (FORMAT csv, HEADER true)

-- 넣지 않는 컬럼과 이유:
--   source_url        앱이 읽지 않는다. 값은 01_Raw 수집 CSV 에 보존된다
--   recent_rank       164 행 전량이 비어 있다
--   audience_acc      164 행 전량이 비어 있다
--   award             popularity_score 에만 쓸 수 있는데 11 행만 채워져 있어 지금은 쓰지 않는다

-- ── 2. 작품 ──────────────────────────────────────────────────────────────────
--
-- CSV 는 "장소 한 곳 × 작품 하나" 가 한 행이라 같은 작품이 여러 번 나온다. 제목으로
-- 하나만 남긴다. ORDER BY 에 id 를 넣어 어느 행이 남는지 고정한다 — 없으면 같은
-- CSV 로 두 번 돌렸을 때 다른 행이 남을 수 있다.
--
-- id 를 nextval 로 미리 뽑는 이유: content 에는 title 컬럼이 없어(다국어라
-- content_i18n 에 있다) INSERT ... RETURNING 으로는 "이 id 가 어느 작품인지" 를 되
-- 가져올 수 없다. 먼저 번호를 매겨 두면 아래 모든 삽입이 이 표를 조인해 쓴다.
CREATE TEMP TABLE t_content ON COMMIT DROP AS
SELECT
    nextval('content_id_seq') AS content_id,
    s.title,
    s.title_aliases,
    s.title_category,
    NULLIF(s.title_network, '')  AS broadcaster,
    NULLIF(s.poster_url, '')     AS poster_url,
    NULLIF(s.title_year, '')::INT AS release_year,
    ARRAY(
        SELECT btrim(g)
        FROM unnest(string_to_array(s.title_genre, ';')) AS g
        WHERE btrim(g) <> ''
    ) AS genres,
    -- 인기도는 지금 임의값이다. 사용자 행동(user_event)이 쌓이면 배치가 계산한다.
    -- famous_rank 는 순위라 작을수록 유명하다 — 점수로 뒤집는다. 비어 있으면 중간값.
    CASE
        WHEN NULLIF(s.famous_rank, '') IS NOT NULL THEN GREATEST(100 - s.famous_rank::INT, 0)
        ELSE 50
    END AS popularity_score,
    s.title_cast,
    s.director
FROM (
    SELECT DISTINCT ON (title) * FROM seed_staging ORDER BY title, id
) s;

INSERT INTO content (id, category, broadcaster, poster_url, release_year, genres, popularity_score)
SELECT content_id, title_category, broadcaster, poster_url, release_year, genres, popularity_score
FROM t_content;

INSERT INTO content_i18n (content_id, lang, title)
SELECT content_id, 'ko', title FROM t_content;

-- ── 3. 작품 별칭과 영문 제목 ─────────────────────────────────────────────────
--
-- title_aliases 는 ';' 로 나뉜 목록인데 영문 제목과 한국어 별칭이 섞여 있다.
--   도깨비 → 'Guardian: The Lonely and Great God;Goblin;쓸쓸하고 찬란하神-도깨비'
--
-- **라틴 문자로 시작하는 첫 번째 것을 en 제목으로 승격한다.** 나머지는 별칭이다.
-- 그래야 Accept-Language: en 인 사용자가 'Goblin' 을 검색했을 때 결과도 영어로 나온다
-- (contracts/openapi/scene-api-v1.yaml). 승격하지 않으면 검색은 걸려도 제목이
-- 한국어로 나온다.
--
-- 문자 범위 대신 '^[A-Za-z]' 로 판별하는 이유: DB 로케일이 C 라 한글 범위 표현이
-- 로케일에 기대게 된다. "라틴으로 시작하는가" 만 보면 로케일과 무관하다.
CREATE TEMP TABLE t_alias ON COMMIT DROP AS
SELECT
    c.content_id,
    btrim(u.alias) AS alias,
    u.ord
FROM t_content c
CROSS JOIN unnest(string_to_array(c.title_aliases, ';')) WITH ORDINALITY AS u(alias, ord)
WHERE btrim(u.alias) <> '';

INSERT INTO content_i18n (content_id, lang, title)
SELECT DISTINCT ON (content_id) content_id, 'en', alias
FROM t_alias
WHERE alias ~ '^[A-Za-z]'
ORDER BY content_id, ord;

-- en 제목으로 승격된 것은 별칭에서 뺀다. 같은 값이 제목과 별칭에 겹쳐 들어가면
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
    WHERE ci.content_id = a.content_id AND ci.lang = 'en' AND ci.title = a.alias
);

-- ── 4. 인물 ──────────────────────────────────────────────────────────────────
--
-- title_cast 는 배우, director 는 감독이다. 둘 다 ';' 로 나뉘고 나열 순서가 곧
-- 비중이라 sort_order 로 보존한다. 같은 사람이 여러 작품에 나오므로 이름으로 합친다.
--
-- 이름만으로 사람을 식별하는 것은 동명이인을 구분하지 못한다. 지금 데이터에는
-- 동명이인이 없고, 사람을 식별할 다른 값(wikidata_qid 등)이 CSV 에 없다.
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

-- 감독 중 'Kim Seong-yoon' 처럼 라틴 표기로 수집된 사람이 있다. 한국어 이름이 없는
-- 사람을 ko 로 넣으면 한국어 사용자에게 라틴 이름이 한국어인 척 나온다.
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
-- naver_place_url 이 dedupe 1차 키다 (MZ2AZ-111). 같은 장소가 여러 작품에 나오면
-- CSV 에 여러 행으로 있는데, 장소로는 하나여야 한다 — 그것이 place_content 가
-- 흡수하는 N:M 이다.
--
-- **주의: 같은 장소인데 행마다 place_type 이 다른 경우가 있다.** 일월수목원이
-- '자연'(눈물의 여왕 행)과 '공원'(이태원 클라쓰 행)으로 들어와 있다. 정제 전
-- 데이터라 생기는 일이고, ORDER BY 로 어느 행이 이기는지 고정해 둔다.
CREATE TEMP TABLE t_place ON COMMIT DROP AS
SELECT nextval('place_id_seq') AS place_id, s.*
FROM (
    SELECT DISTINCT ON (place_naver_url) * FROM seed_staging ORDER BY place_naver_url, id
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
    NULLIF(place_naver_url, '')
FROM t_place;

-- place_i18n.description 은 비운다. CSV 의 scene_description 은 "이 작품의 이 장면"
-- 설명이라 장소 자체의 설명이 아니다 — place_content_i18n 으로 간다.
INSERT INTO place_i18n (place_id, lang, name, address)
SELECT place_id, 'ko', place_name, NULLIF(place_address, '')
FROM t_place;

-- sort_order 를 10 부터 매긴다. 대표 이미지는 첫 번째이고, 나중에 사이에 끼워 넣을
-- 여지를 둔다. 지금은 장소당 한 장뿐이다.
INSERT INTO place_image (place_id, url, sort_order)
SELECT place_id, place_image_url, 10
FROM t_place
WHERE NULLIF(place_image_url, '') IS NOT NULL;

-- place_alias 는 채우지 않는다. CSV 에 장소 별칭 컬럼이 없다. 검색은 place_i18n.name
-- 으로만 걸린다.

-- ── 6. 장소 × 작품 ───────────────────────────────────────────────────────────
--
-- 여기가 CSV 한 행에 해당한다. 같은 (장소, 작품) 이 두 번 나오면 하나로 접는다.
CREATE TEMP TABLE t_place_content ON COMMIT DROP AS
SELECT
    nextval('place_content_id_seq') AS place_content_id,
    p.place_id,
    c.content_id,
    s.scene_description,
    s.scene_image_url,
    s.last_updated
FROM (
    SELECT DISTINCT ON (place_naver_url, title) * FROM seed_staging
    ORDER BY place_naver_url, title, id
) s
JOIN t_place   p ON p.place_naver_url = s.place_naver_url
JOIN t_content c ON c.title = s.title;

INSERT INTO place_content (id, place_id, content_id, scene_image_url, updated_at)
SELECT
    place_content_id, place_id, content_id,
    NULLIF(scene_image_url, ''),
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
UNION ALL SELECT 'place_image',        count(*) FROM place_image
UNION ALL SELECT 'place_content',      count(*) FROM place_content
UNION ALL SELECT 'place_content_i18n', count(*) FROM place_content_i18n
UNION ALL SELECT 'search_term',        count(*) FROM search_term;
