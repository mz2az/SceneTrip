-- 성지후보 CSV(김태환 수집, 10작품)를 스키마로 옮긴다.
--
-- tools/scripts/seed.sh 가 CSV 를 파드 안 /tmp/seed-input.csv 로 옮겨 둔 뒤 이 파일을
-- psql 에 먹인다. `just seed` 가 그 둘을 묶는다.
--
-- v6.sql(승길 수집 V6, 25컬럼)을 이 형식(51컬럼)으로 다시 쓴 것이다. \copy 의 HEADER 는
-- 첫 줄을 건너뛸 뿐 이름으로 맞추지 않으므로 컬럼이 다르면 변환도 달라야 한다.
--
-- 49컬럼은 30컬럼(v3, 2026-08-24)에 다국어 21개와 scene_image_url 을 더하고
-- (2026-09-12), title_producer_url 을 더한 뒤, recent_rank · audience_acc · award ·
-- famous_rank 를 뺀 것이다(2026-09-23). 51컬럼은 거기에 content_key(title 앞)·place_key
-- (place_name 앞)를 더한 것이다(2026-10-07). **53컬럼(2026-10-10)** 은 키를 맨 끝으로 옮기고 이름을 바꿨다 —
-- notes 뒤에 place_popularity_score · title_key(= content_key) · place_key · sanctum_key. 더한 것은 전부 형제 컬럼 옆에 있다 —
-- title 옆에 title_description_*, place_address 옆에 place_address_*. 다국어는 지금
-- 전부 비어 있다.
--
-- scene_image_url 은 v3 의 place_image_url 에 있던 값을 옮긴 것이다. 그 사진들은
-- 장소 사진이 아니라 **그 작품의 그 장면** 스틸이었다(파일명이 행 id 로 시작하고 87행
-- 87장이 다 다르다 — 같은 장소가 두 작품에 나오면 사진도 둘이다). 장소 사진 자리인
-- place_image 에 넣으면 작품마다 다른 사진 중 하나만 남고, 나머지는 버려진다. 옮긴 뒤
-- place_image_url 에는 네이버 플레이스의 장소 사진 URL 을 ';' 로 이어 넣었다(2026-09-12).
--
-- ── 이 파일이 마이그레이션이 아닌 이유 ────────────────────────────────────────
--
-- 스키마가 아니라 데이터다. Flyway 마이그레이션에 INSERT 를 넣으면 데이터가 바뀔
-- 때마다 마이그레이션이 하나씩 쌓이고, 이미 적용된 것은 고칠 수 없어 "잘못 넣은 것을
-- 지우는 마이그레이션" 을 또 붙여야 한다. 다음 수집분이 나와도 명령 한 번으로 갈아
-- 끼우려고 분리했다 (docs/project/plans/scene-api-database.md §3).
--
-- ── 다시 돌릴 수 있게 만드는 방법: 키로 갱신한다 (2026-10-07) ────────────────
--
-- 작품·촬영지는 CSV 의 content_key·place_key 로 알아본다 — 있으면 갱신, 없으면 추가,
-- CSV 에서 빠지면 지우지 않고 hidden_at 을 채운다. id 가 바뀌지 않으므로 그것을 가리키는
-- 사용자 데이터(장바구니·코스·찜·마켓)가 적재 때마다 지워지지 않는다. 예전에는 키가 없어
-- TRUNCATE ... CASCADE 로 통째로 갈았고, 사용자 데이터도 같이 지워졌다.
--
-- 사용자 데이터가 가리키지 않는 딸린 표(i18n·별칭·사진·장소×작품·인물)는 이 CSV 에 있는
-- 작품·촬영지의 것만 지우고 다시 넣는다 — 그편이 단순하고 CSV 와 어긋날 일이 없다. 숨긴
-- 작품·촬영지의 딸린 행은 상세 화면을 위해 그대로 둔다.
--
-- 계획: docs/project/plans/place-key-seed.md.

\set ON_ERROR_STOP on

