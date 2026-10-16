# DEV·PRD AWS 배포와 복구

대상은 `scene-api`·`trip-guide`·RDS다. iOS·Android 스토어 제출은 별도다.
형상은 [아키텍처](../architecture/aws-dev-prd.md), 서비스 용어는
[AWS 해설](../architecture/aws-services.md), 수업은 [교육자료](../education/README.md)를 본다.

**저장소 구성과 실환경 배포는 다르다.** 이 절차는 계정·도메인·GitHub 환경 설정을
완료한 운영자가 실행한다. AWS 생성·이미지 push·공용 DB 변경은 비용과 공유 상태를
바꾼다. 로컬 검증은 실제 AWS 배포 성공을 대신하지 않는다.

## 1. 계정과 외부 입력 준비

DEV와 PRD를 분리된 AWS 계정으로 준비하는 것을 권장한다. **SceneTrip 은 같은 계정에 둔다**([ADR 0024](../architecture/adr/0024-prd-same-account-and-public.md)) — 환경은 `scenetrip-<env>` 이름으로 나눈다. 다음 표를 환경별로
확정한다. 예제의 문서용 주소·ARN을 실제 값으로 착각하지 않는다.

| 입력 | 누가 준비하는가 | 확인할 내용 |
| --- | --- | --- |
| 계정 ID·리전 | AWS 운영자 | workflow 역할의 실제 계정과 일치 |
| 기존 GitHub OIDC provider ARN | AWS 관리자 | issuer `token.actions.githubusercontent.com`, STS audience |
| 최초 bootstrap 역할 | AWS 관리자 | 승인된 workflow에서만 사용, CloudFormation/IAM 생성 권한 |
| API 도메인·ACM 인증서 | 도메인 관리자 | 소유 확인 완료, 같은 계정·리전, 도메인 일치 |
| 사용자 진입 CIDR | 운영자 | 회사/VPN/검증 단말과 HTTPS 검증 runner의 고정 egress; 전체 인터넷 아님 |
| EKS API 접근 CIDR | 운영자 | 배포 runner의 고정 egress; 사용자 CIDR와 별개 |
| self-hosted 배포 runner | 저장소 관리자 | Linux x64, 환경별 라벨, 신뢰된 작업만 실행 |
| Kakao 키·애플 로그인 키(.p8, PRD 필수) | 서비스 운영자 | Secrets Manager `/scenetrip/<env>/scene-api` 에 입력, GitHub 로그·tfvars·repo에 넣지 않음. 챗봇 모델(Bedrock)은 키가 없다 — trip-guide 파드가 Pod Identity 역할 `scenetrip-<env>-bedrock` 로 부른다(MZ2AZ-317) |

배포 runner는 **이번 작업으로 만들 EKS 안에 먼저 있어야 하는 자원으로 가정하지
않는다.** 이미 운영하는 네트워크에서 고정 egress로 AWS/EKS API에 연결할 수 있어야
최초 클러스터 생성도 가능하다. runner를 PR의 임의 코드를 실행하는 공용 runner와
공유하지 않는다. job마다 작업 경로·임시 kubeconfig·Secret 잔여물을 정리한다.
runner는 EKS 제어 API뿐 아니라 공개 API의 HTTPS도 검사하므로 자신의 egress가
`eks_public_access_cidrs`와 `ingress_allowed_cidrs` 양쪽에 포함되어야 한다.
두 입력은 목적이 다르며 한쪽에 넣었다고 다른 쪽까지 허용되는 것은 아니다.

## 2. GitHub Environment

`dev`, `prd` Environment를 만들고 배포 브랜치를 `main`으로 제한한다.
PRD에는 검토자를 설정한다. 환경 OIDC subject는 브랜치 문자열을 포함하지 않으므로
브랜치 보호를 생략하면 다른 브랜치의 workflow가 환경에 접근할 수 있다.

| Environment variable | 의미 |
| --- | --- |
| `AWS_ACCOUNT_ID` | 대상 12자리 AWS 계정 |
| `AWS_REGION` | 대상 리전 |
| `AWS_DEPLOY_ROLE_ARN` | bootstrap 출력의 환경별 배포 역할 |
| `AWS_API_DOMAIN` | HTTPS로 제공할 API 도메인 |
| `TF_VAR_FILE_JSON` | 해당 환경의 **비밀값 없는** Terraform 입력 JSON |
| `AWS_BOOTSTRAP_ROLE_ARN` | 최초 CloudFormation 변경용 기존 역할 |
| `AWS_LIFECYCLE_ROLE_ARN` | bootstrap 출력 `LifecycleRoleArn`. 올리기·내리기 workflow 전용 역할 |
| `AWS_GITHUB_OIDC_PROVIDER_ARN` | 사전에 등록한 GitHub OIDC provider. GitHub 이 `GITHUB_` 접두사 변수를 금지하므로 이 이름으로 저장하고 workflow 가 `GITHUB_OIDC_PROVIDER_ARN` 환경변수로 넘긴다 |

