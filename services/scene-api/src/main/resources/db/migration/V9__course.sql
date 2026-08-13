-- 코스(경로여정) 도메인 — 표 넷.
--
-- 출처: MZ2AZ-199 "코스 도메인 스키마 (DBML)" §3. 컬럼의 뜻과 근거는 거기 있다.
-- 여기 주석은 DBML 이 표현하지 못해 손으로 붙인 것과, SQL 로 옮기며 갈린 곳만 적는다.
--
-- ── Enum 을 TEXT + CHECK 로 바꾼 것은 V2 와 같은 이유다 ──────────────────────
--
-- PostgreSQL 의 Enum 은 값을 뺄 수 없고, 추가한 값을 같은 트랜잭션에서 쓸 수 없다.
-- Flyway 가 마이그레이션을 트랜잭션으로 감싸므로 "값 추가 + 그 값으로 데이터 이관" 을
-- 한 파일에 담을 수 없게 된다. CHECK 는 DROP/ADD 로 갈아 끼우면 끝난다.
--
-- 네 종류(course_status·course_origin·course_pace·pin_category) 모두 쓰는 자리가
-- 하나뿐이라 DOMAIN 으로 묶지 않고 컬럼에 직접 건다.
--
-- ── 일차 표가 없다 ──────────────────────────────────────────────────────────
--
-- 일차는 course_item.day_no 컬럼이고, 며칠짜리인지는 course.day_count 가 든다.
-- 표가 필요했던 유일한 이유는 "빈 일차가 실재한다" 였는데, 기간을 day_count 가
-- 들고 있으면 빈 일차는 그저 항목이 없는 번호가 되어 표 없이도 표현된다.
--
-- 대가는 day_no 가 day_count 를 넘지 않는다는 것을 FK 로 못 막는다는 점이다.
-- 3일 코스에 4일차 항목을 넣어도 구조상 통과한다. 트리거를 걸지 애플리케이션이
-- 지킬지는 아직 정하지 않았다 (설계 문서 §8) — 지금은 애플리케이션이 지키고,
-- 통합 테스트가 그것을 확인한다.

