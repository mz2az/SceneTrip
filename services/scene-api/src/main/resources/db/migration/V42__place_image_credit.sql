-- 촬영지 사진에 출처를 담는다(docs/project/plans/place-photo-credit.md, MZ2AZ-514).
--
-- 사진 없던 촬영지에 넣을 관광공사·위키미디어·서울역사박물관 사진은 모두 출처 표시가 이용 조건이다. 편의시설 사진(poi_image, V37)과
-- 같은 세 칸을 둔다. 모두 비워 둘 수 있다 — 기존 네이버 사진과 그 편집본은 비어 있고, 사진첩은 지금처럼 null 을 내준다.
--
-- license 는 관광공사 사진에만 채운다. 세 앱이 license 가 있으면 출처를 「출처: 한국관광공사」 로 고정해 적기 때문이다. 다른 기관의
-- 공공누리 사진과 위키미디어 사진은 license 를 비우고 조건을 credit 문구 하나에 다 쓴다(앱은 credit 을 그대로 보인다).

ALTER TABLE place_image
    -- 사진 곁에 보일 출처 문구. 예: 한국관광공사 · Christophe95 · CC BY-SA 4.0 · 위키미디어 공용
    ADD COLUMN credit        TEXT,
    -- 공공누리 유형 — 계약 PhotoLicense 와 같은 두 값만
    ADD COLUMN license       TEXT,
    -- 관광공사가 만든 작은 사진(약 300 px). 목록·지도 카드용
    ADD COLUMN thumbnail_url TEXT,
    ADD CONSTRAINT place_image_license_check CHECK (license IN ('kogl_type1', 'kogl_type3'));