입력 JSON은 `platform/environments/<env>/terraform.tfvars.json.example`을 따른다.
role ARN·CIDR·인증서·AZ를 운영 값으로 채우되 암호나 외부 API 키를 넣지 않는다.
일반 배포 역할의 IAM 권한을 늘려 bootstrap 문제를 해결하지 않는다.

runner 라벨은 `self-hosted`, `linux`, `x64`, `scenetrip-dev` 또는 `scenetrip-prd`다.
bootstrap은 기존 역할을 사용하므로 클러스터 runner에 의존하지 않는다.

배포·bootstrap·삭제 workflow는 Environment·OIDC 권한이 없는 별도 사전검증 job에서 신뢰된 `main`의
명령으로 입력 SHA 형식과 `main` 포함 여부를 검사한다. 통과한 SHA만 후속 권한 job이
checkout한다. 검증할 소스 자체에 AWS 권한을 먼저 주지 않는다.

## 3. 저장소 검증

```bash
just check
just aws-check
```

위 명령은 실제 AWS 변경을 하지 않는다. Terraform·chart·테스트 검증 결과와
AWS 실계정의 권한·quota·인증서 상태 검증을 구분한다. 정의된 단위 테스트뿐 아니라
Terraform provider mock과 Helm 실제 렌더도 확인한다.

배포 입력을 채운 깨끗한 checkout에서는 `just aws-validate dev` 또는
`just aws-validate prd`로 계정·환경·commit 입력을 확인한다. 이 명령은 입력 검증이며
전체 테스트를 대신하지 않는다. AWS 실행 도구는 Linux x64 배포 runner가 대상이고,
macOS arm64에서는 Terraform·Helm 렌더·오프라인 검증을 지원한다.

전체 검증이 로컬 SDK/도구 부재로 막히면 해당 원인을 기록한다. 특정 테스트만
통과한 것을 `just check` 통과라고 바꿔 쓰지 않는다.

## 4. 최초 bootstrap

GitHub Actions의 AWS bootstrap workflow에서 환경과 plan/apply를 선택한다.
로컬에서 같은 작업을 수행할 때도 다음 레시피를 사용한다.

```bash
just aws-bootstrap-plan dev
just aws-bootstrap-apply dev
```

CloudFormation change set에서 state 버킷과 고정 역할만 생성/변경하는지 확인한다.
apply는 확인 레시피이며 대상 환경을 표시한다. 출력의 배포 역할·state bucket/key·
EKS cluster/node 역할을 해당 Environment와 Terraform 입력에 반영한다.

state bucket은 public access 차단·암호화·versioning을 갖고 삭제 시 유지한다.
동일 버킷 이름이나 key를 다른 환경에서 재사용하지 않는다. OIDC provider는 기존
계정 자원으로 전달하며 이 스택이 다른 프로젝트의 provider를 덮어쓰지 않는다.

## 5. 계획과 배포

```bash
just aws-plan dev
just aws-apply dev
```

GitHub에서는 AWS deploy workflow의 Run workflow로 같은 절차를 시작한다.
push·PR·schedule 이벤트는 배포를 실행하지 않는다. 배포 SHA는 전체 40자리 commit으로
입력하며, workflow가 허용하는 main의 검증된 revision을 선택한다.

`plan`으로 리소스 추가·삭제·교체, RDS 데이터 보존, 네트워크 CIDR를 검토한다.
`apply` 실행은 **그 실행 안에서 새 계획을 만들고 저장한 계획을 적용**한다. 이전 plan
실행의 artifact를 자동 승격하는 방식이 아니므로 중간에 소스·입력·state가 바뀌었다면
변경을 다시 검토한다. saved plan은 민감 정보를 포함할 수 있어 공개 artifact로
올리지 않는다.

배포기는 다음 경계를 지킨다.

1. 환경·계정·역할·commit·도메인 입력을 검사한다.
2. Terraform state를 환경별로 잠그고 계획을 적용한다.
3. 동일 소스의 API·migration·agent OCI 이미지를 고정 태그로 ECR에 등록한다.
4. 클러스터 신원·Auto Mode NodeClass의 보안 그룹을 확인하고 NetworkPolicy controller를 활성화한다.
5. 비밀값을 출력 없이 동기화한다. RDS master는 일시적인 bootstrap Job에만 쓴다.
6. DB·앱 계정·확장·한글 검색 로케일을 준비하고 migration Job을 완료한다.
7. Helm으로 ALB Ingress·gateway·API·agent를 배포하고 준비 상태를 기다린다.
8. Ingress 주소와 AWS application 타입·active 상태·target health를 제한 시간 안에 확인한다.
9. 외부 HTTPS와 내부 연결을 점검한다. 하나라도 실패하면 성공으로 보고하지 않는다.

ECR 태그는 immutable이다. 같은 태그가 충돌하면 덮어쓰기를 켜는 대신 이전 이미지
digest와 revision을 확인한다. PRD에서는 DEV에서 검증한 소스와 이미지 digest가
동일한지 기록한다. 환경 입력은 이미지 안에 굽지 않는다.

