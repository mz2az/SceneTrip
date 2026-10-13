BEGIN;
SET LOCAL lock_timeout = '5s';
CREATE TEMP TABLE blog_request (payload JSONB) ON COMMIT DROP;
COPY blog_request(payload) FROM STDIN WITH (FORMAT csv);
-- BLOG_DATA
CREATE TEMP TABLE blog_changes ON COMMIT DROP AS
SELECT value AS request FROM blog_request, jsonb_array_elements(payload->'entries');

SELECT p.id FROM community_post p JOIN blog_changes c ON p.id=(c.request->>'id')::BIGINT
FOR UPDATE OF p;
SELECT p.post_id FROM community_post_photo p JOIN blog_changes c ON p.post_id=(c.request->>'id')::BIGINT
FOR UPDATE OF p;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM blog_changes c LEFT JOIN community_post p ON p.id=(c.request->>'id')::BIGINT
    WHERE p.id IS NULL OR p.user_id::TEXT IS DISTINCT FROM (SELECT payload->>'owner' FROM blog_request)
      OR NOT (
        (to_jsonb(p)=c.request->'oldPost' AND
         (SELECT coalesce(jsonb_agg(to_jsonb(ph) ORDER BY ph.sort_order),'[]'::JSONB)
          FROM community_post_photo ph WHERE ph.post_id=p.id)=c.request->'oldPhotos')
        OR
        (to_jsonb(p)=c.request->'newPost' AND
         (SELECT coalesce(jsonb_agg(to_jsonb(ph) ORDER BY ph.sort_order),'[]'::JSONB)
          FROM community_post_photo ph WHERE ph.post_id=p.id)=c.request->'newPhotos')
      )
      OR (SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.day_no,i.sort_order),'[]'::JSONB)
          FROM community_post_item i WHERE i.post_id=p.id) IS DISTINCT FROM c.request->'oldItems'
      OR (SELECT to_jsonb(course) FROM course WHERE id=(c.request->>'courseId')::BIGINT)
         IS DISTINCT FROM c.request->'oldCourse'
      OR (SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.day_no,i.sort_order),'[]'::JSONB)
          FROM course_item i WHERE i.course_id=(c.request->>'courseId')::BIGINT)
         IS DISTINCT FROM c.request->'oldCourseItems'
  ) THEN
    RAISE EXCEPTION '기존 글·사진·코스가 기대 상태와 다릅니다. 갱신을 되돌렸습니다';
  END IF;
END $$;

UPDATE community_post p SET body=c.request->'newPost'->>'body',
  body_blocks=c.request->'newPost'->'body_blocks'
FROM blog_changes c WHERE p.id=(c.request->>'id')::BIGINT;
UPDATE community_post_photo p SET storage_key=ph->>'storage_key'
FROM blog_changes c, jsonb_array_elements(c.request->'newPhotos') ph
WHERE p.post_id=(c.request->>'id')::BIGINT AND p.sort_order=(ph->>'sort_order')::INT;
COMMIT;
