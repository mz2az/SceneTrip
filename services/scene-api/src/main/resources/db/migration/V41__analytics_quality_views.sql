-- 분석용 뷰 2 차(docs/project/plans/metabase-prd.md §3-6) — 봇 거른 설치, 퍼널, 재방문, 검색어. 전부 숫자만 낸다.
-- 설치의 「사람」 은 user_device.user_id(로그인하면 가입 계정으로 바뀐다), 설치 자신의 조회는 entity_view.install_id.
-- IP·기기 정보가 없어 사람처럼 행동하는 스크립트는 「활동한 설치」 로 잡힌다 — 「재방문(2 일 이상)」 이 가장 강한 거르기다.
-- 설치 하나마다 단계를 계산하는 부분은 세 뷰가 같다. 별도 뷰로 빼면 설치 단위 행이 analytics 에 드러나므로 뷰마다 둔다.

CREATE VIEW analytics.daily_install_quality AS
WITH steps AS (
    SELECT (d.created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
           coalesce(v.view_days, 0) AS view_days,
           u.registered_at IS NOT NULL
               OR EXISTS (SELECT 1 FROM saved_content s WHERE s.user_id = d.user_id)
               OR EXISTS (SELECT 1 FROM saved_place s WHERE s.user_id = d.user_id)
               OR EXISTS (SELECT 1 FROM course c WHERE c.user_id = d.user_id)
               OR EXISTS (SELECT 1 FROM review r WHERE r.user_id = d.user_id)
               OR EXISTS (SELECT 1 FROM community_post p WHERE p.user_id = d.user_id) AS acted
    FROM user_device d
    JOIN app_user u ON u.id = d.user_id
    LEFT JOIN (SELECT install_id, count(DISTINCT day) AS view_days FROM entity_view GROUP BY install_id) v
           ON v.install_id = d.install_uuid
)
SELECT day,
       count(*)                                          AS installs,
       count(*) FILTER (WHERE view_days > 0 OR acted)    AS active_installs,
       count(*) FILTER (WHERE view_days >= 2)            AS returning_installs
FROM steps
GROUP BY day;

CREATE VIEW analytics.funnel_by_install_day AS
WITH steps AS (
    SELECT (d.created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
           EXISTS (SELECT 1 FROM entity_view e WHERE e.install_id = d.install_uuid) AS viewed,
           u.registered_at IS NOT NULL AS signed_in,
           EXISTS (SELECT 1 FROM saved_content s WHERE s.user_id = d.user_id)
               OR EXISTS (SELECT 1 FROM saved_place s WHERE s.user_id = d.user_id) AS saved,
           EXISTS (SELECT 1 FROM course c WHERE c.user_id = d.user_id) AS course_created,
           EXISTS (SELECT 1 FROM review r WHERE r.user_id = d.user_id)
               OR EXISTS (SELECT 1 FROM community_post p WHERE p.user_id = d.user_id) AS posted
    FROM user_device d
    JOIN app_user u ON u.id = d.user_id
)
SELECT day,
       count(*)                                 AS installs,
       count(*) FILTER (WHERE viewed)           AS viewed,
       count(*) FILTER (WHERE signed_in)        AS signed_in,
       count(*) FILTER (WHERE saved)            AS saved,
       count(*) FILTER (WHERE course_created)   AS course_created,
       count(*) FILTER (WHERE posted)           AS posted
FROM steps
GROUP BY day;

-- 롤링 재방문 — 설치일 + N 일 이후(그날 포함)에 이 설치로 상세를 다시 봤는가. 조회 기록은 90 일 보관이다.
CREATE VIEW analytics.retention_by_install_day AS
WITH steps AS (
    SELECT (d.created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
           v.last_view_day
    FROM user_device d
    LEFT JOIN (SELECT install_id, max(day) AS last_view_day FROM entity_view GROUP BY install_id) v
           ON v.install_id = d.install_uuid
)
SELECT day,
       count(*)                                                   AS installs,
       count(*) FILTER (WHERE last_view_day >= day + 1)           AS returned_d1,
       count(*) FILTER (WHERE last_view_day >= day + 7)           AS returned_d7,
       count(*) FILTER (WHERE last_view_day >= day + 30)          AS returned_d30
FROM steps
GROUP BY day;

-- 검색어는 원래 기기·회원과 잇지 않고 저장된다(V28, 90 일 보관).
CREATE VIEW analytics.daily_search AS
SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       count(*)                                     AS searches,
       count(*) FILTER (WHERE result_count = 0)     AS zero_result_searches
FROM search_log
GROUP BY 1;

CREATE VIEW analytics.search_terms AS
SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       query,
       count(*)                                     AS searches,
       count(*) FILTER (WHERE result_count = 0)     AS zero_result_searches
FROM search_log
GROUP BY 1, 2;