로컬 실행에는 `AWS_COMMIT_SHA`, 고유한 `GITHUB_RUN_ID`, 재시도 번호
`GITHUB_RUN_ATTEMPT`도 지정한다. workflow는 이 값을 자동으로 제공하지만
로컬 기본 실행 번호를 반복 사용하면 ECR의 불변 태그가 충돌한다.

## 6. DB와 Secret 초기화

앱용 DB 암호는 처음에만 생성하고 다음 배포에서 재사용한다. 배포마다 암호를
바꾸면 이전 Pod와 DB의 인증이 엇갈린다. rotation은 별도의 계획으로 수행한다.
외부 키는 Secrets Manager의 해당 환경 Secret에 운영자가 넣는다. 키가 없는데
임의 값으로 대체해 배포 성공을 만들지 않는다.

DB는 `scenetrip`이며 UTF8·한글 인식 locale·PostGIS·pg_trgm을 확인한다.
관리자 권한과 앱 계정을 분리하고 앱 기동 시 Flyway를 끈다. migration Job이
실패하면 애플리케이션 롤아웃으로 넘어가지 않는다. 기존 migration checksum을
수정하거나 무조건 repair/baseline으로 우회하지 않는다.

데이터는 migration에 포함되지 않는다. 로컬 `just seed`, `just seed-poi`,
`just seed-reset`, `just db-recreate`는 AWS 운영 데이터 적재/복구 명령이 아니다. 로컬 절차를
원격 DB URL로 바꿔 실행하지 않는다.

**원격 데이터 적재는 `aws-deploy` workflow 의 `data-load` 동작이다**(`just aws-data-load <env>`, MZ2AZ-414,
[계획](../project/plans/remote-data-load.md)). 이 커밋의 적재 이미지(`scenetrip-<env>/data-load` — postgres + 적재 SQL·
촬영지 CSV·편의시설 판)를 올리고 EKS Job `data-load` 로 촬영지 → 편의시설 → 같은 곳 연결 → 인기도 → 검색 색인을 넣는다.

- 계정은 소유자 `app_migrate`(임시 Secret `database-load-transient`, 끝나면 지운다), 네트워크는 5432 만(정책 `database-jobs`)
- **지우지 않는다** — 몇 번을 돌려도 같다. 사용자 데이터를 지우는 `seed-reset` 은 원격에 없다
- 건수가 0 이면 실패, 성공하면 `DATA_LOAD_SUMMARY {json}` 한 줄을 출력하고 DB `data_load` 에 기록한다. 실패하면 단계 줄(`==>`)만
  출력한다 — SQL 출력·비밀값은 내보내지 않는다
- DEV 는 `dev-lifecycle` up 이 배포 성공 뒤 자동으로 부른다. PRD 는 사람이 workflow 에서 고르고 Environment 검토자가 승인한다
- 로컬에서 같은 이미지를 돌려 보려면 `just data-load-local`

## 7. DNS와 검증 완료 조건

ALB 주소가 준비되면 운영 DNS에서 API 도메인을 연결한다. `scenetrip.io`의 DNS는
Cloudflare가 담당하며([ADR 0017](../architecture/adr/0017-dev-lifecycle-and-cloudflare-dns.md))
API 레코드와 ACM 검증 레코드는 **DNS only**여야 한다. 프록시를 켜면 ALB가 보는 접속자가
Cloudflare가 되어 허용 CIDR 검사에서 차단된다. 올리기 workflow는 이 CNAME을 자동으로 바꾼다. DNS와 ACM 연결이 끝나기 전까지
IP 주소로 앱을 배포하거나 TLS 검증을 끄지 않는다.

최초 배포는 ALB가 생성되어야 DNS 연결 대상을 알 수 있다. 배포기가 출력한 새 ALB
주소로 DNS를 연결하기 전에는 마지막 HTTPS 검증이 실패할 수 있다. 이 경우
인프라 생성·DB migration·Helm 배포가 이미 끝났는지 확인하고, DNS 전파 후 검증을
다시 수행한다. 실패 표시를 보고 DB를 재생성하지 않는다.

DNS 연결 후에는 기존 인프라를 읽어 검증만 실행한다. 배포와 같은 환경 변수·깨끗한
checkout·AWS 역할을 준비한 뒤 실행하며 Terraform apply·이미지 push·migration은 하지 않는다.

```bash
just aws-verify dev
just aws-verify prd
```

GitHub Actions에서는 AWS 수동 배포 workflow의 `operation: verify`를 선택한다.
같은 환경 보호와 SHA 사전검증을 거쳐 기존 리소스만 읽으며 apply 전용 정리 단계도 실행하지 않는다.

