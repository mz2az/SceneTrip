# contracts/schemas

JSON Schema 와 Avro 정의. **AI 에이전트 도구 스키마**를 포함한다.

에이전트 도구도 다른 것과 똑같은 통신 인터페이스다 — 스키마를 여기 정의하고,
에이전트가 인자를 검증하고, 받는 서비스가 한 번 더 검증한다.
모델이 형식에 맞는 페이로드를 만들어 줄 것이라고 믿지 않는다.

## 목록

| 경로 | 무엇 | 양쪽 |
| --- | --- | --- |
| `guide/context.schema.json` | 여행 가이드에게 주는 화면 상태 — OpenAPI `GuideContext` 와 같은 모양 | 앱이 만들고, scene-api 가 통과시키고, 에이전트가 검증한 뒤 프롬프트로 푼다 |
| `guide/effects.schema.json` | 에이전트가 한 턴에 내보내는 명령 — `effects`(백엔드가 수행)·`ui`(앱이 수행)·`guidePlan`(일정 초안). OpenAPI `GuideEffect`·`GuideUiDirective`·`GuidePlan` 과 같은 모양 | 에이전트가 만들고, scene-api 가 검증한 뒤 `cart.*` 를 저장하고 나머지를 앱에 넘긴다 |
| `guide/tools/poi_nearby.schema.json` | 주변 편의시설 찾기 — 인자·결과 | 에이전트가 모델의 인자를 검증하고, 결과를 이 모양으로 만든다 |

**정본은 이쪽이다.** 에이전트 모듈 안의 사본(`agents/trip-guide/schemas/`)은 이 파일과 같아야
한다 — 지금은 에이전트가 자기 모듈 경로를 읽고 있어 사본을 두고, 갈리면 이쪽이 이긴다.
에이전트에 아직 없는 도구(`route` 등)의 스키마는 그 도구가 생길 때 넣는다 — 있다고 가정하는데
없는 것을 만들지 않는다.

**LLM 이 넣는 인자는 늘 검증한다.** 모델은 `poi_id: "123456"` 같은 값을 지어낸다(프로토타입
실측). 스키마에 없는 키·범위 밖 값은 버리고 기본값으로 간다. 구조: ADR 0013.
