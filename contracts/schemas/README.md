# contracts/schemas

JSON Schema 와 Avro 정의. **AI 에이전트 도구 스키마**를 포함한다.

에이전트 도구도 다른 것과 똑같은 통신 인터페이스다 — 스키마를 여기 정의하고,
에이전트가 인자를 검증하고, 받는 서비스가 한 번 더 검증한다.
모델이 형식에 맞는 페이로드를 만들어 줄 것이라고 믿지 않는다.

## 목록

| 경로 | 무엇 | 양쪽 |
| --- | --- | --- |
| `guide/context.schema.json` | 여행 가이드에게 주는 화면 상태 — OpenAPI `GuideContext` 와 같은 모양 | 앱이 만들고, 서버가 검증한 뒤 프롬프트로 푼다 |
| `guide/tools/poi_nearby.schema.json` | 주변 편의시설 찾기 — 인자·결과 | 서버가 모델의 인자를 검증하고, 결과를 이 모양으로 만든다 |
| `guide/tools/route.schema.json` | 보여 준 장소까지 길찾기 — 인자·결과 | 〃 |
| `guide/tools/add_to_cart.schema.json` | 보여 준 장소 담기 — 인자·결과 | 〃 |

**LLM 이 넣는 인자는 늘 검증한다.** 8B 급 모델은 `poi_id: "123456"` 같은 값을 지어낸다(프로토타입
실측). 스키마에 없는 키·범위 밖 값은 버리고 기본값으로 간다. 계획서: `docs/project/plans/guide-chat.md`.
