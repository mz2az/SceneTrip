-- 사용자 사진의 작은 사진(긴 변 720px JPEG) — 계획 docs/project/plans/photo-thumbnails.md, ADR 0030, MZ2AZ-561.
--
-- 작업(PhotoThumbnailJob)이 1 분마다 thumbnail_key 가 없는 줄을 골라 원본을 줄여 <원래 자리>/thumb-720/<이름>.jpg 에 올리고
-- 키를 적는다. 실패하면 thumbnail_attempts 를 올리고, 3 번 실패한 줄은 더 고르지 않는다 — 응답은 원본으로 간다.
-- 더하기만 한다. 옛 코드는 두 칸을 모르고 그대로 돈다. place_image 는 V42 의 thumbnail_url 을 쓴다(칸을 더하지 않는다).

ALTER TABLE review_image
    ADD COLUMN thumbnail_key      TEXT,
    ADD COLUMN thumbnail_attempts INT NOT NULL DEFAULT 0;

ALTER TABLE community_post_photo
    ADD COLUMN thumbnail_key      TEXT,
    ADD COLUMN thumbnail_attempts INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN review_image.thumbnail_key IS '작은 사진의 저장소 키 reviews/thumb-720/…. 아직 없거나 만들지 못했으면 NULL';
COMMENT ON COLUMN review_image.thumbnail_attempts IS '작은 사진 만들기에 실패한 횟수 — 3 이면 더 하지 않는다';
COMMENT ON COLUMN community_post_photo.thumbnail_key IS '작은 사진의 저장소 키 posts/thumb-720/…. 아직 없거나 만들지 못했으면 NULL';
COMMENT ON COLUMN community_post_photo.thumbnail_attempts IS '작은 사진 만들기에 실패한 횟수 — 3 이면 더 하지 않는다';

-- 작업이 고르는 줄만 담는다 — 다 만들고 나면 비어 있다. 순서는 작업과 같다(새것부터).
CREATE INDEX review_image_thumbnail_pending_idx
    ON review_image (id DESC)
    WHERE thumbnail_key IS NULL AND thumbnail_attempts < 3;

CREATE INDEX community_post_photo_thumbnail_pending_idx
    ON community_post_photo (post_id DESC, sort_order)
    WHERE thumbnail_key IS NULL AND thumbnail_attempts < 3;
