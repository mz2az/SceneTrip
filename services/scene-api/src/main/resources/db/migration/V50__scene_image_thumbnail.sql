-- 장면 사진의 작은 판(계약 1.32.0, docs/project/plans/web-ad-landing.md §10-2·photo-thumbnails.md 「장면 사진」).
--
-- 촬영지 사진이 place_image.thumbnail_url(V42)에 작은 사진 주소를 두는 것과 같은 모양이다. 장면 사진은 공용 버킷 scene/… 에 있고,
-- `just place-thumbnails --scene` 이 긴 변 720px JPEG 를 scene/thumb-720/… 에 덮어쓰기 없이 올린 뒤 seed/scene_image_thumbnails.tsv 에
-- 원본 주소를 열쇠로 적는다. 적재(candidates.sql)가 그 TSV 로 이 칸을 채운다.
--
-- 비워 둘 수 있다 — 아직 작은 판이 없는 장면은 NULL 이고, 응답(Scene.sceneImageThumbnailUrls)은 그 자리에 원본 주소를 낸다.
-- 더하기만 한다. 옛 판 서버는 이 칸을 모르고 그대로 돈다.

ALTER TABLE place_content_image ADD COLUMN thumbnail_url TEXT;

COMMENT ON COLUMN place_content_image.thumbnail_url IS
    '장면 사진의 작은 판(긴 변 720px JPEG, 공용 버킷 scene/thumb-720/…). 없으면 NULL — 응답은 원본을 낸다(1.32.0)';
