-- 편의시설 번역·분류 사전·사진 — 우리 앱 자체 POI 로.
--
-- 계획: docs/project/plans/poi-i18n-image.md §3·§4. 네이버 카드(V14)는 더 쓰지 않는다 —
-- 표는 두고 모은 것은 `just poi-naver-backup` 으로 떠 두었다.
--
-- ── 한국어 원본은 poi 에 그대로 ────────────────────────────────────────────
--
-- V12 는 「다국어가 생기면 name 을 place 처럼 i18n 으로 옮긴다」 고 적었다. 옮기지 않는다.
-- 95 만 POI 의 대부분은 오래 한국어뿐이다. 번역만 옆 표에 두면 폴백이
-- COALESCE(번역, poi.name) 한 번의 LEFT JOIN 으로 끝나고, 한국어 화면의 지도 조회와
-- poi_name_trgm_idx 는 그대로다. place 는 처음부터 다국어라 ko 도 i18n 에 두었다 — 사정이
-- 다르다.
--
-- ── 분류는 사전 하나로 ─────────────────────────────────────────────────────
--
-- 95 만 가게가 분류 42 개를 나눠 쓴다. 가게마다 "Cafe" 를 적으면 같은 값이 12 만 번 반복되고
-- 표기를 고칠 때 12 만 줄을 고친다. 사전이면 한 줄이다.
--
-- 시도·시군구는 따로 번역하지 않는다. 사람이 보는 것은 주소 한 줄이고, 그 영문은 행정안전부
-- 영문도로명주소DB 에서 통째로 가져와 poi_i18n.address 에 넣는다(계획 문서 §6-2·§6-3).
--
-- **사전에 FK 를 걸지 않는다.** poi.category 는 출처가 정하는 값이라, 걸면 출처가 새 분류를
-- 들고 오는 순간 적재가 실패한다. 안 걸면 새 분류는 일단 한국어로 보이고 사전에 한 줄 넣는
-- 순간 번역된다. 빠진 분류를 찾는 질의는 계획 문서 §4-2 에 있다(표 이름만 poi_category_i18n).

-- ───────────── 가게마다 다른 글 ─────────────

CREATE TABLE poi_i18n (
    poi_id       BIGINT NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    lang         lang_code NOT NULL,
    -- 비워 둘 수 있다. 가게 이름의 공식 영어는 없어서 당분간 영어 행은 주소만 있다(계획 §10·§11).
    -- 칸마다 따로 폴백한다 — COALESCE(t.name, p.name), COALESCE(t.address, p.address).
    name         TEXT,
    address      TEXT,
    road         TEXT,
    trans_status trans_status,
    PRIMARY KEY (poi_id, lang),
    -- ko 행을 허용하면 poi.name 과 두 군데가 되고, 어긋났을 때 어느 쪽이 맞는지 알 수 없다.
    CONSTRAINT poi_i18n_not_ko_check CHECK (lang <> 'ko')
);

COMMENT ON TABLE poi_i18n IS '편의시설 번역. 한국어 원본은 poi 에만 있다 — 칸마다 비어 있으면 poi 의 같은 칸으로 폴백';
COMMENT ON COLUMN poi_i18n.address IS 'poi.address(시/군/구 + 동)의 번역';
COMMENT ON COLUMN poi_i18n.road IS 'poi.road(도로명)의 번역';

-- 번역된 이름으로 검색할 때 탄다. poi_name_trgm_idx 와 같은 꼴이다 — 색인과 조회가 같은
-- search_normalize() 를 써야 한다(V12 의 같은 주석 참조).
CREATE INDEX poi_i18n_name_trgm_idx
    ON poi_i18n USING gin (search_normalize(name) gin_trgm_ops);

-- ───────────── 분류 사전 ─────────────

CREATE TABLE poi_category_i18n (
    -- poi.category 값 그대로. 예 '카페'
    ko   TEXT NOT NULL,
    lang lang_code NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (ko, lang),
    CONSTRAINT poi_category_i18n_not_ko_check CHECK (lang <> 'ko')
);

COMMENT ON TABLE poi_category_i18n IS
    '편의시설 분류 사전 — poi.category 의 번역. poi 와 FK 로 잇지 않는다: 새 분류가 와도 적재가 멈추지 않고 한국어로 보인다';

-- ───────────── 사진 ─────────────

CREATE TABLE poi_image (
    id         BIGSERIAL PRIMARY KEY,
    poi_id     BIGINT NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    url        TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    source     TEXT,
    credit     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 수집을 다시 돌려도 같은 사진이 늘지 않게 한다. place_image 에는 없는 제약이다 — 그쪽은
    -- 적재가 TRUNCATE 뒤 다시 넣지만, 이쪽은 계속 모아 더하는 표다.
    CONSTRAINT poi_image_poi_url_uk UNIQUE (poi_id, url)
);

COMMENT ON TABLE poi_image IS
    '우리가 모은 편의시설 사진. 대표 이미지는 sort_order 첫 번째 — place_image 와 같은 규칙. 파일은 바깥 저장소에 있고 여기는 주소만';
COMMENT ON COLUMN poi_image.sort_order IS '10,20,30 처럼 띄워 넣어 중간 삽입에 대비한다';
COMMENT ON COLUMN poi_image.source IS '어디서 왔나. 예 tour_api · own';
COMMENT ON COLUMN poi_image.credit IS '화면에 붙일 저작자·라이선스 표기. 출처가 표기를 요구하면 비우지 않는다';

-- UNIQUE (poi_id, url) 인덱스는 정렬 순서를 못 주므로 카드가 사진을 순서대로 읽는 인덱스를 따로 둔다.
CREATE INDEX poi_image_poi_idx ON poi_image (poi_id, sort_order);
