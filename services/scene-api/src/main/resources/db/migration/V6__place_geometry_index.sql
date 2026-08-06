-- 지도 뷰포트 조회용 인덱스.
--
-- ── 왜 인덱스가 하나 더 필요한가 ────────────────────────────────────────────
--
-- 뷰포트 조회를 geography 가 아니라 geometry 로 한다. geography 로 하면 PostGIS 가
-- 거부하는 경우가 있다 — 경도 -180 에서 180 까지의 사각형을 만들면
-- "Antipodal (180 degrees long) edge detected!" 로 질의가 실패한다(실측). geography 는
-- 구면 위의 도형이라 180 도짜리 간선이 어느 쪽으로 도는지 정할 수 없기 때문이다.
--
-- 뜻으로 봐도 geometry 가 맞다. 지도의 뷰포트는 위경도 평면의 직사각형이고,
-- 클라이언트가 보내는 네 숫자가 바로 그것이다. 구면 위의 영역이 아니다.
--
-- 반경 검색(ST_DWithin)은 그대로 geography 다. 그쪽은 "몇 미터 안" 이라는 뜻이라
-- 구면 거리가 맞고, 기존 place_geom_idx 를 계속 탄다.
--
-- ── 표현식 인덱스인 이유 ────────────────────────────────────────────────────
--
-- place.geom 은 geography 컬럼이라 그 위의 GiST 인덱스는 geography 연산만 받는다.
-- geometry 로 캐스팅한 값에는 쓰이지 못해, 인덱스가 있는데도 전수 스캔이 된다.
-- 캐스팅한 결과에 인덱스를 걸어야 뷰포트 질의가 그것을 탄다.

CREATE INDEX place_geom_geometry_idx ON place USING gist ((geom::geometry));

COMMENT ON INDEX place_geom_geometry_idx IS
    '지도 뷰포트(bbox) 조회용. 반경 검색은 geography 쪽 place_geom_idx 를 탄다';
