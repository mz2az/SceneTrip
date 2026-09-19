# platform/ — 코드로 관리하는 인프라

| 디렉터리 | 내용 |
| --- | --- |
| `kind/` | 로컬 Kubernetes 클러스터 정의 (`cluster.yaml`) |
| `terraform/` | 클라우드 리소스: 네트워크, 데이터베이스, 클러스터, IAM |
| `kubernetes/` | 기본 매니페스트와 오버레이 |
| `helm/` | 이 저장소가 소유하는 차트 |
| `environments/` | 분리된 AWS 계정의 환경별 값: `dev`, `prd` |
| `docker/` | 로컬 개발 보조 컨테이너 구성 |

## 규칙

- **이 트리에 시크릿은 없다.** 값은 시크릿 매니저에서 오고, 커밋하는 것은
  `*.tfvars.json.example`·`backend.hcl.example` 자리표시자뿐이다.
- 모든 환경은 같은 코드로 기술되고 `environments/<env>/` 의 값만 다르다.
  환경 전용 분기는 만들지 않는다.
- 변경은 적용 전에 계획(`just tf-plan`)으로 리뷰한다.
- 상태를 바꾸는 명령은 전부 확인 절차가 있고 대상 환경을 먼저 출력한다.

**로컬** 클러스터는 `tools/just/k8s.just` 가 담당한다 —
[docs/installs/](../docs/installs/README.md) 참조.

AWS 구성과 입력 계약은 [Terraform](terraform/README.md),
[환경 예제](environments/README.md)를 따른다. AWS에는 Scene API·Trip Guide와
일회성 migration Job을 배포하며, 네이티브 모바일 앱은 스토어 배포 대상이다.
외부 HTTPS는 ALB에서 종료하고 private nginx gateway로 전달한다. ALB는 EKS Auto
Mode가 Helm Ingress에서 생성하며, 외부 허용 CIDR과 backend SG 규칙은 Terraform이 소유한다.
계정·인증서·DNS·허용 CIDR을 추측하거나 예제 값을 실제 값으로 취급하지 않는다.

```bash
just cluster-up        # 로컬 kind 클러스터 + SigNoz 생성
just tf-check <env>    # 포맷 + 검증, 읽기 전용
just tf-plan  <env>    # 읽기 전용
just tf-apply <env>    # 인프라를 실제로 바꾼다, 확인 절차 있음
just k8s-diff <env>    # 읽기 전용
```
