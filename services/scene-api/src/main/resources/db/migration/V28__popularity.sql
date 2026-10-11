-- 인기도 — 초기값과 사용자 활동을 섞어 매일 밤 다시 계산한다 (MZ2AZ-407, docs/project/plans/popularity.md).
--
-- 지금까지 popularity_score 는 적재가 넣는 임의값(모두 50)이었다. 이제 두 칸으로 나눈다:
--   popularity_seed  — 출발점. 작품은 데이터 담당자 값(오기 전에는 50), 촬영지는 계산(아래 함수 1 단계)
--   popularity_score — 최종값. recompute_popularity() 가 덮어쓴다. 앱 정렬은 이것을 본다
--
-- user_event 는 지운다. V2 에서 「impression·position 은 소급할 수 없다」 며 미리 만들었지만 쓰는 코드·API·앱이
-- 끝내 없었다(0 행). 이벤트 종류도 지금 서비스와 맞지 않는다 — 편의시설·코스·후기가 없고 기기 칸이 없다. 하트·담기·
-- 코스·리뷰는 이미 제 표가 있으니, 새로 받는 것은 조회와 검색 둘뿐이다.

DROP TABLE user_event;

-- ── 칸 ───────────────────────────────────────────────────────────────────────

ALTER TABLE content ADD COLUMN popularity_seed NUMERIC NOT NULL DEFAULT 50;
ALTER TABLE place   ADD COLUMN popularity_seed NUMERIC NOT NULL DEFAULT 50;

COMMENT ON COLUMN content.popularity_seed IS '인기도 출발점 0~100. 적재가 넣는다(데이터 담당자 값, 없으면 50)';
COMMENT ON COLUMN content.popularity_score IS '최종 인기도. recompute_popularity() 가 매일 밤 덮어쓴다';
COMMENT ON COLUMN place.popularity_seed IS '인기도 출발점. recompute_popularity() 가 작품 출발점 × 작품 안 상대 산정 점수의 최댓값으로 채운다';
COMMENT ON COLUMN place.popularity_score IS '최종 인기도. recompute_popularity() 가 매일 밤 덮어쓴다';

-- 장소×작품 산정 점수(CSV place_popularity_score). 작품끼리는 비교할 수 없는 값이라 **작품 안에서만** 쓴다 —
-- 작품 상세의 촬영지 순서, 그리고 촬영지 출발점의 재료(작품 안 최고점으로 나눈 상대값).
ALTER TABLE place_content ADD COLUMN popularity_score NUMERIC;

COMMENT ON COLUMN place_content.popularity_score IS 'CSV 산정 점수. 작품 안에서만 비교한다(MZ2AZ-339)';

-- 편의시설은 출발점이 없다(0) — 활동으로만 오른다. 90 만 행이지만 NOT NULL DEFAULT 상수는 표를 다시 쓰지 않는다.
ALTER TABLE poi ADD COLUMN popularity_score NUMERIC NOT NULL DEFAULT 0;

COMMENT ON COLUMN poi.popularity_score IS '최종 인기도. 출발점 없이 활동만 — recompute_popularity()';

-- 매일 밤 「지난번에 올랐던 편의시설」 을 90 만 행 전수 없이 찾는다. 대부분 0 이라 작다.
CREATE INDEX poi_popular_idx ON poi (id) WHERE popularity_score <> 0;

-- ── 원본 기록 (90 일 뒤 지운다) ─────────────────────────────────────────────

-- 상세 조회. 앱이 상세 화면을 열 때 POST /events 로 보낸다 — 서버는 상세 API 호출을 세지 않는다(앱이 상세 화면 밖에서도 부른다).
-- 같은 기기·같은 대상·같은 날(한국 시간)은 한 번 — 두 번째는 ON CONFLICT DO NOTHING.
-- 대상에 FK 를 걸지 않는다 — 세 표를 가리킨다. 없는 id 는 받을 때 거른다(EXISTS).
CREATE TABLE entity_view (
    id          BIGSERIAL PRIMARY KEY,
    entity_type TEXT NOT NULL
        CONSTRAINT entity_view_entity_type_check CHECK (entity_type IN ('content', 'place', 'poi')),
    entity_id   BIGINT NOT NULL,
    install_id  UUID NOT NULL,
    user_id     UUID REFERENCES app_user (id) ON DELETE SET NULL,
    lang        lang_code NOT NULL,
    day         DATE NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT entity_view_once_a_day UNIQUE (install_id, entity_type, entity_id, day)
);