gateway Service는 ClusterIP이므로 외부 주소가 없는 것이 정상이다. 주소는
`Ingress/gateway`의 `status.loadBalancer.ingress`에서 얻는다. Helm의 `--wait` 완료와
ALB 준비는 별개이며 배포기가 AWS API로 타입·상태·target health를 추가 검사한다.
이상한 타입이나 보안 그룹 불일치는 기다려서 해결될 문제가 아니므로 배포를 중단한다.
DNS 조회·일시적 연결 실패와 502/503/504는 제한 시간 안에 재시도한다. 잘못 연결된 DNS,
인증서 오류, 내부 경로 노출 등은 일시적 준비 상태로 취급하지 않는다.

다음 표는 자동 검사와 운영자 확인을 합친 완료 조건이다. `aws-verify`의 자동 범위는
NodeClass 보안 그룹, Ingress 주소, ALB 타입·SG·VPC·subnet·상태·IP target health,
DNS/TLS, `/v1/contents` 200과 `/v1/actuator/health`·`/v1/internal` 404다.
listener 규칙 자체, 비허용 외부망·헤더 위조, `/v1/places`·`/plan`·가입 판정·관측과
DB 차단은 운영자가 별도로 확인한다. NetworkPolicy의 Pod 차단 시험은 `aws-apply`가
Job으로 수행하며 읽기 전용 `aws-verify`에서는 다시 실행하지 않는다.

| 확인 | 기대 결과 |
| --- | --- |
| 도메인 HTTPS | 인증서 체인·호스트명 정상, 허용 CIDR에서 접근 |
| ALB 구성 | application 타입, HTTPS 443, host·`/v1` 규칙, gateway IP target 정상 |
| 허용하지 않은 네트워크 | 접근 실패 |
| 전달 헤더 위조 | 임의 X-Forwarded-For로 허용 CIDR·요청 제한 우회 불가 |
| API `/v1/contents`·`/v1/places` | 계약에 맞는 응답, 초기 빈 데이터면 빈 결과 |
| 외부 `/v1/actuator`·agent 디버그 경로 | 노출되지 않음 |
| 내부 API readiness | DB와 연결된 준비 상태 |
| agent health·구조화 `/plan` | 내부 연결 정상, 실모델 호출 없는 경로 우선 |
| 등록이 필요한 기능 | 미등록 사용자는 기존 계약대로 거절 |
| network policy 차단 | 허용되지 않은 Pod에서 API·agent 접근 실패를 자동 검사; DB 접근 차단은 별도 실환경 확인 |
| 관측 | OTEL 비활성화 또는 실제 수집기에서 수신 확인 |

실제 모델(Bedrock) 호출은 비용이 발생한다. 운영 검증에서 필요한 최소 요청만 하고
테스트 결과에 모델 호출 여부를 기록한다. 일반 오프라인 테스트는 모델을 부르지 않는다.

앱은 다음처럼 HTTPS 주소를 명시하여 빌드한다. 서명·스토어 제출 단계는 별도다.

```bash
just mobile-build-cloud ios https://api.example.com/v1
just mobile-build-cloud android https://api.example.com/v1
```

## 8. 장애 진단과 롤백

먼저 **어느 환경·revision인지**, 다음으로 **어느 단계가 실패했는지**, 마지막으로
**DB 변경이 이미 완료됐는지**를 확인한다. 에이전트·API·gateway·RDS 원인을 섞지 않는다.

Helm의 `--atomic`은 Helm 업그레이드 안의 실패에 적용된다. 그 호출이 성공한 뒤 수행하는
NetworkPolicy·ALB·DNS·HTTPS 검증이 실패해도 이전 release로 자동 복귀하지 않는다.
새 release가 이미 실행 중일 수 있으므로 상태를 확인하고 원인 수정 후 `just aws-verify`
또는 검토된 재배포를 선택한다. NetworkPolicy 차단 실패를 고친 경우는 해당 Job까지
실행하는 재배포로 다시 확인한다. `aws-verify` 성공만으로 그 경계가 복구됐다고 기록하지 않는다.
어떤 경우에도 DB·Terraform 변경은 Helm이 되돌리지 않는다.

| 증상 | 최초 확인 | 복구 방향 |
| --- | --- | --- |
| OIDC AccessDenied | repo/environment subject, audience, Environment 보호 | 신뢰 조건 수정 검토. 장기 키로 우회하지 않음 |
| EKS 접속 timeout | runner egress CIDR·endpoint·DNS | 네트워크 경로 수정. 전체 공개로 임시 전환하지 않음 |
| Ingress 주소 없음 | Auto Mode IngressClass·subnet·controller 이벤트·IAM | 클래스·서브넷·권한 수정. Service 외부 주소를 기다리지 않음 |
| ALB target unhealthy | ALB SG→workload SG 8080·NetworkPolicy·`/healthz` | target IP와 실제 NodeClass SG 대조. 허용 범위를 전체 VPC로 넓히지 않음 |
| gateway 403 | 원래 접속 ALB subnet·XFF append·사용자 허용 CIDR | 신뢰 프록시/사용자 대역을 수정. 임의 XFF를 신뢰하도록 우회하지 않음 |
| migration 실패 | Job 상태, 실패 migration, locale·확장·권한 | 새 migration/권한 수정 후 재실행; 앱 배포 중단 유지 |
| API readiness 실패 | RDS SG·Secret·DB URL·앱 권한 | 연결 원인을 수정. master 계정을 앱에 넣지 않음 |
| GUIDE_UNAVAILABLE | agent Pod·내부 DNS·40초 timeout·키 | agent 복구, 메모리 세션 소실 안내 |
| 검색 결과/성능 이상 | 데이터 적재·search_term·한글 trigram | 정본 데이터·MV·locale부터 점검 |
| TLS 오류 | DNS·ACM ARN·리전·도메인 | 인증서 연결 수정, TLS 검증 유지 |

