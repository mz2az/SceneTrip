-- 코스 항목에 편의시설 갈래 — 촬영지 · 편의시설 · 개인 핀 중 정확히 하나.
--
-- 계획: docs/project/plans/course-poi-item.md (MZ2AZ-377), poi.md §4-2. 계약: scene-api 1.8.0 CourseItemInput.poiId.
--
-- 참조로 둔다(사본이 아니다) — 편의시설은 우리가 관리하는 자료라 이름·주소가 갱신되면 코스에도 반영돼야 한다. 직접 찍은 핀은
-- 사용자가 만든 것이라 사본이 맞다.
--
-- ON DELETE CASCADE 는 촬영지·리뷰와 같은 규칙이다. 편의시설 행이 지워지는 것은 출처를 통째로 바꿀 때(seed-poi --prune)뿐이고,
-- 분기 갱신(--update)은 폐업 표시(closed_at)만 해 코스 항목이 남는다.

ALTER TABLE course_item ADD COLUMN poi_id BIGINT REFERENCES poi (id) ON DELETE CASCADE;

-- 둘에서 셋으로. `<>` 로 쓴 배타적 논리합은 둘일 때만 읽힌다.
ALTER TABLE course_item DROP CONSTRAINT course_item_target_check;
ALTER TABLE course_item ADD CONSTRAINT course_item_target_check
    CHECK (num_nonnulls(place_id, poi_id, custom_pin_id) = 1);

CREATE INDEX course_item_poi_idx ON course_item (poi_id) WHERE poi_id IS NOT NULL;

COMMENT ON COLUMN course_item.poi_id IS
    '편의시설. 촬영지와 같은 곳으로 연결된 편의시설(place_poi_link)은 여기 두지 않고 place_id 로 저장한다';
