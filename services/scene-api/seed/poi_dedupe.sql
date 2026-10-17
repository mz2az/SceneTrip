-- 이미 남아 있는 같은 가게 중복을 정리한다 — 한쪽을 숨기고 리뷰·코스·글 항목·번호를 남는 쪽으로 옮긴다 (MZ2AZ-491).
--
-- 실행: `just poi-dedupe [--dry-run]` — 원격(DEV·PRD)은 데이터 적재(remote-load.sh)가 같은 파일을 부른다. 몇 번을 돌려도 같다
-- (두 번째부터는 찾을 쌍이 없다). 계획: docs/project/plans/poi-load-cleanup.md §8 2단계.
--
-- ── 왜 남아 있나 ─────────────────────────────────────────────────────────────
--
-- 처음 적재의 「같은 이름·1 m」 는 좌표를 소수 5 자리로 반올림해 같은지 보는 규칙이라 경계에서 갈린다 — 0.85 m 떨어진 두 줄도
-- 반올림 값이 다르면 둘 다 남는다. 로컬 실측(2026-10-17): 이름이 같고 1.5 m 안인 열린 편의시설 187 쌍.
--
-- ── 같은 가게 ─────────────────────────────────────────────────────────────────
--
-- 열린 편의시설 둘이 거리 1.5 m 안 · 같은 갈래 · 이름이 같다 — 유니코드 정규화(NFKC)로 전각·로마 숫자를 풀어(「５」→「5」,
-- 「Ⅱ」→「II」) 대괄호 꼬리를 빼고 소문자 영숫자·한글만 남겨 비교한다. 정규화 없이 기호를 지우면 「관악산５호점」·「관악산２호점」,
-- 「오션힐４１０호」·「오션힐３０１호」 처럼 숫자만 다른 다른 가게가 같은 이름이 된다(2026-10-17 미리 보기에서 잡았다).
-- 전화가 둘 다 있고 다르면서 세부 종류도 다르면 다른 가게일 수 있어 합치지 않고 목록만 보인다(처음 적재의 「따로 둠」 과 같은 기준).
--
-- ── 남는 쪽 ───────────────────────────────────────────────────────────────────
--
-- 처음 적재와 같은 순서 — 자루 분류(「~기타」)가 아닌 쪽 → 전화 있는 쪽 → 번호가 짧은 쪽 → 작은 쪽. 셋 이상 이어지면 모두 가장
-- 나은 하나로 모은다.
--
-- ── 옮기는 것 ─────────────────────────────────────────────────────────────────
--
-- 리뷰 · 코스 항목 · 커뮤니티 글 항목 · 번호 기록(poi_source) · 상세·영어 칸(남는 쪽에 그 언어 줄이 없을 때) · 사진(남는 쪽에 같은
-- 주소가 없을 때) · 촬영지 연결(남는 쪽에 없을 때). 한 사람이 양쪽에 리뷰를 썼으면 updated_at 이 늦은 것만 남기고 나머지는
-- review_archive 에 보관한 뒤 지운다 — place_poi_link.sql 의 리뷰 옮기기와 같은 규칙이다. 숨긴 쪽은 closed_at 만 — 지우지 않는다
-- (ADR 0022).

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE t_dd_rank ON COMMIT DROP AS
SELECT id,
       regexp_replace(lower(regexp_replace(normalize(name, NFKC), '\[[^]]*\]', '', 'g')), '[^0-9a-z가-힣]', '', 'g') AS name_key,
       ROW_NUMBER() OVER (ORDER BY (category NOT LIKE '%기타') DESC, (tel IS NOT NULL) DESC,
                                   length(source_id), source_id) AS rnk
FROM poi
WHERE closed_at IS NULL;

CREATE UNIQUE INDEX ON t_dd_rank (id);