애플리케이션은 이전에 검증한 이미지 revision으로 다시 배포한다. **스키마는 이미지
롤백과 함께 되돌아가지 않는다.** 이전 앱과 새 스키마가 호환되는지 먼저 확인하고
호환되지 않으면 전진 수정 또는 RDS 시점 복구를 선택한다.

RDS 복구는 별도 인스턴스로 복원 → 데이터/스키마 검증 → 유지보수 창 확보 →
DB endpoint·Secret 전환 → smoke → 이전 DB 보존 순서로 진행한다.
복구 목표(RTO/RPO)는 실제 훈련 결과로 정하며 이 문서가 달성을 보장하지 않는다.

긴급 임시 Secret/Job 정리는 `just aws-cleanup <env>`의 삭제 대상을 먼저 확인한다.
이 명령은 Terraform destroy나 데이터베이스 초기화 명령이 아니다.
PRD 보호를 풀고 DB를 지우는 동작은 정상 배포/복구에 포함하지 않는다.

## 9. 이전 NLB 형상을 이미 배포한 경우

저장소의 최초 AWS 배포 전이라면 이 절차는 필요 없다. 이전 형상이 실제로 존재한다면
gateway Service의 LoadBalancer→ClusterIP 변경으로 NLB가 삭제될 수 있다. 현재 배포기는
두 로드밸런서를 병행 운영하는 무중단 전환 도구가 아니다.

1. 기존 Ingress/Service·DNS·보안 그룹·이미지 revision을 기록하고 변경 창과 DNS TTL을 준비한다.
2. bootstrap 권한 변경과 Terraform 계획에서 ALB 보안 그룹·8080 규칙을 검토한다.
3. ALB 형상을 배포하고 Ingress 주소·target health를 확인한다. 기존 DNS가 끊기는 구간을 예상한다.
4. API 도메인을 새 ALB에 연결하고 허용/비허용 접속·TLS·내부 경로 차단·업무 흐름을 확인한다.
5. 이전 NLB와 불필요한 규칙이 남았는지 확인하고 별도 검토 후 정리한다. 이력과 비용을 기록한다.

이전 chart로 롤백해도 같은 NLB 주소가 돌아온다는 보장은 없다. 새 주소의 DNS 전환이
다시 필요할 수 있다. DB 스키마 호환성을 함께 확인하고 자동 DB 되돌리기는 하지 않는다.

## 10. 버튼 하나로 올리고 내리기

bootstrap과 DNS 준비가 끝난 환경은 GitHub Actions의 **AWS 환경 올리기·내리기**
(`dev-lifecycle.yml`)로 다시 올리고 내린다. 기존 배포·삭제 workflow를 호출하므로 위 절의
검증과 보호가 그대로 적용된다.

| action | 하는 일 |
| --- | --- |
| `up` | runner EC2 켜기 → 삭제 예약된 `/scenetrip/<env>/` Secret 복원 → 배포 `apply` 호출 → ALB가 생기면 Cloudflare `api-<env>` CNAME 갱신(DNS only) → 배포 성공 확인 → runner 끄기 |
| `down` | runner EC2 켜기 → 삭제 `plan` → `destroy`(실패 시 같은 입력으로 1회 재시도) → runner 끄기 |

필요한 Environment 설정은 변수 `AWS_LIFECYCLE_ROLE_ARN`과 secret `CLOUDFLARE_API_TOKEN`
(`scenetrip.io` 존의 DNS 편집 권한만)이다. runner EC2는 태그 `project=scenetrip`·
`environment=<env>`·`role=github-runner`로 찾는다. 배포·삭제 run이 아직 진행 중이면 runner를
끄지 않는다. 진행 중에 끄면 state 잠금이 남는다.

유예 기간 안의 Secret은 `aws-apply`가 복원하고 Terraform state로 가져온다.
Secret이 복구 유예(DEV 7일)를 넘겨 완전히 삭제됐다면 Terraform이 빈 Secret을 새로 만들고
배포가 외부 키 단계에서 멈춘다. 운영자가 Kakao 키(와 애플 .p8)를 넣고 `up`을 다시 실행한다.
내릴 때마다 RDS는 새로 만들어지므로 데이터가 비워진다.

