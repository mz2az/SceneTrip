-- 코스마켓 — 표 넷.
--
-- 출처: MZ2AZ-199 "코스 도메인 스키마 (DBML)" §3.
--
-- ── 왜 course 를 참조하지 않고 값을 복사하는가 ───────────────────────────────
--
-- 올린 코스는 그 시점의 순서와 머무는 시간을 통째로 뜬 **사본**이다. 원본을 고쳐도
-- 마켓의 것은 그대로 남아야 한다 — 남이 담아 간 코스가 어느 날 갑자기 바뀌면 안 되기
-- 때문이다. 그래서 market_course_item 이 course_item 을 참조하지 않는다.
--
-- 사본이라 고칠 방법이 없다. 바꾸려면 내렸다가 다시 올린다.

CREATE TABLE market_course (
    id               BIGSERIAL PRIMARY KEY,
    -- 가입 사용자만 올릴 수 있다. 비회원이 올린 뒤 앱을 지우면 그 코스를 아무도
    -- 내릴 수 없기 때문이다 — 신고가 들어와도 본인이 처리하지 못한다.
    -- 이 제약은 DB 가 아니라 핸들러가 지킨다 (registered_at IS NOT NULL 검사).
    author_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- 어느 코스에서 떴는지. 원본이 지워져도 사본은 남아야 하므로 SET NULL 이다.
    source_course_id BIGINT REFERENCES course (id) ON DELETE SET NULL,
    title            TEXT NOT NULL,
    description      TEXT NOT NULL
        CONSTRAINT market_course_description_check CHECK (char_length(description) <= 200),
    -- 올릴 때 원본에서 그대로 뜬다. 날짜는 복사하지 않는다 — 언제 갈지는 담아 가는
    -- 사람이 정한다.
    day_count        INT NOT NULL
        CONSTRAINT market_course_day_count_check CHECK (day_count BETWEEN 1 AND 15),
    like_count       INT NOT NULL DEFAULT 0,
    save_count       INT NOT NULL DEFAULT 0,
    published_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    unpublished_at   TIMESTAMPTZ
);

COMMENT ON TABLE market_course IS
    '올린 시점의 사본. course 를 참조하지 않고 값을 복사해 둔다';
COMMENT ON COLUMN market_course.unpublished_at IS
    '내리면 시각이 찍힌다. 행을 지우지 않는 이유는 이미 담아 간 사람의 course.source_market_course_id 가 이 행을 가리키고 있어서다 — 지우면 그 사람 화면에서 출처가 사라진다';
COMMENT ON COLUMN market_course.like_count IS
    'market_like 행 수의 사본. 목록 정렬 기준이라 화면을 열 때마다 세면 코스 수만큼 집계가 돈다. 토글과 같은 트랜잭션에서 갱신한다';

-- 목록 정렬 — 담기순·좋아요순.
--
-- 내려간 코스는 목록에 나오지 않으므로 부분 인덱스로 아예 뺀다. 인덱스가 작아지고,
-- 내리기가 잦아져도 목록 질의는 느려지지 않는다.
--
-- id DESC 를 뒤에 붙이는 것은 동점 순서를 고정해 페이지네이션이 흔들리지 않게 하려는
-- 것이다 (V2 의 popularity 인덱스와 같은 이유).
CREATE INDEX market_course_saves_idx ON market_course (save_count DESC, id DESC)
    WHERE unpublished_at IS NULL;
CREATE INDEX market_course_likes_idx ON market_course (like_count DESC, id DESC)
    WHERE unpublished_at IS NULL;

-- 내가 올린 것 목록.
CREATE INDEX market_course_author_idx ON market_course (author_id);

-- 두 표가 서로를 가리켜 V9 에서 걸지 못했던 FK. market_course 가 이제 있다.
ALTER TABLE course
    ADD CONSTRAINT course_source_market_course_id_fkey
    FOREIGN KEY (source_market_course_id) REFERENCES market_course (id) ON DELETE SET NULL;

-- ───────────── 사본 아이템 ─────────────

CREATE TABLE market_course_item (
    id               BIGSERIAL PRIMARY KEY,
    market_course_id BIGINT NOT NULL REFERENCES market_course (id) ON DELETE CASCADE,
    day_no           INT NOT NULL,
    -- NOT NULL 이다 — 직접 찍은 핀은 올릴 때 뺀다.
    --
    -- 이유가 둘이다. 개인 숙소 위치가 남에게 그대로 공개되면 안 되고, custom_pin 은
    -- course_id 로 코스에 매달려 있어 올린 사람이 코스를 지우면 함께 사라진다 —
    -- 여기서 참조하면 어느 날 끊어진 참조가 된다. 값을 복사해 박는 방법도 있지만
    -- 그러면 첫 번째 이유에 걸린다.
    place_id         BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    sort_order       INT NOT NULL,
    dwell_min        INT NOT NULL,
    source_content_id BIGINT REFERENCES content (id) ON DELETE SET NULL,

    -- 지연 검사가 필요 없다. 사본은 올릴 때 한 번 쓰고 다시 정렬하지 않는다 —
    -- 순서를 바꾸려면 내렸다가 다시 올려 새 사본을 만든다. course_item 쪽이
    -- DEFERRABLE 인 것과 갈리는 지점이다.
    CONSTRAINT market_course_item_order_uk UNIQUE (market_course_id, day_no, sort_order)
);

COMMENT ON TABLE market_course_item IS
    '장소 정보 자체는 복사하지 않고 place 를 계속 참조한다. 이름이나 주소가 나중에 고쳐지면 마켓에서도 고쳐진 값이 보이는 편이 맞다 — 사본으로 굳혀야 하는 것은 장소가 아니라 순서와 머무는 시간이다';
COMMENT ON COLUMN market_course_item.source_content_id IS
    '함께 복사한다. 없으면 담아 간 코스가 항목마다 「이 장소가 어느 작품 때문에 담겼는지」를 잃는다 — market_course_content 는 코스 단위 태그라 거기까지 말하지 못한다';

-- ───────────── 작품 태그 ─────────────

CREATE TABLE market_course_content (
    market_course_id BIGINT NOT NULL REFERENCES market_course (id) ON DELETE CASCADE,
    content_id       BIGINT NOT NULL REFERENCES content (id) ON DELETE CASCADE,
    PRIMARY KEY (market_course_id, content_id)
);

COMMENT ON TABLE market_course_content IS
    '담긴 장소들의 place_content 로 계산할 수도 있지만 값으로 굳혀 둔다. 사본이라 나중에 장소-작품 연결이 늘어도 올릴 때의 태그가 유지돼야 하고, 작품 이름 검색이 이 표만 읽으면 끝나기 때문이다';

-- 작품 이름으로 코스 찾기 — 마켓 검색이 타는 인덱스.
CREATE INDEX market_course_content_content_idx ON market_course_content (content_id);

-- ───────────── 좋아요 ─────────────

CREATE TABLE market_like (
    user_id          UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    market_course_id BIGINT NOT NULL REFERENCES market_course (id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, market_course_id)
);

COMMENT ON TABLE market_like IS
    '누른 상태를 토글할 수 있어야 해서 행으로 둔다. 개수는 market_course.like_count. 비회원은 누를 수 없어 가입 사용자 행만 쌓이고, 그래서 계정 병합이 이 표를 훑을 일이 없다';

CREATE INDEX market_like_course_idx ON market_like (market_course_id);
