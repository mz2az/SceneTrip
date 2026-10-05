-- POI 분기 갱신 — 같은 가게 잇기와 폐업 표시. `just seed-poi --update` 가 poi.sql 안에서 부른다.
--
-- 계획: docs/project/plans/poi-i18n-image.md §8. 규칙과 실측(2025-12 판 → 2026-06 판)이 거기 있다.
--
-- ── 이 파일은 순수 SQL 이다 ─────────────────────────────────────────────────────
--
-- psql 메타 명령(\echo · \if …)을 쓰지 않는다. 통합 시험(PoiUpdateIntegrationTest)이 이 파일을
-- 그대로 JDBC 로 돌려 검증하고, 서버 적재(계획 §6-6 3f)도 같은 파일을 쓴다. 결과 수는 t_update_summary
-- 에 남기고 보여 주는 일은 부른 쪽(poi.sql)이 한다.
--
-- 받는 것: 임시 표 t_load(source_id, name, lat, lng, category) — 이번 판에서 넣을 행 전부.
-- 바꾸는 것: poi.source_id(이어진 행), poi.closed_at(폐업 판정).
-- 이 파일이 끝난 뒤 부른 쪽이 UPSERT 를 하면, 이어진 행은 새 번호로 갱신되고 새 가게는 추가된다.
--
-- ── 같은 출처끼리만 ──────────────────────────────────────────────────────────────
--
-- 출처는 source_id 앞의 영문이다(MA 상가정보 · tour 관광공사 · busstop · metro …). 이번 입력에 없는
-- 출처의 POI 는 사라진 것으로 보지 않는다 — 상가정보 파일만 넣어도 관광지가 폐업되지 않는다.

CREATE TEMP TABLE t_update_source ON COMMIT DROP AS
SELECT DISTINCT substring(source_id FROM '^[A-Za-z]+') AS source
FROM t_load;

-- 비교용 이름: 소문자, 공백·괄호·기호를 지운 것. 「스타벅스 (구리갈매역)」 과 「스타벅스구리갈매역」 이 같아진다.
-- 사라진 행: 영업 중인데 이번 입력에 번호가 없는 것.
CREATE TEMP TABLE t_update_gone ON COMMIT DROP AS
SELECT p.id, p.geom, p.category, s.source,
       regexp_replace(lower(p.name), '[[:space:]()\[\]·.,&_/-]', '', 'g') AS name_key
FROM poi p
JOIN t_update_source s ON s.source = substring(p.source_id FROM '^[A-Za-z]+')
WHERE p.closed_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM t_load l WHERE l.source_id = p.source_id);
CREATE INDEX ON t_update_gone USING gist (geom);

-- 새 번호: 이번 입력에 있는데 표에 없는 것. 폐업 표시된 채 표에 있는 번호는 새 번호가 아니다 —
-- UPSERT 가 그 행을 갱신하며 폐업 표시를 지운다(다시 문을 연 가게).
CREATE TEMP TABLE t_update_new ON COMMIT DROP AS
SELECT l.source_id, l.category, substring(l.source_id FROM '^[A-Za-z]+') AS source,
       ST_SetSRID(ST_MakePoint(l.lng, l.lat), 4326)::geography AS geom,
       regexp_replace(lower(l.name), '[[:space:]()\[\]·.,&_/-]', '', 'g') AS name_key
FROM t_load l
WHERE NOT EXISTS (SELECT 1 FROM poi p WHERE p.source_id = l.source_id);
CREATE INDEX ON t_update_new USING gist (geom);

-- 후보 쌍 — 30 m 안에서 규칙 셋 중 하나를 만족하는 것. rule 이 클수록 강하다.
--   3 이름이 같다(분류 상관없이)
--   2 한쪽 이름이 다른 쪽에 들어 있다. 짧은 쪽 3 글자 이상, 분류가 같다
--   1 편집 거리 비율 1 − levenshtein / 긴 쪽 길이 ≥ 0.8, 분류가 같다
-- 0.8 은 이어야 할 쌍 777 · 잇지 말아야 할 쌍 2,000 표본에서 잘못 이음 0 인 값이다(0.7 은 19).
-- levenshtein 은 255 글자까지 받는다 — 가게 이름은 그보다 짧지만 넘으면 이 규칙만 건너뛴다.
CREATE TEMP TABLE t_update_pair ON COMMIT DROP AS
SELECT g.id, n.source_id, ST_Distance(g.geom, n.geom) AS dist,
       CASE
           WHEN g.name_key = n.name_key THEN 3
           WHEN g.category = n.category
                AND least(length(g.name_key), length(n.name_key)) >= 3
                AND (position(g.name_key IN n.name_key) > 0 OR position(n.name_key IN g.name_key) > 0) THEN 2
           WHEN g.category = n.category
                AND greatest(length(g.name_key), length(n.name_key)) <= 255
                AND 1 - levenshtein(g.name_key, n.name_key)::float
                        / greatest(length(g.name_key), length(n.name_key)) >= 0.8 THEN 1
           ELSE 0
       END AS rule
FROM t_update_gone g
-- 잇는 상대도 같은 출처에서만 고른다. 같은 가게가 상가정보와 관광공사에 둘 다 있는 경우가 있어(계획 §6-1,
-- 658 쌍) 출처를 넘나들면 사라진 관광지가 옆 상가정보의 번호를 가로챈다.
JOIN t_update_new n ON n.source = g.source AND ST_DWithin(g.geom, n.geom, 30)
WHERE g.name_key <> '' AND n.name_key <> '';
DELETE FROM t_update_pair WHERE rule = 0;

-- 서로에게 최선인 쌍만 잇는다 — 옛 행에게도, 새 번호에게도 상대가 1 순위인 쌍.
-- 순위: 규칙이 강한 것 → 가까운 것 → 번호(같은 결과가 언제나 나오도록).
CREATE TEMP TABLE t_update_link ON COMMIT DROP AS
SELECT a.id, a.source_id, a.rule
FROM (SELECT DISTINCT ON (id) * FROM t_update_pair ORDER BY id, rule DESC, dist, source_id) a
JOIN (SELECT DISTINCT ON (source_id) * FROM t_update_pair ORDER BY source_id, rule DESC, dist, id) b
  ON a.id = b.id AND a.source_id = b.source_id;

-- 잇기 — 옛 행의 번호를 새 번호로. poi.id 와 거기 붙은 번역·사진은 그대로다.
UPDATE poi p
SET source_id = k.source_id, updated_at = now()
FROM t_update_link k
WHERE p.id = k.id;

-- 폐업 표시 — 사라졌는데 잇지 못한 행. 지우지 않는다(잘못 판정해도 되돌릴 수 있게, 붙은 행이 남게).
CREATE TEMP TABLE t_update_closed ON COMMIT DROP AS
SELECT g.id FROM t_update_gone g
WHERE NOT EXISTS (SELECT 1 FROM t_update_link k WHERE k.id = g.id);

UPDATE poi p
SET closed_at = now(), updated_at = now()
FROM t_update_closed c
WHERE p.id = c.id;

CREATE TEMP TABLE t_update_summary ON COMMIT DROP AS
SELECT
    (SELECT count(*) FROM t_update_gone)                    AS gone,
    (SELECT count(*) FROM t_update_new)                     AS new_ids,
    (SELECT count(*) FROM t_update_link WHERE rule = 3)     AS linked_same_name,
    (SELECT count(*) FROM t_update_link WHERE rule = 2)     AS linked_contained,
    (SELECT count(*) FROM t_update_link WHERE rule = 1)     AS linked_similar,
    (SELECT count(*) FROM t_update_closed)                  AS closed;
