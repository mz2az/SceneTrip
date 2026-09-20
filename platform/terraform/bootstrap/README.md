# AWS 최초 부트스트랩

[template.json](template.json)은 환경별 state S3와 고정 EKS 역할, GitHub OIDC
배포 역할만 소유한다. VPC·DB·EKS는 Terraform이 소유한다.

| CloudFormation 입력 | 의미 |
| --- | --- |
| `Environment` | `dev` 또는 `prd` |
| `GitHubRepository` | 실제 SceneTrip 저장소의 `owner/repo`. 임의 기본값 없음 |
| `GitHubOidcProviderArn` | 같은 계정에 관리자가 먼저 구성한 GitHub OIDC provider |

출력 `DeploymentRoleArn`을 GitHub Environment의 AWS 배포 역할로 사용한다.
`TerraformStateBucket`, `TerraformStateKey`는 backend 입력이고
`ClusterRoleArn`, `NodeRoleArn`은 고정 서비스 역할이다.

state 버킷 이름은 `scenetrip-tfstate-<account>-<region>-<env>`,
키는 `scenetrip/<env>/terraform.tfstate`이다. S3 암호화·버전 보존·public
차단·TLS 강제·스택 삭제 시 보존을 설정한다. 배포 역할은 state를 읽고 쓰지만
삭제하지 못하며 `.tflock` 파일만 생성·삭제한다.

OIDC 신뢰는 정확한 저장소·Environment subject와 `sts.amazonaws.com` audience에
한정한다. Environment subject만으로 브랜치가 제한되지는 않으므로 GitHub
Environment에서 허용 브랜치·승인자를 관리한다. 최초 bootstrap 자격증명을
일상 배포에 사용하지 않는다.

배포 역할은 고정 EKS 역할을 수정하거나 임의 IAM 역할·정책을 만들지 못한다.
EC2 생성은 환경 요청 태그, 수정은 기존 환경 태그로 제한한다. RDS·ECR·EKS·
로그·Secret은 환경 이름으로 제한한다. RDS 관리자 Secret은
`aws:rds:primaryDBInstanceArn` 태그가 해당 환경 DB와 일치할 때만 읽는다.
이 IAM 정책의 실제 AWS API별 조건 평가는 첫 DEV 계획·적용에서 별도로 확인한다.

EKS가 만든 cluster SG에는 프로젝트 태그가 없으므로 ALB→gateway 규칙을 추가·수정·
제거하는 권한은 `aws:eks:cluster-name=scenetrip-<env>` 태그의 SG로 별도 제한한다.
ALB frontend SG와 개별 규칙은 기존 프로젝트·환경 태그 조건을 따른다. ALB·target group·
target health 조회는 해당 리전에 한정한 읽기 권한이며 배포기가 준비 상태를 확인한다.
Auto Mode의 backend SG 자동 관리는 끄고 Terraform만 이 규칙을 소유한다.
기본 EKS 역할의 IAM 정책을 배포 시 임의 확장하지 않는다.

EKS `CreateCluster`는 리소스 ARN 제한을 지원하지 않아 그 작업만 `Resource: *`를
사용하고 요청 환경 태그·리전·API 인증·생성자 관리자 비활성화를 조건으로 강제한다.
Auto Mode가 생성하는 접근 항목까지 고려하여 `CreateAccessEntry`는 이 환경의
고정 deploy·cluster·node 역할 세 개에 한정한다.
[공식 EKS IAM 작업 표](https://docs.aws.amazon.com/service-authorization/latest/reference/list_eks.html)와
정적 테스트가 이 예외를 설명·검증한다.

## 삭제

서비스 삭제가 완료된 뒤 **AWS 수동 삭제** workflow에서 `scope: bootstrap`을 선택한다.
`plan`으로 빈 state·잠금 부재·실제 서비스 리소스 부재와 소유권을 확인하고, `destroy`에는
`DELETE <env> <12자리계정>` 확인 문자열을 입력한다. 이 스택이 만드는 배포 역할은 삭제
대상이므로, 실행에는 스택 밖에서 관리하는 `AWS_BOOTSTRAP_ROLE_ARN`을 사용한다.

```bash
just aws-bootstrap-delete-plan dev false
just aws-bootstrap-delete dev false
```

두 번째 인자는 state 영구 삭제 여부다. 기본 `false`는 버킷과 모든 이력을 보존하고,
`true`는 스택 삭제 완료 후 Retain 버킷의 버전·삭제 마커·미완료 업로드와 버킷을 삭제한다.
스택이 삭제된 뒤 버킷 정리에서 실패해도 같은 환경·계정 검사를 거쳐 재시도할 수 있다.
OIDC provider와 외부 bootstrap 역할은 이 명령이 삭제하지 않는다.

보존된 버킷은 새 bootstrap의 동일 이름 생성과 충돌할 수 있다. 이후 재생성에는 별도
import/재사용 계획이 필요하다. 외부 역할 권한과 서비스 삭제 순서·남는 비용은
[삭제 런북](../../../docs/ops/aws-teardown.md)을 따른다.
