# DEV·PRD 서비스와 bootstrap 삭제

사용하지 않는 환경의 EKS·RDS·ALB·NAT Gateway 등 상시 비용을 줄이는 절차다.
GitHub Actions의 **AWS 수동 삭제**(`aws-destroy.yml`)를 사용하며, 서비스 삭제가
끝난 뒤 bootstrap을 별도 실행으로 삭제한다. 배포·PR·머지로 삭제가 시작되지는 않는다.
삭제한 DB와 이미지는 자동으로 돌아오지 않는다. 다시 사용할 데이터의 보존 정책을 먼저 정한다.

## 삭제 범위

| 선택 | 삭제 대상 | 유지되는 것 |
| --- | --- | --- |
| `scope: service` | Ingress/ALB·Helm release, Terraform이 관리하는 EKS·RDS·네트워크·ECR 이미지/저장소·로그 그룹·Secret | bootstrap 스택·state S3, 선택한 최종 RDS 스냅샷, 관리 범위 밖 리소스 |
| `scope: bootstrap`, `purge_state: false` | CloudFormation 스택의 배포·EKS cluster/node IAM 역할과 버킷 정책 | Retain 설정의 state S3와 모든 버전, 외부 OIDC provider·bootstrap 역할 |
| `scope: bootstrap`, `purge_state: true` | 위 스택과 state S3의 모든 버전·삭제 마커·미완료 multipart upload·버킷 | 외부 OIDC provider·bootstrap 역할과 관리 범위 밖 리소스 |

서비스 형상은 Terraform, bootstrap은 [CloudFormation template](../../platform/terraform/bootstrap/template.json)이
소유한다. state 버킷은 `scenetrip-tfstate-<account>-<region>-<env>`, state 키는
`scenetrip/<env>/terraform.tfstate`이다. 다른 프로젝트나 환경의 자원을 대신 삭제하지 않는다.
`aws-cleanup`은 임시 DB 자격 증명 정리 명령으로, 이 전체 삭제 절차와 별개다.

## 실행 전 준비

