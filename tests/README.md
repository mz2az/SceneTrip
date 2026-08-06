# tests/ — 모듈을 가로지르는 테스트

**배포 단위 둘 이상에 걸친 테스트만 여기 둔다.** 모듈 하나만 검증하는 테스트는
그 모듈 안, 코드 옆에 둔다.

| 디렉터리 | 범위 | 명령 | 속도 |
| --- | --- | --- | --- |
| `contract/` | 생산자·소비자가 `contracts/` 에 합의했는지 | `just test-contract` | 빠름 |
| `integration/` | 여러 모듈 + 실제 의존성 | `just test-integration` | 중간 |
| `e2e/` | 사용자 대면 표면을 관통하는 전체 스택 | `just test-e2e` | 느림 |
| `load/` | 부하 상태의 처리량·지연 | `just test-load` | 수동 |

## 레인 규율

어떤 레인에서 도는지는 Bazel 태그가 결정한다 — AGENTS.md §4.2 참조. 태그를 잘못 달면
빠른 레인이 느려지거나, 그 테스트가 **영영 실행되지 않는다.** 의도적으로 붙일 것:

- `unit` — 격리됨, 네트워크·컨테이너 없음
- `integration` — 외부 프로세스 필요
- `e2e` — 전체 스택
- `slow` — 30초 초과
- `manual` — 와일드카드로 절대 선택되지 않음

## CI 에서 어떻게 도는가

`integration` 은 **별도 잡**에서 돈다. 실제 PostgreSQL 이 필요해서, 워크플로가 서비스
컨테이너를 붙인 뒤 노트북에서와 같은 명령을 부른다 — `just db-migrate` · `just seed` ·
`just db-refresh-search` · `just test-integration` (ADR 0005).

로컬에서는 그 DB 를 kind 클러스터가 준다.

```bash
just cluster-up && just seed && just db-refresh-search
just test-integration
```

`e2e` 는 **아직 CI 에서 돌지 않는다.** 전체 스택이 필요해 서비스 컨테이너로 해결되지
않고, 지금 타깃이 0 개다. 첫 e2e 테스트가 들어올 때 ADR 0005 를 보정하며 정한다.
