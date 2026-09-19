# 소스 기반 AWS 구성도

| 파일 | 내용 |
| --- | --- |
| [dev-overview.svg](dev-overview.svg) | DEV 환경 예제의 AZ·subnet·NAT·DB 형상 |
| [prd-overview.svg](prd-overview.svg) | PRD 환경 예제의 AZ·subnet·NAT·DB 형상 |
| [hcl-references.svg](hcl-references.svg) | Terraform 리소스 사이의 명시적 HCL 직접 참조 |
| [hcl-references.dot](hcl-references.dot) | 동일 참조 관계의 DOT 표현 |
| [provenance.json](provenance.json) | 입력·산출물의 SHA256 출처 |
| [terraform-graph.dot](terraform-graph.dot) | 고정 도구로 생성한 실제 Terraform 엔진의 구성 의존 그래프 |
| [terraform-graph-provenance.json](terraform-graph-provenance.json) | 엔진 버전·실행 인자·provider·소스·DOT 출처 |

그림은 체크인된 환경 **예제**와 소스의 선언을 나타낸다. 실제 AWS 계정 조회 결과,
현재 배포 상태나 Terraform plan이 아니다. HCL 직접 참조 그림은 Terraform 엔진의
graph 출력이 아니며 실제 엔진 DOT와 파일을 구분한다.
EKS Auto Mode가 생성할 실제 노드 수·배치와 ALB 주소를 확정하지 않는다.

Terraform은 VPC·EKS·RDS와 ALB/대상 보안 그룹을 소유한다. ALB 자체는 Helm Ingress를
읽은 EKS Auto Mode가 생성하므로 실제 Terraform 엔진 DOT에는 ALB가 직접 나타나지 않는다.
개요 SVG는 Ingress·nginx·NetworkPolicy를 포함한 Helm 템플릿도 해시하고 지원하는
Auto Mode ALB·IP 대상·HTTPS 443·ClusterIP 선언인지 검사한다. 이는 Helm 렌더 검사를
대체하지 않는다. 실제 ALB 트래픽은 nginx Pod IP에 전달되고 Service는 논리적 참조다.

```bash
just education-generate
just education-check
just education-graph
just education-graph-check
```

AZ·NAT·DB 정책·subnet 수식이 달라지면 그림도 함께 갱신한다. 해석하지 못하는 HCL 표현은
생성을 중단한다. HCL 참조 그림은 `local` 등을 통한 간접 의존을 펼치지 않는다.
계산과 시각화의 상세 범위는 [생성기 설명](../../../tools/education/README.md)을 따른다.
엔진 그래프는 임시 복사본에서만 backend를 제거하고 로컬 provider mirror로 실행한다.
일반 교육 자료 재생성은 엔진 그래프의 별도 출처를 덮어쓰지 않는다.
