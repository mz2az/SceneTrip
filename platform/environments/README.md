# platform/environments — DEV·PRD 입력

| 환경 | 파일 | 가용성·보존 |
| --- | --- | --- |
| [dev/](dev/README.md) | `terraform.tfvars.json.example`, `backend.hcl.example` | 2 AZ·NAT 1개·Single-AZ DB·7일 백업 |
| [prd/](prd/README.md) | `terraform.tfvars.json.example`, `backend.hcl.example` | 3 AZ·NAT 3개·Multi-AZ DB·14일 백업·삭제 보호 |

DEV·PRD는 AWS 계정, state 버킷·키, GitHub Environment, 역할, ECR, Secret을 분리한다.
예제의 리전·AZ·인스턴스 종류는 설계 입력이고 실제 계정 지원 여부를 확인해야 한다.
각각의 `aws_account_id`는 해당 환경 계정 ID로 채운다. 예제의 자리표시자는
검증에서 실패하며 기본 자격증명이나 공개 접근으로 대체되지 않는다.

필수 외부 입력은 계정·리전·선택한 AZ, 기존 ACM 인증서 ARN, EKS API에 접근할
고정 runner egress CIDR, HTTPS에 접근할 승인된 사용자 CIDR이다.
인증서 DNS 검증과 API DNS 레코드, GitHub OIDC 최초 신뢰는 계정 관리자가 준비한다.
현재 설치 UUID는 로그인 인증이 아니므로 DEV·PRD 모두 인터넷 전체 접근을 금지한다.
두 환경 모두 `HTTPS ALB → private nginx gateway → Scene API` 경로를 사용한다.
`ingress_allowed_cidrs`는 ALB frontend 보안 그룹의 443과 nginx의 실제 client IP
허용 범위다. ALB가 배치될 public subnet ID와 proxy 신뢰 CIDR은 Terraform 출력에서
계산하며 사람이 client CIDR 대신 입력하지 않는다. DNS는 배포 결과의 ALB 주소로 연결한다.

DB·외부 API 비밀값은 이 디렉터리에 넣지 않는다. Terraform이 만든
`/scenetrip/<env>/scene-api`, `trip-guide`, `database` Secret에 배포 도구가
직접 입력한다. 값·state·plan·로컬 실설정 파일은 커밋하지 않는다.