BEGIN;

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
    title_producer_url  TEXT,
    title_aliases       TEXT,
    title_en            TEXT,
    title_ja            TEXT,
    title_zh_hant       TEXT,
    title_description         TEXT,
    title_description_en      TEXT,
    title_description_ja      TEXT,
    title_description_zh_hant TEXT,
    title_category      TEXT,
    title_cast          TEXT,
    title_cast_en       TEXT,
    title_cast_ja       TEXT,
    title_cast_zh_hant  TEXT,
    place_name          TEXT,
    place_name_en       TEXT,
    place_name_ja       TEXT,
    place_name_zh_hant  TEXT,
    place_aliases       TEXT,
    place_type          TEXT,
    place_type_code     TEXT,
    place_address       TEXT,
    place_address_en    TEXT,
    place_address_ja    TEXT,
    place_address_zh_hant TEXT,
    place_latitude      TEXT,
    place_longitude     TEXT,
    place_description         TEXT,
    place_description_en      TEXT,
    place_description_ja      TEXT,
    place_description_zh_hant TEXT,
    place_image_url     TEXT,
    place_naver_url     TEXT,
    scene_description         TEXT,
    scene_description_en      TEXT,
    scene_description_ja      TEXT,
    scene_description_zh_hant TEXT,
    scene_image_url     TEXT,
    source_url          TEXT,
    last_updated        TEXT,
    director            TEXT,
    director_en         TEXT,
    director_ja         TEXT,
    director_zh_hant    TEXT,
    poster_url          TEXT,
    notes               TEXT,
    -- 53컬럼(2026-10-10, 128 작품 545 행): 끝에 넷이 붙는다. CSV 이름은 title_key 지만 여기서는 content_key 로 받는다 —
    -- 자리로 맞추므로 이름은 상관없고, 아래 변환이 쓰는 이름을 바꾸지 않으려고서다.
    place_popularity_score TEXT,  -- 장소×작품 인기도. 아직 넣지 않는다 — MZ2AZ-339(place_content 인기도)에서 받는다
    content_key         TEXT,     -- CSV 의 title_key (예: tv197067)
    place_key           TEXT,     -- 예: nv17052534(네이버 장소 번호) · px…(번호를 못 찾은 곳)
    sanctum_key         TEXT      -- 작품-촬영지 짝(title_key-place_key). 행마다 하나 — 넣을 곳은 아직 없다
) ON COMMIT DROP;

-- \copy 는 **psql 이 도는 쪽**의 파일을 읽는다. 파드 안에서 돌면 파드의 /tmp 이고,
-- 직접 접속이면 이 노트북이나 CI 러너의 /tmp 다. seed.sh 가 어느 쪽이든 그 자리에
-- 놓아 준다. 변수로 받지 않는 이유: psql 은 \copy 인자에 변수 치환을 하지 않는다.
-- 파일 머리의 BOM 은 HEADER 가 첫 줄째 버리므로 문제없고, CRLF 는 CSV 모드가 다룬다.
\copy seed_staging FROM '/tmp/seed-input.csv' WITH (FORMAT csv, HEADER true)

-- 넣지 않는 컬럼과 이유:
--   title_tmdb_url    전량 비어 있다. 채워지면 content 에 외부 id 컬럼을 두고 받는다
--   title_producer_url 작품 제작사의 페이지. 포스터를 누르면 이리로 간다. content 에 받을
--                     컬럼이 아직 없다 — 마이그레이션과 API 가 생기면 넣는다
--   source_url        앱이 읽지 않는다. 값은 수집 CSV 에 보존된다
--   notes             수집 판정 메모(「성지점수 20.7 …」). 사용자에게 보일 것이 아니다

