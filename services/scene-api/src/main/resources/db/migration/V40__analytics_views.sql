-- 분석용 뷰(ADR 0027, docs/project/plans/metabase-prd.md §3-4). Metabase 의 metabase_ro 는 이 스키마만 읽는다.
-- 뷰가 곧 공개 범위다 — 개인을 가리키는 칸(사용자·설치 ID, 이메일·이름·소셜 ID, 토큰, 글 본문·제목, IP)을 내지 않는다.
-- 날짜(day)는 한국 시간 날짜. 뷰는 주인(app_migrate) 권한으로 원본 표를 읽으므로 metabase_ro 에 원본 표 권한이 필요 없다.
-- 스키마는 운영에서 bootstrap 이 먼저 만들고(권한을 걸려고), 로컬·시험에서는 여기서 만든다.
CREATE SCHEMA IF NOT EXISTS analytics;

CREATE VIEW analytics.contents AS
SELECT c.id                       AS content_id,
       ko.title                   AS title_ko,
       en.title                   AS title_en,
       c.category,
       c.popularity_score,
       c.hidden_at IS NOT NULL    AS hidden
FROM content c
LEFT JOIN content_i18n ko ON ko.content_id = c.id AND ko.lang = 'ko'
LEFT JOIN content_i18n en ON en.content_id = c.id AND en.lang = 'en';

CREATE VIEW analytics.places AS
SELECT p.id                       AS place_id,
       ko.name                    AS name_ko,
       en.name                    AS name_en,
       p.popularity_score,
       p.hidden_at IS NOT NULL    AS hidden
FROM place p
LEFT JOIN place_i18n ko ON ko.place_id = p.id AND ko.lang = 'ko'
LEFT JOIN place_i18n en ON en.place_id = p.id AND en.lang = 'en';

CREATE VIEW analytics.daily_new_installs AS
SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       count(*)                                     AS installs
FROM user_device
GROUP BY 1;

CREATE VIEW analytics.daily_new_members AS
SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       provider,
       count(*)                                     AS members
FROM user_identity
GROUP BY 1, 2;

CREATE VIEW analytics.daily_active_viewers AS
SELECT day,
       count(DISTINCT install_id) AS viewers
FROM entity_view
GROUP BY day;

CREATE VIEW analytics.daily_entity_views AS
SELECT s.day,
       s.entity_type,
       s.entity_id,
       CASE s.entity_type
           WHEN 'content' THEN (SELECT title FROM content_i18n WHERE content_id = s.entity_id AND lang = 'ko')
           WHEN 'place'   THEN (SELECT name  FROM place_i18n   WHERE place_id   = s.entity_id AND lang = 'ko')
           -- 편의시설은 한국어 이름을 본체(poi.name)에 둔다 — poi_i18n 은 번역만 있다.
           WHEN 'poi'     THEN (SELECT name  FROM poi          WHERE id         = s.entity_id)
       END                                AS name_ko,
       CASE s.entity_type
           WHEN 'content' THEN (SELECT title FROM content_i18n WHERE content_id = s.entity_id AND lang = 'en')
           WHEN 'place'   THEN (SELECT name  FROM place_i18n   WHERE place_id   = s.entity_id AND lang = 'en')
           WHEN 'poi'     THEN (SELECT name  FROM poi_i18n     WHERE poi_id     = s.entity_id AND lang = 'en')
       END                                AS name_en,
       s.viewers
FROM (
    SELECT day, entity_type, entity_id, sum(viewers)::bigint AS viewers
    FROM entity_daily_stats
    GROUP BY day, entity_type, entity_id
) s;

-- 지금 남아 있는 행 기준이다 — 장바구니에서 뺀 것, 지운 글처럼 행이 사라진 것은 세지 않는다.
-- save_content 는 찜, save_place 는 장바구니에 촬영지 담기(옛 cart_item 은 V8 에서 지워졌다).
CREATE VIEW analytics.daily_actions AS
SELECT day, action, count(*) AS actions
FROM (
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day, 'save_content' AS action FROM saved_content
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'save_place'    FROM saved_place
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'course_create' FROM course
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'review_create' FROM review
    UNION ALL
    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date, 'post_create'   FROM community_post
) a
GROUP BY day, action;

-- 챗봇 처리 기록(90 일 보관). 대화 내용은 원본에도 없다.
CREATE VIEW analytics.daily_chat AS
SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       lang,
       outcome,
       count(*)                                     AS turns,
       round(avg(took_ms))::integer                 AS avg_took_ms
FROM guide_turn
GROUP BY 1, 2, 3;
