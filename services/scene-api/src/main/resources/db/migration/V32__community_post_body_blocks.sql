-- 기존 본문·사진·코스 사본은 보존한다. 배치 없는 기존 글은 NULL(MZ2AZ-428).
ALTER TABLE community_post ADD COLUMN body_blocks JSONB;
ALTER TABLE community_post ADD CONSTRAINT community_post_body_blocks_array
    CHECK (body_blocks IS NULL OR
           (jsonb_typeof(body_blocks) = 'array' AND jsonb_array_length(body_blocks) BETWEEN 1 AND 72));
