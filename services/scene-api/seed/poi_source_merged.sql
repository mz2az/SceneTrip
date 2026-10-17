-- 처음 적재 때 다른 편의시설에 합쳐진 줄의 번호를 번호 기록(poi_source, V38)에 넣는다 (MZ2AZ-491).
--
-- 실행: `just poi-source-merged` — 원격(DEV·PRD)은 데이터 적재(remote-load.sh)가 같은 파일을 부른다.
-- 계획: docs/project/plans/poi-load-cleanup.md §3.
--
-- 입력 seed/poi_merged_2026-06.tsv(22,818 줄): 「이 번호 → 이 번호에 합쳐졌다」. 2026-06 판 원본 97 만 줄의 행방을 재구성한
-- 결과(poi-source.md §2-1)에서 뽑았다 — 관광공사 → 상가정보 11,664, 상가정보끼리 10,412, 상가정보 → 관광공사 685 등.
-- 합쳐진 대상을 **번호로** 찾는다 — 편의시설 번호(poi.id)는 DB 마다 달라도 출처 번호는 같다. 몇 번을 돌려도 같다.
--
-- 이 번호들이 기록되면 관광공사 상세가 합쳐진 곳에도 붙는다(poi_detail.sql 이 기록으로 찾는다) — 상세 적재를 다시 돌린다.

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE t_merged (source_id TEXT, merged_into TEXT) ON COMMIT DROP;
\copy t_merged FROM '/tmp/poi-merged.tsv' WITH (FORMAT csv, DELIMITER E'\t', HEADER true)

CREATE TEMP TABLE t_merged_done ON COMMIT DROP AS
WITH ins AS (
    INSERT INTO poi_source (source_id, poi_id, source)
    SELECT m.source_id,
           s.poi_id,
           CASE WHEN m.source_id LIKE 'MA%'   THEN 'store'
                WHEN m.source_id LIKE 'tour-%' THEN 'tour'
                ELSE 'transit' END
    FROM t_merged m
    JOIN poi_source s ON s.source_id = m.merged_into
    ON CONFLICT (source_id) DO NOTHING
    RETURNING source
)
SELECT source, count(*) AS rows FROM ins GROUP BY 1;

\echo ''
\echo '합쳐진 번호 기록:'
SELECT (SELECT count(*) FROM t_merged)                                          AS input_rows,
       (SELECT coalesce(sum(rows), 0) FROM t_merged_done)                         AS recorded_now,
       (SELECT count(*) FROM t_merged m
          WHERE NOT EXISTS (SELECT 1 FROM poi_source s WHERE s.source_id = m.merged_into)) AS target_missing;
SELECT * FROM t_merged_done ORDER BY 1;

COMMIT;
