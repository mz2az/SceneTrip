# agents/trip-guide/docs/design

설계와 조사 문서. 코드가 왜 지금 모양인지는 여기에 있다.

| 문서 | 무엇 |
| --- | --- |
| [ai-course-planner.md](ai-course-planner.md) | **코스 추천 엔진 설계.** 왜 일정 계산에서 LLM 을 뺐는가, 알고리즘 4 단계, 평가 지표 5 종 |
| [chatbot-and-planner-survey.md](chatbot-and-planner-survey.md) | **경쟁·기술 조사.** 다른 서비스는 어떻게 만들었나, 무엇이 깨졌나, 대화형 수정·능동 제안·평가의 실무 근거 |
| [backend-agent-contract.md](backend-agent-contract.md) | **백엔드↔에이전트 통신 구조 (초안).** 앱→백엔드→에이전트 흐름, 에이전트가 필요로 하는 정보, `effects` 로 쓰기를 백엔드에 넘기는 방식 |
| [backend-handoff.md](backend-handoff.md) | **권호님께 넘기는 연결 명세.** 에이전트가 부르는 API 5 종, 응답 모양, `effects` 처리법 |
| [integration-gaps.md](integration-gaps.md) | **감사 결과.** 에이전트가 있다고 가정하는데 실제로 없는 API·통신, 그리고 권호님·승길님께 요청할 목록 |
| [plan-shape.md](plan-shape.md) | **일정의 두 모양.** 모델이 읽는 글과 앱이 그리는 데이터를 왜 갈랐는가, 편집 중 정본은 왜 앱인가 (MZ2AZ-318) |
| [contract-1.2.0.md](contract-1.2.0.md) | **계약 1.2.0 맞추기.** 화면 번호·장바구니의 정본을 앱으로 넘긴 이유, 경계의 이름, 시간 예산 (MZ2AZ-320) |
| [build-draft.md](build-draft.md) | `rules_python` 이 켜질 때 붙일 BUILD.bazel 초안. 지금 넣으면 팀 빌드가 깨진다 |
