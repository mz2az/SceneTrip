-- 촬영지와 같은 곳인 편의시설을 연결하고, 편의시설 쪽에 쌓인 리뷰를 촬영지로 옮긴다.
--
-- 계획: docs/project/plans/place-poi-link.md §2~§5 (MZ2AZ-371). 표: V25 place_poi_link.
-- 실행: `just place-poi-link [--dry-run]` — 촬영지 적재(`just seed`)와 편의시설 갱신(`just seed-poi --update`) 뒤에.
-- 판정 파일: seed/place_poi_links.tsv — 스크립트가 psql 이 도는 기계의 /tmp/place-poi-links.tsv 로 놓는다.
--
-- **몇 번을 돌려도 같다.** 연결은 매번 처음부터 계산해 표를 그 결과로 맞추고, 리뷰 옮기기는 연결된 편의시설에
-- 남은 리뷰만 옮긴다.

\set ON_ERROR_STOP on

BEGIN;

-- ── 판정 파일 ─────────────────────────────────────────────────────────────────

CREATE TEMP TABLE verdict (
    place_key     TEXT,
    poi_source_id TEXT,
    verdict       TEXT,
    note          TEXT
) ON COMMIT DROP;

\copy verdict FROM '/tmp/place-poi-links.tsv' WITH (FORMAT csv, DELIMITER E'\t', HEADER true, QUOTE E'\x01')

DO $$
DECLARE bad TEXT;
BEGIN
    SELECT string_agg(coalesce(place_key, '?') || ' / ' || coalesce(poi_source_id, '?'), ', ') INTO bad
    FROM verdict WHERE verdict IS NULL OR verdict NOT IN ('same', 'not');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '판정은 same 이나 not 이어야 합니다 — %', bad;
    END IF;
    SELECT string_agg(place_key || ' / ' || poi_source_id, ', ') INTO bad
    FROM (SELECT place_key, poi_source_id FROM verdict GROUP BY 1, 2 HAVING count(*) > 1) d;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '같은 쌍이 판정 파일에 두 번 있습니다 — %', bad;
    END IF;
END $$;

-- 판정 파일이 가리키는 것이 DB 에 없으면 알린다(멈추지는 않는다 — 편의시설이 분기 갱신으로 폐업했을 수 있다).
DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(v.place_key || ' / ' || v.poi_source_id, ', ') INTO missing
    FROM verdict v
    WHERE NOT EXISTS (SELECT 1 FROM place p WHERE p.place_key = v.place_key)
       OR NOT EXISTS (SELECT 1 FROM poi q WHERE q.source_id = v.poi_source_id);
    IF missing IS NOT NULL THEN
        RAISE NOTICE '판정 파일의 쌍 중 DB 에 없는 것(건너뜀): %', missing;
    END IF;
END $$;

-- ── 같은 곳 판정 (계획 §2) ────────────────────────────────────────────────────

