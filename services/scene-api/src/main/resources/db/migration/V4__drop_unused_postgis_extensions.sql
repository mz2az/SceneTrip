-- 쓰지 않는 PostGIS 부가 확장을 제거한다.
--
-- 우리가 설치한 것이 아니다. imresamu/postgis 이미지의 초기화 스크립트가 postgis 와
-- 함께 postgis_topology · postgis_tiger_geocoder 까지 기본으로 켠다. 그 둘이 테이블
-- 35 개와 스키마 셋(tiger · tiger_data · topology)을 만든다 — 우리 테이블 14 개보다
-- 많다. `\dt` 가 우리 스키마를 보여 주지 못하는 상태가 된다(실측).
--
-- 지우는 근거:
--   postgis_tiger_geocoder  미국 TIGER 인구조사 데이터로 미국 주소를 지오코딩한다.
--                           SceneTrip 은 지오코딩을 하지 않고, 한다 해도 한국 주소다.
--   postgis_topology        위상(면의 인접·공유 경계) 모델. 우리는 점(Point)만 쓴다.
--
-- fuzzystrmatch 는 남긴다. 테이블을 만들지 않고, levenshtein 이 나중에 검색 보정에
-- 쓰일 수 있다. 지금 쓰지 않는다는 이유만으로 지우지는 않는다 — 비용이 0 이다.
--
-- **V1 을 고치지 않고 새 마이그레이션으로 만든 이유:** Flyway 는 적용된 마이그레이션의
-- 체크섬을 기록해 두고, 파일이 바뀌면 다음 기동에서 검증 실패로 죽는다. 이미 어딘가에
-- 적용된 마이그레이션은 고치지 않고 뒤에 붙인다. 새 DB 에서는 V1 이 만들지도 않은 것을
-- V4 가 지우는 모양이 되는데, 만든 주체가 이미지이므로 순서상 문제는 없다.

DROP EXTENSION IF EXISTS postgis_tiger_geocoder CASCADE;
DROP EXTENSION IF EXISTS postgis_topology CASCADE;

-- 확장을 지워도 확장이 만든 스키마는 남는다. 비어 있을 때만 지워지도록 RESTRICT 를
-- 쓴다 — 혹시 무언가 들어 있으면 조용히 지우는 대신 마이그레이션이 실패해야 한다.
DROP SCHEMA IF EXISTS tiger_data RESTRICT;
DROP SCHEMA IF EXISTS tiger RESTRICT;
DROP SCHEMA IF EXISTS topology RESTRICT;
