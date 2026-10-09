-- 개인 핀으로 저장된 편의시설을 편의시설 항목으로 바꾼다 (MZ2AZ-377, course-poi-item.md §5).
--
-- 실행: `just course-pin-to-poi [--dry-run]`. **앱(MZ2AZ-380)이 배포된 뒤에** 돌린다 — 1.7.0 이하 앱은 source: poi 를
-- 디코딩하지 못해 그 코스를 못 연다(계약 CourseItemSource).
--
-- 1.7.0 까지 앱은 편의시설을 담을 때 그 이름·좌표를 그대로 복사해 핀을 만들었다. 그래서 「이름이 정확히 같고 1 m 안의 영업 중인
-- 편의시설」 이면 그 편의시설이다. 같은 곳으로 연결된 편의시설이면 촬영지 항목으로 바꾼다(MZ2AZ-371). 못 찾은 핀은 그대로 둔다 —
-- 사용자가 직접 찍은 숙소일 수 있다. 몇 번을 돌려도 같다.

\set ON_ERROR_STOP on

BEGIN;

-- 핀마다 가장 가까운 후보 하나. 같은 이름의 가게가 1 m 안에 둘이면(드물다) 가까운 쪽, 그다음 id 순.
CREATE TEMP TABLE pin_match ON COMMIT DROP AS
SELECT DISTINCT ON (ci.id)
    ci.id AS item_id, cp.id AS pin_id, cp.name, q.id AS poi_id, lp.id AS place_id
FROM course_item ci
JOIN custom_pin cp ON cp.id = ci.custom_pin_id
JOIN poi q ON q.closed_at IS NULL AND q.name = cp.name AND ST_DWithin(q.geom, cp.geom, 1)
LEFT JOIN place_poi_link l ON l.poi_id = q.id
LEFT JOIN place lp ON lp.id = l.place_id AND lp.hidden_at IS NULL
ORDER BY ci.id, ST_Distance(q.geom, cp.geom), q.id;

UPDATE course_item ci
SET place_id = m.place_id,
    poi_id = CASE WHEN m.place_id IS NULL THEN m.poi_id END,
    custom_pin_id = NULL
FROM pin_match m
WHERE ci.id = m.item_id;

-- 아무 항목도 가리키지 않게 된 핀은 지운다(코스 저장이 하는 정리와 같다).
DELETE FROM custom_pin cp
WHERE cp.id IN (SELECT pin_id FROM pin_match)
  AND NOT EXISTS (SELECT 1 FROM course_item ci WHERE ci.custom_pin_id = cp.id);

\echo
\echo '── 바꾼 핀 ──'
SELECT CASE WHEN place_id IS NOT NULL THEN '촬영지' ELSE '편의시설' END AS "바뀐 것", count(*)
FROM pin_match GROUP BY 1 ORDER BY 1;

\echo
\echo '── 그대로 둔 핀(편의시설을 못 찾음) ──'
SELECT count(*) AS kept_pins FROM course_item WHERE custom_pin_id IS NOT NULL;

-- 미리 보기(`just course-pin-to-poi --dry-run`)면 계산·출력만 하고 되돌린다.
\if :{?dry_run}
ROLLBACK;
\echo
\echo '(미리 보기 — DB 는 바뀌지 않았습니다)'
\else
COMMIT;
\endif
