-- 사용자 표와 장소 장바구니 정리.
--
-- 출처: MZ2AZ-199 "코스 도메인 스키마 (DBML)" §7 과 MZ2AZ-111 의 2026-08-12 갱신분.
-- 여기 주석은 "왜 문서와 다르게 썼는가" 만 적는다. 컬럼의 뜻과 설계 근거는 그 두
-- 문서를 볼 것.
--
-- ── 왜 사용자 표를 지금 만드는가 ────────────────────────────────────────────
--
-- 로그인은 8/23 주차 스토리다. 그런데 코스·찜·마켓이 전부 "누구의 것인가" 를 물으므로
-- 주체를 담을 자리가 먼저 있어야 한다. 지금 없는 채로 만들면 그 다섯 테이블이 설치
-- UUID 를 직접 들게 되고, 로그인이 붙을 때 다섯 곳의 FK 타입을 함께 바꿔야 한다.
-- 행이 없는 지금 만드는 값은 거의 공짜다.
--
-- ── 왜 설치 UUID 를 기본키로 쓰지 않는가 ─────────────────────────────────────
--
-- 설치 UUID 는 사람이 아니라 그 설치본을 가리킨다. 앱을 지웠다 깔면 새로 생기므로
-- 그것이 PK 면 그 사람의 코스와 장바구니가 통째로 끊긴다. app_user.id 는 서버가
-- 만들어 끝까지 바꾸지 않고, 설치 값은 user_device 에서 갈아 끼운다.
--
-- 표를 둘로 나눈 것은 병합 때문이다. 한 사람이 폰과 태블릿을 함께 쓰면 계정 하나에
-- 설치 값 두 개가 붙어야 하는데 컬럼 하나로는 담을 수 없다.

CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    registered_at TIMESTAMPTZ,
    merged_into   UUID REFERENCES app_user (id),
    last_seen_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE app_user IS
    '비회원과 가입 사용자가 같은 표를 쓴다. 가입하면 행이 새로 생기지 않고 registered_at 만 채워진다 — id 가 안 바뀌므로 그 사람의 코스·장바구니·찜이 그대로 붙어 있다';
COMMENT ON COLUMN app_user.registered_at IS '채워지면 가입 사용자, 비어 있으면 비회원';
COMMENT ON COLUMN app_user.merged_into IS '병합돼 비워진 계정. 행을 지우지 않고 어디로 합쳐졌는지 남긴다';
COMMENT ON COLUMN app_user.last_seen_at IS '방치된 비회원 계정 정리용. 소급 수집이 안 되므로 처음부터 받는다';

-- 기본값을 두지 않는다.
--
-- 설계 문서는 UUIDv7 을 권한다 — 앞자리에 시각이 들어가 대체로 생성 순서대로 정렬되어
-- 인덱스가 잘게 쪼개지지 않는다. 그런데 uuidv7() 은 PostgreSQL 18 부터이고 우리는 17 이다
-- (platform/kubernetes/postgres/). gen_random_uuid() 를 기본값으로 걸면 v4 가 되는데,
-- 그러면 나중에 v7 로 바꿔도 이미 들어간 행은 v4 로 남아 두 세대가 섞인다.
--
-- 대신 애플리케이션이 만들어 넣는다. PG 18 로 올라가면 여기에 DEFAULT 를 붙이고
-- 애플리케이션 쪽을 지우면 된다.

