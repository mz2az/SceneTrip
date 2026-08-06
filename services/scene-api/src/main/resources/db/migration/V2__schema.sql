-- SceneTrip 스키마 v1 — 테이블 14 개.
--
-- 출처: MZ2AZ-111 "SceneTrip DB 스키마" 와 그 DBML 표현.
-- 옮기면서 손본 곳은 docs/project/plans/scene-api-database.md §4 에 표로 있다.
-- 여기 주석은 "왜 이 SQL 이 DBML 과 다른가" 만 적는다. 컬럼의 의미와 설계 근거는
-- MZ2AZ-111 을 볼 것.
--
-- ── DBML 의 Enum 을 TEXT + 제약으로 바꾼 이유 ────────────────────────────────
--
-- 원본 문서(MZ2AZ-111)는 전부 TEXT 로 정의한다. Enum 은 DBML 로 옮기면서 생긴
-- 표현이다 — DBML 에는 CHECK 제약 문법이 없어 "값이 이 넷 중 하나" 를 적을 방법이
-- Enum 밖에 없었다. geography 나 search_term 이 Table 로 표현된 것과 같은 종류의
-- 변환이고, DBML 문서의 "DBML로 옮기면서 생긴 차이" 표에 그렇게 적혀 있다.
--
-- 실무적으로도 TEXT 쪽이 맞다. PostgreSQL 의 Enum 은 값을 뺄 수 없고, 추가한 값은
-- 같은 트랜잭션 안에서 쓸 수 없다 — Flyway 는 마이그레이션을 트랜잭션으로 감싸므로
-- "값 추가 + 그 값으로 데이터 이관" 을 한 마이그레이션에 담을 수 없게 된다.
-- CHECK 제약은 DROP/ADD 로 갈아 끼우면 끝난다.
--
-- 여러 테이블이 공유하는 두 값(lang, trans_status)만 DOMAIN 으로 묶었다. DOMAIN 은
-- "이름 붙은 TEXT + CHECK" 라서 제약을 한 곳에서 고칠 수 있으면서 타입은 TEXT 다.
-- 나머지는 쓰는 자리가 한둘뿐이라 컬럼에 CHECK 를 직접 건다.
--
-- search_term.entity_type 과 user_event.entity_type 은 값이 같아도 각자 CHECK 를
-- 가진다. 일부러 분리한 것이다 — 한쪽에 값이 늘어나도(검색 쪽 region·role,
-- 로그 쪽 route·review) 다른 쪽은 그대로여야 한다.

CREATE DOMAIN lang_code AS TEXT
    CONSTRAINT lang_code_check CHECK (VALUE IN ('ko', 'en', 'ja', 'zh-Hant'));

COMMENT ON DOMAIN lang_code IS 'zh-Hant 는 번체(대만·홍콩). 간체(zh-Hans)와 구분한다';

CREATE DOMAIN trans_status AS TEXT
    CONSTRAINT trans_status_check CHECK (VALUE IN ('machine', 'reviewed', 'human'));

-- ───────────── 장소 ─────────────

