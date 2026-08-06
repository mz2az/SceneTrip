-- PostgreSQL 확장.
--
-- 스키마보다 먼저 도는 별도 마이그레이션인 이유: V2 의 place.geom 이 postgis 가
-- 설치돼 있어야만 존재하는 타입을 쓴다. 한 파일에 합쳐도 순서상 동작은 하지만,
-- 확장 설치는 "DB 를 준비하는 일"이고 스키마는 "우리 모델을 만드는 일"이라 실패했을
-- 때 원인이 갈린다. 확장이 없는 이미지에 붙으면 V1 에서 멈춘다.
--
-- 이 두 확장이 이미지 선택을 결정했다 (platform/kubernetes/postgres/statefulset.yaml).

-- 위경도 좌표와 반경 검색. place.geom GEOGRAPHY(Point,4326) 과 GiST 인덱스가 쓴다.
CREATE EXTENSION IF NOT EXISTS postgis;

-- 3-gram(trigram) 유사도. search_term 의 gin_trgm_ops 인덱스가 쓴다 — 검색어가 정확히
-- 일치하지 않아도(오타·부분 일치) 걸리게 하는 것이 이 확장이다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
