# AWS 배포 실행기

`just aws-*`가 Bazel Python과 고정 클라우드 CLI를 실행한다. 운영 절차와 필수 환경 값은
[배포 런북](../../docs/ops/aws-deployment.md), 설계는
[AWS 구성](../../docs/architecture/aws-dev-prd.md)을 따른다.

Actions는 권한이 없는 별도 preflight 잡에서 `main`의 검사기로 후보 SHA가 `main`에
포함되는지 먼저 확인한다. 이 잡에는 GitHub Environment나 OIDC 발급 권한이 없다.
검증된 SHA만 환경별 배포 잡에 전달하며, 후보 커밋의 코드를 검증 전에 실행하지 않는다.
부트스트랩 역할은 `bootstrap-plan`·`bootstrap-apply`에서만 선택한다. 두 역할의 환경변수를
함께 설정해도 일반 plan·apply·verify·cleanup은 배포 역할 세션만 허용한다.

- `aws.py`: 환경·체크아웃·AWS 계정 검증, CloudFormation 변경 집합, Terraform 저장 계획,
  SHA+실행 ID 이미지 푸시, HTTPS 검증.
- `config.py`: 입력 형식·비밀값 키 허용 목록과 프로세스 실행 경계.
- `deploy.py`: Secrets Manager → Kubernetes stdin 동기화, DB 준비·마이그레이션,
  Helm 배포. 런타임은 관리자 자격 증명을 받지 않는다.
- `alb.py`: subnet·신뢰 CIDR 검증, NodeClass의 실제 보안 그룹 검사, Ingress 주소와
  AWS application ALB의 VPC·subnet·보안 그룹·healthy IP target 확인.
- `db-bootstrap.sh`: PostgreSQL 역할과 `C`/`C.UTF-8` DB·확장·권한 준비.
- `tests/`: 잘못된 입력·계정 혼선·소스 격리·비밀값 전달·실패 정리·CLI 순서 회귀 검사.
- `integration/`: 폐기 가능한 로컬 PostgreSQL·nginx 컨테이너로 DB 권한과 gateway 동작 검사.

`just test //tools/aws:unit_test`는 AWS를 호출하지 않는다. `just aws-render dev`와
`just aws-render prd`는 예시 값으로 매니페스트를 보여주며 실제 클러스터를 조회하지 않는다.
실환경 배포는 `just --yes aws-apply`를 수동 Actions의 승인된 Environment 안에서 실행한다.
로컬은 `just aws-apply dev`의 확인을 거친다. Terraform 계획·상태·비밀값·Docker 인증은
artifact로 업로드하지 않고 임시 디렉터리/메모리에서만 취급한다.

ALB는 HTTPS 443을 종료하고 gateway의 ClusterIP HTTP 8080으로 전달한다. gateway는
ALB subnet의 연결만 신뢰하고, ALB가 X-Forwarded-For 마지막에 붙인 실제 클라이언트
주소로 접근 CIDR과 요청 속도를 검사한다. 신뢰 범위는 중복 없는 RFC1918 /24~27로
제한하며 현재 Terraform은 AZ마다 /24를 사용한다. Terraform이 전용 ALB 보안 그룹의
443 수신과 클러스터 보안 그룹의 8080 수신 규칙을 소유한다.

`just aws-verify dev` 또는 수동 Actions의 `verify`는 기존 state의 출력과 ALB·DNS·HTTPS만
조회한다. 소스·계정·배포 역할 검증은 유지하며 apply·이미지 발행·마이그레이션·Helm 변경은
수행하지 않는다. 첫 배포에서 출력한 ALB로 DNS를 연결한 뒤 이 명령으로 다시 확인한다.
ALB 주소·active·target healthy를 최대 600초 동안 확인하고, HTTPS의 일시적 DNS 조회·연결·
502/503/504 오류는 120초 동안 재시도한다. 개별 요청에도 제한 시간이 있으며 TLS 오류,
다른 ALB로 해석되는 DNS, 공개·차단 경로의 예상 상태 불일치는 즉시 실패한다.

클라우드 명령 실패 시 비밀값이 포함될 수 있는 stderr를 터미널에 재출력하지 않는다.
실제 AWS 서비스 로그와 CloudTrail, 비밀값을 출력하지 않는 kubectl 이벤트를 조사한다.
`--atomic`은 DB 스키마와 Terraform 리소스를 되돌리지 않는다.
