-- 가이드 챗봇 처리 기록 — 한 턴에 한 줄 (MZ2AZ-410).
--
-- 무엇이 남지 않았나: 요청 한도(usage_counter)는 횟수만, 서버 로그는 연결 실패만 남긴다. 에이전트가 장소를 못 찾고
-- 둘러대는 턴(「경복궁 근처 촬영지」, 2026-10-10 실측)은 200 정상 응답이라 어디에도 남지 않는다. 그런 턴을 모아야
-- 에이전트(agents/trip-guide)를 고칠 수 있다.
--
-- **대화 내용은 남기지 않는다** — 질문·답 원문, 사용자·기기 id 모두. 사람이 이름·전화번호·숙소 위치를 칠 수 있다.
-- 「어떻게 처리됐나」 만: 쓴 도구, 돌려준 장소 수(0 이고 효과도 없으면 못 찾은 답 후보), 이어진 결과(cart.add·plan.draft), 걸린 시간,
-- 실패 코드. 90 일 뒤 지운다(scenetrip.guide.turn-log-retention-days).
CREATE TABLE guide_turn (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    lang        lang_code NOT NULL,
    outcome     TEXT NOT NULL,
    tools       TEXT[] NOT NULL DEFAULT '{}',
    place_count INT CHECK (place_count >= 0),
    effects     TEXT[] NOT NULL DEFAULT '{}',
    took_ms     INT NOT NULL CHECK (took_ms >= 0)
);

CREATE INDEX guide_turn_created_idx ON guide_turn (created_at);

COMMENT ON TABLE guide_turn IS '챗봇 한 턴의 처리 기록. 대화 원문·사용자·기기는 없다. 90 일 뒤 지운다';
COMMENT ON COLUMN guide_turn.outcome IS 'ok, 또는 에이전트를 부르다 난 오류 코드(GUIDE_UNAVAILABLE 등)';
COMMENT ON COLUMN guide_turn.tools IS '에이전트가 쓴 도구 이름(toolsUsed[].tool), 쓴 순서대로';
COMMENT ON COLUMN guide_turn.place_count IS '돌려준 장소 수. 0 이고 effects 도 비면 못 찾은 답 후보(일정 턴은 0 에 plan.draft). 실패한 턴은 NULL';
COMMENT ON COLUMN guide_turn.effects IS '앱으로 간 효과의 op(cart.add·plan.draft…)';
COMMENT ON COLUMN guide_turn.took_ms IS '서버가 에이전트를 기다린 시간';