CREATE TEMP TABLE t_dd_pair ON COMMIT DROP AS
SELECT a.id AS a_id, b.id AS b_id,
       (a.tel IS NOT NULL AND b.tel IS NOT NULL AND a.tel <> b.tel AND a.category <> b.category) AS ambiguous,
       round(ST_Distance(a.geom, b.geom)::numeric, 2) AS meters
FROM poi a
JOIN poi b ON b.id > a.id
          AND b.closed_at IS NULL
          AND b.category_group = a.category_group
          AND ST_DWithin(b.geom, a.geom, 1.5)
JOIN t_dd_rank ra ON ra.id = a.id
JOIN t_dd_rank rb ON rb.id = b.id AND rb.name_key = ra.name_key
WHERE a.closed_at IS NULL
  AND ra.name_key <> '';

-- 쌍을 이어 묶음을 만들고(A–B, B–C 면 A·B·C 한 묶음), 묶음에서 가장 나은 하나만 남긴다. 쌍마다 승자를 고르면 A–B 에서 이긴 A 가
-- 어디에서도 지는 쪽에 오르지 않아 남는다(2026-10-17 시험이 잡았다).
CREATE TEMP TABLE t_dd_edge ON COMMIT DROP AS
SELECT a_id AS x, b_id AS y FROM t_dd_pair WHERE NOT ambiguous
UNION
SELECT b_id, a_id FROM t_dd_pair WHERE NOT ambiguous;

CREATE TEMP TABLE t_dd_reach ON COMMIT DROP AS
WITH RECURSIVE reach(node, other) AS (
    SELECT x, y FROM t_dd_edge
    UNION
    SELECT r.node, e.y FROM reach r JOIN t_dd_edge e ON e.x = r.other WHERE e.y <> r.node
)
SELECT node, other FROM reach;

CREATE TEMP TABLE t_dd_map ON COMMIT DROP AS
SELECT DISTINCT ON (r.node) r.node AS loser, best.id AS winner
FROM (SELECT node, other FROM t_dd_reach UNION SELECT DISTINCT node, node FROM t_dd_reach) r
JOIN t_dd_rank best ON best.id = r.other
ORDER BY r.node, best.rnk;

DELETE FROM t_dd_map WHERE loser = winner;

-- ── 리뷰 ──────────────────────────────────────────────────────────────────────

CREATE TEMP TABLE t_dd_clash ON COMMIT DROP AS
SELECT r.id AS loser_review, wr.id AS winner_review,
       CASE WHEN r.updated_at > wr.updated_at THEN wr.id ELSE r.id END AS dropped
FROM review r
JOIN t_dd_map m ON m.loser = r.poi_id
JOIN review wr ON wr.poi_id = m.winner AND wr.user_id = r.user_id
WHERE r.user_id IS NOT NULL;

INSERT INTO review_archive (reason, review_id, place_id, poi_id, user_id, rating, body, created_at, updated_at,
                            photo_keys, kept_review_id)
SELECT 'poi_dedupe: 같은 가게 정리로 한 사람의 리뷰가 겹쳐 오래된 쪽',
       r.id, r.place_id, r.poi_id, r.user_id, r.rating, r.body, r.created_at, r.updated_at,
       COALESCE((SELECT array_agg(i.storage_key ORDER BY i.sort_order) FROM review_image i WHERE i.review_id = r.id), '{}'),
       CASE WHEN c.dropped = c.loser_review THEN c.winner_review ELSE c.loser_review END
FROM t_dd_clash c
JOIN review r ON r.id = c.dropped;

DELETE FROM review WHERE id IN (SELECT dropped FROM t_dd_clash);

CREATE TEMP TABLE t_dd_moved_reviews ON COMMIT DROP AS
WITH u AS (UPDATE review r SET poi_id = m.winner FROM t_dd_map m WHERE r.poi_id = m.loser RETURNING r.id)
SELECT id FROM u;

-- ── 코스 · 글 항목 · 번호 기록 ─────────────────────────────────────────────────