-- ── 1-1. 걸러 낸 행 ─────────────────────────────────────────────────────────
--
-- 좌표가 없는 행은 place.geom NOT NULL 을 만족할 수 없다. 그 한 행 때문에 적재 전체가
-- 롤백되면 나머지 86행이 볼모가 된다 — 건너뛰고 몇 행인지 찍는다. 좌표가 채워진
-- 파일로 다시 `just seed` 하면 그때 들어온다.
--
-- 키는 앞뒤 공백을 떼어 쓴다. 손으로 고친 CSV 에 붙은 공백 하나로 다른 작품·촬영지가 되면 안 된다.
-- 키가 빈 행은 아래 검사가 적재 전체를 멈춘다 — 어느 작품·촬영지인지 모르는 행을 넣을 수 없다.
CREATE TEMP TABLE seed_rows ON COMMIT DROP AS
SELECT s.*
FROM seed_staging s
WHERE NULLIF(btrim(s.place_latitude), '') IS NOT NULL
  AND NULLIF(btrim(s.place_longitude), '') IS NOT NULL;

UPDATE seed_rows SET content_key = btrim(content_key), place_key = btrim(place_key);

\echo ''
\echo '좌표가 없어 건너뛴 행 (place.geom 이 NOT NULL 이라 넣을 수 없다):'
SELECT id, place_name
FROM seed_staging
WHERE NULLIF(btrim(place_latitude), '') IS NULL OR NULLIF(btrim(place_longitude), '') IS NULL
ORDER BY id;

-- 키 검사. 하나라도 걸리면 적재 전체를 되돌린다 — 반쯤 들어간 상태를 남기지 않는다.
--   키가 빈 행               어느 작품·촬영지인지 알 수 없다
--   한 작품 키에 제목이 둘   같은 키를 다른 작품에 잘못 붙였을 가능성이 크다
DO $$
DECLARE bad TEXT;
BEGIN
    SELECT string_agg(id, ', ' ORDER BY id) INTO bad
    FROM seed_staging
    WHERE NULLIF(btrim(content_key), '') IS NULL OR NULLIF(btrim(place_key), '') IS NULL;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'content_key 나 place_key 가 빈 행이 있습니다 — id: %', bad;
    END IF;

    -- 좌표가 없어 건너뛴 행까지 본다(seed_staging) — 그 행이 다음 수집분에 좌표를 달고 들어올 때 터지지 않게.
    SELECT string_agg(k || ' (' || titles || ')', ', ') INTO bad
    FROM (
        SELECT btrim(content_key) AS k, string_agg(DISTINCT title, ' / ') AS titles
        FROM seed_staging GROUP BY btrim(content_key) HAVING count(DISTINCT title) > 1
    ) x;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '한 content_key 에 제목이 여럿입니다 — %', bad;
    END IF;
END $$;

-- ── 2. 작품 ──────────────────────────────────────────────────────────────────
--
-- CSV 는 "장소 한 곳 × 작품 하나" 가 한 행이라 같은 작품이 여러 번 나온다. content_key 로
-- 하나만 남긴다. ORDER BY 에 id 를 넣어 어느 행이 남는지 고정한다.
--
-- content_id 는 UPSERT 뒤에 키로 찾아 채운다 — 있던 작품은 있던 id, 새 작품은 새 id.
--
-- 이 형식에는 방송사·방영 연도·장르 컬럼이 없다. broadcaster·release_year 는 NULL,
-- genres 는 NOT NULL 이라 빈 배열이다.
CREATE TEMP TABLE t_content ON COMMIT DROP AS
SELECT
    NULL::BIGINT AS content_id,
    s.content_key,
    s.title,
    s.title_aliases,
    NULLIF(btrim(s.title_en), '')      AS title_en,
    NULLIF(btrim(s.title_ja), '')      AS title_ja,
    NULLIF(btrim(s.title_zh_hant), '') AS title_zh_hant,
    NULLIF(btrim(s.title_description), '')         AS title_description,
    NULLIF(btrim(s.title_description_en), '')      AS title_description_en,
    NULLIF(btrim(s.title_description_ja), '')      AS title_description_ja,
    NULLIF(btrim(s.title_description_zh_hant), '') AS title_description_zh_hant,
    s.title_category,
    NULLIF(s.poster_url, '') AS poster_url,
    -- 인기도는 지금 임의값이다. 사용자 행동(user_event)이 쌓이면 배치가 계산한다.
    -- famous_rank 컬럼을 뺐으므로(2026-09-23) 모든 작품이 중간값 50 에서 시작한다.
    50 AS popularity_score,
    s.title_cast,
    s.title_cast_en,
    s.title_cast_ja,
    s.title_cast_zh_hant,
    s.director,
    s.director_en,
    s.director_ja,
    s.director_zh_hant
