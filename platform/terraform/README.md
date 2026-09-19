# platform/terraform — SceneTrip AWS 인프라

| 경로 | 책임 |
| --- | --- |
| [bootstrap/](bootstrap/README.md) | CloudFormation: state S3, GitHub OIDC 배포 역할, 고정 EKS 역할 |
| [aws/](aws/README.md) | Terraform: 환경별 VPC·NAT·ALB SG·EKS Auto Mode·RDS17·ECR·비밀값 메타데이터 |
| [tests/](tests/README.md) | IAM·state·환경 입력 정적 보안 회귀 테스트 |

환경 값은 [platform/environments/](../environments/README.md)에 둔다.
Terraform 1.13.5·AWS provider 6.64.0과 프로바이더 체크섬을 고정한다.
컨테이너 배포·비밀값 읽기·DB 확장 설치는 Terraform state 밖의 배포 단계에서
수행한다. 현재 코드·예제는 실제 계정에 배포했다는 증거가 아니다.
ALB·listener·target group은 Helm Ingress를 보는 EKS Auto Mode가 생성하고,
ALB의 외부 443·private gateway 8080 보안 그룹 규칙은 Terraform이 관리한다.

`just test //platform/terraform:unit_test`는 AWS 자격증명 없이 실행한다.
`just tf-check dev`는 포맷·스키마·mock provider 형상 검증이고,
`just tf-plan dev`는 실제 계정과 기존 state를 읽는다.
`just tf-apply dev`는 계획 검토 후 실행하는 확인 대상이다.

부트스트랩은 계정 관리자에게 별도로 부여된 권한이 필요하다. 배포 역할에 IAM 역할
생성·신뢰 정책 변경 권한을 추가하여 이 단계를 우회하지 않는다.
