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

## CI 가 덮지 않는 레인

**`integration` 과 `e2e` 는 GitHub CI 에서 돌지 않는다.** 러너에 실제 PostgreSQL 도
클러스터도 없어서 `just ci-full` 이 두 태그를 제외한다. 그러니 이 레인들은 **사람이
돌려야 지켜진다.**

```bash
just cluster-up && just seed && just db-refresh-search
just test-integration
```

레인을 러너에서 돌리려면 CI 잡이 DB 를 띄우고 마이그레이션과 적재까지 해야 한다.
지금은 `just seed` 가 `kubectl exec` 로 동작해 클러스터에 묶여 있다 — 그것을 푸는 것이
선결 과제다.
