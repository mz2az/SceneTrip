# SceneTrip AWS 차트

외부 HTTPS는 ACM 인증서가 연결된 ALB 443 → nginx gateway 8080 → scene-api 8080으로
들어온다. `/v1`을 유지하며 계약에 있는 API 경로 계열만 전달한다. Actuator·internal과
agent 8899는 외부에 공개하지 않는다. CIDR을 반드시 지정하며 전체 인터넷 CIDR은 거절한다.
`X-Device-Id`는 인증 수단이 아니므로 허용 CIDR은 초기 제한 운영의 필수 경계다.

EKS Auto Mode의 `IngressClassParams`·`IngressClass` 이름은 환경별
`scenetrip-dev-alb`·`scenetrip-prd-alb`이며 `gateway` Ingress가 API 도메인의 `/v1` Prefix를
HTTP IP target 8080으로 연결한다. Service는 모두 ClusterIP다. ALB health check는
`/healthz`, idle timeout은 60초로 gateway의 응답 대기 45초보다 길다.

ALB는 X-Forwarded-For에 실제 클라이언트 주소를 추가한다. nginx는 Terraform의 ALB
public subnet CIDR만 신뢰하고 `real_ip_recursive off`로 마지막 주소를 사용한다.
연결한 peer가 ALB subnet에 있고 실제 클라이언트가 허용 CIDR에 있을 때만 API를 전달한다.
위조된 앞쪽 헤더와 직접 gateway 연결은 이 경계를 통과하지 못한다. 속도 제한은
클라이언트 IP당 gateway Pod별 10r/s이며 전역 합산 제한은 아니다.

전용 ALB 보안 그룹의 443 허용 CIDR과 클러스터 보안 그룹의 ALB SG → 8080 규칙은
Terraform이 관리한다. custom SG 사용 시 `inbound-cidrs` annotation은 보안 규칙을
만들지 않으므로 Terraform 규칙이 실제 경계다. NetworkPolicy도 ALB subnet에서 오는
gateway 8080 연결만 허용하며 배포기는 default NodeClass에 해당 클러스터 SG가
실제로 연결되었는지 확인한 뒤 진행한다.

| 값 | DEV | PRD |
| --- | --- | --- |
| `sceneApi.replicas` | 1 | 2 |
| `gateway.replicas` | 1 | 2 |
| `tripGuide.replicas` | 1, Recreate | 1, Recreate |
| `otel.endpoint` | 빈 값이면 비활성 | 빈 값이면 비활성 |

에이전트는 메모리 세션을 사용해 재배포 시 대화가 초기화된다. `values.yaml`의 이미지·DB·
인증서·접근 CIDR·도메인은 배포 실행기가 검증된 Terraform 출력과 입력으로 채운다.
런타임 Secret에는 `app_runtime`만 전달하고 Flyway를 앱에서 끈다. DB 관리자는 배포
Job에서만 사용하고 완료·실패 시 임시 Secret과 Job을 삭제한다.

EKS Auto Mode의 `kube-system/amazon-vpc-cni` ConfigMap에서 네트워크 정책을 활성화한 뒤
기본 차단과 서비스별 허용 정책을 설치한다. 정책 파일 렌더링만으로 실제 차단 성공을
주장하지 않는다. AWS 실배포 후 허용 경로와 금지된 Pod 연결을 확인해야 한다.
외부 HTTPS 및 PostgreSQL egress는 목적지 IP가 변하므로 포트 기반이며 RDS 보안 그룹이
추가 경계를 제공한다. OTEL은 별도 collector 설치·주소·포트 4317 연결이 준비되어야 한다.

첫 배포 후 ALB 주소로 DNS를 연결하고 `just aws-verify dev` 또는 `just aws-verify prd`로
기존 형상의 HTTPS 검증을 완료한다. DNS가 없으면 배포 검증은
실패로 남지만 이미 적용된 인프라·Helm을 자동 삭제하지 않는다. 자세한 복구는
[런북](../../../docs/ops/aws-deployment.md)을 따른다.
