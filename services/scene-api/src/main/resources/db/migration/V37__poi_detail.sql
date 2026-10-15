-- 편의시설 상세 — 관광공사 TourAPI 상세(개요·이용시간·쉬는 날·주차·대표 메뉴·입장료·홈페이지)와 사진 출처(MZ2AZ-376).
--
-- 계획: docs/project/plans/poi-source.md §6. 계약: scene-api 1.21.0 PoiDetail.info · Photo.thumbnailUrl·license.
--
-- ── 왜 poi 에 칸을 늘리지 않나 ─────────────────────────────────────────────
--
-- 상세는 관광공사에서 온 약 5 만 곳에만 있다. 91 만 POI 에 빈 칸 일곱 개를 두지 않고, 지도 조회가 읽는 poi 를 가볍게 둔다.
--
-- ── 한국어도 이 표에 ───────────────────────────────────────────────────────
--
-- poi_i18n 은 한국어 원본이 poi 에 있어 번역만 든다(ko 행 금지). 상세는 원본 자체가 관광공사 자료라 한국어·영어를 같은
-- 표에 나란히 둔다. 조회는 요청 언어 줄과 ko 줄을 함께 읽어 **칸마다 따로** 폴백한다 — 개요는 영어, 주차는 한국어일 수 있다.
--
-- 적재는 다시 돌려도 된다 — (poi_id, lang) 로 덮어쓴다. 수집이 이어지는 동안 몇 번이고 다시 넣는다.

CREATE TABLE poi_detail (
    poi_id          BIGINT NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    lang            lang_code NOT NULL,
    -- 관광공사가 사람이 읽으라고 쓴 자유 문장. HTML 을 지우고 줄바꿈은 \n 으로 넣는다. 시각으로 계산하지 않는다.
    overview        TEXT,
    use_time        TEXT,
    rest_date       TEXT,
    parking         TEXT,
    first_menu      TEXT,
    use_fee         TEXT,
    homepage        TEXT,
    -- 출처 — 관광공사 콘텐츠 번호(언어마다 다르다)와 관광공사 쪽 수정 시각. 다시 받았을 때 바뀐 것만 고치는 데 쓴다.
    tour_content_id TEXT NOT NULL,
    tour_modified   TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (poi_id, lang)
);

COMMENT ON TABLE poi_detail IS
    '관광공사 상세. POI·언어마다 한 줄, ko 도 여기. 조회는 요청 언어 → ko 로 칸마다 폴백한다';

-- 다시 적재할 때 관광공사 번호로 찾는다.
CREATE INDEX poi_detail_tour_content_idx ON poi_detail (tour_content_id);

-- ── 사진 — 모든 언어가 같이 쓴다 ───────────────────────────────────────────
--
-- 사진에는 언어가 없다. 국문·영문 상세의 사진은 짝지은 POI 에 한 목록으로 합쳐진다(같은 주소는 poi_image_poi_url_uk 로 한 번).
-- 출처 문구는 저장하지 않는다 — 공공누리 유형과 출처 이름(credit)만 두고 앱이 언어에 맞춰 문구를 만든다.

ALTER TABLE poi_image
    -- 관광공사가 만든 작은 사진(약 300 px). 목록·지도 카드용
    ADD COLUMN thumbnail_url   TEXT,
    -- 공공누리 유형. 관광공사 cpyrhtDivCd Type1 → kogl_type1, Type3 → kogl_type3
    ADD COLUMN license         TEXT,
    -- 이 사진을 준 관광공사 콘텐츠 번호
    ADD COLUMN tour_content_id TEXT,
    ADD CONSTRAINT poi_image_license_check CHECK (license IN ('kogl_type1', 'kogl_type3'));
