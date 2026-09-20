# AWS 서비스·bootstrap 삭제 검증 기록

대상: 2026-09-20 수동 삭제 workflow. [계획](../project/plans/aws-teardown.md)과
[삭제 런북](../ops/aws-teardown.md)을 따른다. 실제 AWS 삭제는 실행하지 않았다.

## 완료한 검사

| 검사 | 결과 |
| --- | --- |
| `just check-awake` | `just check`의 포맷·린트·100개 빌드 타깃·13개 테스트 타깃 통과 |
| `just aws-check` | AWS·고정 도구·Terraform 검사 5개 타깃과 DEV·PRD Helm 렌더 통과 |
| AWS 실행기 단위 시험 | 120개 통과. 인증 전 입력 검증, 조회 전용 plan, 저장 계획·소유권, 삭제 순서와 부분 재시도 포함 |
| 실제 Terraform 생명주기 시험 | 4개 통과. Terraform 1.13.5·AWS provider 6.64.0 사용, AWS 호출은 mock |
| `just coverage //tools/aws:unit_test` | 신규 실행기 7개 파일 합산 601/610행, 98.5% |
| `just education-check` | 교육 원본과 생성 HTML 일치, 교육 단위 시험 21개 통과 |
| 실제 브라우저 화면 | 상세·발표 HTML을 390px·1440px에서 확인. 가로 넘침 없음, 35개 장 유지 |
| 독립 코드·보안 리뷰 | 발견한 부분 재시도·세션 시간 문제 수정 후 blocking 결함 없음 |

확인 문자열·삭제 입력·workflow가 없을 때 실패하는 시험을 먼저 실행했다.
서비스 시험은 최종 스냅샷 정책, 허용 속성 외 변경 거부, ALB 정리 실패 시 중단,
새 리소스의 최종 삭제 계획 유입 차단과 환경 태그·ARN·VPC 검증을 포함한다.
bootstrap 시험은 비어 있지 않은 state·잠금·실제 서비스 잔존 차단, S3 버전·삭제 마커·
미완료 업로드, 페이지 처리와 삭제 API의 부분 실패를 포함한다.

Terraform 시험은 내장 리소스로 실제 저장 계획 생성·적용·삭제를 수행한다.
targeted plan의 `complete=false`와 unknown 속성을 구별하고 전체 삭제 후 빈 state를
확인했다. PRD 소스에는 실행기와 동일한 임시 override를 적용하여 AWS mock provider의
보존·폐기 설정과 리소스 소유권 검사 목록을 검증했다. 원본 배포 설정은 변경하지 않는다.

## 커버리지

Bazel의 실제 LCOV 실행 행 수를 읽었다. `coverage-report.sh`는 아직 안내용이므로
이 스크립트가 기준을 자동 강제한 결과라는 뜻은 아니다.

| 범위 | 실행 행 커버리지 |
| --- | --- |
| 서비스 삭제 4개 파일 | 330/338, 97.6% |
| bootstrap 삭제 2개 파일 | 251/252, 99.6% |
| 삭제 입력 검증 | 20/20, 100% |
| 기존 진입점 `aws.py` 전체 | 235/268, 87.7% |

리뷰 후 EKS 접근 정책이 먼저 삭제된 상태에서의 재시도, Internet Gateway가 분리된 뒤
삭제에 실패한 상태에서의 재시도 회귀를 추가했다. 서비스 잡의 120분 실행 제한에 맞춰
OIDC 세션도 7,200초로 설정하고 검사했다. 태그·ARN 검증은 재시도에도 유지한다.

## 실환경 검증 범위

로컬 검사는 실제 계정의 IAM 권한, 사설 EKS 접근, ALB 컨트롤러의 finalizer 처리,
RDS 스냅샷 생성 시간, CloudFormation·S3 삭제 완료를 대신 증명하지 않는다.
Environment·외부 runner·역할을 준비한 뒤 런북에 따라 DEV의 `plan`을 검토하고
`service` 삭제 완료 후 `bootstrap`을 별도로 실행한다. 이번 작업에서는 AWS 인증이나
workflow dispatch를 통한 실제 삭제를 수행하지 않았다.

기존 `ci-security`·문서 린트·workflow lint 레시피는 안내용이며 실질적인 보안 감사나
YAML 검사 통과로 계산하지 않았다. 단위 시험은 workflow의 권한·main 조건·동시 실행
그룹과 인증 전 검증 순서를 별도로 확인한다. 전체 게이트에는 기존 비차단 스타일 경고와
외부 빌드 규칙의 폐기 예정 안내가 남아 있지만 종료 상태는 0이다.
