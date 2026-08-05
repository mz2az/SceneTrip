# 구현 계획

코드보다 먼저 쓰는 계획 문서. 파일 하나가 기능 하나에 대응한다.

| 문서 | 대상 |
| --- | --- |
| [scene-api-search-map.md](./scene-api-search-map.md) | 검색·지도 백엔드 API (MZ2AZ-149) |
| [scene-api-service-module.md](./scene-api-service-module.md) | 백엔드 서비스 모듈과 클러스터 배포 (MZ2AZ-181) |

## 언제 여기에 쓰는가

[CLAUDE.md §3](../../../CLAUDE.md) 의 조건 중 하나라도 걸리면 쓴다 — 모듈 둘 이상에
걸치거나, `contracts/` 를 바꾸거나, `MODULE.bazel` 에 의존성을 더하거나, 대략 100줄을
넘는 변경.

계획은 티켓이 끝나도 지우지 않는다. "왜 이렇게 만들었나" 를 답하는 것이 계획서의
두 번째 수명이다. 결정이 뒤집히면 문서를 고치지 말고 **뒤집힌 사실을 덧붙인다.**
오래 영향을 남기는 결정은 [ADR](../../architecture/adr/README.md) 로 승격한다.
