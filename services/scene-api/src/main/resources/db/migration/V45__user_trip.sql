-- 정한 여행(도시·며칠·속도) — 계약 1.25.0 /me/trip, 계획 docs/project/plans/trip-memory.md, MZ2AZ-542.
--
-- 계정 하나에 여행 하나(지금 화면이 하나만 다룬다). 비회원도 쓴다(설치 계정). 탈퇴하면 지운다(app_user CASCADE).
-- 코스를 지우면 여행은 남고 course_id 만 비운다. 비회원이 가입하며 합쳐질 때는 AccountLinkStore.moveData 가 옮긴다.
CREATE TABLE user_trip (
    user_id    UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    city       TEXT NOT NULL,
    days       INT NOT NULL,
    pace       TEXT NOT NULL,
    course_id  BIGINT REFERENCES course (id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_trip_city_ck CHECK (char_length(city) BETWEEN 1 AND 40),
    CONSTRAINT user_trip_days_ck CHECK (days BETWEEN 1 AND 15),
    CONSTRAINT user_trip_pace_ck CHECK (pace IN ('relaxed', 'normal', 'packed'))
);

CREATE INDEX user_trip_course_idx ON user_trip (course_id) WHERE course_id IS NOT NULL;
