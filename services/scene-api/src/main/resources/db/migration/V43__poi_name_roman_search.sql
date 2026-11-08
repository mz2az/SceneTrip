-- 편의시설 이름 검색(GET /pois?q=, 1.23.0, MZ2AZ-283) — 읽는 법(name_roman)으로도 찾는다.
--
-- 한국어 이름(poi_name_trgm_idx, V12)·번역 이름(poi_i18n_name_trgm_idx, V17)과 같은 꼴이다 — 색인과 조회가 같은
-- search_normalize() 를 써야 LIKE '%…%' 가 47만 행을 훑지 않는다(PoiStore.MATCHED_SQL).
CREATE INDEX poi_name_roman_trgm_idx
    ON poi USING gin (search_normalize(name_roman) gin_trgm_ops);
