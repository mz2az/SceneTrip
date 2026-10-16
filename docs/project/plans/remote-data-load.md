# 원격 데이터 적재 — DEV·PRD 의 RDS 에 촬영지·편의시설·같은 곳 연결을 넣는다

- **상태**: 계획(2026-10-12). 티켓은 이 문서가 합쳐진 뒤 만든다(권호)
- **관련**: [aws-deployment.md](../../ops/aws-deployment.md) §적재 · [aws-dev-prd.md](../../architecture/aws-dev-prd.md) ·
  [poi-i18n-image.md](poi-i18n-image.md) §3f·§14 · [place-poi-link.md](place-poi-link.md) · [popularity.md](popularity.md) ·
  [dev-lifecycle.md](dev-lifecycle.md) · ADR 0005 · ADR 0022

## 1. 왜

적재 명령(`just seed`·`seed-poi-release`·`place-poi-link`·`seed-reset`)은 **전부 로컬 kind 용**이다. DEV 는 올릴 때마다 빈 DB 로
시작해 앱에 아무것도 안 나오고(2026-10-01 실측: `/contents` 빈 목록), PRD 는 데이터를 넣을 길 자체가 없다. PRD 로 가는 길의 가장
큰 구멍이다.

## 2. 실측 — 지금 배포가 DB 일을 하는 방식 (2026-10-12, 읽기만)

| 대상 | 지금 |
| --- | --- |
| RDS 에 닿는 길 | **EKS 안에서만.** RDS 보안 그룹은 클러스터 SG 의 5432 만 받고 `publicly_accessible = false`, `rds.force_ssl` (`database.tf`) |
| 일회성 DB 작업 | `deploy.py` 의 `migrate_database` 가 Job 셋(`database-bootstrap` → `database-migrate` → `database-finalize`)을 **Helm 앞에서** 돌린다. 임시 Secret 두 개를 만들고 `finally` 에서 지운다 |
| Job 규칙 | 네임스페이스가 Pod Security **restricted** — 비루트(10001)·읽기 전용 루트·권한 없음·seccomp. `backoffLimit 0`, `activeDeadlineSeconds 300`, 메모리 상한 128Mi |
| `psql` 이 든 이미지 | **우리 이미지엔 없다**(셋 다 distroless). 공개 `postgres:17.6-bookworm`(digest 고정)만 있다 — bootstrap Job 이 쓴다 |
| 적재 SQL 이 바라는 것 | `\copy … FROM '/tmp/…'`(클라이언트 쪽 파일)·`\if`·`\i`·`\echo` — **진짜 psql 이 있어야** 한다. JDBC 로는 못 돈다 |
| DB 역할 | 앱의 `app_runtime` 은 못 돌린다 — 임시 표(TEMPORARY 권한 없음)·`TRUNCATE person`·`REFRESH MATERIALIZED VIEW search_term`(소유자만). **`app_migrate`(소유자)** 로 돌려야 한다 |
| 네트워크 정책 | 기본 전부 차단. DB(5432)로 나가는 것은 `app` 라벨이 `database-bootstrap`·`database-migrate` 인 파드뿐이고 **443 은 막혀 있다** — 새 Job 은 정책에 이름을 올려야 한다 |
| 이미지에 든 적재 파일 | **없다.** `services/scene-api/seed/` 는 어느 배포 이미지에도 없다 |
| 실패 보고 | 배포기가 stderr 를 버린다(비밀값 때문) — Job 이 실패하면 `kubectl wait` 실패만 보이고 로그가 없다 |
| 문서 | `aws-deployment.md`: 「`seed` 는 AWS 적재 명령이 아니다 — 원격 DB 주소로 돌리지 않는다」. poi-i18n-image.md §14: 「나중에 배포 Job 도 같은 방식을」 |

