-- 장면 스틸을 여러 장으로(MZ2AZ-429) — place_content.scene_image_url 칸 하나에서 표로 옮긴다.
--
-- 한 성지(작품 × 장소)에 장면 그림이 두 장 이상인 곳이 생겼다. CSV 는 scene_image_url 칸에 보여 줄 순서대로 ';' 로
-- 이어 준다(place_image_url 과 같은 모양). 그 문자열을 칸 하나에 그대로 담으면 한 칸에 여러 값 — 제1정규형 위반이다.
-- V7 이 적어 둔 대로 「여러 장이 필요해지면 표로 옮기고 그 칸은 마이그레이션의 입력이 된다」.
--
-- 모양은 장소 사진(place_image)과 같다: 한 장이 한 줄, sort_order 는 10·20·30 으로 띄워 사이에 끼울 여지를 둔다.
-- 장소×작품 행을 지우면 사진도 함께 지운다(CASCADE) — 적재(candidates.sql)가 장소×작품을 지웠다 다시 넣는다.
--
-- scene_image_url 칸은 **이번에 지우지 않는다**. 배포 중에는 이 칸을 읽는 옛 판 서버가 함께 돈다 — 새 판이 다 뜬 뒤
-- 다음 마이그레이션에서 지운다(늘리고 → 줄이기). 그동안 적재는 이 칸을 비워 둔다.

CREATE TABLE place_content_image (
    id               BIGSERIAL PRIMARY KEY,
    place_content_id BIGINT NOT NULL REFERENCES place_content (id) ON DELETE CASCADE,
    url              TEXT NOT NULL,
    sort_order       INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE place_content_image IS
    '장면 스틸(작품 × 장소). 한 장이 한 줄, 보여 줄 순서는 sort_order. 언어 무관(V7). 저작권 미결 — ADR 0007';
COMMENT ON COLUMN place_content_image.sort_order IS '10,20,30 처럼 띄워 넣어 중간 삽입에 대비한다';

CREATE INDEX place_content_image_pc_idx ON place_content_image (place_content_id, sort_order);

-- 있던 값을 옮긴다 — 한 장이면 한 줄, ';' 로 이은 것이면 순서대로 여러 줄. 빈 조각은 버린다.
INSERT INTO place_content_image (place_content_id, url, sort_order)
SELECT pc.id, btrim(u.url), u.ord * 10
FROM place_content pc
CROSS JOIN unnest(string_to_array(pc.scene_image_url, ';')) WITH ORDINALITY AS u(url, ord)
WHERE NULLIF(btrim(u.url), '') IS NOT NULL;

COMMENT ON COLUMN place_content.scene_image_url IS
    '쓰지 않는다 — place_content_image 로 옮겼다(V33, MZ2AZ-429). 다음 마이그레이션에서 지운다';
