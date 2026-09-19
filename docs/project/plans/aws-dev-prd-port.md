# DEV·PRD AWS 배포와 교육자료 이식 계획

작성일: 2026-09-19. 갱신일: 2026-09-20.
상태: 저장소 구현·로컬 검증 완료. 실제 AWS 배포는 계정·도메인 등 운영 입력 대기.
검증 결과와 남은 실환경 범위는 [검증 기록](../../qa/aws-port-verification.md)을 본다.

> 2026-09-20 후속 요청: 초기 NLB 진입점은 [ALB 전환 계획](alb-deployment.md)에 따라
> ALB·nginx ClusterIP 구성으로 바꾼다. 이 문서의 나머지 이식 범위는 유지한다.

## 근거와 범위

- [TripPilot #579](https://github.com/ASM-TripPilot/trippilot/pull/579): 수동 EKS 배포,
  환경 격리, OIDC 부트스트랩, Terraform, 이미지, DB 초기화, Helm, 배포 검증.
- [TripPilot #645](https://github.com/ASM-TripPilot/trippilot/pull/645): 상세 HTML 강의안,
  발표 자료, 오프라인 탐색, 소스 기반 구성도와 출처 해시, 회귀 테스트.
- 요청에 중복된 #579는 한 번 적용한다. TripPilot의 Kotlin 비동기 일정 변경과
  해당 테스트 수정은 SceneTrip에 대응 구현이 없어 이식하지 않는다.

착수 시점(2026-09-19)의 SceneTrip 기준은 `services/scene-api`(Java 21·Spring Boot),
`agents/trip-guide`(Python 표준 라이브러리), PostgreSQL 17·PostGIS·pg_trgm,
iOS·Android 네이티브 앱, 로컬 kind·SigNoz다. 당시 Terraform은 자리만 존재했고
에이전트의 Bazel 타깃과 OCI 이미지 타깃이 없었다. 이 공백을 배포 경로와 함께 채운다.

## 설계 원칙

1. 환경 이름은 `dev`, `prd`로 통일하고 같은 Terraform·Helm 소스를 공유한다.
2. DEV는 비용을 줄이는 2 AZ·단일 NAT·단일 AZ DB, PRD는 3 AZ·AZ별 NAT·
   Multi-AZ DB·삭제 보호·긴 백업 보존을 사용한다. 계정과 state·역할·비밀값을 분리한다.
3. 외부 요청은 HTTPS gateway의 `/v1/`로만 받는다. 내부 agent·Actuator·DB는
   외부에 공개하지 않는다. 모바일 앱 자체는 EKS에 올리지 않는다.
4. SceneTrip에 없는 Redis·임베딩 서버·pgvector·JWT를 복제하지 않는다.
   에이전트 대화는 메모리 상태이므로 단일 replica와 재배포 시 대화 소실을 명시한다.
5. RDS 관리자·마이그레이션·런타임 권한을 분리한다. DB 확장과 한국어 검색
   로케일을 준비하고, 기존 Flyway SQL을 수정하지 않고 배포 전에 실행한다.
6. 앱 등록 판정을 우회하지 않는다. 현재 설치 UUID는 인증 수단이 아니므로
   PRD 공개 서비스 전 인증·권한 검증이 필요한 상태를 분명히 표시한다.
7. `just`가 명령 창구이고 Bazel이 빌드·테스트·패키징을 담당한다.
   AWS 변경은 수동 workflow와 확인 레시피에서만 실행한다.
8. 교육자료는 구현 파일·명령·환경 값에 연결한다. 그림은 AWS 실시간 조회 결과와
   구분하고 출처 해시를 남긴다. AWS 서비스 정의·필요성·비용 요인·대안을 설명한다.

## 구현 순서와 담당 경계

| 단계 | 산출물 | 검증 |
| --- | --- | --- |
| 현황·원본 분석 | 적용/제외 대응표, 위험과 의존성 | 원본 PR 파일과 현재 코드 대조 |
| 기반 | Python·OCI Bazel 타깃, 고정 의존성 | 모듈 단위 테스트·이미지 빌드 |
| 인프라 | Terraform, 환경 값, OIDC/state 부트스트랩 | fmt·validate·환경별 정책 테스트 |
| 배포 | Helm, DB Job, 비밀값 동기화, 수동 Actions | 실패 입력·배포 순서·렌더링 테스트 |
| 교육 | 아키텍처·운영 가이드·강의·발표·구성도 | 링크·구성 출처·탐색·브라우저 검증 |
| 통합 | 문서 인덱스, 리뷰, 검증 결과 | `just check` 및 변경 영역 테스트 |

## 위험·외부 입력

- AWS 계정·OIDC 최초 신뢰·ACM 인증서·DNS·GitHub Environment 설정은 저장소에서
  추측할 수 없다. 자리표시자와 입력 검증을 제공하고 미설정이면 배포를 중단한다.
- AWS 실제 생성은 비용과 공유 상태를 바꾸므로 검토 가능한 계획과 대상이 준비된
  뒤 수행한다. 로컬 테스트 통과를 실환경 배포 성공으로 기록하지 않는다.
- RDS 확장·DB 로케일·프로바이더 API는 공식 문서와 Terraform 검증으로 확인한다.
- 기존 `just check`도 작업 시작 시 실행하여 환경 문제와 새 회귀를 구분한다.
- 스키마 되돌리기는 이미지 롤백과 다르다. 파괴적 down migration을 자동 실행하지
  않고 백업 복원·전진 수정 절차를 문서화한다.