FROM (
    SELECT DISTINCT ON (content_key) * FROM seed_rows ORDER BY content_key, id
) s;

-- 다리(계획 §2-3). 이 CSV 의 키가 아직 DB 에 없으면, 한국어 제목이 같은 작품 중 **키가 없거나 이 CSV 에 없는 옛 키**를
-- 가진 것에 새 키를 붙인다 — 그 작품을 가리키는 코스·마켓이 새로 생긴 작품으로 끊기지 않게. 처음엔 V20 직후의 키
-- 없는 작품을 위해서였고, 2026-10-10 키 체계를 바꾸며(C0001… → tv197067…) 옛 키도 받게 넓혔다. 이 CSV 에 있는 키를
-- 가진 작품은 건드리지 않으므로 몇 번 돌려도 같다.
UPDATE content c
SET content_key = m.content_key
FROM (
    SELECT DISTINCT ON (t.content_key) t.content_key, ci.content_id
    FROM t_content t
    JOIN content_i18n ci ON ci.lang = 'ko' AND ci.title = t.title
    JOIN content x ON x.id = ci.content_id
         AND (x.content_key IS NULL
              OR NOT EXISTS (SELECT 1 FROM t_content o WHERE o.content_key = x.content_key))
    WHERE NOT EXISTS (SELECT 1 FROM content k WHERE k.content_key = t.content_key)
    ORDER BY t.content_key, ci.content_id
) m
WHERE c.id = m.content_id;

-- 있으면 갱신, 없으면 추가. CSV 에 없는 방송사·연도·장르는 덮지 않는다(이 형식에 칸이 없다).
-- 다시 CSV 에 나온 작품은 숨김을 푼다.
INSERT INTO content (content_key, category, broadcaster, poster_url, release_year, genres, popularity_score)
SELECT content_key, title_category, NULL, poster_url, NULL, '{}', popularity_score
FROM t_content
ON CONFLICT (content_key) DO UPDATE SET
    category         = EXCLUDED.category,
    poster_url       = EXCLUDED.poster_url,
    popularity_score = EXCLUDED.popularity_score,
    hidden_at        = NULL;

UPDATE t_content t SET content_id = c.id FROM content c WHERE c.content_key = t.content_key;

-- CSV 에서 빠진 작품은 지우지 않고 숨긴다(계획 §2-2). 키가 없는 작품(다리에서 짝을 못 찾은
-- 옛 행)도 이 CSV 에 없는 것이다.
UPDATE content
SET hidden_at = now()
WHERE hidden_at IS NULL
  AND (content_key IS NULL OR content_key NOT IN (SELECT content_key FROM t_content));

-- 딸린 표는 이 CSV 의 작품 것만 지우고 아래에서 다시 넣는다.
DELETE FROM content_i18n  WHERE content_id IN (SELECT content_id FROM t_content);
DELETE FROM content_alias WHERE content_id IN (SELECT content_id FROM t_content);

-- 작품 소개(title_description)는 지금 파일에 전부 비어 있다. 채워지면 그대로 들어간다.
INSERT INTO content_i18n (content_id, lang, title, description)
SELECT content_id, 'ko', title, title_description FROM t_content;

