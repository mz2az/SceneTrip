# SceneTrip AWS 스택

두 환경은 같은 Terraform 소스를 사용한다. `environment`에 따라 AZ 수·NAT 수,
Multi-AZ·백업·삭제 보호가 정해져 PRD에서 우연히 DEV 설정을 쓰지 못한다.

| 소스 | 구성 |
| --- | --- |
| [variables.tf](variables.tf) | 계정·리전·AZ·CIDR·ACM·DB 크기 입력 검증 |
| [network.tf](network.tf) | public /24, compute /20, isolated DB /24 서브넷, AZ별 경로 |
| [ingress.tf](ingress.tf) | ALB frontend SG와 승인 CIDR 443, ALB→gateway 8080 SG 규칙 |
| [eks.tf](eks.tf) | API 인증·명시적 배포 접근, private 노드, Auto Mode, 제어면 로그 |
| [database.tf](database.tf) | 비공개 PostgreSQL17, EKS SG만 5432 허용, SSL·암호화 |
| [registry-secrets.tf](registry-secrets.tf) | 불변 이미지 저장소 3개, 값 없는 Secret 3개 |
| [outputs.tf](outputs.tf) | 배포 도구가 읽는 비밀값 없는 출력 계약 |
| [versions.tf](versions.tf) | Terraform·provider 버전과 S3 잠금 |
| [tests/](tests/README.md) | DEV·PRD 계획과 잘못된 입력을 검증하는 mock 테스트 |

DB 서브넷에는 인터넷·NAT 기본 경로가 없다. EKS 노드는 compute 서브넷에만
생성되고 외부 지도·모델 API 호출은 NAT를 거친다. NAT는 외부 요청을 받는
프록시가 아니다. HTTPS 진입점은 Helm Ingress를 통해 EKS Auto Mode가 생성하는
ALB다. ALB는 public subnet에서 TLS를 종료하고 private gateway Pod IP의 8080으로
직접 전달한다. Ingress가 ClusterIP Service를 참조해 대상을 찾지만 패킷이 ClusterIP를
경유하는 것은 아니다.

## 출력 계약

`aws_region`, `aws_account_id`, `environment`, `cluster_name`, `namespace`,
`database_host`, `database_port`, `database_name`, `database_master_secret_arn`,
`ingress_certificate_arn`, `ingress_allowed_cidrs`를 출력한다.

- `ecr_repository_urls`: `scene_api`, `trip_guide`, `migration`.
- `app_secret_arns`: `scene_api`, `trip_guide`, `database`.
- `vpc_id`, `private_subnet_ids`, `topology`: 운영·구성도 검토용.
- `public_subnet_ids`: IngressClassParams가 선택하는 ALB 서브넷.
- `public_subnet_cidrs`: ALB private source IP 범위. nginx proxy 신뢰와 gateway NetworkPolicy에 동일 적용.
- `alb_security_group_id`: 승인 사용자 CIDR의 443만 받는 frontend SG.
- `workload_security_group_id`: ALB의 8080 연결을 받는 EKS cluster SG. 배포 전 기본 NodeClass의 실제 SG와 대조.

ALB에는 Terraform이 만든 SG를 지정하므로 Ingress의 `inbound-cidrs` annotation만으로
접근을 제한할 수 없다. 실제 외부 경계는 `ingress_allowed_cidrs`로 만든 frontend SG
규칙이다. ALB egress와 workload ingress는 SG 참조로 TCP8080만 연결한다.
EKS의 기존 control-plane·self 규칙은 EKS가 소유하며, workload 간 접근은
NetworkPolicy가 추가로 제한한다. custom NodeClass·별도 Pod SG로 바꾸려면 이 계약도
함께 변경해야 하며, 배포기는 알 수 없는 SG로 자동 대체하지 않는다.

ECR 경로는 `scenetrip-<env>/scene-api`, `trip-guide`, `migration`이고
태그는 변경 불가다. 이미 배포한 이미지는 같은 태그로 덮어쓸 수 없다.
Secret ARN만 출력하며 Secret value 데이터 소스·random password를 사용하지 않는다.
RDS 관리자 비밀값은 임시 DB bootstrap Job만 사용하고 서비스 파드에 전달하지 않는다.
PostGIS·pg_trgm 설치와 앱 역할 분리는 배포 도구의 DB 단계가 맡는다.

## 권한과 운영 한계

CloudFormation이 생성한 `scenetrip-<env>-eks-cluster`·`-eks-node`를
Terraform은 읽어서 전달한다. 배포 역할 `scenetrip-<env>-deploy`만 EKS
접근 항목으로 등록한다. 역할의 AWS 권한은 해당 환경으로 제한되지만 namespace
생성·배포를 위해 그 클러스터의 관리자다. GitHub Environment 브랜치 보호와
승인자, 분리된 runner를 설정한다.

PRD DB는 삭제 보호를 의도적으로 해제하기 전에는 제거되지 않는다. 최종 스냅샷
이름 `scenetrip-prd-final`이 이미 존재하면 삭제가 실패한다. 폐기 시 운영자가
보존할 스냅샷을 확인하고 충돌 없는 고정 식별자로 변경한 계획을 리뷰해야 한다.
자동 삭제·자동 destroy는 제공하지 않는다.

## 공식 근거

- [AWS EKS Auto Mode IAM](https://docs.aws.amazon.com/eks/latest/userguide/auto-learn-iam.html):
  cluster·node 역할과 BlockStoragePolicyV2.
- [AWS EKS Auto Mode 생성](https://docs.aws.amazon.com/eks/latest/userguide/automode-get-started-cli.html):
  compute·storage·load balancing 기능의 공동 활성화.
- [EKS Auto Mode ALB](https://docs.aws.amazon.com/eks/latest/userguide/auto-configure-alb.html):
  `eks.amazonaws.com/alb` IngressClass와 명시적인 ALB 서브넷.
- [EKS cluster SG](https://docs.aws.amazon.com/eks/latest/userguide/sec-group-reqs.html):
  EKS 소유 규칙과 `aws:eks:cluster-name` 태그.
- [RDS PostgreSQL17 확장](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-extensions.html):
  PostGIS·pg_trgm 지원 목록. 실제 선택된 minor 버전에서도 배포 전에 확인한다.
- [RDS PostGIS 관리](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.PostgreSQL.CommonDBATasks.PostGIS.html):
  확장 설치용 관리자 권한을 런타임과 분리하는 근거.
- [Terraform S3 backend](https://developer.hashicorp.com/terraform/language/backend/s3):
  S3 잠금 파일·버전 보존과 상태 접근 권한.
