# contracts/ — 인터페이스의 정본

SceneTrip 의 모든 통신 인터페이스는 여기서, 손으로, 구현보다 **먼저** 정의된다.
생성된 스텁과 클라이언트는 Bazel 빌드 산출물이며 커밋하지 않는다.

| 디렉터리 | 내용 |
| --- | --- |
| `proto/` | gRPC 서비스와 protobuf 메시지 |
| `openapi/` | REST API 명세 |
| `asyncapi/` | 이벤트·스트림 명세 |
| `schemas/` | JSON Schema / Avro — AI 에이전트 도구 스키마 포함 |

## 계약 우선 규칙

통신 형식이 바뀔 때는:

```
1. contracts/ 수정      2. just gen         3. 생성된 스텁에 맞춰 구현
```

역순은 없다. 계약에서 벗어난 구현은 **구현 쪽의 결함**이다.

## 호환성

- 파괴적 변경은 새 버전 디렉터리를 만든다(`scene/v1` → `scene/v2`). 제자리 수정은 금지.
- protobuf 필드를 지우거나 번호를 재사용하는 것은 파괴적 변경이다. `reserved` 를 쓴다.
- 모든 계약 변경은 `just test-contract` 로 검증한다.

```bash
just new-contract <종류> <이름>
just gen
just test-contract
```
