# 품질 문서

| 문서 | 목적 |
| --- | --- |
| `test-strategy.md` | 각 계층에서 무엇을 왜 테스트하는가 |
| `coverage-policy.md` | 커버리지 기준과 예외를 허용하는 방식 |
| `test-plans/<기능>.md` | 기능별 테스트 계획 |
| `flaky-tests.md` | 격리된 불안정 테스트, 담당자, 기한 |

## 상시 규칙

- 새로 쓰거나 바꾼 코드는 커버리지 80% 이상, 핵심 경로는 end-to-end 로 덮는다.
- 테스트는 태그가 가리키는 레인에서 돈다 — AGENTS.md §4.2 참조.
- 불안정한 테스트는 결함이다. 담당자와 기한을 붙여 격리하되, 빌드를 초록으로 만들려고
  **삭제하지 않는다.**
- 테스트는 구현을 고쳐서 통과시킨다. 테스트 자체가 잘못된 기대를 담고 있을 때만 예외다.

```bash
just test / test-integration / test-e2e
just coverage
just test-flaky <타깃>
```