-- ── 3. 작품 다국어 제목과 별칭 ──────────────────────────────────────────────
--
-- 이 형식에는 title_en·title_ja·title_zh_hant 컬럼이 있다. 채워져 있으면 그것이
-- 정본이다. 지금 파일은 셋 다 비어 있지만 다음 수집분이 채우면 그대로 들어간다.
--
-- 소개는 제목 행에 얹혀 간다. content_i18n.title 이 NOT NULL 이라 제목 없는 언어에는
-- 행을 만들 수 없다 — 그 언어의 소개만 채워져 있으면 제목이 올 때까지 기다린다.
INSERT INTO content_i18n (content_id, lang, title, description)
SELECT content_id, 'en', title_en, title_description_en FROM t_content WHERE title_en IS NOT NULL
UNION ALL
SELECT content_id, 'ja', title_ja, title_description_ja FROM t_content WHERE title_ja IS NOT NULL
UNION ALL
SELECT content_id, 'zh-Hant', title_zh_hant, title_description_zh_hant FROM t_content WHERE title_zh_hant IS NOT NULL;

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

INSERT INTO content_i18n (content_id, lang, title, description)
SELECT DISTINCT ON (a.content_id) a.content_id, 'en', a.alias, c.title_description_en
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
--
-- 다국어 이름(title_cast_en·ja·zh_hant, director_en·ja·zh_hant)은 **같은 자리끼리**
-- 짝이다 — title_cast 의 세 번째 사람의 영어 이름은 title_cast_en 의 세 번째 항목이다.
-- 그래서 ';' 로 나눈 뒤 순번(ord)으로 잇는다. 외국어 목록이 짧거나 비어 있으면 그
-- 자리는 NULL 이고, 한국어 목록보다 길면 남는 것은 버려진다.
CREATE TEMP TABLE t_cast ON COMMIT DROP AS
SELECT
    c.content_id,
    btrim(u.name) AS name,
    NULLIF(btrim(en.v), '') AS name_en,
    NULLIF(btrim(ja.v), '') AS name_ja,
    NULLIF(btrim(zh.v), '') AS name_zh_hant,
    'actor' AS role_type,
    u.ord::INT AS sort_order
FROM t_content c
CROSS JOIN unnest(string_to_array(c.title_cast, ';')) WITH ORDINALITY AS u(name, ord)
LEFT JOIN unnest(string_to_array(c.title_cast_en, ';'))      WITH ORDINALITY AS en(v, ord) ON en.ord = u.ord
LEFT JOIN unnest(string_to_array(c.title_cast_ja, ';'))      WITH ORDINALITY AS ja(v, ord) ON ja.ord = u.ord
LEFT JOIN unnest(string_to_array(c.title_cast_zh_hant, ';')) WITH ORDINALITY AS zh(v, ord) ON zh.ord = u.ord
WHERE btrim(u.name) <> ''
UNION ALL
SELECT
    c.content_id,
    btrim(u.name),
    NULLIF(btrim(en.v), ''),
    NULLIF(btrim(ja.v), ''),
    NULLIF(btrim(zh.v), ''),
    'director',
    u.ord::INT
FROM t_content c
CROSS JOIN unnest(string_to_array(c.director, ';')) WITH ORDINALITY AS u(name, ord)
LEFT JOIN unnest(string_to_array(c.director_en, ';'))      WITH ORDINALITY AS en(v, ord) ON en.ord = u.ord
LEFT JOIN unnest(string_to_array(c.director_ja, ';'))      WITH ORDINALITY AS ja(v, ord) ON ja.ord = u.ord
LEFT JOIN unnest(string_to_array(c.director_zh_hant, ';')) WITH ORDINALITY AS zh(v, ord) ON zh.ord = u.ord
WHERE btrim(u.name) <> '';

-- 인물은 통째로 다시 넣는다. 사용자 데이터가 가리키지 않고(content_cast·person_i18n 만),
-- 이름 말고는 사람을 알아볼 키가 없다. 숨긴 작품의 출연진은 이때 빠진다.
TRUNCATE person RESTART IDENTITY CASCADE;

CREATE TEMP TABLE t_person ON COMMIT DROP AS
SELECT nextval('person_id_seq') AS person_id, name
FROM (SELECT DISTINCT name FROM t_cast) d;

INSERT INTO person (id) SELECT person_id FROM t_person;

