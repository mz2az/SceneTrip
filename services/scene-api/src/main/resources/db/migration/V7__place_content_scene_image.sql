-- 장면 스틸 URL.
--
-- ── 왜 place_content 인가 (place_content_i18n 이 아니라) ─────────────────────
--
-- 장면 설명(relation_description)은 _i18n 에 있는데 사진은 여기 둔다. 둘의 축이
-- 다르기 때문이다.
--
--   relation_description  "이 장면이 어떤 장면인가" — 언어마다 문장이 다르다
--   scene_image_url       그 장면의 스틸 — 언어와 무관하게 같은 이미지다
--
-- _i18n 에 두면 같은 URL 이 언어 수만큼 복제되고, 이미지를 갈아 끼울 때 모든
-- 언어 행을 함께 고쳐야 한다. 한 곳만 고치면 언어에 따라 다른 사진이 나오는
-- 상태가 조용히 생긴다.
--
-- ── 왜 별도 테이블이 아니라 컬럼인가 ────────────────────────────────────────
--
-- 장소 사진은 place_image 테이블이다(여러 장 + sort_order). 장면 스틸은 수집
-- CSV 가 (장소, 작품) 한 쌍에 **하나만** 준다. 한 장뿐인 값에 테이블을 두면
-- 조인만 늘고 얻는 것이 없다. 여러 장이 필요해지면 그때 scene_image 테이블로
-- 옮긴다 — 그 시점에 이 컬럼은 마이그레이션의 입력이 된다.
--
-- ── 저작권은 아직 미결이다 ──────────────────────────────────────────────────
--
-- 방송사·제작사의 장면 스틸을 서비스가 실어 나르는 문제에 팀의 결론이 아직 없다.
-- 그 상태로 적재하기로 한 결정과 그것을 되돌리는 조건은
-- docs/architecture/adr/0007-scene-still-images-pending-rights.md 에 있다.
-- **원격 환경에 이 데이터를 올리기 전에 그 ADR 을 먼저 읽는다.**

ALTER TABLE place_content ADD COLUMN scene_image_url TEXT;

COMMENT ON COLUMN place_content.scene_image_url IS
    '장면 스틸 URL. 언어 무관이라 _i18n 이 아니라 여기 둔다. 저작권 미결 — ADR 0007';
