#!/usr/bin/env sh
# 비밀번호는 환경에서 psql 내부 변수로만 읽는다. 인자·파일·로그에 쓰지 않는다.
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
GRANT app_migrate TO CURRENT_USER WITH SET TRUE;
SELECT 'CREATE DATABASE scenetrip OWNER app_migrate ENCODING ''UTF8'' LC_COLLATE ''C'' LC_CTYPE ''C.UTF-8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'scenetrip') \gexec
\connect scenetrip
DO $$ BEGIN
  IF (SELECT datcollate <> 'C' OR datctype <> 'C.UTF-8' FROM pg_database WHERE datname = current_database()) THEN
    RAISE EXCEPTION 'scenetrip locale mismatch: restore into C/C.UTF-8 database';
  END IF;
END $$;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
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
RESET ROLE;
SQL