**DEV 바깥 IP 는 고정이다(MZ2AZ-472)** — 카카오 모빌리티는 허용 IP 만 받는다. DEV NAT 의 공인 IP 는 bootstrap 스택의
`NatEip`(태그 `role=nat-fixed`, 출력 `NatEipPublicIp`)라 내리기에도 남고, 배포(`network.tf`)가 그 IP 로 NAT 를 만든다 — 카카오에
한 번만 등록한다. bootstrap 에 아직 없으면 배포가 예전처럼 새 IP 를 만든다. 내려 둔 동안에도 공인 IPv4 요금(월 약 3.6 달러)이 난다.
PRD 는 내리지 않아 배포가 만든 IP 3 개가 그대로다.

## 11. 사용하지 않는 환경 삭제

GitHub Actions의 **AWS 수동 삭제**로 서비스 형상을 먼저 삭제하고, 완료 후 bootstrap을
별도로 삭제한다. `plan`이 기본이며 실제 삭제에는 환경·계정을 포함한 확인 문자열이 필요하다.
RDS 최종 스냅샷과 state S3는 기본 보존한다. 서비스 삭제 runner와 bootstrap 역할은
삭제 대상 밖에 유지해야 한다. 입력·권한·남는 비용·재배포 조건은
[삭제 런북](aws-teardown.md)을 따른다.

**PRD 는 이 경로로 지우지 못한다**(MZ2AZ-364, 2026-10-13). 워크플로 선택지에 `prd` 가 없고, 삭제 도구도 `prd` 의 `destroy` 를
거절한다(`tools/aws/teardown_options.py` `PROTECTED_ENVIRONMENTS`). 읽기 전용 계획은 도구에서 된다. 정말 지워야 하면 그 목록과
워크플로 선택지를 고치는 PR 이 먼저다. PRD 의 DB·EKS 삭제 보호와 최종 스냅샷 강제는 Terraform 이 그대로 켠다.

## 12. 사용자 사진 버킷 (리뷰 사진)

리뷰 사진은 `scenetrip-user-media-<계정>-<리전>-<환경>` 버킷에 산다. **bootstrap 이 만들고(`UserMediaBucket`,
`DeletionPolicy: Retain`) 서비스 내리기로는 지워지지 않는다** — dev 를 내렸다 올려도, 그 dev 버킷을 쓰는 로컬
kind 의 리뷰 사진도 그대로다. 계획: [review.md](../project/plans/review.md) §13.

| 무엇 | 어디 | 누가 |
| --- | --- | --- |
| 버킷(비공개·TLS 강제·`uploads/tmp/` 하루 뒤 삭제) · `scenetrip-<env>-media` 역할(Pod Identity) | bootstrap 템플릿 | bootstrap apply |
| scene-api 파드 ↔ 역할 연결(`aws_eks_pod_identity_association`) · 버킷 이름 출력 | `platform/terraform/aws/eks.tf` | 배포(올리기) |
| 파드의 서비스 계정 `scene-api` · `SCENETRIP_MEDIA_BUCKET` | Helm | 배포 |
| (로컬 kind) | 클러스터 안 MinIO — AWS 를 쓰지 않는다 | `just stack-up` |

**처음 한 번 — 계정 관리자가 한다.**

1. bootstrap 역할(`scenetrip-bootstrap`, 저장소 밖에서 관리)의 인라인 정책 `scenetrip-bootstrap-scope` 에 버킷 권한을
   더한다. 지금은 state 버킷(`scenetrip-tfstate-*`)만 다룰 수 있어 새 버킷을 만들지 못한다.

   ```json
   {"Sid":"UserMediaBucket","Effect":"Allow","Action":"s3:*",
    "Resource":["arn:aws:s3:::scenetrip-user-media-*","arn:aws:s3:::scenetrip-user-media-*/*"]}
   ```
2. GitHub Actions 의 bootstrap workflow 로 그 환경을 apply 한다 — 버킷·`-media` 역할이 생기고 배포 역할에 Pod
   Identity 권한이 붙는다.
3. ~~로컬용 IAM 사용자~~ — **만들 수 없다.** 조직 정책(SCP)이 `iam:CreateUser` 를 막는다(2026-10-07). 로컬은 MinIO 를
   쓴다([review.md](../project/plans/review.md) §14).

그 뒤 dev 를 올리면 파드가 키 없이 역할을 받는다. 버킷이 없거나 설정이 비면 서버는 뜨고 `POST /uploads` 만
`503 UPLOAD_UNAVAILABLE` 이다.

## 13. 끊긴 배포 정리

올리기·배포가 Terraform apply 도중 끊기면(러너가 끊기거나 취소되면) **잠금 파일과 state 밖 자원**이 남을 수 있다.
2026-10-09 dev 에서 실제로 일어났다 — 잠금 파일, state 에 적히기 직전의 EKS, 같은 이유로 state 에서 빠진 RDS 가 남아
다음 올리기는 「잠김」 으로, 내리기는 「EKS 가 state 밖에 있다」 로 멈췄다. 계획: [ops-protection.md](../project/plans/ops-protection.md).

