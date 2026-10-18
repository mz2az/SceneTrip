# SceneTrip AWS 차트

외부 HTTPS는 ACM 인증서가 연결된 ALB 443 → nginx gateway 8080 → scene-api 8080으로
들어온다. `/v1`을 유지하며 계약에 있는 API 경로 계열만 전달한다. Actuator·internal과
agent 8899는 외부에 공개하지 않는다. `allowedCidrs` 에 전체 인터넷 CIDR 은 거절한다.

**DEV·PRD 모두 인터넷 전체에 열려 있다** — `gateway.public: true` 면 client IP 검증을 끄고 ALB 가 `0.0.0.0/0` 의 443 을
받는다. 배포기가 Terraform 출력(`ingress_public`)으로 넣는다. 결정과 감수한 위험은
[ADR 0019](../../../docs/architecture/adr/0019-dev-api-is-public.md)(DEV)·[ADR 0024](../../../docs/architecture/adr/0024-prd-same-account-and-public.md)(PRD,
계정별 요청 제한·유료 API 한도 MZ2AZ-334 가 선행 조건이었다). 공개여도 「ALB 를 거쳐 왔는가」 검사와 IP 당 제한은 그대로다.

EKS Auto Mode의 `IngressClassParams`·`IngressClass` 이름은 환경별
`scenetrip-dev-alb`·`scenetrip-prd-alb`이며 `gateway` Ingress가 API 도메인의 `/v1` Prefix를
HTTP IP target 8080으로 연결한다. Service는 모두 ClusterIP다. ALB health check는
`/healthz`, idle timeout은 60초로 gateway의 응답 대기 45초보다 길다.

ALB는 X-Forwarded-For에 실제 클라이언트 주소를 추가한다. nginx는 Terraform의 ALB
public subnet CIDR만 신뢰하고 `real_ip_recursive off`로 마지막 주소를 사용한다.
연결한 peer가 ALB subnet에 있고 실제 클라이언트가 허용 CIDR에 있을 때만(DEV 는 모든 client) API를 전달한다.
위조된 앞쪽 헤더와 직접 gateway 연결은 이 경계를 통과하지 못한다. 속도 제한은
클라이언트 IP당 gateway Pod별 30r/s(burst 60, 동시 60)이며 전역 합산 제한은 아니다.

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

`files/fake_external.py` 는 DEV 부하 시험(MZ2AZ-502) 때만 쓰는 가짜 바깥 서버(카카오 길찾기·Bedrock·구글 JWKS)다.
표준 라이브러리만 쓰는 파일 하나라 ConfigMap 으로 trip-guide 이미지에 얹어 `python3 fake_external.py` 로 돈다. 설정은 `FAKE_*`
환경변수(파일 머리말), 시험은 `//tests/load/fake:unit_test`. 계획: [load-test.md](../../../docs/project/plans/load-test.md).

## DEV 부하 시험 모드 (`loadtest.enabled`, 기본 꺼짐)

부하 시험 동안만 바깥 회사 서버를 클러스터 안 가짜로 바꾼다. **PRD 에서 켜면 렌더가 실패한다**(`templates/loadtest.yaml` 의 `fail`).
꺼져 있으면 렌더 결과는 이 기능이 없던 때와 바이트 단위로 같다(`tools/aws/tests/test_loadtest_mode.py` 가 지킨다).

| 켜면 | 무엇이 |
| --- | --- |
| 가짜 바깥 서버 | ConfigMap `fake-external`(`files/fake_external.py`) · Deployment `fake-external`(1 개, trip-guide 이미지, `/usr/bin/python3 /fake/fake_external.py`, `/healthz`) · Service `fake-external:8080` |
| 통로 | NetworkPolicy `fake-external`(scene-api·trip-guide 에게서만 8080 을 받고 나가는 곳 없음) · `loadtest-to-fake-external`(scene-api·trip-guide → 가짜 8080) |
| scene-api 주소 | ConfigMap `scene-api` 에 `SCENETRIP_NAVIGATION_KAKAO_BASEURL=…/kakao` · `SCENETRIP_AUTH_GOOGLE_JWKSURI=…/google/jwks` · `SCENETRIP_NAVER_SEARCHURL=…/naver/graphql`(편의시설 상세의 네이버 링크 찾기, ADR 0021) · `SCENETRIP_AUTH_GOOGLE_CLIENTIDS=<loadtest.googleClientId>` — 그동안 진짜 구글 로그인은 안 된다 |
| trip-guide 모델 | `TRIP_GUIDE_MODEL_BASE_URL=http://fake-external:8080/bedrock` · `TRIP_GUIDE_MODEL_AUTH=none` |
| gateway IP 상한 | `loadtest.gatewayRate`·`gatewayBurst`·`gatewayConnections`(기본 2000r/s · 4000 · 4000)가 평소의 30r/s · burst 60 · 동시 60 을 대신한다 |

필수 값은 `loadtest.googleClientId`(시험 토큰의 aud)와 `loadtest.googleJwkJson`(`just load-tokens --jwk-out` 의 공개 JWK)이다.
켜고 끄면 설정 checksum·파드 환경변수가 바뀌어 scene-api·trip-guide·gateway 가 다시 뜬다. 켜고 끄기는 `just loadtest-mode dev on|off`
(깔린 릴리스 값은 그대로 두고 이 값만 바꾼다), 보통 배포(`just aws-apply dev`)는 꺼진 상태로 되돌린다. 절차는
[계획 §3 「시험 모드 켜고 끄기」](../../../docs/project/plans/load-test.md).