CREATE TABLE course (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- 비우면 서버가 「이름 없는 코스」로 채운다. NULL 을 허용하지 않는 이유는 이름이
    -- 없는 코스를 화면마다 따로 처리하지 않기 위해서다 — 채우는 자리를 서버 한 곳으로
    -- 모은다.
    title      TEXT NOT NULL,
    start_date DATE,
    day_count  INT NOT NULL
        CONSTRAINT course_day_count_check CHECK (day_count BETWEEN 1 AND 15),
    status     TEXT NOT NULL DEFAULT 'upcoming'
        CONSTRAINT course_status_check CHECK (status IN ('upcoming', 'active')),
    -- 진행 중일 때 지금 몇 일차인지. 아래 CHECK 가 두 가지를 함께 지킨다 —
    -- 예정 코스에 값이 남아 있지 않을 것, 그리고 기간을 벗어나지 않을 것.
    -- 같은 행의 다른 컬럼은 CHECK 가 볼 수 있어서 day_count 와 견줄 수 있다.
    current_day_no INT
        CONSTRAINT course_current_day_no_check
        CHECK (
            (status = 'active' AND current_day_no BETWEEN 1 AND day_count)
            OR current_day_no IS NULL
        ),
    origin     TEXT NOT NULL
        CONSTRAINT course_origin_check CHECK (origin IN ('ai', 'self', 'market')),
    -- 「빡빡하게 / 널널하게」. AI 로 짠 코스만 값이 있다. 항목은 확정됐지만 일정을
    -- 어떻게 바꾸는지는 아직 없어 지금은 두 값의 결과가 같다 (3주차 회의 Open Issue 3).
    -- 그래도 받아 두는 이유는, 로직이 정해지면 지난 코스도 다시 계산할 수 있어서다.
    pace       TEXT
        CONSTRAINT course_pace_check CHECK (pace IN ('tight', 'loose')),
    -- 마켓에서 담아 왔다면 그 원본. FK 는 V10 에서 건다 — market_course 가 아직 없다.
    -- 두 표가 서로를 가리켜서(course.source_market_course_id ↔
    -- market_course.source_course_id) 어느 쪽을 먼저 만들어도 한쪽 FK 는 뒤로 밀린다.
    source_market_course_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE course IS
    '시작 시각 컬럼은 없다 — 3주차 회의에서 뺐다. 돌아오는 날도 두지 않는다(start_date + day_count - 1 로 나오는 파생값). 상태에 「완료」가 없는 것도 목업 그대로다';
COMMENT ON COLUMN course.day_count IS '기간의 유일한 출처. 일차 표가 없으므로 어긋날 상대가 없다';
COMMENT ON COLUMN course.start_date IS '떠나는 날. 안 정해도 코스가 성립한다';
COMMENT ON COLUMN course.origin IS 'AI 가 짠 코스와 직접 짠 코스의 완주율 비교용. 만든 시점에만 남길 수 있다';

-- 홈 목록 — 진행 중을 위, 예정을 아래, 각각 최신순.
CREATE INDEX course_user_status_created_idx ON course (user_id, status, created_at DESC);

-- 마켓 목록의 「담김」 표시 판정.
CREATE INDEX course_source_market_idx ON course (source_market_course_id);

-- ───────────── 직접 찍은 핀 ─────────────
--
-- course_item 보다 먼저 만든다 — 그쪽이 이 표를 참조한다.

CREATE TABLE custom_pin (
    id         BIGSERIAL PRIMARY KEY,
    -- 주인이 사용자가 아니라 코스다. 사용자로 잡으면 핀 주인과 코스 주인이 어긋나는
    -- 상태를 DB 가 막지 못한다 — 남의 핀 id 를 내 코스 항목에 넣어도 FK 는 통과한다.
    -- 코스에 매달아 두면 그런 상태가 아예 생기지 않고, 핀의 주인은 course.user_id 를
    -- 한 번 타고 가면 나온다.
    course_id  BIGINT NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    category   TEXT NOT NULL
        CONSTRAINT custom_pin_category_check
        CHECK (category IN ('lodging', 'food', 'attraction', 'street', 'building')),
    geom       GEOGRAPHY(Point, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE custom_pin IS
    '등록되지 않은 숙소처럼 우리 데이터에 없는 곳을 지도에서 눌러 찍은 핀. place 에 섞지 않는 이유는 둘이다 — 남이 찍은 숙소가 검색 결과와 지도 핀에 뜨면 안 되고, place 는 dedupe 와 다국어 표기를 거친 값인데 이쪽은 아무 검증도 거치지 않는다';
COMMENT ON COLUMN custom_pin.course_id IS
    '일차가 아니라 코스에 매단다. 2박 3일이면 같은 숙소가 1일차 끝과 2일차 끝에 들어가 핀 하나를 course_item 여럿이 가리킨다';

CREATE INDEX custom_pin_course_idx ON custom_pin (course_id);

-- ───────────── 코스 아이템 ─────────────

CREATE TABLE course_item (
    id            BIGSERIAL PRIMARY KEY,
    course_id     BIGINT NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    day_no        INT NOT NULL
        CONSTRAINT course_item_day_no_check CHECK (day_no >= 1),
    place_id      BIGINT REFERENCES place (id) ON DELETE CASCADE,
    custom_pin_id BIGINT REFERENCES custom_pin (id) ON DELETE CASCADE,
    sort_order    INT NOT NULL,
    dwell_min     INT NOT NULL DEFAULT 45
        CONSTRAINT course_item_dwell_min_check CHECK (dwell_min BETWEEN 15 AND 180),
    source_content_id BIGINT REFERENCES content (id) ON DELETE SET NULL,
    visited_at    TIMESTAMPTZ,

    -- 둘 중 정확히 하나만 채운다. DBML 에 CHECK 문법이 없어 설계 문서가 "DDL 을 뽑은 뒤
    -- 직접 붙이라" 고 적어 둔 제약이다.
    --
    -- 한 컬럼에 몰지 않은 이유는 두 대상의 주인이 달라서다. place 는 우리가 수집해
    -- 모두가 함께 쓰는 데이터이고 custom_pin 은 그 사람만 보는 개인 데이터다. 한 컬럼에
    -- 섞으면 FK 를 아예 못 걸어 지워진 장소를 가리키는 행이 조용히 남는다.
    CONSTRAINT course_item_target_check
        CHECK ((place_id IS NULL) <> (custom_pin_id IS NULL))
);

COMMENT ON COLUMN course_item.day_no IS '몇 일차. 1 부터 course.day_count 까지 — 이 상한은 FK 로 못 막아 애플리케이션이 지킨다';
COMMENT ON COLUMN course_item.sort_order IS '10,20,30 처럼 띄워 넣어 중간 삽입에 대비한다';
COMMENT ON COLUMN course_item.source_content_id IS
    '어떤 작품 보다가 담았는지. 장바구니에서 넘어올 때 saved_place.source_content_id 를 그대로 옮긴다 — 이것이 없으면 같은 N서울타워가 어느 작품에서 비롯됐는지 구별하지 못한다';
COMMENT ON COLUMN course_item.visited_at IS '여행 중 방문 체크. NULL 이면 아직 안 갔다';

-- 지연 검사가 핵심이다.
--
-- sort_order 를 10,20,30 으로 띄워 넣으면 하나를 옮길 때는 그 행만 고치면 되어 값이
-- 겹치지 않는다. 그런데 드래그로 일차를 통째로 다시 매기면 바꾸는 도중에 값이 잠깐
-- 겹친다. DEFERRABLE INITIALLY DEFERRED 면 PostgreSQL 이 트랜잭션 끝에 한 번만
-- 검사하므로 그 중간 상태를 문제 삼지 않는다.
ALTER TABLE course_item
    ADD CONSTRAINT course_item_order_uk
    UNIQUE (course_id, day_no, sort_order) DEFERRABLE INITIALLY DEFERRED;

-- 장소 → 그 장소가 든 코스 역방향.
CREATE INDEX course_item_place_idx ON course_item (place_id);

-- ───────────── 작품 찜 ─────────────

CREATE TABLE saved_content (
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    content_id BIGINT NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, content_id)
);

COMMENT ON TABLE saved_content IS
    '작품 찜(하트). 장소 장바구니(saved_place)와 별개 표다 — 합치면 대상 종류를 나타내는 칸이 생겨 FK 를 못 걸고, 장바구니에만 있는 source_content_id 때문에 컬럼 구성도 다르다';

-- 작품별 찜 수. 「어떤 작품을 좋아하나요?」 화면이 내가 찜한 것을 위에 모으고
-- 나머지를 찜한 사람이 많은 순서로 놓는다.
CREATE INDEX saved_content_content_idx ON saved_content (content_id);