CREATE TABLE place (
    id               BIGSERIAL PRIMARY KEY,
    type             TEXT,
    -- DBML 은 geography 로만 적혀 있다. 괄호·쉼표가 든 타입을 파싱하지 못해 note 로
    -- 뺀 것이고, 실제 타입은 이것이다. Point 로 못 박지 않으면 같은 컬럼에 선·면이
    -- 섞여 들어갈 수 있고, 4326(WGS84)이 아니면 위경도로 해석되지 않는다.
    geom             GEOGRAPHY(Point, 4326) NOT NULL,
    naver_place_url  TEXT UNIQUE,
    popularity_score NUMERIC NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE place IS '언어중립 값만 보관. 이름·주소는 place_i18n';
COMMENT ON COLUMN place.type IS 'place_type 코드값';
COMMENT ON COLUMN place.naver_place_url IS 'map.naver.com/p/entry/place/{id} · dedupe 1차 키';

-- 반경 검색이 타는 인덱스. GEOGRAPHY 에는 GiST 를 쓴다 — B-tree 는 2차원 근접을
-- 표현하지 못한다.
CREATE INDEX place_geom_idx ON place USING gist (geom);

-- 인기도 정렬. DBML 에는 DESC 문법이 없어 note 로만 적혀 있었다.
-- 인덱스의 정렬 방향이 ORDER BY 와 어긋나면 인덱스를 타고도 정렬을 다시 한다.
-- id 를 뒤에 붙이는 것은 동점 순서를 고정해 페이지네이션이 흔들리지 않게 하려는 것이다.
CREATE INDEX place_popularity_idx ON place (popularity_score DESC, id DESC);

CREATE TABLE place_i18n (
    place_id     BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    lang         lang_code NOT NULL,
    name         TEXT NOT NULL,
    address      TEXT,
    description  TEXT,
    trans_status trans_status,
    PRIMARY KEY (place_id, lang)
);

COMMENT ON COLUMN place_i18n.description IS '장소 자체의 설명. 작품별 장면 설명은 place_content_i18n';

CREATE TABLE place_image (
    id         BIGSERIAL PRIMARY KEY,
    place_id   BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    url        TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE place_image IS
    '장소 사진만. 대표 이미지는 sort_order 첫 번째 — 별도 컬럼을 두지 않는다. 리뷰 사진은 review_image 로 분리';
COMMENT ON COLUMN place_image.sort_order IS '10,20,30 처럼 띄워 넣어 중간 삽입에 대비한다';

CREATE INDEX place_image_place_idx ON place_image (place_id, sort_order);

CREATE TABLE place_alias (
    id       BIGSERIAL PRIMARY KEY,
    place_id BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    alias    TEXT NOT NULL,
    lang     lang_code
);

COMMENT ON TABLE place_alias IS 'dedupe 로 병합된 표기들을 보존. alias_type 은 두지 않는다 — 실데이터가 분류에 담기지 않는다';
COMMENT ON COLUMN place_alias.lang IS '한글 ko / 가나 ja / 한자 zh-Hant / 라틴 NULL — 문자 범위로 자동 판별';

-- NULLS NOT DISTINCT 가 핵심이다. 기본값(NULLS DISTINCT)이면 lang 이 NULL 인 라틴
-- 표기는 몇 번을 넣어도 매번 새 행이 되어, just seed 를 두 번 돌리면 별칭이 두 배가
-- 된다. 적재를 다시 돌릴 수 있게 만드는 것이 이 티켓의 전제다.
CREATE UNIQUE INDEX place_alias_uk ON place_alias (place_id, alias, lang) NULLS NOT DISTINCT;

-- ───────────── 작품 ─────────────

CREATE TABLE content (
    id               BIGSERIAL PRIMARY KEY,
    category         TEXT NOT NULL
        CONSTRAINT content_category_check CHECK (category IN ('drama', 'movie', 'variety', 'kpop')),
    broadcaster      TEXT,
    poster_url       TEXT,
    -- release_year 와 genres 는 스키마 v1 에 없던 컬럼이다. 이미 머지된 API 명세
    -- (contracts/openapi/scene-api-v1.yaml 의 ContentSummary)가 releaseYear·genres 를
    -- 약속했는데 담을 자리가 없으면 명세가 거짓이 된다. 근거와 결정은
    -- docs/project/plans/scene-api-database.md §5.
    --
    -- 문서가 air_period·air_status 를 뺀 이유(TEXT 범위라 정렬 불가 + 파생값이라
    -- 낡음)는 연도 단일 INT 에는 해당하지 않는다. 정렬 가능하고 낡지 않는다.
    release_year     INT,
    -- 수집 원본에서 ';' 로 구분된 값을 분해해 담는다. 별도 테이블로 빼지 않은 이유는
    -- 장르로 조인·집계할 요구가 아직 없고, 명세도 문자열 배열로 내보내기 때문이다.
    -- 장르별 필터가 생기면 그때 코드 테이블로 승격한다.
    genres           TEXT[] NOT NULL DEFAULT '{}',
    popularity_score NUMERIC NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE content IS
    '수집 원본 지표(score_total·en_views_12m·audience_acc·rank·wikidata_qid 등)는 컬럼으로 두지 않는다 — popularity_score 계산에만 쓰고 원본은 01_Raw CSV 에 보존된다';
COMMENT ON COLUMN content.popularity_score IS '정렬값. 적재 시 직접 계산해 입력한다';
COMMENT ON COLUMN content.genres IS '현재 값은 수집 언어(한국어) 문자열이다. 장르 자체의 다국어화는 후속';

CREATE INDEX content_popularity_idx ON content (popularity_score DESC, id DESC);

CREATE TABLE content_i18n (
    content_id   BIGINT NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    lang         lang_code NOT NULL,
    title        TEXT NOT NULL,
    description  TEXT,
    trans_status trans_status,
    PRIMARY KEY (content_id, lang)
);

CREATE TABLE content_alias (
    id         BIGSERIAL PRIMARY KEY,
    content_id BIGINT NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    alias      TEXT NOT NULL,
    lang       lang_code
);

COMMENT ON TABLE content_alias IS 'title_aliases 를 ; 로 분리. alias_type 은 두지 않는다';

CREATE UNIQUE INDEX content_alias_uk ON content_alias (content_id, alias, lang) NULLS NOT DISTINCT;

-- ───────────── 인물 ─────────────

CREATE TABLE person (
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE person IS '사람 자체에는 직군을 붙이지 않는다. 배우/감독 구분은 content_cast.role_type';

CREATE TABLE person_i18n (
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    lang      lang_code NOT NULL,
    name      TEXT NOT NULL,
    PRIMARY KEY (person_id, lang)
);

CREATE TABLE content_cast (
    content_id BIGINT NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    person_id  BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    role_type  TEXT NOT NULL
        CONSTRAINT content_cast_role_type_check CHECK (role_type IN ('actor', 'director')),
    sort_order INT,
    PRIMARY KEY (content_id, person_id, role_type)
);

COMMENT ON TABLE content_cast IS
    '작품 단위 출연진만. role_type 이 person 이 아니라 여기 있는 이유는 겸업 때문이다 — 역할은 사람의 속성이 아니라 사람과 작품 사이의 속성이고, 한 작품에서 연출·주연을 겸할 수 있어 PK 에도 들어간다';
COMMENT ON COLUMN content_cast.sort_order IS 'title_cast 나열 순서 = 비중. is_main 은 sort_order <= 2 로 계산하므로 컬럼을 두지 않는다';

-- 배우 → 작품 역방향 조회.
CREATE INDEX content_cast_person_idx ON content_cast (person_id);

-- ───────────── 장소 × 작품 (핵심 연결) ─────────────

CREATE TABLE place_content (
    id         BIGSERIAL PRIMARY KEY,
    place_id   BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    content_id BIGINT NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE place_content IS
    '수집 CSV 한 행이 여기에 해당한다. N:M 을 흡수. 출처·수집 메타는 두지 않는다 — 앱이 읽지 않고 값은 01_Raw 수집 CSV 에 보존된다';

CREATE UNIQUE INDEX place_content_uk ON place_content (place_id, content_id);
CREATE INDEX place_content_place_idx ON place_content (place_id);
CREATE INDEX place_content_content_idx ON place_content (content_id);

CREATE TABLE place_content_i18n (
    place_content_id     BIGINT NOT NULL REFERENCES place_content (id) ON DELETE CASCADE,
    lang                 lang_code NOT NULL,
    relation_description TEXT,
    trans_status         trans_status,
    PRIMARY KEY (place_content_id, lang)
);

COMMENT ON TABLE place_content_i18n IS
    '장면 설명. 현재 수집분은 실질적으로 비어 있다(실제 설명 60건) — 전량 채우는 것을 전제한 설계';

-- ───────────── 검색 · 로그 ─────────────
--
-- search_term 은 테이블이 아니라 MATERIALIZED VIEW 다. V3 에서 만든다.

-- user_event 와 saved_place 는 MVP2 용이다. 지금 만드는 이유는 impression·position 이
-- 소급 수집이 불가능하기 때문이다 — 나중에 붙이면 그 전 기간의 데이터는 영영 없다.
--
-- user_id 에 FK 를 걸지 않는다. 사용자 테이블이 아직 스키마에 없어 참조할 대상이
-- 없다. 사용자 테이블이 생기면 그때 ALTER 로 건다.
CREATE TABLE user_event (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    session_id  TEXT,
    event_type  TEXT NOT NULL
        CONSTRAINT user_event_event_type_check
        CHECK (event_type IN ('impression', 'search', 'click', 'save', 'route_add', 'review')),
    entity_type TEXT
        CONSTRAINT user_event_entity_type_check
        CHECK (entity_type IN ('place', 'content', 'person')),
    entity_id   BIGINT,
    query       TEXT,
    position    INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN user_event.user_id IS '비로그인 허용';
COMMENT ON COLUMN user_event.entity_type IS '대상 없는 이벤트는 NULL (search)';
COMMENT ON COLUMN user_event.query IS 'search 전용';
COMMENT ON COLUMN user_event.position IS '목록 순번 · CTR 보정';

-- 집계 배치가 타는 인덱스.
CREATE INDEX user_event_entity_idx ON user_event (entity_type, entity_id, created_at);

CREATE TABLE saved_place (
    user_id           BIGINT NOT NULL,
    place_id          BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    source_content_id BIGINT REFERENCES content (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, place_id)
);

COMMENT ON TABLE saved_place IS
    '찜(MVP2). place_content(성지)가 아니라 place 기준이다 — route_add 가 물리적 장소 단위라 맞춰야 같은 장소 중복 저장을 피한다. user_event 와 별도인 이유는 이쪽이 토글 가능한 현재 상태이고 user_event 는 append-only 로그이기 때문이다';
COMMENT ON COLUMN saved_place.source_content_id IS '어떤 작품 보다가 찜했는지 (선택)';
