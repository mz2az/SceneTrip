-- 장바구니에 담은 편의시설(계약 1.31.0, docs/project/plans/keep-poi.md §2).
--
-- ── 왜 saved_place 에 섞지 않는가 ───────────────────────────────────────────
--
-- saved_place.place_id 는 place 를 가리키는 FK 다. 편의시설을 같은 표에 넣으려면 「대상
-- 종류」 칸을 두고 FK 를 풀어야 한다 — saved_content 를 따로 둔 V9 와 같은 이유로 표를
-- 나눈다. 응답도 나뉜다: 옛 iOS 앱(1.0.0)은 CartItem.placeId 가 반드시 있다고 읽으므로
-- 편의시설은 Cart.items 가 아니라 Cart.pois 로 따로 나간다.
--
-- 촬영지와 같은 곳인 편의시설(place_poi_link, 숨기지 않은 촬영지)은 이 표가 아니라
-- saved_place 에 촬영지로 담긴다 — 코스의 poiId 규칙과 같다. 그래서 같은 곳이 두 표에
-- 갈라져 담기지 않는다.

CREATE TABLE saved_poi (
    -- saved_place 와 같은 주체·같은 삭제 규칙. 비회원 정리(GuestPurge)·탈퇴는 app_user 를
    -- 지우고, 담은 것은 그 연쇄로 함께 사라진다.
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- 편의시설 행이 재적재에서 지워지면 담은 것도 지운다. 폐업(closed_at)은 행을 남기므로
    -- 담은 것도 남고, 응답에 closed=true 로 알린다 — 촬영지의 delisted 와 같은 생각.
    poi_id     BIGINT NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 같은 사람이 같은 편의시설을 두 번 담는 것을 DB 가 막는다(409). 「조회 뒤 넣기」 는
    -- 동시에 두 번 눌렀을 때 둘 다 통과한다 — saved_place 의 기본키와 같은 이유.
    PRIMARY KEY (user_id, poi_id)
);

COMMENT ON TABLE saved_poi IS
    '장바구니에 담은 편의시설(1.31.0). 주체는 app_user.id. 촬영지와 이어진 편의시설은 여기가 아니라 saved_place 에 담긴다';

-- 장바구니 조회는 한 사람 것을 담은 순서대로 가져온다 — saved_place_user_created_idx 와 같다.
CREATE INDEX saved_poi_user_created_idx ON saved_poi (user_id, created_at);
