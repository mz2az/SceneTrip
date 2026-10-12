-- 작품 제작사(원저작권자)의 작품 페이지 (MZ2AZ-393, 계약 1.12.0 producerUrl).
--
-- 포스터를 누르면 여기로 간다 — 법률 멘토와 정한 규칙(2026-09-23): 포스터는 플랫폼이 올린 이미지를 주소 그대로 쓰고,
-- 누르면 원저작권자에게 보낸다. 수집 CSV 의 title_producer_url 이 정본이고 적재(candidates.sql)가 넣는다. 제작사에 사이트가
-- 없는 작품은 함께 크레디트된 모회사·공동제작사의 작품 페이지다(seed/README.md).
ALTER TABLE content ADD COLUMN producer_url TEXT;

COMMENT ON COLUMN content.producer_url IS '제작사(원저작권자)의 작품 페이지. 포스터를 누르면 이리로. http:// 가 있다 — 앱은 외부 브라우저로 연다';
