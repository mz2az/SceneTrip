-- 장바구니.
--
-- ── 왜 saved_place 를 쓰지 않는가 ────────────────────────────────────────────
--
-- 명세는 장바구니의 주체를 `X-Device-Id` 헤더의 기기 UUID 로 정했다. 로그인이 없어
-- 사용자를 식별할 방법이 그것뿐이기 때문이다. 그런데 스키마 v1 의 saved_place 는
-- user_id 가 BIGINT 라 UUID 를 담을 수 없고, 참조할 사용자 테이블도 아직 없다.
--
-- saved_place 에 device_id 를 덧붙이는 방법도 있었다. 그러면 한 테이블이 "기기의
-- 장바구니(MVP1, 담기)" 와 "사용자의 찜(MVP2, 토글)" 을 겸하게 된다. 둘은 수명주기도
-- 화면도 다른 개념이고, PK 를 (user_id, place_id) 에서 바꾼 뒤 "둘 중 하나는 반드시
-- 있어야 함" CHECK 를 얹어야 한다. 나중에 사용자 기준으로 옮길 때 그 둘을 다시
-- 분리하는 일이 더해진다.
--
-- ── 로그인이 생기면 ─────────────────────────────────────────────────────────
--
-- user_id BIGINT 를 여기에 더하고, 기기와 계정을 이어 붙인 뒤 device_id 를 NULL 로
-- 만든다. 지금 user_id 를 미리 만들어 두지 않는 이유는 참조할 테이블이 없어 FK 를
-- 걸 수 없고, 언제나 NULL 인 컬럼은 읽는 사람을 헷갈리게 하기 때문이다.

CREATE TABLE cart_item (
    device_id         UUID NOT NULL,
    place_id          BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    -- 어떤 작품을 보다가 담았는지. 담는 경로에 따라 있을 수도 없을 수도 있다 —
    -- 작품 → 장소 → 담기면 채워지고, 장소 목록에서 바로 담으면 비어 있다.
    -- 작품이 지워져도 장바구니 항목은 남아야 하므로 SET NULL 이다.
    source_content_id BIGINT REFERENCES content (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 같은 기기가 같은 장소를 두 번 담는 것을 DB 가 막는다. 애플리케이션에서만
    -- 검사하면 동시에 두 번 눌렀을 때 둘 다 통과한다 — 명세가 409 를 약속한 자리다.
    PRIMARY KEY (device_id, place_id)
);

COMMENT ON TABLE cart_item IS
    '기기 단위 장바구니(MVP1). 담기까지만이고 루트 만들기는 범위 밖이다. 로그인이 생기면 user_id 를 더해 옮긴다';

-- 장바구니 조회는 언제나 한 기기 것을 담은 순서대로 가져온다. PK 의 앞자리가
-- device_id 라 기기로 좁히는 것까지는 PK 인덱스가 받지만, 정렬은 받지 못한다.
CREATE INDEX cart_item_device_created_idx ON cart_item (device_id, created_at);
