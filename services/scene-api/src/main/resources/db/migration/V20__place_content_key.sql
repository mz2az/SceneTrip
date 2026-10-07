-- 촬영지·작품에 바뀌지 않는 키와 숨김 표시를 둔다.
--
-- 계획: docs/project/plans/place-key-seed.md. 적재(seed/candidates.sql)가 TRUNCATE 로 통째로 갈던 것을
-- 키로 UPSERT 하게 바꾼다 — 촬영지·작품을 가리키는 사용자 데이터(장바구니·코스·찜·마켓)가 적재 때마다
-- CASCADE 로 지워지지 않게.
--
-- 키는 비워 둘 수 있다. 이 마이그레이션 직후의 기존 행에는 키가 없고, 다음 적재가 옛 묶음 규칙(네이버
-- URL · 이름+주소 · 제목)으로 찾아 붙인다(계획 §2-3). UNIQUE 는 NULL 끼리는 겹쳐도 된다.

ALTER TABLE place
    ADD COLUMN place_key TEXT,
    ADD COLUMN hidden_at TIMESTAMPTZ,
    ADD CONSTRAINT place_place_key_uk UNIQUE (place_key);

COMMENT ON COLUMN place.place_key IS
    '수집 CSV 의 촬영지 키. 한 번 정하면 바꾸지 않는다 — 적재가 이것으로 같은 촬영지를 알아보고 id 를 지킨다';
COMMENT ON COLUMN place.hidden_at IS
    '적재 CSV 에서 빠진 때. 찬 촬영지는 지도·목록·검색에서 빠지고 상세와 사용자의 저장분에는 남는다';

ALTER TABLE content
    ADD COLUMN content_key TEXT,
    ADD COLUMN hidden_at TIMESTAMPTZ,
    ADD CONSTRAINT content_content_key_uk UNIQUE (content_key);

COMMENT ON COLUMN content.content_key IS
    '수집 CSV 의 작품 키. 한 번 정하면 바꾸지 않는다 — 적재가 이것으로 같은 작품을 알아보고 id 를 지킨다';
COMMENT ON COLUMN content.hidden_at IS
    '적재 CSV 에서 빠진 때. 찬 작품은 목록·검색에서 빠지고 상세와 사용자의 저장분에는 남는다';
