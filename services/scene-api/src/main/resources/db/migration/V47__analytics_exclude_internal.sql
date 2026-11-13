-- 분석 뷰에서 팀원 계정을 뺀다(MZ2AZ-563, docs/project/plans/metabase-prd.md §3-7). 멘토 계정은 일반 사용자로 센다.
-- 이메일은 평문으로 두지 않는다 — 소문자로 바꾼 주소의 SHA-256(소문자 16 진수)만 둔다. 더할 때는 새 마이그레이션으로 행을 더한다.
-- 해시 표와 내부 계정 뷰는 public 에 둔다 — metabase_ro 는 analytics 만 읽으므로 이 둘을 못 본다.
-- 바뀌는 analytics 뷰는 칸 이름·순서·형이 그대로다(CREATE OR REPLACE) — Metabase 질문이 깨지지 않는다.

CREATE TABLE analytics_internal_email (
    email_sha256 TEXT PRIMARY KEY
        CONSTRAINT analytics_internal_email_sha256_check CHECK (email_sha256 ~ '^[0-9a-f]{64}$'),
    note         TEXT
);

COMMENT ON TABLE analytics_internal_email IS
    '분석 수치에서 뺄 팀원 로그인 이메일의 해시. sha256(lower(email)) 의 소문자 16 진수';

INSERT INTO analytics_internal_email (email_sha256, note) VALUES
    ('c267f790867922ee30ece8aa508a22103e840dec917a9eca792e55b4e6effcae', '팀원'),
    ('a3e3cedddd5e9bcf6a866f5eafda9fdee5a6ee57189f724681ae803f212bef23', '팀원'),
    ('339d67a441b28789a90abd8fae0be470ae6e535db480df9da4046ad448be9a9a', '팀원');

-- 내부 계정 — 그 이메일로 로그인한 계정, 그리고 그 계정으로 합쳐진 비회원 계정.
CREATE VIEW analytics_internal_user AS
WITH team AS (
    SELECT DISTINCT i.user_id
    FROM user_identity i
    JOIN analytics_internal_email e
      ON e.email_sha256 = encode(sha256(convert_to(lower(i.email), 'UTF8')), 'hex')
)
SELECT user_id FROM team
UNION
SELECT u.id FROM app_user u JOIN team t ON u.merged_into = t.user_id;

COMMENT ON VIEW analytics_internal_user IS '분석 수치에서 빼는 계정(MZ2AZ-563). analytics 뷰가 안에서만 쓴다';

CREATE OR REPLACE VIEW analytics.daily_new_installs AS
SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       count(*)                                     AS installs
FROM user_device
WHERE user_id NOT IN (SELECT user_id FROM analytics_internal_user)
GROUP BY 1;

CREATE OR REPLACE VIEW analytics.daily_new_members AS
SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       provider,
       count(*)                                     AS members
FROM user_identity
WHERE user_id NOT IN (SELECT user_id FROM analytics_internal_user)
GROUP BY 1, 2;

CREATE OR REPLACE VIEW analytics.daily_active_viewers AS
SELECT day,
       count(DISTINCT install_id) AS viewers
FROM entity_view e
WHERE NOT EXISTS (SELECT 1 FROM user_device d JOIN analytics_internal_user i ON i.user_id = d.user_id
                  WHERE d.install_uuid = e.install_id)
  AND (e.user_id IS NULL OR e.user_id NOT IN (SELECT user_id FROM analytics_internal_user))
GROUP BY day;

CREATE OR REPLACE VIEW analytics.daily_actions AS
SELECT day, action, count(*) AS actions
FROM (
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day, 'save_content' AS action, user_id FROM saved_content
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'save_place',    user_id FROM saved_place
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'course_create', user_id FROM course
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'review_create', user_id FROM review
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'post_create',   user_id FROM community_post
) a
WHERE a.user_id IS NULL OR a.user_id NOT IN (SELECT user_id FROM analytics_internal_user)
GROUP BY day, action;

CREATE OR REPLACE VIEW analytics.daily_install_quality AS
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
    WHERE d.user_id NOT IN (SELECT user_id FROM analytics_internal_user)
)
SELECT day,
       count(*)                                          AS installs,
       count(*) FILTER (WHERE view_days > 0 OR acted)    AS active_installs,
       count(*) FILTER (WHERE view_days >= 2)            AS returning_installs
FROM steps
GROUP BY day;

CREATE OR REPLACE VIEW analytics.funnel_by_install_day AS
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
    WHERE d.user_id NOT IN (SELECT user_id FROM analytics_internal_user)
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

CREATE OR REPLACE VIEW analytics.retention_by_install_day AS
WITH steps AS (
    SELECT (d.created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
           v.last_view_day
    FROM user_device d
    LEFT JOIN (SELECT install_id, max(day) AS last_view_day FROM entity_view GROUP BY install_id) v
           ON v.install_id = d.install_uuid
    WHERE d.user_id NOT IN (SELECT user_id FROM analytics_internal_user)
)
SELECT day,
       count(*)                                                   AS installs,
       count(*) FILTER (WHERE last_view_day >= day + 1)           AS returned_d1,
       count(*) FILTER (WHERE last_view_day >= day + 7)           AS returned_d7,
       count(*) FILTER (WHERE last_view_day >= day + 30)          AS returned_d30
FROM steps
GROUP BY day;
