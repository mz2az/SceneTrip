# AWS 배포·교육자료 이식 검증 기록

검증일: 2026-09-20. 대상: `fastpopo/port-trippilot-changes` 작업 변경.
**로컬 구현 검증 기록이며 실제 DEV·PRD AWS 배포 기록이 아니다.**

아래 수치는 최초 이식 완료 시점의 기록이다. 이후 ALB 진입점 전환 검증은
[ALB 검증 기록](alb-verification.md)에 별도로 남긴다.

## 원본과 대응 범위

| 원본 | 확인한 revision | SceneTrip 적용 |
| --- | --- | --- |
| [TripPilot #579](https://github.com/ASM-TripPilot/trippilot/pull/579) | `9e438d88c0e0065c7aca476586b1f64eaf07286e` | 수동 DEV·PRD, OIDC·state bootstrap, Terraform, OCI, DB 권한 분리, Helm, 검증 |
| [TripPilot #645](https://github.com/ASM-TripPilot/trippilot/pull/645) | `8420d8e09bd48dc2650883a3f2fe43fc96dea231` | 상세 교재·발표, 오프라인 탐색, 구성도·실제 Terraform DOT·출처 해시·회귀 검사 |

중복 요청된 #579는 한 번 적용했다. Java API·Python agent·네이티브 모바일·
PostgreSQL/PostGIS가 SceneTrip의 실행 단위다. 대응 구현이 없는 Redis·임베딩 서버·
벡터 DB·웹 프런트엔드·TripPilot의 일정 비동기 코드는 복제하지 않았다.

## 완료한 변경 영역 검증

| 검사 | 결과와 범위 |
| --- | --- |
| `just check` | 포맷·전체 린트·99개 빌드 타깃·12개 테스트 타깃 통과 |
| `just deps-update` | bzlmod 및 서비스·Android Maven 잠금 갱신 성공 |
| `just aws-check` | 고정 CLI·Terraform 정적 검사·실제 validate와 provider mock 계획·양 환경 Helm 렌더 통과 |
| `just aws-test-integration` | 실제 PostgreSQL 17·PostGIS와 nginx 컨테이너 2/2 통과 |
| `just test //agents/trip-guide:unit_test` | 161개 단위 시험 통과 |
| agent 오프라인 평가 | 네트워크·모델 호출 없이 통과 |
| API·migration·agent OCI | 이미지 3개 빌드, linux/amd64·비루트·실행 경로 확인 |
| migration JAR | 기존 SQL 14개·PostgreSQL 드라이버·Flyway 서비스 등록 확인 |
| 모바일 주소 생성 | HTTPS `/v1` 주소와 잘못된 입력 21개 회귀 검사 통과 |
| 모바일 클라우드 주소 빌드 | 예제 HTTPS 주소를 주입한 iOS·Android 빌드 통과; 실제 서버 통신·스토어 서명은 제외 |
| iOS 단위 시험 | 시뮬레이터의 88개 시험 통과 |
| 교육 브라우저 | AWS·로컬 발표 각 23개 실제 DOM 동작 검사 통과 |
| 교육 화면 | 상세·발표 HTML 4개, 390px·1440px 가로 넘침 없음, 데스크톱 화면 확인 |
| `just education-check`·`just education-graph-check` | 교육 산출물·구성 출처와 고정 Terraform 엔진의 실제 DOT 그래프 일치 |

브라우저 검사는 `tools/education/navigation-browser-tests.js`를 실제 발표 페이지에서
실행했다. 이전·다음·경계·수정 키·입력 중 단축키 무시·목차·초점 복원·노트·읽기 모드·
hash·인쇄 호출·외부 스크립트 미사용을 검사한다. 인쇄 버튼 호출 시험은 실제 PDF의
페이지 조판 검증과 다르다.

처음 전체 시험에서는 iOS·Terraform이 시간 초과로 판정됐다. 로그상 iOS 88개는
통과했고 Terraform 단독 시험도 16초에 통과했으나 Bazel 경과시간은 수백~수천 초로
기록되어 호스트 절전·시간 경과의 영향으로 판단했다. 테스트·제한을 변경하지 않은
재실행에서 12개 타깃 전체가 16초에 통과했다. 긴 로컬 검증에서 같은 문제가 나면
`just check-awake`로 macOS 유휴 절전을 실행 중에만 억제할 수 있다.

## 실제 실행으로 발견하고 고친 결함

- PostgreSQL 17의 역할 멤버십에 `SET` 권한이 필요해 제한된 관리자 bootstrap이
  실패했다. 명시적 역할 전환 권한을 추가하고 같은 DB에서 재실행·권한 검사를 통과했다.
- 읽기 전용 nginx 컨테이너가 기본 FastCGI·uWSGI·SCGI 임시 경로 때문에 종료됐다.
  `/tmp`로 옮긴 뒤 실제 이미지 기동·경로 라우팅·차단 검사를 통과했다.
- EKS Auto Mode의 노드 로컬 DNS를 정책에 허용하도록 service CIDR에서 주소를 계산했다.
- 입력 commit의 코드가 자기 자신을 검증하기 전에 OIDC 권한을 받던 workflow 순서를
  수정했다. 권한 없는 `main` 사전검증을 통과한 commit만 배포 job이 실행한다.
- 긴 한글 대화 요청이 기존 API 계약 안에서도 agent의 작은 본문 제한에 걸렸다.
  제한을 1 MiB로 조정하고 실제 HTTP 회귀 시험을 추가했다.
- API 조회·DeepSeek 재시도가 각자 timeout을 소비하던 경로에 공유 30초 처리 예산을
  적용했다. 입력 읽기의 socket timeout은 비활성 시간 제한이다.

## 커버리지

`just coverage`의 실제 LCOV를 확인했다. 기존 `coverage-report.sh`는 아직 안내용이며
전체 저장소 80% 기준을 자동 강제한다고 해석하지 않는다.

| 신규 코드 범위 | 실행 행 커버리지 |
| --- | --- |
| `production.py` | 94.0% |
| `internal_server.py` | 88.9% |
| AWS 실행기 3개 파일 | 합계 89.1%, 각 파일 83.6% 이상 |
| 교육 생성기 | 합계 99.3% |

## 실환경에서 남은 확인

AWS 계정·리전·OIDC 최초 역할·ACM·DNS·허용 CIDR·전용 runner·환경 Secret은
운영 값이 필요하다. [배포 런북](../ops/aws-deployment.md)의 입력을 갖춘 뒤 실제
계획과 환경별 배포를 실행하고 별도 기록을 남긴다.

로컬 DB 시험은 V1–V14 SQL 실행과 역할·스키마·런타임 CRUD·재실행을 확인했다.
RDS 전용 확장 특권·TLS·실제 Flyway runner/checksum은 해당 Docker 시험 범위 밖이다.
EKS Auto Mode 정책 적용, ALB/ACM/DNS, AWS IAM의 실제 허용·거부, quota,
RDS 복구와 실제 모델 호출도 실계정 검증이 필요하다.

모바일 스토어 서명·제출과 운영 데이터 적재는 별도 절차다. 현재 설치 UUID는
인증 수단이 아니며 제한된 접근 CIDR을 전제로 한다. 공개 서비스 전 인증·권한
검증은 [아키텍처](../architecture/aws-dev-prd.md)의 남은 과제를 따른다.