-- 라틴 표기로 수집된 사람을 ko 로 넣으면 한국어 사용자에게 라틴 이름이 한국어인 척
-- 나온다.
INSERT INTO person_i18n (person_id, lang, name)
SELECT person_id, CASE WHEN name ~ '^[A-Za-z]' THEN 'en' ELSE 'ko' END, name
FROM t_person;

-- CSV 가 준 다국어 이름이 정본이다. 같은 사람이 여러 작품에 나오면 어느 행의 표기를
-- 쓸지 정해야 한다 — 작품·순번으로 고정한다. 위에서 라틴 이름을 en 으로 넣은 것과
-- 겹치면 CSV 쪽이 이긴다(ON CONFLICT ... UPDATE).
INSERT INTO person_i18n (person_id, lang, name)
SELECT person_id, lang, name
FROM (
    SELECT DISTINCT ON (p.person_id, l.lang)
        p.person_id, l.lang, l.name
    FROM t_person p
    JOIN t_cast r ON r.name = p.name
    CROSS JOIN LATERAL (
        VALUES ('en', r.name_en), ('ja', r.name_ja), ('zh-Hant', r.name_zh_hant)
    ) AS l(lang, name)
    WHERE l.name IS NOT NULL
    ORDER BY p.person_id, l.lang, r.content_id, r.sort_order
) x
ON CONFLICT (person_id, lang) DO UPDATE SET name = EXCLUDED.name;

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
-- place_key 로 중복을 접는다. 같은 장소가 여러 작품에 나오면 CSV 에 여러 행으로
-- 있는데, 장소로는 하나여야 한다 — 그것이 place_content 가 흡수하는 N:M 이다. 이
-- 파일에서는 청라호수공원·중앙고가 두 작품에 나온다.
--
-- 같은 장소인데 행마다 place_type 이 다를 수 있다. ORDER BY 로 어느 행이 이기는지
-- 고정해 둔다.
CREATE TEMP TABLE t_place ON COMMIT DROP AS
SELECT NULL::BIGINT AS place_id, s.*
FROM (
    SELECT DISTINCT ON (place_key) * FROM seed_rows ORDER BY place_key, id
) s;

-- 다리(계획 §2-3). 이 CSV 의 키가 아직 DB 에 없으면, 옛 묶음 규칙(네이버 URL, 없으면 한국어 이름+주소)으로 같은 촬영지를
-- 찾아 새 키를 붙인다 — 대상은 **키가 없거나 이 CSV 에 없는 옛 키**를 가진 촬영지. 그래야 장바구니·코스·찜·리뷰·같은 곳
-- 연결이 가리키는 촬영지가 그대로 이어진다(id 를 지킨다). 2026-10-10 키 체계를 바꿀 때(P0001… → nv17052534…) 옛 키도
-- 받게 넓혔다 — 넓히지 않으면 같은 네이버 URL 의 촬영지가 둘이 되어 UNIQUE 에 걸린다.
UPDATE place pl
SET place_key = m.place_key
FROM (
    SELECT DISTINCT ON (t.place_key) t.place_key, p.id
    FROM t_place t
    JOIN place p ON (p.place_key IS NULL
                     OR NOT EXISTS (SELECT 1 FROM t_place o WHERE o.place_key = p.place_key))
    WHERE NOT EXISTS (SELECT 1 FROM place k WHERE k.place_key = t.place_key)
      AND (
          (NULLIF(btrim(t.place_naver_url), '') IS NOT NULL
           AND p.naver_place_url = btrim(t.place_naver_url))
          OR (NULLIF(btrim(t.place_naver_url), '') IS NULL
              AND p.naver_place_url IS NULL
              AND EXISTS (SELECT 1 FROM place_i18n pi
                          WHERE pi.place_id = p.id AND pi.lang = 'ko'
                            AND pi.name = t.place_name
                            AND pi.address IS NOT DISTINCT FROM NULLIF(t.place_address, '')))
      )
    ORDER BY t.place_key, p.id
) m
WHERE pl.id = m.id;