CREATE TABLE user_device (
    install_uuid UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_device IS
    '이름이 device 가 아니라 install 인 이유 — 이 값은 기기가 아니라 설치본을 가리킨다. iOS 는 UserDefaults 에 두므로 앱을 지우면 사라지고, 같은 폰에 두 번 깔면 서로 다른 사람으로 보인다. 로그인하지 않은 데이터는 그 설치본에서만 유지된다';
COMMENT ON COLUMN user_device.install_uuid IS '앱이 최초 실행에 만들어 보관하는 값. X-Device-Id 헤더로 온다';

CREATE INDEX user_device_user_idx ON user_device (user_id);

-- ───────────── 장소 장바구니 ─────────────
--
-- ── 왜 옮기지 않고 다시 만드는가 ────────────────────────────────────────────
--
-- 설계 문서 §7 은 두 갈래를 준다. 기존 값을 살리려면 기기마다 계정을 만들되 그 id 를
-- 설치 UUID 와 같은 값으로 두어 값을 한 줄도 안 고치는 방법이 있고, 살릴 것이 없으면
-- 지우고 새로 만드는 방법이 있다.
--
-- **후자를 골랐다. 지금 DB 에 있는 것은 개발 데이터뿐이다.** 전자로 가면 설치 UUID 와
-- 계정 id 가 우연히 같은 행이 영구히 남아, 그 둘을 구분하라고 표를 나눈 취지가 첫
-- 데이터부터 흐려진다.
--
-- ── 왜 이름이 두 개였는가 ───────────────────────────────────────────────────
--
-- V2 의 saved_place 는 「찜(MVP2)」 이었고 아무도 쓰지 않았다. V5 의 cart_item 은
-- 「기기 장바구니(MVP1)」 이고 검색 탭이 쓰고 있다. 8/11 회의가 작품엔 찜, 장소엔
-- 장바구니로 가르면서 「장소 찜」 이 없어졌고, 남은 하나의 이름이 saved_place 로
-- 정해졌다. 그래서 두 표가 같은 자리를 두고 겹친다 — 둘 다 지우고 하나로 만든다.

DROP TABLE cart_item;
DROP TABLE saved_place;

CREATE TABLE saved_place (
    user_id           UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    place_id          BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    -- 어떤 작품을 보다가 담았는지. 담는 경로에 따라 있을 수도 없을 수도 있다.
    -- 작품이 지워져도 장바구니 항목은 남아야 하므로 SET NULL 이다.
    --
    -- 코스로 옮겨 담을 때 course_item.source_content_id 로 그대로 따라간다. 그것이
    -- 없으면 "N서울타워가 이태원 클라쓰의 것인지 눈물의 여왕의 것인지" 를 코스가
    -- 구별하지 못한다.
    source_content_id BIGINT REFERENCES content (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 같은 사람이 같은 장소를 두 번 담는 것을 DB 가 막는다. 애플리케이션에서만
    -- 검사하면 동시에 두 번 눌렀을 때 둘 다 통과한다 — 명세가 409 를 약속한 자리다.
    PRIMARY KEY (user_id, place_id)
);

COMMENT ON TABLE saved_place IS
    '장소 장바구니(MVP1). 주체는 app_user.id 이고 설치 UUID 는 user_device 에 있다. 작품 찜은 별도 표 saved_content';

-- 장바구니 조회는 언제나 한 사람 것을 담은 순서대로 가져온다. PK 앞자리가 user_id 라
-- 사람으로 좁히는 것까지는 PK 인덱스가 받지만 정렬은 받지 못한다.
CREATE INDEX saved_place_user_created_idx ON saved_place (user_id, created_at);

-- ───────────── 행동 로그 ─────────────
--
-- user_id 를 UUID 로 맞춘다. 한 스키마 안에서 같은 사람을 가리키는 칸의 타입이 갈리면
-- 장바구니와 코스를 조인할 수 없고, 장바구니에서 코스로 장소를 옮겨 담는 흐름이 그
-- 조인 위에 서 있다.
--
-- USING NULL 로 비우는 것이 안전한 이유는 이 표에 쓰는 코드가 아직 한 줄도 없기
-- 때문이다 (MVP2 용으로 자리만 만들어 둔 표다).
--
-- FK 는 걸지 않는다. append-only 분석 로그라 비로그인(NULL)을 허용하고, FK 를 걸면
-- 방치된 비회원 계정을 정리할 때 이 표가 막는다. 설계 문서의 Ref 목록에도 없다.
ALTER TABLE user_event
    ALTER COLUMN user_id TYPE UUID USING NULL;

COMMENT ON COLUMN user_event.user_id IS '비로그인 허용(NULL). app_user.id — 설치 UUID 가 아니다';