CREATE TEMP TABLE t_dd_moved_course ON COMMIT DROP AS
WITH u AS (UPDATE course_item ci SET poi_id = m.winner FROM t_dd_map m WHERE ci.poi_id = m.loser RETURNING ci.id)
SELECT id FROM u;

CREATE TEMP TABLE t_dd_moved_post ON COMMIT DROP AS
WITH u AS (
    UPDATE community_post_item pi SET poi_id = m.winner FROM t_dd_map m WHERE pi.poi_id = m.loser
    RETURNING pi.post_id
)
SELECT post_id FROM u;

UPDATE poi_source s SET poi_id = m.winner, updated_at = now() FROM t_dd_map m WHERE s.poi_id = m.loser;

-- ── 상세 · 영어 칸 · 사진 · 촬영지 연결 — 남는 쪽에 없을 때만 ─────────────────

UPDATE poi_detail d SET poi_id = m.winner
FROM t_dd_map m
WHERE d.poi_id = m.loser
  AND NOT EXISTS (SELECT 1 FROM poi_detail w WHERE w.poi_id = m.winner AND w.lang = d.lang);

UPDATE poi_i18n t SET poi_id = m.winner
FROM t_dd_map m
WHERE t.poi_id = m.loser
  AND NOT EXISTS (SELECT 1 FROM poi_i18n w WHERE w.poi_id = m.winner AND w.lang = t.lang);

UPDATE poi_image i SET poi_id = m.winner, sort_order = i.sort_order + 2000
FROM t_dd_map m
WHERE i.poi_id = m.loser
  AND NOT EXISTS (SELECT 1 FROM poi_image w WHERE w.poi_id = m.winner AND w.url = i.url);

UPDATE place_poi_link l SET poi_id = m.winner
FROM t_dd_map m
WHERE l.poi_id = m.loser
  AND NOT EXISTS (SELECT 1 FROM place_poi_link w WHERE w.poi_id = m.winner);

-- ── 숨긴다 ────────────────────────────────────────────────────────────────────

UPDATE poi p SET closed_at = now(), updated_at = now() FROM t_dd_map m WHERE p.id = m.loser;

-- ── 알림 ──────────────────────────────────────────────────────────────────────

\echo ''
\echo '같은 가게 정리:'
SELECT (SELECT count(*) FROM t_dd_pair)                       AS pairs,
       (SELECT count(*) FROM t_dd_pair WHERE ambiguous)       AS ambiguous_pairs,
       (SELECT count(*) FROM t_dd_map)                        AS hidden,
       (SELECT count(*) FROM t_dd_moved_reviews)              AS moved_reviews,
       (SELECT count(*) FROM t_dd_clash)                      AS archived_reviews,
       (SELECT count(*) FROM t_dd_moved_course)               AS moved_course_items,
       (SELECT count(*) FROM t_dd_moved_post)                 AS moved_post_items;

\echo ''
\echo '숨긴 쪽 → 남는 쪽 (앞 20):'
SELECT lp.source_id AS hidden, lp.name, wp.source_id AS kept, wp.name AS kept_name
FROM t_dd_map m JOIN poi lp ON lp.id = m.loser JOIN poi wp ON wp.id = m.winner
ORDER BY lp.source_id LIMIT 20;

\echo ''
\echo '합치지 않은 애매한 쌍 (전화·세부 종류가 둘 다 다름) — 사람이 본다:'
SELECT a.source_id, a.name, a.category, a.tel, b.source_id, b.category, b.tel, p.meters
FROM t_dd_pair p JOIN poi a ON a.id = p.a_id JOIN poi b ON b.id = p.b_id
WHERE p.ambiguous
ORDER BY a.name;

\if :{?dry_run}
ROLLBACK;
\echo ''
\echo '(미리 보기 — DB 는 바뀌지 않았습니다)'
\else
COMMIT;
\endif
