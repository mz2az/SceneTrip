# AWS 서비스·bootstrap 삭제 자동화

## 목표

사용하지 않는 DEV·PRD의 상시 비용을 줄이기 위해 GitHub Actions에서 서비스 형상과
bootstrap을 순서대로 삭제한다. 이번 작업은 삭제 기능 구현이며 실제 계정의 삭제는 실행하지 않는다.

## 실행 경계

- 수동 workflow, main 포함 SHA 사전검증, 보호된 Environment, 환경별 OIDC를 재사용한다.
- 배포와 같은 `aws-<env>` 동시 실행 그룹으로 배포·삭제를 직렬화한다.
- `plan`은 조회·계획만 수행한다. `destroy`는 환경·계정을 포함한 확인 문자열이 필요하다.
- 서비스는 기존 고정 egress 전용 runner, bootstrap은 외부의 bootstrap 역할을 사용한다.
- 서비스와 bootstrap은 별도 실행으로 선택한다. 서비스 삭제 완료 후 bootstrap 삭제를 실행한다.
- 입력은 `environment`, `scope`, `operation`, `commit_sha`, `snapshot_policy`,
  `purge_state`, `confirmation`이다. 기본은 서비스 `plan`, 최종 스냅샷 보존, state 보존이다.
- 실제 삭제 확인값은 `DELETE <env> <12자리계정>`이며 plan은 확인값을 요구하지 않는다.

## 삭제 순서

1. 계정·역할·소스와 환경 입력을 확인하고 Terraform backend에 연결한다.
2. PRD RDS/EKS 삭제 보호, ECR 이미지와 최종 DB 스냅샷 정책을 계획한다.
   일반 배포 설정은 유지하고 격리된 임시 Terraform override에서만 보호 해제를 선언한다.
   준비 계획 JSON에서 지정된 속성 이외의 변경·생성·교체를 거부한다.
3. 소유권이 확인된 Ingress를 먼저 삭제하고 실제 ALB 소멸을 기다린다. 그동안
   EKS·IngressClass·IngressClassParams는 유지하며, 완료 후 Helm을 `--no-hooks`로 해제한다.
4. 검증한 준비 계획을 적용하고 삭제 전용 저장 계획을 생성·검증·적용한다.
   Terraform 관리 리소스가 남으면 서비스 삭제를 성공으로 처리하지 않는다.
5. bootstrap 삭제는 서비스 state가 비었고 잠금과 실제 서비스 리소스가 없는지 다시 확인한다.
   CloudFormation 스택의 IAM 역할을 삭제한 뒤, 명시적으로 선택한 경우 보존된 state S3의
   버전·삭제 마커·미완료 업로드까지 정리하고 버킷을 삭제한다.

## 데이터·비용 정책

최종 RDS 스냅샷 보존을 기본값으로 제공하며 명시적인 폐기 선택도 지원한다.
스냅샷 이름은 실행별로 고유하게 지정한다. ECR 이미지는 서비스 삭제에 포함된다.
Secrets Manager는 기존 복구 유예를 유지한다. state S3 보존/삭제는 별도 선택이다.
기존 스냅샷·외부 DNS/ACM·수동 runner·계정의 OIDC 공급자와 최초 bootstrap 역할은
관리 범위 밖이며 남는 비용과 재생성 조건을 런북에 적는다.

Secret 삭제 예약 중에는 과금되지 않지만 동일 이름 재배포 전에 복원/import 또는 영구 삭제
완료가 필요하다. bootstrap 스택만 삭제하고 남긴 S3는 같은 이름의 최초 생성과 충돌하므로
별도 import/재사용 계획을 마련한다. 실행 순서와 권한·재시도·비용 기준은
[삭제 런북](../../ops/aws-teardown.md)을 정본으로 유지한다.

## 검증

클라우드 호출을 가짜 실행기로 대체한 단위 시험을 먼저 작성한다. 잘못된 확인값·계정·
역할, 변경 범위를 벗어난 준비 계획, ALB 정리 실패, 부분 삭제와 재실행, 비어 있지 않은
state, S3 페이지/오류 처리, workflow의 권한·동시 실행·실행 순서를 검사한다.
`just aws-check`, 신규 코드 커버리지와 `just check`를 실행하고 독립 보안 검토를 받는다.
