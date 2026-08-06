-- 검색어 색인.
--
-- **테이블이 아니라 MATERIALIZED VIEW 다.** DBML 에는 뷰 개념이 없어 Table 로
-- 표현돼 있을 뿐이고, 문서가 "DDL 그대로 쓰면 안 됨" 이라고 못 박아 뒀다.
--
-- 테이블로 만들면 안 되는 이유는 단순하다. 여기 있는 값은 전부 다섯 원본 테이블에서
-- 나온 파생값이라, 테이블로 두면 원본이 바뀔 때마다 트리거나 배치로 동기화해야 하고
-- 그 동기화가 어긋나면 "DB 에는 있는데 검색은 안 되는" 상태가 조용히 생긴다.
-- MV 는 정의가 곧 동기화 규칙이고, 갱신은 REFRESH 한 줄이다.

-- ── 정규화 ────────────────────────────────────────────────────────────────────
--
-- 함수로 빼는 이유: 색인을 만들 때와 검색할 때 정규화가 **같아야** 한다. 서로 다르면
-- 실패가 조용하다 — 오류 없이 결과가 0 건이 된다. 앱이 Java 로 따로 구현하면 언젠가
-- 반드시 갈라지므로, 한 곳(DB)에 두고 양쪽이 이것을 부른다.
--
-- 남길 문자를 고르는 방식(allowlist)이 아니라 지울 문자를 고르는 방식(blocklist)인
-- 이유가 중요하다. DB 로케일이 C 라서(platform/kubernetes/postgres/configmap.yaml)
-- [[:alnum:]] 은 ASCII 만 문자로 친다. allowlist 로 쓰면 '도깨비' 가 통째로 지워져
-- 빈 문자열이 된다. 지울 것만 지우면 한글·가나·한자는 그대로 남는다.
--
-- IMMUTABLE 로 선언해야 인덱스·MV 정의에서 쓸 수 있다. 실제로 같은 입력에 항상 같은
-- 값을 낸다 — lower() 와 regexp_replace() 둘 다 IMMUTABLE 이다.
CREATE FUNCTION search_normalize(input TEXT) RETURNS TEXT
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT lower(regexp_replace(input, '[[:space:][:punct:]·・、。「」『』〈〉《》…—–~]+', '', 'g'))
$$;

COMMENT ON FUNCTION search_normalize(TEXT) IS
    '검색어 정규화 — 소문자 + 공백·구두점 제거. 색인(search_term)과 조회가 같은 정의를 써야 한다';

-- ── search_term ───────────────────────────────────────────────────────────────
--
-- 다섯 곳을 UNION ALL 한다: 장소명·장소별칭·작품명·작품별칭·인물명.
-- **설명은 들어가지 않는다.** 자동완성에 문장이 뜨면 안 되고, 작품·장소 설명 검색은
-- 검색 API 가 원본 테이블을 직접 조회한다 (contracts/openapi/scene-api-v1.yaml).
--
-- entity_id 는 place·content·person 을 가리키는 다형 참조라 FK 가 없다. MV 라서
-- 애초에 제약을 걸 수 없기도 하다.
--
-- GROUP BY 로 감싼 이유는 둘이다.
--   1. REFRESH ... CONCURRENTLY 에는 유니크 인덱스가 필요한데, 원본에 같은 표기가
--      두 번 나오면(장소 이름과 별칭이 같은 경우가 실제로 있다) 중복 행이 생겨
--      인덱스를 만들 수 없다. 집계해서 애초에 중복이 나오지 않게 한다.
--   2. 같은 뜻의 두 표기 중 가중치가 높은 쪽을 남긴다.
CREATE MATERIALIZED VIEW search_term AS
WITH terms AS (
    -- 장소 이름
    SELECT
        search_normalize(pi.name) AS term_norm,
        pi.name                   AS term_display,
        'place'::TEXT             AS entity_type,
        pi.place_id               AS entity_id,
        pi.lang                   AS lang,
        100 + (p.popularity_score / 10)::INT AS weight
    FROM place_i18n pi
    JOIN place p ON p.id = pi.place_id

    UNION ALL

    -- 장소 별칭. 정식 명칭보다 가중치가 낮다.
    SELECT
        search_normalize(pa.alias),
        pa.alias,
        'place'::TEXT,
        pa.place_id,
        pa.lang,
        80 + (p.popularity_score / 10)::INT
    FROM place_alias pa
    JOIN place p ON p.id = pa.place_id

    UNION ALL

    -- 작품 제목
    SELECT
        search_normalize(ci.title),
        ci.title,
        'content'::TEXT,
        ci.content_id,
        ci.lang,
        100 + (c.popularity_score / 10)::INT
    FROM content_i18n ci
    JOIN content c ON c.id = ci.content_id

    UNION ALL

    -- 작품 별칭
    SELECT
        search_normalize(ca.alias),
        ca.alias,
        'content'::TEXT,
        ca.content_id,
        ca.lang,
        80 + (c.popularity_score / 10)::INT
    FROM content_alias ca
    JOIN content c ON c.id = ca.content_id

    UNION ALL

    -- 인물 이름. person 에는 popularity_score 가 없어 고정값이다.
    SELECT
        search_normalize(pn.name),
        pn.name,
        'person'::TEXT,
        pn.person_id,
        pn.lang,
        60
    FROM person_i18n pn
)
SELECT
    term_norm,
    min(term_display) AS term_display,
    entity_type,
    entity_id,
    lang,
    max(weight) AS weight
FROM terms
-- 구두점만으로 이루어진 표기는 정규화하면 빈 문자열이 된다. 남겨 두면 어떤 검색어의
-- 접두사도 되지 못하면서 인덱스만 차지한다.
WHERE term_norm <> ''
GROUP BY term_norm, entity_type, entity_id, lang;

COMMENT ON MATERIALIZED VIEW search_term IS
    'place_i18n · place_alias · content_i18n · content_alias · person_i18n 5곳의 UNION ALL. 적재 후 REFRESH MATERIALIZED VIEW CONCURRENTLY search_term';

-- REFRESH ... CONCURRENTLY 의 전제 조건. 이것이 없으면 갱신하는 동안 MV 전체가
-- 잠겨 검색이 멈춘다.
--
-- 컬럼 이름만 쓰고 WHERE 절이 없어야 한다는 제약이 있어 표현식 인덱스를 쓸 수 없다.
-- NULLS NOT DISTINCT 가 필요한 이유가 여기 있다 — lang 이 NULL 인 라틴 표기는
-- 기본 규칙(NULL 끼리는 서로 다름)에서는 중복이 걸러지지 않는다.
CREATE UNIQUE INDEX search_term_uk
    ON search_term (entity_type, entity_id, term_norm, lang) NULLS NOT DISTINCT;

-- 앞글자 일치 — 자동완성이 타는 인덱스. text_pattern_ops 는 LIKE 'abc%' 를
-- 인덱스로 처리할 수 있게 한다(기본 opclass 는 로케일에 따라 못 탄다).
CREATE INDEX search_term_prefix_idx ON search_term (term_norm text_pattern_ops);

-- 부분·유사 일치 — 검색어가 가운데 있거나 오타가 있어도 걸리게 하는 인덱스.
-- pg_trgm 확장이 있어야 한다 (V1).
CREATE INDEX search_term_trgm_idx ON search_term USING gin (term_norm gin_trgm_ops);

-- 언어별 후보 축소. 사용자 언어로 먼저 거르고 가중치 순으로 자른다.
CREATE INDEX search_term_lang_weight_idx ON search_term (lang, weight DESC);