## 3. 결정

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| 어디서 도나 | **EKS 안의 Job `data-load`** | RDS 에 닿는 유일한 길. 배포 Job 들과 같은 모양(restricted) |
| 무엇으로 | **적재 이미지 `scenetrip-<env>/data-load`** — `postgres:17.6-bookworm`(bootstrap 과 같은 digest) 위에 적재 SQL·스크립트·데이터 파일을 얹는다. Bazel 로 짓고 ECR 에 올린다 | psql 이 있고, **데이터가 이미지에 박혀** 내려받을 필요가 없다 — 443·IAM 이 필요 없고, 배포한 커밋과 데이터가 함께 고정된다(태그 = 커밋) |
| 편의시설 파일 | Release `poi-data-<판>` 의 gz 여섯 개(약 70 MB)를 **`MODULE.bazel` 의 `http_file`(sha256 고정)** 로 받아 이미지에 넣는다. 판을 바꾸면 sha256 을 바꾸는 PR 하나 | 빌드 중 네트워크 금지(헤르메틱)를 지킨다 — Bazel 이 저장소 단계에서 sha256 으로 받는다. manifest 의 sha256 을 그대로 옮긴다 |
| 역할 | **`app_migrate`** — migrate Job 과 같은 임시 Secret 방식(`database-load-transient`, 끝나면 지운다) | 적재 SQL 이 임시 표·TRUNCATE·MV 갱신을 한다(§2) |
| SQL | **로컬과 같은 파일**을 그대로 쓴다(`candidates.sql`·`poi.sql`·`poi_update.sql`·`place_poi_link.sql`) | 두 벌이 되면 어긋난다. 이미지 안 스크립트가 로컬 스크립트처럼 입력을 `/tmp` 에 놓고 부른다 |
| 순서 | ① 촬영지(`candidates.sql`, 끝에 인기도 계산) ② 편의시설(첫 적재면 기본, 이미 있으면 `--update`) ③ 같은 곳 연결 ③-2 관광공사 상세(판 `poi-detail-<판>`, 국문 → 영문 — 2026-10-16 MZ2AZ-376) ④ 인기도 다시(`recompute_popularity()`) ⑤ `REFRESH MATERIALIZED VIEW CONCURRENTLY search_term` | 연결은 촬영지·편의시설이 다 있어야 한다. 검색 색인은 맨 끝 |
| 멱등 | 몇 번을 돌려도 같다 — 촬영지는 키로 갱신·추가(빠진 것은 숨김), 편의시설은 지우지 않음(ADR 0022), 연결은 다시 계산 | PRD 에서 다시 돌려도 사용자 데이터가 지워지지 않는다. **`seed-reset` 은 원격에서 절대 부르지 않는다** |
| 네트워크 | 정책 `database-jobs` 의 라벨 목록에 `data-load` 를 더한다(5432 만). 443 은 열지 않는다 | 이미지에 다 있어 밖으로 나갈 일이 없다 |
| 자원 | `activeDeadlineSeconds` **1800**, 메모리 512Mi·`/tmp` emptyDir 1Gi(편의시설 압축 해제 약 250 MB) | 편의시설 91 만 행. 시간은 첫 DEV 적재에서 실측해 고친다 |
| 언제 | **DEV**: `dev-lifecycle` up 이 배포 성공 뒤 자동으로. **PRD**: 수동만(환경 검토자 승인). 새 워크플로를 만들지 않고 `aws-deploy.yml` 에 동작 `data-load` 를 더했다 — preflight·OIDC·러너·동시 실행 막기를 그대로 쓴다(구현 때 바꿈). 적재 이미지는 **이 동작이 올린다** — 배포 태그가 실행마다 달라(`커밋-실행-시도`) 배포 때 올린 것을 찾을 수 없고, 평소 배포가 큰 이미지를 올리지 않게 | DEV 는 매번 빈 DB 다. PRD 는 사람이 판·커밋을 보고 누른다 |
| 확인 | Job 끝에 건수 검사 — 작품·촬영지·편의시설·연결·검색 색인이 0 이면 **실패**. 성공하면 건수 요약 한 줄(JSON)을 배포기가 `kubectl logs` 로 받아 출력 | 배포는 초록인데 화면이 비는 조용한 실패를 막는다. 요약에는 비밀값이 없어 stderr 정책과 부딪히지 않는다 |
| 기록 | 표 `data_load`(V30) — 시각·커밋·CSV sha256·편의시설 판·건수 | PRD 에 무엇이 언제 들어갔는지 DB 에서 바로 본다 |
| 겹친 리뷰 | **지우기 전에 보관 표 `review_archive` 로 복사한다**(V30 에 함께). 같은 곳 연결이 새로 생겨 한 사람의 리뷰가 한 곳에 둘이 되면 `place_poi_link.sql` 이 오래된 쪽을 지운다(「한 사람 한 곳 하나」) — 그 행과 사진 키를 먼저 옮겨 둔다 | 지금은 말없이 사라진다. 숨김(`removed_at`)으로 바꾸면 유일 제약이 숨긴 행까지 세어 제약·쓰기 쿼리까지 고쳐야 한다. 드문 일이라 보관 표면 충분하고, 화면·규칙은 그대로다(권호 2026-10-12) |

## 4. 하지 않는 것 / 나중

- **S3 에 데이터 두기** — poi-i18n-image.md §14 의 안. 지금 데이터(약 75 MB)는 이미지로 충분하고 IAM·443 이 필요 없다. 편의시설 사진·
  상세가 붙어 커지면(#184, TourAPI 수집 끝) 다시 본다
- **DEV 를 스냅샷에서 복원** — dev-lifecycle 의 열린 항목. 적재가 자동이면 필요 없다
- **원격 `seed-reset`** — 사용자 데이터를 지운다. 키를 다시 정하는 일은 PRD 전에 끝낸다
- **편의시설 사진(#184)** — main 에 들어오면 같은 이미지·같은 Job 에 단계를 더한다

## 5. 순서

1. 이 문서 → 머지. 티켓
2. **적재 이미지** — Bazel 타깃(`//services/scene-api:data_load_image`·`_push`), `MODULE.bazel` 에 기반 이미지와 편의시설 판 `http_file`.
   이미지 안 `load.sh`(§3 순서·건수 검사·요약). **`MODULE.bazel` 에 의존성을 더하는 일이라 PR 전에 한 번 더 확인받는다**
3. **V30 `data_load`·`review_archive`**(+ `place_poi_link.sql` 이 지우기 전에 복사) · 정책 라벨 · 배포기 `data-load` 동작(`tools/aws`) · `just aws-data-load <env>` · 워크플로 `aws-data-load.yml`
4. 로컬 검증 — kind 에서 같은 이미지로 Job 을 띄워(로컬 postgres) 처음부터·두 번째(멱등) 모두 — **2026-10-12 `just data-load-local`: 편의시설 91 만 행 포함 78 초·75 초, 두 번 모두 같은 건수(작품 128 · 촬영지 479 · 편의시설 910,394 · 연결 110 · 검색 색인 4,841)**
5. **DEV 에서 실제로** — up 뒤 자동 적재, 앱에서 작품·지도·검색·편의시설 확인. 걸린 시간 실측 → `activeDeadlineSeconds` 조정
6. dev-lifecycle up 에 연결. 문서(aws-deployment.md 「적재」, 이 문서 결과)
