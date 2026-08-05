# tests/contract

모든 생산자와 소비자가 contracts/ 의 정의를 지키는지 검증한다. 빠르고 CI 를 막는다.

레인 규율과 태그 규칙은 [tests/README.md](../README.md) 참조.

## 지금 있는 것

| 타깃 | 검증 |
| --- | --- |
| `:scene_api_contract_test` | `contracts/openapi/scene-api-v1.yaml` 이 소비자 셋(Spring 서버 · Swift iOS · Kotlin Android)의 생성기를 모두 통과하는지 |

생성기는 코드를 뽑기 전에 명세를 파싱·검증한다. 그래서 명세가 깨지면 이 테스트가
실패한다. 무엇을 잡고 무엇을 못 잡는지는 `BUILD.bazel` 의 주석 참조.

`unit` 태그가 붙어 있어 `just test-contract` 뿐 아니라 빠른 레인(`just test`)과
`just check` 에서도 함께 돈다 — 계약이 깨진 채로 커밋되지 않게 한다.