-- place.type 은 코드값 자리다(V2 주석). CSV 의 place_type_code 가 채워지면 그것을 쓰고,
-- 비어 있으면 한국어 라벨(place_type)을 그대로 둔다 — 코드 매핑표가 생기기 전까지의
-- 과도기다. 지금 파일은 코드가 전부 비어 있어 라벨이 들어간다.
-- 있으면 갱신, 없으면 추가. 다시 CSV 에 나온 촬영지는 숨김을 푼다.
INSERT INTO place (place_key, type, geom, naver_place_url)
SELECT
    place_key,
    COALESCE(NULLIF(btrim(place_type_code), ''), NULLIF(place_type, '')),
    -- ST_MakePoint 는 (경도, 위도) 순이다. 뒤집으면 오류 없이 엉뚱한 곳에 찍힌다 —
    -- 위도 37 · 경도 127 을 뒤집으면 대한민국이 아니라 인도양이 된다.
    ST_SetSRID(
        ST_MakePoint(place_longitude::DOUBLE PRECISION, place_latitude::DOUBLE PRECISION),
        4326
    )::geography,
    NULLIF(btrim(place_naver_url), '')
FROM t_place
ON CONFLICT (place_key) DO UPDATE SET
    type            = EXCLUDED.type,
    geom            = EXCLUDED.geom,
    naver_place_url = EXCLUDED.naver_place_url,
    hidden_at       = NULL,
    updated_at      = now();

UPDATE t_place t SET place_id = p.id FROM place p WHERE p.place_key = t.place_key;

-- CSV 에서 빠진 촬영지는 지우지 않고 숨긴다(계획 §2-2).
UPDATE place
SET hidden_at = now()
WHERE hidden_at IS NULL
  AND (place_key IS NULL OR place_key NOT IN (SELECT place_key FROM t_place));

-- 딸린 표는 이 CSV 의 촬영지 것만 지우고 아래에서 다시 넣는다. 장소×작품도 여기서 지운다
-- (place_content_i18n 은 CASCADE) — §6 이 다시 넣는다.
DELETE FROM place_i18n    WHERE place_id IN (SELECT place_id FROM t_place);
DELETE FROM place_alias   WHERE place_id IN (SELECT place_id FROM t_place);
DELETE FROM place_image   WHERE place_id IN (SELECT place_id FROM t_place);
DELETE FROM place_content WHERE place_id IN (SELECT place_id FROM t_place);

-- place_i18n.description 은 장소 자체의 소개(place_description)다. scene_description 은
-- "이 작품의 이 장면" 설명이라 여기가 아니라 place_content_i18n 으로 간다. 지금 파일은
-- place_description 이 전부 비어 있다.
INSERT INTO place_i18n (place_id, lang, name, address, description)
SELECT place_id, 'ko', place_name, NULLIF(place_address, ''), NULLIF(btrim(place_description), '')
FROM t_place;

-- 이 형식에는 place_name_en·ja·zh_hant 가 있다. 채워진 것만 넣는다 — 지금 파일은 전부
-- 비어 있지만 다음 수집분이 채우면 영어 사용자가 장소명을 영어로 본다.
--
-- 주소는 그 언어의 것(place_address_en·ja·zh_hant)이 있으면 그것을, 없으면 한국어
-- 주소를 준다 — 지도에 넣을 수는 있으니 없는 것보다 낫다. 소개는 그 언어의 것만 넣는다.
INSERT INTO place_i18n (place_id, lang, name, address, description)
SELECT place_id, 'en', btrim(place_name_en),
       COALESCE(NULLIF(btrim(place_address_en), ''), NULLIF(place_address, '')),
       NULLIF(btrim(place_description_en), '')
FROM t_place WHERE NULLIF(btrim(place_name_en), '') IS NOT NULL
UNION ALL
SELECT place_id, 'ja', btrim(place_name_ja),
       COALESCE(NULLIF(btrim(place_address_ja), ''), NULLIF(place_address, '')),
       NULLIF(btrim(place_description_ja), '')