**1. 점검(읽기만)** — `just aws-drift <env>`. 잠금 파일, state 밖 EKS·RDS·NAT·VPC 를 보여 주고, 어긋난 것이 있으면 실패로 끝난다.
노트북의 관리자 자격으로 돈다(호스트 `aws` — 고정 CLI 는 리눅스 러너 전용).

**2. 잠금 파일** — 도는 배포·삭제가 **정말 없는지** 먼저 본다(GitHub Actions 의 `aws-deploy`·`aws-destroy` 실행 목록, 러너 EC2
상태). 그다음 잠금 파일의 내용(`Who`·`Created`)이 끊긴 그 실행의 것인지 확인하고 지운다.

```sh
aws s3 cp s3://scenetrip-tfstate-<계정>-ap-northeast-2-<env>/scenetrip/<env>/terraform.tfstate.tflock -
aws s3 rm s3://scenetrip-tfstate-<계정>-ap-northeast-2-<env>/scenetrip/<env>/terraform.tfstate.tflock
```

**3. state 밖 자원** — 무엇인지 보고 정한다.

| 자원 | 이번에 만들어진 빈 것이면 | 데이터가 있거나 오래된 것이면 |
| --- | --- | --- |
| EKS(노드 그룹·애드온 없음) | `aws eks delete-cluster` 후 내리기를 다시 | `terraform import` 로 state 에 들인다 |
| RDS | **최종 스냅샷을 남기고** 지운다 — `aws rds delete-db-instance --final-db-snapshot-identifier scenetrip-<env>-final-manual-<날짜>` | 지우지 않는다. import 한다 |
| NAT·VPC | state 에 있는 나머지와 함께 내리기가 지운다. 남으면 콘솔에서 의존 자원(ENI·EIP)부터 | — |

**4. 다시 내리기(또는 올리기)** — dev 는 `dev-lifecycle` 의 down. 끝나면 `just aws-drift <env>` 가 「어긋난 것 없음」 이어야 한다.

## 14. 스냅샷·장면 사진 버킷

**dev 최종 스냅샷** — 내리기(retain)가 끝나면 `scenetrip-dev-final-*` 중 **최근 2 개만 남기고** 지운다(삭제 흐름이 자동으로).
올리기는 스냅샷을 복원하지 않으므로(빈 DB 로 시작) 최종 스냅샷은 사람이 꺼내 쓰는 백업이다. 손으로는 `just aws-snapshot-prune dev`
(미리 보기) · `--execute`. **prd 스냅샷은 이 명령이 지우지 않는다** — 배포 역할에도 prd 스냅샷 삭제 권한이 없다.

**장면 사진 버킷 `scenetrip-media-prod`** — 촬영지 장면 사진(시드의 `scene_image_url`). 모든 환경이 공개 주소로 읽는다. 2026-08-28
콘솔에서 만든 것을 CloudFormation 스택 `scenetrip-shared-media`(`platform/terraform/shared-media/template.json`)로 가져와
관리한다 — `DeletionPolicy: Retain`, **버전 관리**(지운 사진은 30 일 동안 되살릴 수 있다), 공개 읽기 정책. 환경 bootstrap 과 따로 둬
dev 를 지울 때 휘말리지 않는다. 바꿀 때는 템플릿을 고치고 `just aws-shared-media`(미리 보기) · `--execute`.

**사용자 사진 버킷** — prd 만 버전 관리(지운 사진 30 일). bootstrap 템플릿의 `IsProduction` 조건.

## 15. 관측 — SigNoz (DEV·PRD)

[ADR 0025](../architecture/adr/0025-aws-observability-signoz-in-eks.md) · [계획](../project/plans/observability-aws.md). DEV·PRD 둘 다
(`tools/aws/config.py` `SIGNOZ_ENVIRONMENTS`, PRD 는 2026-10-13 부터). 화면은 DEV `signoz-dev.scenetrip.io` · PRD `signoz.scenetrip.io`.
PRD 는 올리기(lifecycle)를 쓰지 않으니 **화면 DNS 는 첫 배포 뒤 한 번** SigNoz ALB 로 가리킨다(프록시 켬) — PRD 의 Ingress 를 지우지 않는 한
ALB 이름이 그대로다. PRD 디스크는 지우지 않는다.

- **배포(apply)** 가 앱보다 먼저 `signoz` 네임스페이스에 공식 차트 0.145.0(`MODULE.bazel` `@signoz_chart`)을 올리고, scene-api 의
  `otel.endpoint` 를 수집기(`signoz-otel-collector.signoz:4317`)로 켠다.
