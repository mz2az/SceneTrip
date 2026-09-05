-- poi 지도 뷰포트 조회용 인덱스. place 의 V6 와 같은 이유, 같은 모양이다.
--
-- V12 의 poi_geom_idx 는 geography 위의 GiST 라 geography 연산만 받는다. 뷰포트 조회는
-- V6 에서 정한 대로 geometry 로 한다(경도 180 도 간선 문제 — V6 주석). 그래서
-- `geom::geometry && ST_MakeEnvelope(...)` 는 인덱스가 있는데도 전수 스캔이 됐다.
--
-- 2026-09-03 실측 — 음식점 404,830 행, 강남역 2 km 뷰포트에서 중심 거리순 30개:
--
--   이 인덱스 없이            Parallel Seq Scan   36 ms   (40만 행을 다 훑는다)
--   이 인덱스 + geometry 정렬  Index Scan (KNN)    0.5 ms
--
-- 덤으로 통계가 생긴다. 표현식 인덱스가 있어야 플래너가 `geom::geometry` 의 선택도를
-- 알아서(없으면 4,027 행을 17 행으로 어림했다) 다른 계획도 제대로 고른다.
--
-- 반경 검색(ST_DWithin)은 그대로 geography 인 poi_geom_idx 를 탄다.

CREATE INDEX poi_geom_geometry_idx ON poi USING gist ((geom::geometry));

COMMENT ON INDEX poi_geom_geometry_idx IS
    '지도 뷰포트(bbox) 조회용. 반경 검색은 geography 쪽 poi_geom_idx 를 탄다';
