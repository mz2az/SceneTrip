-- 편의시설 출처별 업데이트 실행 — `just poi-update <출처> <묶음 폴더>` 와 원격 적재가 psql 로 돌린다 (MZ2AZ-492).
--
-- 계획: docs/project/plans/poi-load-cleanup.md §5. 규칙은 순수 SQL 둘 — poi_filter.sql(줄 거르기) · poi_source_update.sql(번호 기록 기준 업데이트).
--
-- 받는 변수: source(store·tour·transit) · full(1 = 판 전체 · 0 = 바뀐 것만) · filter_sql · update_sql(두 SQL 파일 경로) · dry_run(있으면 되돌림)
-- 받는 파일: /tmp/poi-update-input.jsonl(편의시설 줄) · /tmp/poi-update-hidden.jsonl(표출 중단 번호, 관광공사만 — 없으면 빈 파일)

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE t_raw (doc TEXT) ON COMMIT DROP;
\copy t_raw FROM '/tmp/poi-update-input.jsonl' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')

CREATE TEMP TABLE t_hidden_raw (doc TEXT) ON COMMIT DROP;
\copy t_hidden_raw FROM '/tmp/poi-update-hidden.jsonl' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')

CREATE TEMP TABLE t_hidden ON COMMIT DROP AS
SELECT DISTINCT btrim(doc::jsonb ->> 'source_id') AS source_id
FROM t_hidden_raw WHERE btrim(doc) <> '' AND NULLIF(btrim(doc::jsonb ->> 'source_id'), '') IS NOT NULL;

CREATE TEMP TABLE t_upd_param ON COMMIT DROP AS SELECT :'source'::text AS source, (:full = 1) AS full;

\i :filter_sql

\echo ''
\echo '줄 거르기:'
SELECT * FROM t_filter_summary;

\i :update_sql

\echo ''
\echo '출처별 업데이트:'
SELECT * FROM t_upd_summary;

\echo ''
\echo '숨긴 편의시설 (앞 20):'
SELECT p.source_id, p.name, p.category FROM t_u_hidden h JOIN poi p ON p.id = h.id ORDER BY p.source_id LIMIT 20;

\echo ''
\echo '같은 가게로 이은 새 번호 (앞 20):'
SELECT l.source_id AS new_number, n.name AS new_name, p.source_id AS poi, p.name AS poi_name, l.rule
FROM t_u_link l JOIN t_u_new n ON n.source_id = l.source_id JOIN poi p ON p.id = l.poi_id
ORDER BY l.source_id LIMIT 20;

\if :{?dry_run}
ROLLBACK;
\echo ''
\echo '(미리 보기 — DB 는 바뀌지 않았습니다)'
\else
COMMIT;
ANALYZE poi;
ANALYZE poi_source;
\endif
