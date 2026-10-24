#!/usr/bin/env sh
# 비밀번호는 환경에서 psql 내부 변수로만 읽는다. 인자·파일·로그에 쓰지 않는다.
# 이 파일은 Kubernetes Job 의 command 문자열로 실행된다. Kubernetes 는 command 안의
# 겹달러(두 개 연속)를 달러 하나로, 달러-괄호를 환경변수로 치환하므로 달러 인용에는
# 이름을 붙인다(도 인용). 주석에도 그 두 패턴을 쓰지 않는다.
set -eu
export PGSSLMODE=require
export PGDATABASE=postgres
psql -X --set=ON_ERROR_STOP=1 <<'SQL'
\getenv runtime_pw RUNTIME_PASSWORD
\getenv migration_pw MIGRATION_PASSWORD
SELECT format('CREATE ROLE app_migrate LOGIN PASSWORD %L', :'migration_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_migrate') \gexec
SELECT format('ALTER ROLE app_migrate PASSWORD %L', :'migration_pw') \gexec
SELECT format('CREATE ROLE app_runtime LOGIN PASSWORD %L', :'runtime_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') \gexec
SELECT format('ALTER ROLE app_runtime PASSWORD %L', :'runtime_pw') \gexec
-- Metabase(ADR 0027) — 비밀번호 두 개가 다 있을 때만. 없으면 건너뛰어 지금까지의 배포가 그대로 돈다.
\getenv metabase_ro_pw METABASE_RO_PASSWORD
\getenv metabase_app_pw METABASE_APP_PASSWORD
\set metabase false
\if :{?metabase_ro_pw}
\if :{?metabase_app_pw}
SELECT :'metabase_ro_pw' <> '' AND :'metabase_app_pw' <> '' AS metabase \gset
\endif
\endif
\if :metabase
SELECT format('CREATE ROLE metabase_ro LOGIN PASSWORD %L', :'metabase_ro_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'metabase_ro') \gexec
SELECT format('ALTER ROLE metabase_ro PASSWORD %L', :'metabase_ro_pw') \gexec
SELECT format('CREATE ROLE metabase_app LOGIN PASSWORD %L', :'metabase_app_pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'metabase_app') \gexec
SELECT format('ALTER ROLE metabase_app PASSWORD %L', :'metabase_app_pw') \gexec
-- PRD 주 DB 를 같이 쓰므로 분석 질의를 묶는다 — 연결 5 개, 질의 30 초, 읽기 전용 트랜잭션.
ALTER ROLE metabase_ro CONNECTION LIMIT 5;
ALTER ROLE metabase_ro SET statement_timeout = '30s';
ALTER ROLE metabase_ro SET default_transaction_read_only = on;
-- Metabase 자기 설정·대시보드 저장소. scenetrip 과 다른 DB 라 metabase_app 은 서비스 데이터에 닿지 않는다.
GRANT metabase_app TO CURRENT_USER WITH SET TRUE;
SELECT 'CREATE DATABASE metabase OWNER metabase_app ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'metabase') \gexec
REVOKE ALL ON DATABASE metabase FROM PUBLIC;
GRANT CONNECT ON DATABASE metabase TO metabase_app;
\endif
GRANT app_migrate TO CURRENT_USER WITH SET TRUE;
SELECT 'CREATE DATABASE scenetrip OWNER app_migrate ENCODING ''UTF8'' LC_COLLATE ''C'' LC_CTYPE ''C.UTF-8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'scenetrip') \gexec
\connect scenetrip
DO $do$ BEGIN
  IF (SELECT datcollate <> 'C' OR datctype <> 'C.UTF-8' FROM pg_database WHERE datname = current_database()) THEN
    RAISE EXCEPTION 'scenetrip locale mismatch: restore into C/C.UTF-8 database';
  END IF;
END $do$;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- 편의시설 갱신 적재(poi_update.sql)의 levenshtein. 로컬 이미지(imresamu/postgis)는 저절로 켜지만 RDS 는 아니다 —
-- 없어서 DEV 갱신 적재가 「function levenshtein does not exist」 로 실패했다(2026-10-13).
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
SET ROLE app_migrate;
REVOKE ALL ON DATABASE scenetrip FROM PUBLIC;
GRANT CONNECT ON DATABASE scenetrip TO app_migrate, app_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO app_migrate;
GRANT USAGE ON SCHEMA public TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE app_migrate IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE app_migrate IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO app_runtime;
SELECT 'REVOKE ALL ON TABLE public.flyway_schema_history FROM app_runtime'
WHERE to_regclass('public.flyway_schema_history') IS NOT NULL \gexec
-- metabase_ro 는 analytics 스키마(개인정보 칸을 뺀 뷰)만 읽는다. 스키마는 권한을 걸려고 여기서 먼저 만들고, 마이그레이션(V40)이 뷰를 채운다.
\if :metabase
GRANT CONNECT ON DATABASE scenetrip TO metabase_ro;
CREATE SCHEMA IF NOT EXISTS analytics;
GRANT USAGE ON SCHEMA analytics TO metabase_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO metabase_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE app_migrate IN SCHEMA analytics GRANT SELECT ON TABLES TO metabase_ro;
\endif
RESET ROLE;
SQL