CREATE INDEX entity_view_day_idx ON entity_view (day);

COMMENT ON TABLE entity_view IS '상세 조회 원본. 기기 id 는 하루 중복을 막는 데만 쓰고 90 일 뒤 지운다';

-- 확정한 검색. 기기·사용자와 잇지 않는다 — 결과 0 건 목록(다음 수집 대상)을 뽑는 데 필요 없다.
CREATE TABLE search_log (
    id           BIGSERIAL PRIMARY KEY,
    query        TEXT NOT NULL CHECK (char_length(query) BETWEEN 1 AND 100),
    result_count INT NOT NULL CHECK (result_count >= 0),
    lang         lang_code NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX search_log_created_idx ON search_log (created_at);

COMMENT ON TABLE search_log IS '확정한 검색어와 결과 수. 기기와 잇지 않는다. 90 일 뒤 지운다';

-- ── 하루 집계 (계속 남긴다) ──────────────────────────────────────────────────

CREATE TABLE entity_daily_stats (
    entity_type TEXT NOT NULL,
    entity_id   BIGINT NOT NULL,
    day         DATE NOT NULL,
    lang        lang_code NOT NULL,
    viewers     INT NOT NULL,
    PRIMARY KEY (entity_type, entity_id, day, lang)
);

COMMENT ON TABLE entity_daily_stats IS '그날 그 언어로 상세를 연 기기 수. 원본을 지워도 남는다';

-- ── 계산 ─────────────────────────────────────────────────────────────────────
--
-- 최종 = w × 출발점 + (1 − w) × 활동 순위,  w = k / (k + 활동 점수).
--
-- 활동 점수는 지난 window_days 일의 재료 × 무게의 합, 활동 순위는 같은 종류 전체(활동 없는 것 포함) 중 이 점수
-- 이하인 비율 × 100(가장 많은 것이 100). 활동이 없는 것은 w = 1 이라 출발점 그대로다. 계획 §4·§5.
--
-- 한 곳(이 함수)에만 둔다 — 서버의 매일 밤 작업, `just popularity-recompute`, 적재(`just seed`) 끝이 모두 이것을 부른다.
-- 무게·k·기간의 서버 값은 application.yaml(scenetrip.popularity) 이 넘긴다. 기본값은 로컬에서 인자 없이 부를 때 쓴다.
-- 여러 파드가 동시에 불러도 한 번만 돈다(트랜잭션 advisory lock) — 못 잡으면 {"skipped": true}.
CREATE FUNCTION recompute_popularity(
    k              NUMERIC DEFAULT 200,
    window_days    INT     DEFAULT 30,
    retention_days INT     DEFAULT 90,
    w_view         NUMERIC DEFAULT 1,
    w_heart        NUMERIC DEFAULT 3,
    w_cart         NUMERIC DEFAULT 3,
    w_course       NUMERIC DEFAULT 5,
    w_review       NUMERIC DEFAULT 5,
    w_visit        NUMERIC DEFAULT 8
) RETURNS JSONB
LANGUAGE plpgsql AS $$
DECLARE
    today   DATE := (now() AT TIME ZONE 'Asia/Seoul')::DATE;
    since   DATE := today - window_days;
    since_t TIMESTAMPTZ := (since::TIMESTAMP AT TIME ZONE 'Asia/Seoul');
    n_content INT;
    n_place   INT;
    n_poi     INT;
    n_purged_views    INT;
    n_purged_searches INT;
BEGIN
    IF NOT pg_try_advisory_xact_lock(hashtext('recompute_popularity')) THEN
        RETURN jsonb_build_object('skipped', true);
    END IF;

    -- 1. 하루 집계 — 기간 전체를 다시 센다(늦게 들어온 줄도 잡는다). 원본을 지운 날은 건드리지 않는다.
    DELETE FROM entity_daily_stats WHERE day >= since;
    INSERT INTO entity_daily_stats (entity_type, entity_id, day, lang, viewers)
    SELECT entity_type, entity_id, day, lang, count(*)
    FROM entity_view
    WHERE day >= since
    GROUP BY entity_type, entity_id, day, lang;

    -- 2. 촬영지 출발점 — 작품마다 「산정 점수 ÷ 그 작품의 최고 산정 점수」, 거기에 작품 출발점을 곱한 것의 최댓값.
    --    산정 점수가 없는 짝(옛 CSV)은 상대값 1 — 예전처럼 작품 점수를 물려받는다.
    UPDATE place p
    SET popularity_seed = s.seed
    FROM (
        SELECT r.place_id, max(c.popularity_seed * COALESCE(r.rel, 1)) AS seed
        FROM (
            SELECT pc.place_id, pc.content_id,
                   pc.popularity_score / NULLIF(max(pc.popularity_score) OVER (PARTITION BY pc.content_id), 0) AS rel
            FROM place_content pc
        ) r
        JOIN content c ON c.id = r.content_id
        GROUP BY r.place_id
    ) s
    WHERE p.id = s.place_id AND p.popularity_seed IS DISTINCT FROM s.seed;

    -- 3. 활동 점수. 코스는 「기간 안에 저장한 코스에 들어 있는가」 — course_item 에 담은 시각이 없다(계획 §4).
    DROP TABLE IF EXISTS pop_activity, pop_blend;
    CREATE TEMP TABLE pop_activity (
        entity_type TEXT,
        entity_id   BIGINT,
        points      NUMERIC
    ) ON COMMIT DROP;

    INSERT INTO pop_activity
    SELECT entity_type, entity_id, sum(points) FROM (
        -- 조회(작품·촬영지·편의시설)
        SELECT s.entity_type, s.entity_id, w_view * sum(s.viewers) AS points
        FROM entity_daily_stats s WHERE s.day >= since
        GROUP BY s.entity_type, s.entity_id

        -- 작품 하트
        UNION ALL
        SELECT 'content', sc.content_id, w_heart * count(*)
        FROM saved_content sc WHERE sc.created_at >= since_t
        GROUP BY sc.content_id

        -- 장바구니 — 촬영지에, 그리고 그 작품 때문에 담았으면 작품에도
        UNION ALL
        SELECT 'place', sp.place_id, w_cart * count(*)
        FROM saved_place sp WHERE sp.created_at >= since_t
        GROUP BY sp.place_id
        UNION ALL
        SELECT 'content', sp.source_content_id, w_cart * count(*)
        FROM saved_place sp WHERE sp.created_at >= since_t AND sp.source_content_id IS NOT NULL
        GROUP BY sp.source_content_id

        -- 코스 — 한 코스가 같은 곳을 두 번 담아도 한 번
        UNION ALL
        SELECT 'place', x.place_id, w_course * count(*) FROM (
            SELECT DISTINCT ci.course_id, ci.place_id
            FROM course_item ci JOIN course c ON c.id = ci.course_id
            WHERE c.updated_at >= since_t AND ci.place_id IS NOT NULL
        ) x GROUP BY x.place_id
        UNION ALL
        SELECT 'poi', x.poi_id, w_course * count(*) FROM (
            SELECT DISTINCT ci.course_id, ci.poi_id
            FROM course_item ci JOIN course c ON c.id = ci.course_id
            WHERE c.updated_at >= since_t AND ci.poi_id IS NOT NULL
        ) x GROUP BY x.poi_id
        UNION ALL
        SELECT 'content', x.source_content_id, w_course * count(*) FROM (
            SELECT DISTINCT ci.course_id, ci.source_content_id
            FROM course_item ci JOIN course c ON c.id = ci.course_id
            WHERE c.updated_at >= since_t AND ci.source_content_id IS NOT NULL
        ) x GROUP BY x.source_content_id

        -- 실제 방문(도장)
        UNION ALL
        SELECT 'place', ci.place_id, w_visit * count(*)
        FROM course_item ci WHERE ci.visited_at >= since_t AND ci.place_id IS NOT NULL
        GROUP BY ci.place_id
        UNION ALL
        SELECT 'poi', ci.poi_id, w_visit * count(*)
        FROM course_item ci WHERE ci.visited_at >= since_t AND ci.poi_id IS NOT NULL
        GROUP BY ci.poi_id

        -- 리뷰(내려진 것은 빼고)
        UNION ALL
        SELECT 'place', r.place_id, w_review * count(*)
        FROM review r WHERE r.created_at >= since_t AND r.removed_at IS NULL AND r.place_id IS NOT NULL
        GROUP BY r.place_id
        UNION ALL
        SELECT 'poi', r.poi_id, w_review * count(*)
        FROM review r WHERE r.created_at >= since_t AND r.removed_at IS NULL AND r.poi_id IS NOT NULL
        GROUP BY r.poi_id
    ) parts
    GROUP BY entity_type, entity_id
    HAVING sum(points) > 0;

    -- 4. 활동 순위와 w. 순위는 같은 종류 **전체**(활동이 없는 것 포함) 중 이 활동 점수 이하인 비율 × 100 이다.
    --    활동이 있는 것끼리만 세면 조회 한 번 받은 것이 꼴찌(0 근처)가 되어 출발점보다 내려간다 — 아무도 안 본 것은
    --    출발점 그대로인데. 전체로 세면 활동이 조금이라도 있으면 활동 없는 것들보다 늘 위다. 활동 없는 것은 모두 0 점으로
    --    같으니, 「전체 중 이하인 수」 = 활동 없는 수 + 활동 있는 것 중 이하인 수(cume_dist × 활동 있는 수)로 계산한다 —
    --    90 만 편의시설에 창 함수를 돌리지 않는다.
    CREATE TEMP TABLE pop_blend ON COMMIT DROP AS
    SELECT a.entity_type, a.entity_id,
           k / (k + a.points) AS w,
           100 * (t.total - t.active + t.active * a.dist::NUMERIC) / t.total AS pct
    FROM (
        SELECT entity_type, entity_id, points,
               cume_dist() OVER (PARTITION BY entity_type ORDER BY points) AS dist
        FROM pop_activity
    ) a
    JOIN (
        SELECT x.entity_type, x.total, (SELECT count(*) FROM pop_activity pa WHERE pa.entity_type = x.entity_type) AS active
        FROM (
            SELECT 'content' AS entity_type, (SELECT count(*) FROM content) AS total
            UNION ALL SELECT 'place', (SELECT count(*) FROM place)
            UNION ALL SELECT 'poi',   (SELECT count(*) FROM poi)
        ) x
    ) t ON t.entity_type = a.entity_type;

    -- 5. 최종값. 활동이 없는 것은 출발점으로 돌아간다(바뀐 것만 쓴다).
    UPDATE content t
    SET popularity_score = v.score
    FROM (
        SELECT c.id, round(COALESCE(b.w * c.popularity_seed + (1 - b.w) * b.pct, c.popularity_seed), 3) AS score
        FROM content c
        LEFT JOIN pop_blend b ON b.entity_type = 'content' AND b.entity_id = c.id
    ) v
    WHERE t.id = v.id AND t.popularity_score IS DISTINCT FROM v.score;
    GET DIAGNOSTICS n_content = ROW_COUNT;

    UPDATE place t
    SET popularity_score = v.score
    FROM (
        SELECT p.id, round(COALESCE(b.w * p.popularity_seed + (1 - b.w) * b.pct, p.popularity_seed), 3) AS score
        FROM place p
        LEFT JOIN pop_blend b ON b.entity_type = 'place' AND b.entity_id = p.id
    ) v
    WHERE t.id = v.id AND t.popularity_score IS DISTINCT FROM v.score;
    GET DIAGNOSTICS n_place = ROW_COUNT;

    -- 편의시설은 90 만 행 — 활동이 있는 것과, 지난번에 올랐다가 활동이 사라진 것(0 이 아닌 것)만 본다.
    UPDATE poi t
    SET popularity_score = v.score
    FROM (
        SELECT touched.id, round(COALESCE((1 - b.w) * b.pct, 0), 3) AS score
        FROM (
            SELECT entity_id AS id FROM pop_blend WHERE entity_type = 'poi'
            UNION
            SELECT id FROM poi WHERE popularity_score <> 0
        ) touched
        LEFT JOIN pop_blend b ON b.entity_type = 'poi' AND b.entity_id = touched.id
    ) v
    WHERE t.id = v.id AND t.popularity_score IS DISTINCT FROM v.score;
    GET DIAGNOSTICS n_poi = ROW_COUNT;

    -- 6. 원본 정리
    DELETE FROM entity_view WHERE day < today - retention_days;
    GET DIAGNOSTICS n_purged_views = ROW_COUNT;
    DELETE FROM search_log WHERE created_at < now() - make_interval(days => retention_days);
    GET DIAGNOSTICS n_purged_searches = ROW_COUNT;

    RETURN jsonb_build_object(
        'skipped', false,
        'since', since,
        'active', (SELECT jsonb_object_agg(entity_type, n) FROM
                     (SELECT entity_type, count(*) AS n FROM pop_activity GROUP BY entity_type) a),
        'updated', jsonb_build_object('content', n_content, 'place', n_place, 'poi', n_poi),
        'purged', jsonb_build_object('views', n_purged_views, 'searches', n_purged_searches)
    );
END;
$$;

COMMENT ON FUNCTION recompute_popularity IS '인기도 다시 계산(docs/project/plans/popularity.md §5). 매일 밤·just popularity-recompute·적재 끝';
