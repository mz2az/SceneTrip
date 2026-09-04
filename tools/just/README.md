# tools/just/ — 명령 모듈

루트 `justfile` 이 여기 있는 파일을 전부 import 한다. 저장소가 커져도 명령 목록을
훑어볼 수 있도록 영역마다 파일을 나눈다.

| 파일 | 그룹 | 내용 |
| --- | --- | --- |
| `bazel.just` | `build` | 빌드, 실행, 질의, 정리 |
| `dev.just` | `dev` | 세팅, 진단, 포맷, 린트, 생성 |
| `test.just` | `test` | 테스트 레인, 커버리지, 불안정 테스트 추적 |
| `docs.just` | `docs` | 문서 린트, ADR 생성, 문서 사이트 |
| `infra.just` | `infra` | 레지스트리 이미지, terraform, 매니페스트 렌더링 |
| `k8s.just` | `k8s` · `observability` | kind 클러스터, 모듈 배포, SigNoz |
| `ci.just` | `ci` | 파이프라인 진입점, 릴리스 |
| `agent.just` | `agent` | AI 에이전트 실행과 평가 |
| `scaffold.just` | `scaffold` | 새 서비스·앱·에이전트·라이브러리 생성 |
| `pipeline.just` | `pipeline` | POI 수집·대조·적재, kind 의 Airflow |

## 레시피 추가하기

1. 영역에 맞는 파일에 넣는다.
2. `[group('…')]` 속성과 `#` 설명 주석을 단다. 둘 다 `just --list` 에 나오고,
   기여자와 AI 에이전트가 명령을 발견하는 통로가 바로 그 목록이다.
   `just` 는 **레시피 바로 위 마지막 주석 줄**을 설명으로 쓴다 — 여러 줄을 쓸 때는
   요약 문장을 맨 아래에 둔다.
3. 공용 상태를 바꾸는 것은 `[confirm]` 을 달고 대상 환경을 먼저 출력한다.
4. 본문은 짧게 — 로직은 `tools/scripts/` 로 밀어낸다.
