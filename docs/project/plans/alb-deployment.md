# ALB 기반 DEV·PRD 배포 전환

작성일: 2026-09-20. 사용자 요청에 따른 후속 계획이며 연결된 JIRA 티켓은 없다.
기존 [AWS 이식 계획](aws-dev-prd-port.md)의 외부 진입점 결정을 대체한다.
상태: 저장소 구현·로컬 검증 완료. 결과는 [검증 기록](../../qa/alb-verification.md)에 있다.
실제 AWS 적용은 환경 입력 준비 후 운영 절차로 진행한다.

## 목표와 결정

SceneTrip의 외부 API는 HTTP/HTTPS이므로 EKS Auto Mode의 ALB를 사용한다.
요청 경로는 `HTTPS ALB → nginx gateway ClusterIP:8080 → scene-api:8080`이다.
nginx의 경로 허용 목록, 내부 헤더 제거, 본문 크기와 IP별 요청 제한은 유지한다.
EKS·RDS·에이전트 및 DEV·PRD 가용성 구성은 기존 SceneTrip 설계를 따른다.

## 구현 순서

1. Terraform 경계와 Helm 렌더링, 배포 대기·검증에 실패하는 테스트를 먼저 추가한다.
2. Terraform이 ALB 전용 보안 그룹과 backend 보안 그룹 규칙을 관리한다.
   HTTPS 443은 `ingress_allowed_cidrs`만, gateway 8080은 ALB 보안 그룹만 허용한다.
   출력 계약은 `public_subnet_ids`, `public_subnet_cidrs`,
   `alb_security_group_id`, `workload_security_group_id`이다.
   필요한 IAM 권한은 환경과 리소스 범위로 제한한다.
3. Helm에 `IngressClassParams`·`IngressClass`·`Ingress`를 추가하고 gateway Service를
   ClusterIP로 바꾼다. HTTPS 443, ACM, host와 `/v1` 경로, IP target, healthcheck를 지정한다.
   ALB는 public subnet, gateway와 애플리케이션은 private subnet에 둔다.
4. ALB의 X-Forwarded-For append를 명시한다. nginx는 ALB subnet CIDR만 신뢰하고
   `real_ip_recursive off`로 마지막 주소를 읽는다. 위조된 이전 항목은 upstream에 전달하지
   않는다. NetworkPolicy도 gateway 입력을 같은 ALB subnet으로 제한한다.
5. 배포기는 Terraform 출력과 Auto Mode NodeClass의 실제 보안 그룹을 대조한다.
   Ingress 주소 및 AWS API의 application 타입·active 상태·target health를 제한 시간 내
   확인한 뒤 ALB DNS를 출력한다. DNS 연결 후 HTTPS 제품 경로와 내부 경로 차단을 확인한다.
   읽기 전용 `just aws-verify <env>`와 Actions `verify`로 이 검증을 다시 실행할 수 있게 한다.
6. 현재 아키텍처·서비스 정의·운영 절차·교육 원본과 HTML·SVG·실제 Terraform graph 및
   출처 해시를 갱신한다. 수락된 ADR의 본문은 보존하고 새 ADR로 진입점 결정을 대체한다.

## 검증과 완료 조건

- DEV·PRD 렌더링: NLB Service 없음, ALB Ingress와 제한된 보안 경계 있음.
- Terraform mock test: ALB→gateway 포트·보안 그룹 방향, 환경별 subnet 출력 확인.
- 실제 nginx 통합 테스트: 정상 경로·차단 경로·헤더 제거·신뢰/비신뢰 XFF·IP별 제한 확인.
- 배포 단위 테스트: 잘못된 출력·로드밸런서 타입·미준비 상태·시간 초과를 거부.
- 새 코드 커버리지 80% 이상, `just aws-check`, `just aws-test-integration`, `just check` 통과.
- 교육 생성물 일치 검사와 브라우저 탐색·좁은 화면 확인, 변경 코드 보안 리뷰 완료.

## 실제 배포 경계

현재 작업에서는 AWS 리소스를 변경하지 않는다. 계정·인증서·도메인·허용 CIDR·runner
등 환경 입력을 채운 뒤 확인 가능한 수동 배포 recipe를 실행한다.
이미 이전 형상을 배포한 환경은 Service 교체로 기존 NLB가 삭제될 수 있으므로 변경 창과
새 ALB DNS 연결을 먼저 준비해야 한다. 이 변경만으로 무중단 전환을 보장하지 않는다.