- **이미지**는 Docker Hub 가 아니라 환경 ECR 의 복사본 `scenetrip-dev/mirror/*` 에서 받는다. 배포가 **없는 태그만** 복사한다(태그 불변).
- **디스크**는 배포가 만든 StorageClass `signoz-gp3`(Auto Mode EBS, gp3·암호화) — ClickHouse 20Gi·ZooKeeper 8Gi·SigNoz 1Gi.
- **ClickHouse 비밀번호**는 배포가 클러스터에 한 번 만든다(`signoz/signoz-clickhouse`). 사람이 쓸 값이 아니다.
- **내리기**는 클러스터보다 먼저 ClickHouse 설치 → Helm 해제 → PVC 를 지우고 디스크가 사라질 때까지 기다린다 — 남은 EBS 가 없다.
- **관리자**는 배포가 만든다 — 아이디 `admin@scenetrip.io`, 비밀번호는 Secrets Manager `/scenetrip/<env>/signoz` 의
  `SIGNOZ_ROOT_PASSWORD`(배포가 한 번 만들고 다시 쓴다). 관리자(=조직)가 없으면 수집기가 설정을 못 받아 trace 를 버린다.
- **확인**: 배포·`just aws-verify dev` 가 HTTPS 검증 뒤 ClickHouse 에 최근 15 분 span 을 센다 — 0 이면 실패.
- **화면** `https://signoz-dev.scenetrip.io` — Cloudflare 프록시 + Access(허용 메일, 메일 코드) 뒤. 배포가 SigNoz 전용 ALB
  (Terraform `signoz_alb` 보안 그룹: Cloudflare IPv4 대역만 443)를 만들고, 올리기가 이 주소를 그 ALB 로 가리킨다(프록시 켬).
  인증서는 Terraform 변수 `signoz_certificate_arn`(GitHub `dev` 의 `TF_VAR_FILE_JSON`) — 비우면 화면을 열지 않는다.
  Access 애플리케이션·정책은 주소에 묶여 내렸다 올려도 그대로다(Cloudflare Zero Trust 에서 관리, 허용 메일은 저장소에 없다).
  Cloudflare 가 바꾼 IP 대역은 `platform/terraform/aws/signoz.tf` 에서 고친다.
- **내리기**는 SigNoz 화면 Ingress 도 함께 지우고 두 ALB 가 모두 사라질 때까지 기다린다.

## 16. 바깥 감시 알람

[계획 §6-3](../project/plans/observability-aws.md) · 계정 공용 CloudFormation 스택 `scenetrip-alerts`(us-east-1,
`platform/terraform/alerts/template.json`). 관리자 자격으로 `just aws-alerts --email <받는 메일>`(미리 보기) · `--execute`.

| 알람 | 울리는 때 |
| --- | --- |
| `scenetrip-prd-api-down` | Route 53 헬스 체크가 `https://api.scenetrip.io/v1/search/suggestions?q=a` 를 30 초마다(도쿄·싱가포르·오리건) 불러 2 분 연속 실패 — 회복하면 OK 메일 |
| `scenetrip-monthly` (Budgets) | 계정 월 비용의 예상·실제가 900 달러를 넘을 때 |

- 메일은 SNS 주제 `scenetrip-alerts` 의 구독 — 구독 확인 메일의 「Confirm subscription」 을 눌러야 온다.
- PRD 를 내리면 헬스 체크가 실패해 알람이 울린다 — 내릴 때는 이 스택도 함께 정리하거나 알람을 끈다.
- 회사 조직 정책(SCP)이 Cost Explorer 를 막는다. Budgets·Route 53 헬스 체크·CloudWatch 알람은 된다(2026-10-13 생성 확인).

## 17. 노드와 자원 — 한 대에 몰리지 않게

EKS Auto Mode 는 파드가 적은 **최소 필요량(requests)** 을 보고 노드 수를 정한다. 실제보다 작게 적으면 한 대에 넘치게 싣는다.
2026-10-14 PRD 는 앱 5 개와 SigNoz 전체가 `c5a.large` 한 대(메모리 약 3GB)에 있었고, 상한 합이 노드의 115% 였다 — 노드가 스왑
(ZRAM)에 들어가 멈출 때마다 그 노드의 모든 파드가 상태 검사 시간 초과로 함께 재시작했고, API 가 15 분마다 502 를 냈다
(바깥 감시 알람 25 번).

- scene-api(JVM)는 최소 필요량을 상한과 같은 1Gi 로. SigNoz 부품도 공식 권장치에 가깝게(`platform/helm/signoz/values.yaml`).
- 두 벌인 scene-api·gateway 는 가용 영역을 **반드시** 나눈다(`DoNotSchedule`) — 한 노드가 멈춰도 다른 영역의 것이 받는다.
- 클러스터에 metrics-server 가 없어 실제 사용량은 아직 재지 못했다. 재시작 횟수(`kubectl get pods`)와 바깥 감시 알람으로 본다.

## 배포 기록

환경·계정·commit·이미지 digest·workflow URL·Terraform 변경 요약·Flyway 버전·
검증 결과·담당자·복구 시도를 기록한다. 로그에는 암호·사용자 위치·대화 본문을
붙이지 않는다. 처음 배포하는 동안 발견한 차이는 코드와 이 가이드에 함께 반영한다.
