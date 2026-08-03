# AI 문서

[`agents/`](../../agents/README.md) 모듈의 설계와 평가.

| 문서 | 목적 |
| --- | --- |
| `agent-catalog.md` | 모든 에이전트와 능력·가드레일 |
| `prompt-library.md` | 공용 프롬프트 패턴과 그 근거 |
| `eval-methodology.md` | 에이전트 품질을 재는 방법 |
| `evals/<에이전트>-<날짜>.md` | 기록된 평가 결과 |
| `model-policy.md` | 승인된 모델, 비용 한도, 폴백 동작 |
| `safety.md` | 프롬프트 주입 방어, 출력 검증, 에스컬레이션 |

## 상시 규칙

- **프롬프트는 버전 관리되는 파일**이다. 코드 안의 문자열 리터럴이 아니다.
- **프롬프트나 모델을 바꾸면 평가를 함께 돌리고** 결과를 여기 기록한다.
- **모델 출력은 신뢰할 수 없는 입력**이다. `contracts/schemas/` 스키마로 검증한 뒤
  쓰고, `eval` 하지 않으며, 셸·SQL·파일 경로에 그대로 끼워 넣지 않는다.
- **오프라인 평가가 CI 를 막는다.** 실제 모델 평가는 비용이 들며 의도적으로만 돌린다.
- **비용과 지연도 품질 지표**다. 정확도와 함께 추적한다.

```bash
just agent-eval <이름>
just agent-eval-diff <이름> <기준>
just agent-lint-prompts
```
