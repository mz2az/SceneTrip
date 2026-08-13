-- 한 코스는 동시에 하나만 올라가 있다.
--
-- V10 에서 빠뜨린 제약이다. 계획서(docs/project/plans/course-api.md §4-3)는
-- `UNIQUE (source_course_id)` 라고 적었는데, **그대로 걸면 「내리기」와 부딪힌다.**
--
-- 내리기는 행을 지우지 않고 unpublished_at 에 시각을 찍는다. 이미 담아 간 사람의
-- course.source_market_course_id 가 그 행을 가리키고 있어서, 지우면 그 사람 화면에서
-- 출처가 사라지기 때문이다(V10 주석). 그래서 내렸다가 다시 올리면 같은
-- source_course_id 를 가진 행이 둘이 되고, 단순 UNIQUE 는 그 재등록을 영영 막는다.
--
-- 부분 인덱스가 그 둘을 함께 만족시킨다. 내려간 것은 얼마든지 쌓이고, **살아 있는 것만**
-- 하나로 제한한다. 애플리케이션이 검사하는 대신 DB 에 두는 이유는 늘 같다 — 올리기를
-- 동시에 두 번 누르면 두 검사가 모두 "없음" 을 보고 둘 다 통과한다.
--
-- source_course_id IS NOT NULL 을 함께 거는 이유: 원본 코스가 지워지면 그 칸이
-- SET NULL 이 되는데, NULL 인 행이 여럿이어도 겹치는 것이 아니다.
CREATE UNIQUE INDEX market_course_live_source_uk
    ON market_course (source_course_id)
    WHERE unpublished_at IS NULL AND source_course_id IS NOT NULL;

COMMENT ON INDEX market_course_live_source_uk IS
    '한 코스에서 살아 있는 사본은 하나뿐. 내려간 것은 제한하지 않는다 — 내렸다 다시 올리면 새 사본이 된다';