-- 도로명 + 건물번호. 「경기도」/「경기」, 괄호 속 법정동 같은 표기 차이를 지운다. 사이에 공백이 있어야 한다 —
-- 「종로1·2·3·4가동」 의 「종로」 + 「1」 을 도로명으로 읽지 않게. 지번 주소는 견주지 않는다(NULL).
CREATE FUNCTION pg_temp.road_key(a TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $fn$
    SELECT m[1] || ' ' || m[2] FROM regexp_match(a, '(\S+(?:로|길))\s+(\d+(?:-\d+)?)') AS m
$fn$;

-- 후보: 보이는 촬영지 50 m 안의 영업 중인 편의시설 중, 띄어쓰기를 뺀 편의시설 이름이 촬영지 이름 안에 든 것.
-- 반대 방향(촬영지 이름 ⊂ 편의시설 이름)은 보지 않는다 — 「대구 이월드」 ⊂ 「스타벅스 대구이월드」 는 그 안의 다른 가게다.
-- strpos 로 견준다 — LIKE 는 이름의 % · _ 를 와일드카드로 읽는다.
CREATE TEMP TABLE candidate ON COMMIT DROP AS
SELECT
    p.id AS place_id, p.place_key, pi.name AS place_name, pi.address AS place_address,
    q.id AS poi_id, q.source_id AS poi_source_id, q.name AS poi_name, q.address AS poi_address,
    round(ST_Distance(p.geom, q.geom))::INT AS meters,
    pg_temp.road_key(pi.address) AS place_road,
    pg_temp.road_key(q.address)  AS poi_road
FROM place p
JOIN place_i18n pi ON pi.place_id = p.id AND pi.lang = 'ko'
JOIN poi q ON q.closed_at IS NULL AND ST_DWithin(p.geom, q.geom, 50)
WHERE p.hidden_at IS NULL
  AND p.place_key IS NOT NULL
  AND strpos(replace(pi.name, ' ', ''), replace(q.name, ' ', '')) > 0;

-- A: 도로명 주소가 같다 · B: 한쪽이라도 견줄 수 없고 5 m 안 · C: 그 밖(사람 판정 전까지 연결하지 않는다)
ALTER TABLE candidate ADD COLUMN tier TEXT;
UPDATE candidate SET tier = CASE
    WHEN place_road IS NOT NULL AND place_road = poi_road THEN 'A'
    WHEN (place_road IS NULL OR poi_road IS NULL) AND meters <= 5 THEN 'B'
    ELSE 'C' END;

-- 연결할 것: 자동(A·B) 중 판정이 not 이 아닌 것 + 판정이 same 인 것(후보가 아니어도 — 사람이 본 것이다).
-- 편의시설 하나는 촬영지 하나에만 — 사람 판정이 자동보다 앞서고, 그다음 가까운 쪽.
CREATE TEMP TABLE wanted ON COMMIT DROP AS
SELECT DISTINCT ON (poi_id) poi_id, place_id, method
FROM (
    SELECT c.poi_id, c.place_id, CASE c.tier WHEN 'A' THEN 'address' ELSE 'near' END AS method,
           c.meters, 1 AS rank
    FROM candidate c
    WHERE c.tier IN ('A', 'B')
      AND NOT EXISTS (SELECT 1 FROM verdict v
                      WHERE v.place_key = c.place_key AND v.poi_source_id = c.poi_source_id
                        AND v.verdict = 'not')
    UNION ALL
    SELECT q.id, p.id, 'manual', round(ST_Distance(p.geom, q.geom))::INT, 0
    FROM verdict v
    JOIN place p ON p.place_key = v.place_key AND p.hidden_at IS NULL
    JOIN poi q ON q.source_id = v.poi_source_id AND q.closed_at IS NULL
    WHERE v.verdict = 'same'
) s
ORDER BY poi_id, rank, meters, place_id;

-- ── 표를 맞춘다 ───────────────────────────────────────────────────────────────

CREATE TEMP TABLE was ON COMMIT DROP AS SELECT poi_id, place_id FROM place_poi_link;

DELETE FROM place_poi_link l
WHERE NOT EXISTS (SELECT 1 FROM wanted w WHERE w.poi_id = l.poi_id AND w.place_id = l.place_id);

INSERT INTO place_poi_link (poi_id, place_id, method)
SELECT poi_id, place_id, method FROM wanted
ON CONFLICT (poi_id) DO UPDATE SET method = EXCLUDED.method;

-- ── 리뷰를 촬영지로 옮긴다 (계획 §5) ──────────────────────────────────────────
--
-- 한 사람이 양쪽에 썼으면 updated_at 이 늦은 것 하나만 남긴다(「한 사람 한 곳 하나」). 지우는 리뷰의 사진 행은 CASCADE 로
-- 지워진다 — 저장소의 파일은 아래 NOTICE 로 알린다. 연결이 나중에 풀려도 옮긴 리뷰는 촬영지에 남는다.

CREATE TEMP TABLE clash ON COMMIT DROP AS
SELECT r.id AS poi_review, pr.id AS place_review,
       CASE WHEN r.updated_at > pr.updated_at THEN pr.id ELSE r.id END AS loser
FROM review r
JOIN place_poi_link l ON l.poi_id = r.poi_id
JOIN place pl ON pl.id = l.place_id AND pl.hidden_at IS NULL
JOIN review pr ON pr.place_id = l.place_id AND pr.user_id = r.user_id
WHERE r.user_id IS NOT NULL;

DO $$
DECLARE n INT; keys TEXT;
BEGIN
    SELECT count(*), string_agg(i.storage_key, ', ') INTO n, keys
    FROM clash c JOIN review_image i ON i.review_id = c.loser;
    IF (SELECT count(*) FROM clash) > 0 THEN
        RAISE NOTICE '양쪽에 쓴 리뷰 % 건 — 최근 것만 남긴다. 지우는 사진 % 장(저장소에서 지울 것): %',
            (SELECT count(*) FROM clash), n, coalesce(keys, '없음');
    END IF;
END $$;

DELETE FROM review WHERE id IN (SELECT loser FROM clash);

CREATE TEMP TABLE moved ON COMMIT DROP AS
WITH m AS (
    UPDATE review r
    SET place_id = l.place_id, poi_id = NULL
    FROM place_poi_link l
    JOIN place pl ON pl.id = l.place_id AND pl.hidden_at IS NULL
    WHERE r.poi_id = l.poi_id
    RETURNING r.id
)
SELECT id FROM m;

-- ── 알림 (트랜잭션 안 — 임시 표가 COMMIT 에 사라진다) ─────────────────────

\echo
\echo '── 연결 (방법별) ──'
SELECT method, count(*) FROM place_poi_link GROUP BY 1 ORDER BY 1;

\echo
\echo '── 이번에 바뀐 연결 (+ 새로 · - 풀림) ──'
SELECT '+' AS change, p.place_key, pi.name AS place, q.source_id, q.name AS poi, l.method
FROM place_poi_link l
JOIN place p ON p.id = l.place_id JOIN place_i18n pi ON pi.place_id = p.id AND pi.lang = 'ko'
JOIN poi q ON q.id = l.poi_id
WHERE NOT EXISTS (SELECT 1 FROM was w WHERE w.poi_id = l.poi_id AND w.place_id = l.place_id)
UNION ALL
SELECT '-', p.place_key, pi.name, q.source_id, q.name, ''
FROM was w
JOIN place p ON p.id = w.place_id JOIN place_i18n pi ON pi.place_id = p.id AND pi.lang = 'ko'
JOIN poi q ON q.id = w.poi_id
WHERE NOT EXISTS (SELECT 1 FROM place_poi_link l WHERE l.poi_id = w.poi_id AND l.place_id = w.place_id)
ORDER BY 1, 2;

\echo
\echo '── 후보 전부 — 단계(A·B 자동, C 사람) · 판정 · 연결 ──'
SELECT c.tier, coalesce(v.verdict, '') AS verdict,
       CASE WHEN l.poi_id IS NOT NULL THEN '연결' ELSE '' END AS linked,
       c.place_key, c.poi_source_id, c.place_name, c.poi_name, c.meters AS m,
       coalesce(c.place_road, '-') AS place_road, coalesce(c.poi_road, '-') AS poi_road
FROM candidate c
LEFT JOIN verdict v ON v.place_key = c.place_key AND v.poi_source_id = c.poi_source_id
LEFT JOIN place_poi_link l ON l.poi_id = c.poi_id AND l.place_id = c.place_id
ORDER BY c.tier, c.meters;

\echo
\echo '── 판정이 필요한 후보 (C, 판정 없음) — seed/place_poi_links.tsv 에 same/not 으로 적는다 ──'
SELECT c.place_key || E'\t' || c.poi_source_id || E'\t' || '?' || E'\t' || c.place_name || ' ↔ ' || c.poi_name
       || ' (' || c.meters || ' m)' AS "붙여 넣을 줄"
FROM candidate c
WHERE c.tier = 'C'
  AND NOT EXISTS (SELECT 1 FROM verdict v WHERE v.place_key = c.place_key AND v.poi_source_id = c.poi_source_id)
ORDER BY c.meters;

\echo
\echo '── 옮긴 리뷰 ──'
SELECT count(*) AS moved_reviews FROM moved;

-- 미리 보기(`just place-poi-link --dry-run`)면 계산·출력만 하고 되돌린다.
\if :{?dry_run}
ROLLBACK;
\echo
\echo '(미리 보기 — DB 는 바뀌지 않았습니다)'
\else
COMMIT;
\endif
