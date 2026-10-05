-- 편의시설 이름의 로마자 읽기 — 영어 화면에서 한국어 이름 아래 작게 보인다.
--
-- 계획: docs/project/plans/poi-i18n-image.md §12.
--
-- ── 왜 poi_i18n.name 이 아니라 여기인가 ─────────────────────────────────────
--
-- 로마자는 번역이 아니라 읽는 법이다. 언어마다 달라지는 값이 아니고 한국어 이름에서 기계적으로 나온다
-- (국어의 로마자 표기법). 영어 이름 칸에 넣으면 공식 영어처럼 보이는데, 외래어 이름은 로마자로 원래 영어가
-- 되돌아오지 않는다(스타벅스 → Seutabeokseu). 그래서 진짜 영어 이름(poi_i18n.name, 확실할 때만)과 나눈다.
--
-- 값은 적재(`just poi-en` → `just seed-poi`)가 채운다. 비어 있으면 아직 안 만든 것이다.
ALTER TABLE poi ADD COLUMN name_roman TEXT;

COMMENT ON COLUMN poi.name_roman IS
    '이름의 로마자 읽기(국어의 로마자 표기법). 번역이 아니다 — 진짜 영어 이름은 poi_i18n(en).name';
