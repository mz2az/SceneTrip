-- POI(편의시설) — 표 하나.
--
-- 출처: MZ2AZ-275 "POI 도메인 스키마 (DBML)" §3. 컬럼의 뜻과 근거는 거기 있다.
-- 여기 주석은 DBML 이 표현하지 못해 손으로 붙인 것과, SQL 로 옮기며 갈린 곳만 적는다.
--
-- ── place 와 왜 나누나 ──────────────────────────────────────────────────────
--
-- 성지(place)는 155개이고 POI 는 473,203개다. 성격도 다르다 — 성지는
-- place_content 로 작품과 이어져 장면 설명을 가지지만 POI 는 그런 연결이 없다.
-- 한 표에 담으면 place 를 읽는 **모든** 질의가 3,000배 커진 표를 훑는다.
--
-- ── poi_i18n 이 없다 ───────────────────────────────────────────────────────
--
-- 수집 자료가 한국어뿐이라 표를 나눌 근거가 없다. 미리 만들면 언제나 ko 한 줄뿐인
-- 표가 되고, 조인만 하나 늘어난다. 다국어 표기가 생기면 그때 place 와 같은 모양으로
-- 뗀다 — 그 시점에 name 을 옮기는 마이그레이션 하나면 된다.
--
-- ── popularity_score 가 없다 ───────────────────────────────────────────────
--
-- place 에는 있지만 그 값은 지금 임시 고정값이고, POI 에는 순위를 매길 근거가
-- 아무것도 없다. 없는 값으로 정렬하는 척하지 않는다 — 정렬은 거리순과 이름순뿐이다.

CREATE TABLE poi (
    id             BIGSERIAL PRIMARY KEY,

    -- 출처가 부여한 id. 전역 고유이고 **재적재의 열쇠**다.
    --
    -- TEXT 인 이유: 지금 값은 전부 숫자지만 우리 숫자가 아니라 출처의 식별자다.
    -- 다음 자료가 영숫자를 섞어 오면 타입을 바꿔야 하는데, 그때는 이미 47만 행이
    -- 들어 있다. 산술을 할 일이 없으므로 잃는 것도 없다.
    source_id      TEXT NOT NULL UNIQUE,

    name           TEXT NOT NULL,
    geom           GEOGRAPHY(Point, 4326) NOT NULL,

    -- 원본 biz_lower. 적재할 때 '\/' -> '/' 로 정규화한 값이 들어온다. 115종이다.
    category       TEXT NOT NULL,

    -- 네 갈래. 원본 biz_middle 아홉 종을 접은 것이고, 접는 표는 적재 SQL 이 든다.
    --
    -- 갈래 이름이 출처 파일(poi_food·poi_sight·poi_stay·poi_transit)과 같지만
    -- **파일로 정하지 않는다.** poi_food 안에 펜션·박물관·미술관이 40건쯤 섞여 있어
    -- 파일을 믿으면 그것들이 food 가 된다.
    category_group TEXT NOT NULL,

    address        TEXT,  -- 원본 addr — 시/군/구 + 동까지. 건물번호는 없다
    road           TEXT,  -- 원본 road — 도로명
    tel            TEXT,  -- 28~60% 가 비어 있다
    region         TEXT,
    city           TEXT,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Enum 대신 CHECK 인 것은 V2·V9 와 같은 이유다 — PostgreSQL Enum 은 값을 뺄 수
    -- 없고 추가한 값을 같은 트랜잭션에서 쓸 수 없다. Flyway 가 마이그레이션을
    -- 트랜잭션으로 감싸므로 "값 추가 + 그 값으로 데이터 이관" 을 한 파일에 못 담는다.
    CONSTRAINT poi_category_group_check
        CHECK (category_group IN ('food', 'sight', 'stay', 'transit'))
);

COMMENT ON TABLE poi IS '편의시설. 촬영지(place)와 분리 — 47만 대 155';
COMMENT ON COLUMN poi.source_id IS '출처가 부여한 id. 재적재의 열쇠라 UNIQUE';
COMMENT ON COLUMN poi.category IS '원본 biz_lower (115종)';
COMMENT ON COLUMN poi.category_group IS 'food · sight · stay · transit';

-- 반경·뷰포트 조회. GEOGRAPHY 에는 GiST 를 쓴다 — B-tree 는 2차원 근접을 표현하지
-- 못한다. place_geom_idx 와 같은 형태다.
CREATE INDEX poi_geom_idx ON poi USING gist (geom);

-- 갈래로 거르는 질의(/pois?categoryGroup=food)가 탄다. 네 값뿐이라 선택도가 낮지만,
-- 지도 뷰포트 조건과 함께 걸릴 때 BitmapAnd 로 묶여 값을 한다.
CREATE INDEX poi_category_group_idx ON poi (category_group);

-- ── 검색이 타는 인덱스 둘 ──────────────────────────────────────────────────
--
-- search_normalize() 는 V3 이 만든 함수다. **색인과 조회가 같은 정규화를 써야 한다** —
-- 다르면 오류가 나지 않고 결과가 0건이 된다. 그 함수가 IMMUTABLE 로 선언돼 있어
-- 표현식 인덱스에 쓸 수 있다.
--
-- 47만 행에서 ILIKE '%...%' 는 순차 훑기가 된다. pg_trgm 의 GIN 이 그것을 받는다(V1).
--
-- **이름과 종류를 한 인덱스로 합치지 않는다.** 합치면 '카페' 로 검색할 때 이름에
-- 카페가 든 가게와 종류가 카페인 가게를 구분할 수 없어, 나중에 "종류로만 거르기" 를
-- 붙일 수 없다. 둘로 두면 PostgreSQL 이 BitmapOr 로 묶어 준다.
CREATE INDEX poi_name_trgm_idx
    ON poi USING gin (search_normalize(name) gin_trgm_ops);

CREATE INDEX poi_category_trgm_idx
    ON poi USING gin (search_normalize(category) gin_trgm_ops);