FROM t_place WHERE NULLIF(btrim(place_name_ja), '') IS NOT NULL
UNION ALL
SELECT place_id, 'zh-Hant', btrim(place_name_zh_hant),
       COALESCE(NULLIF(btrim(place_address_zh_hant), ''), NULLIF(place_address, '')),
       NULLIF(btrim(place_description_zh_hant), '')
FROM t_place WHERE NULLIF(btrim(place_name_zh_hant), '') IS NOT NULL;

-- place_image_url 은 ';' 로 나뉜 URL 목록이다(2026-09-12, 네이버 플레이스 상위 사진).
-- 목록 순서대로 sort_order 를 10, 20, 30… 으로 매긴다. 대표 이미지는 첫 번째이고,
-- 10 단위로 띄워 나중에 사이에 끼워 넣을 여지를 둔다. 네이버 URL 이 없거나 네이버에
-- 사진이 없는 22 행은 비어 있어 place_image 가 생기지 않고, 그 장소의 썸네일은 NULL 이다.
INSERT INTO place_image (place_id, url, sort_order)
SELECT p.place_id, btrim(u.url), u.ord * 10
FROM t_place p
CROSS JOIN unnest(string_to_array(p.place_image_url, ';')) WITH ORDINALITY AS u(url, ord)
WHERE NULLIF(btrim(u.url), '') IS NOT NULL;

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
-- scene_image_url 은 (장소, 작품) 한 쌍에 한 장이라 여기 붙는다(V7).
CREATE TEMP TABLE t_place_content ON COMMIT DROP AS
SELECT
    nextval('place_content_id_seq') AS place_content_id,
    p.place_id,
    c.content_id,
    s.scene_description,
    s.scene_description_en,
    s.scene_description_ja,
    s.scene_description_zh_hant,
    s.scene_image_url,
    s.last_updated
FROM (
    SELECT DISTINCT ON (place_key, content_key) * FROM seed_rows
    ORDER BY place_key, content_key, id
) s
JOIN t_place   p ON p.place_key   = s.place_key
JOIN t_content c ON c.content_key = s.content_key;

INSERT INTO place_content (id, place_id, content_id, scene_image_url, updated_at)
SELECT
    place_content_id, place_id, content_id,
    NULLIF(btrim(scene_image_url), ''),
    COALESCE(NULLIF(last_updated, '')::TIMESTAMPTZ, now())
FROM t_place_content;

INSERT INTO place_content_i18n (place_content_id, lang, relation_description)
SELECT place_content_id, 'ko', scene_description
FROM t_place_content
WHERE NULLIF(scene_description, '') IS NOT NULL;

-- 장면 설명의 다국어. 채워진 언어만 넣는다 — 지금 파일은 전부 비어 있다. 없는 언어는
-- API 가 ko 로 폴백한다.
INSERT INTO place_content_i18n (place_content_id, lang, relation_description)
SELECT place_content_id, 'en', btrim(scene_description_en)
FROM t_place_content WHERE NULLIF(btrim(scene_description_en), '') IS NOT NULL
UNION ALL
SELECT place_content_id, 'ja', btrim(scene_description_ja)
FROM t_place_content WHERE NULLIF(btrim(scene_description_ja), '') IS NOT NULL
UNION ALL
SELECT place_content_id, 'zh-Hant', btrim(scene_description_zh_hant)
FROM t_place_content WHERE NULLIF(btrim(scene_description_zh_hant), '') IS NOT NULL;

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

\echo ''
\echo '숨긴 것 (CSV 에서 빠져 hidden_at 이 찬 것 — 지우지 않았다):'
SELECT 'content' AS 테이블, count(*) FILTER (WHERE hidden_at IS NOT NULL) AS 숨김, count(*) AS 전체 FROM content
UNION ALL SELECT 'place', count(*) FILTER (WHERE hidden_at IS NOT NULL), count(*) FROM place;