[배포 런북의 GitHub Environment](aws-deployment.md#2-github-environment)에 지정한
환경 값을 그대로 유지한다. 계정·리전·배포 역할·API 도메인·40자리 소스 SHA는 두 범위에서
검사하며, `TF_VAR_FILE_JSON`은 서비스 삭제에 사용한다. bootstrap 삭제에는 별도의
`AWS_BOOTSTRAP_ROLE_ARN`이 필요하다. bootstrap에서 배포 역할 ARN은 입력 검증용이므로
해당 역할이 앞선 시도에서 삭제됐어도 외부 bootstrap 역할로 재시도할 수 있다.

서비스 삭제 runner는 기존 `self-hosted`, `linux`, `x64`, `scenetrip-<env>` 라벨과
고정 egress를 사용한다. EKS API에 접근할 수 있고 삭제할 EKS·VPC 밖에 있어야 한다.
bootstrap은 GitHub의 Ubuntu runner에서 실행한다. 외부 bootstrap 역할과 OIDC provider는
이 스택 밖에서 관리해야 하며 삭제가 끝날 때까지 유지한다.

workflow는 `main`에서 실행한다. Environment/OIDC 권한이 없는 preflight가 입력 SHA의
형식과 `main` 포함 여부를 검사하고, 통과한 소스만 보호된 환경에서 실행한다.
배포·bootstrap·삭제는 같은 `aws-<env>` 동시 실행 그룹을 사용한다.
GitHub의 동시 실행 제한은 로컬 실행까지 잠그지 않는다. 삭제 작업 중에는 같은 환경의
로컬 Terraform·AWS 변경을 중지한다. 서비스 삭제 job과 배포 역할 세션은 최대 2시간,
bootstrap 삭제는 최대 1시간이다.

## 외부 bootstrap 역할의 삭제 권한

최초 생성만 허용된 역할에는 삭제 권한이 없을 수 있다. 계정 관리자는 기존 bootstrap 역할에
아래 실행 범위를 추가한다. 일반 배포 역할에 IAM·state 영구 삭제 권한을 부여하지 않는다.
계정·리전·환경별 스택과 세 역할, state 버킷 ARN으로 제한하고, 리소스 단위 제한이 불가능한
목록 API만 필요한 읽기 권한을 허용한다.

| 용도 | 필요한 작업 |
| --- | --- |
| 스택 조회·소유권 확인·삭제 대기 | `cloudformation:ListStacks`, `DescribeStacks`, `GetTemplate`, `ListStackResources` |
| 스택 삭제 | `cloudformation:DeleteStack` — `scenetrip-<env>-bootstrap` 스택 |
| 스택의 IAM 역할 정리 | `iam:GetRole`, `ListRolePolicies`, `GetRolePolicy`, `DeleteRolePolicy`, `ListAttachedRolePolicies`, `DetachRolePolicy`, `DeleteRole` — `scenetrip-<env>-deploy`, `scenetrip-<env>-eks-cluster`, `scenetrip-<env>-eks-node` |
| 버킷 소유권·리전·버전·state 검사 | `s3:ListAllMyBuckets`, `GetBucketLocation`, `GetBucketTagging`, `ListBucketVersions`, `ListBucketMultipartUploads`, `GetObjectVersion` |
| 스택의 버킷 정책 정리 | `s3:GetBucketPolicy`, `DeleteBucketPolicy` — 해당 state 버킷 |
| `purge_state: true`일 때 추가 삭제 | `s3:DeleteObjectVersion`, `AbortMultipartUpload`, `DeleteBucket` — 해당 state 버킷과 객체 |
| 실제 서비스 리소스 잔존 검사 | `eks:ListClusters`, `rds:DescribeDBInstances`, `ec2:DescribeVpcs`, `ecr:DescribeRepositories`, `secretsmanager:ListSecrets` |

각 행에서 접두사를 생략한 작업은 첫 작업과 같은 서비스에 속한다. IAM 삭제는 실행기가
임의 역할을 찾아 직접 지우는 방식이 아니라 CloudFormation이 자기 스택의 역할을 정리할 때
필요한 권한이다. `purge_state: false`의 plan도 버전 목록과 현재 state를 읽을 수 있어야 한다.
상태가 비었다는 이유만으로 실제 리소스 조회 권한을 생략하지 않는다.

S3는 이름만 대조하지 않고 계정 소유자·리전·프로젝트/환경/CloudFormation 태그까지 검사한다.
삭제 역할이 다른 환경까지 정리할 수 있도록 포괄적인 관리자 권한으로 우회하지 않는다.

## GitHub Actions 실행

저장소의 Actions → **AWS 수동 삭제** → **Run workflow**에서 다음 값을 지정한다.

| 입력 | 값과 의미 |
| --- | --- |
| `environment` | `dev` 또는 `prd` |
| `scope` | 기본 `service`. 서비스 삭제 완료 후 별도 실행에서 `bootstrap` |
| `operation` | 기본 `plan`; 삭제 실행은 `destroy` |
| `commit_sha` | 삭제 도구를 포함하며 `main`에 포함된 전체 40자리 SHA |
| `snapshot_policy` | 서비스 전용. 기본 `retain`은 최종 RDS 스냅샷 생성, `discard`는 최종 스냅샷 생략 |
| `purge_state` | bootstrap 전용. 기본 `false`는 state S3 보존, `true`는 이력까지 영구 삭제 |
| `confirmation` | `destroy`에서 `DELETE <env> <12자리계정>`을 정확히 입력. `plan`에서는 불필요 |

서비스 실행에서 `purge_state`는 `false`로 유지한다. bootstrap에서는
`snapshot_policy`를 기본값 `retain`으로 두며, 이 값으로 기존 스냅샷을 삭제하지 않는다.

1. `scope: service`, `operation: plan`으로 대상·준비 변경·삭제 계획을 검토한다.
2. 같은 환경·SHA·스냅샷 정책으로 `operation: destroy`를 실행한다. 실행 중 새 계획을
   검증하여 적용하므로 이전 plan artifact를 그대로 승격하는 절차는 아니다.
3. 서비스 삭제 완료와 빈 Terraform state를 확인한다. bootstrap을 유지하면 이후
   서비스 재배포에서 기존 state 저장소와 역할을 사용할 수 있다.
4. bootstrap도 불필요하면 `scope: bootstrap`, `operation: plan`으로 남은 자원을 확인한다.
5. state 보존 여부를 결정한 뒤 같은 입력의 `operation: destroy`를 실행한다.

`plan`은 보호 해제·Ingress/Helm 제거·스택 삭제·S3 삭제를 수행하지 않는다. Terraform backend의
일시적인 state 잠금은 사용할 수 있다. plan/state/Secret 원문은 Actions artifact로 올리지 않는다.

로컬에서도 같은 입력·소스·AWS 역할을 준비하고 다음 레시피를 사용한다. 삭제 레시피는
확인을 요구하며, 별도로 `AWS_DELETE_CONFIRMATION`에 위 확인 문자열을 설정해야 한다.

```bash
just aws-destroy-plan dev retain
just aws-destroy dev retain
just aws-bootstrap-delete-plan dev false
just aws-bootstrap-delete dev false
```

## 서비스 삭제가 진행되는 순서

1. 소스·환경·AWS 계정·배포 역할을 검증하고 기존 backend에 연결한다.
2. 격리된 임시 Terraform override로 삭제 준비 계획을 만든다. RDS/EKS 삭제 보호 해제,
   ECR의 이미지 포함 삭제와 선택한 RDS 최종 스냅샷 정책만 허용한다. 생성·교체 또는
   허용된 속성 밖 변경이 있으면 중단한다. 일반 배포의 PRD 보호 설정은 바꾸지 않는다.
3. 소유권을 확인한 gateway Ingress를 먼저 삭제하고, Ingress와 AWS ALB가 실제로 사라질 때까지
   기다린다. 이 동안 IngressClass·IngressClassParams와 EKS는 유지한다. 그 뒤 Helm release를
   `--no-hooks`로 제거한다.
4. 검증한 준비 계획을 적용한다. 이어서 새 삭제 전용 저장 계획을 생성·검증하여 적용하고,
   Terraform 관리 리소스가 남지 않았는지 확인한다. 중간 실패를 성공으로 처리하거나 실패 후
   자동으로 bootstrap을 삭제하지 않는다.

`retain`은 이번 삭제 시점의 최종 스냅샷을 남긴다. `discard`는 그 최종 스냅샷을 만들지
않는 선택이며, 이전 수동 스냅샷이나 다른 리전의 백업을 찾아 지우는 기능이 아니다.
RDS 삭제 보호를 끄는 것과 최종 스냅샷 생성 여부는 별도 설정이다.
[RDS 삭제 동작](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_DeleteInstance.html)

최종 스냅샷 이름은 `scenetrip-<env>-final-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>`다.
동일 이름이 이미 있으면 첫 변경 전에 중단한다. 로컬 실행도 고유한 실행 ID와 재시도 번호를
설정한다. Ingress/ALB 삭제 확인은 최대 600초이며, finalizer를 강제로 제거하지 않는다.
EKS가 이미 없어졌어도 ALB가 남아 있으면 중단하여 원인을 조사한다. EKS와 ALB가 모두
사라졌다면 나머지 Terraform 리소스 삭제를 이어갈 수 있다.

## bootstrap 삭제와 재시도

bootstrap 삭제기는 현재 state의 관리 리소스가 비었는지, `.tflock`이 없는지, 해당 환경의
실제 서비스 리소스가 남아 있지 않은지 검사한다. AWS 조회 실패를 리소스가 없다는 뜻으로
취급하지 않는다. 서비스 형상이 남아 있거나 잠금이 있으면 삭제를 중단한다.
state가 한 번도 만들어지지 않은 bootstrap도 미사용 상태를 확인한 경우에만 삭제한다.
과거 state 버전은 있는데 현재 state가 지워진 경우는 빈 환경으로 간주하지 않는다.
현재 state를 복구하여 실제 관리 리소스를 대조한 뒤 삭제를 다시 계획한다.

검사를 통과하면 CloudFormation 스택을 삭제하고 완료까지 기다린다. `purge_state: true`인
경우에만 보존된 S3의 모든 버전·삭제 마커·미완료 업로드를 정리하고 버킷을 삭제한다.
스택 삭제 후 S3 정리에서 실패하면 동일한 입력으로 재시도한다. 이미 스택이 없어도
정확한 계정·환경의 보존 버킷을 다시 검사하여 정리할 수 있다.
부분 실패 때 검증 근거가 남도록 현재의 빈 state 버전은 과거 버전보다 마지막에 삭제한다.
스택 삭제가 실패하면 S3 영구 삭제 단계로 넘어가지 않는다.

CloudFormation의 `Retain`은 스택 삭제 후에도 버킷을 남긴다. 버전 관리 버킷은 현재 객체만
지워서는 삭제할 수 없으므로 모든 버전의 정리가 필요하다.
[CloudFormation 보존 정책](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-attribute-deletionpolicy.html),
[S3 버킷 삭제](https://docs.aws.amazon.com/AmazonS3/latest/userguide/delete-bucket.html)

잠금이 남으면 먼저 실행 중인 배포·삭제 작업이 없는지 확인한다. 조회 권한 오류나 네트워크
문제를 해결하려고 state 또는 잠금을 임의로 지우지 않는다. 준비 계획 적용 이후 실패했다면
일부 삭제 보호가 이미 해제됐을 수 있다. 재시도로 삭제를 마치거나 정상 배포 계획을 검토하여
보호를 복구한다. 실행 취소는 이미 삭제한 자원을 되돌리지 않는다.

## 남는 비용과 재배포 조건

| 남을 수 있는 항목 | 후속 처리 |
| --- | --- |
| 최종·기존 수동 RDS 스냅샷, 별도로 보존한 백업 | 복구 필요성과 보관 기한을 정하고 별도 삭제. DB 삭제와 함께 자동 제거되지 않음 |
| `purge_state: false`의 S3 버전과 객체 | 과거 state 보관 비용 유지. 불필요해지면 bootstrap 삭제를 `purge_state: true`로 재실행 |
| Terraform 관리 밖 RDS CloudWatch 로그 그룹 | 보관 기한·삭제 정책을 별도로 적용 |
| 외부 runner·추가 EBS·DNS hosted zone 등 | 해당 소유자가 사용 여부·요금을 확인하고 별도 정리 |
| 외부 ACM 인증서·OIDC provider·bootstrap 역할 | 공유 사용과 재배포 필요성을 확인. 이 workflow는 삭제하지 않음 |

스냅샷과 보존 백업은 삭제 전까지 비용이 발생한다.
[RDS 스냅샷 삭제 안내](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_DeleteSnapshot.html)
RDS가 CloudWatch로 보낸 로그도 별도 보관 기간이 없으면 계속 남는다.
[RDS 로그 보관](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_LogAccess.Procedural.UploadtoCloudWatch.html)
이 workflow 성공은 계정 전체의 비용이 0이라는 뜻이 아니다.

Secret은 기존 DEV 7일·PRD 30일 복구 유예로 삭제 예약한다. **삭제 예약된 Secret은 과금되지
않지만 값에 접근할 수 없다.** 같은 이름으로 즉시 재생성할 수 없으므로 다시 쓸 때는
유예 기간 안에 복원한 뒤 Terraform state로 가져오는 별도 복구 계획을 수립하거나,
실제 영구 삭제가 끝날 때까지 기다린다. 자동 강제 삭제로 유예를 없애지 않는다.
[Secret 삭제·요금](https://docs.aws.amazon.com/secretsmanager/latest/userguide/manage_delete-secret.html),
[Secret 복원](https://docs.aws.amazon.com/secretsmanager/latest/userguide/manage_restore-secret.html)

bootstrap만 삭제하고 S3를 보존했다면 같은 이름으로 bootstrap을 새로 만드는 작업은
버킷 이름 충돌로 실패할 수 있다. 남은 버킷을 CloudFormation으로 가져오거나 재사용하는
계획을 별도로 준비한다. `purge_state: true`로 삭제한 과거 state는 복구할 수 없다.

서비스를 다시 배포하면 새 ALB 주소에 DNS를 연결하고 ECR 이미지를 다시 발행해야 한다.
새 RDS는 이전 스냅샷을 자동 복원하지 않는다. 데이터 복원·Secret 준비·migration·기능 검증을
[배포/복구 절차](aws-deployment.md)에 따라 수행한다.

## 삭제 기록

환경·계정·리전·SHA·workflow URL·스냅샷 정책과 생성된 스냅샷 식별자·state 보존 여부·
완료 단계·남은 리소스와 담당자를 기록한다. 실제 AWS 호출은 오프라인 단위 테스트와
구분해서 기록하고, 로그에 state 원문이나 비밀값을 첨부하지 않는다.
