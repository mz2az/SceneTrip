---
number: 0016
title: DEV·PRD 외부 HTTP 진입점에 ALB를 사용한다
status: accepted
date: 2026-09-20
amends: [0015]
---

# ADR 0016: ALB 기반 HTTP 진입점

## 배경

ADR 0015 이후 만든 초기 배포 형상은 NLB와 nginx gateway를 사용했다.
SceneTrip에는 TCP/UDP 공개 서비스나 고정 IP 요구가 없고 외부 인터페이스는 HTTPS API다.
사용자가 ALB로 배포 형상과 문서 전체를 전환하도록 요청했다. 이 결정은 ADR 0015의
환경 격리·수동 배포·데이터 권한 분리는 유지하고 외부 진입점 구성을 구체화한다.

## 결정

- EKS Auto Mode의 ALB를 public subnet에 생성한다. HTTPS 443만 열고 승인된 사용자
  CIDR과 API 호스트의 `/v1` 경로만 허용한다. ACM 인증서는 외부 입력으로 받는다.
- Helm이 `IngressClassParams`·`IngressClass`·`Ingress`를 선언한다. 컨트롤러는
  `eks.amazonaws.com/alb`이며 gateway Pod IP를 target으로 쓴다.
- nginx gateway Service는 ClusterIP로 바꾼다. 세부 API 경로 허용 목록, 내부 헤더 제거,
  본문 제한과 IP별 요청·연결 제한을 유지한다. ALB와 gateway는 책임이 다르다.
- ALB 전용 보안 그룹과 ALB→workload 8080 규칙은 Terraform이 관리한다. gateway
  NetworkPolicy와 nginx의 신뢰 프록시 범위는 ALB가 놓인 public subnet CIDR로 제한한다.
  배포기는 실제 Auto Mode NodeClass의 보안 그룹을 출력 계약과 대조한다.
- ALB의 X-Forwarded-For append를 명시하고 nginx는 마지막 주소만 사용한다.
  원래 연결자와 복원한 사용자 주소를 각각 검사하며, upstream에는 정리한 주소만 전달한다.
- Ingress hostname과 AWS의 ALB 타입·active 상태·target health를 제한 시간 안에
  검증한다. DNS 연결 후 실제 도메인의 HTTPS와 내부 경로 차단을 별도로 검사한다.

## 대안과 결과

| 대안 | 판단 |
| --- | --- |
| NLB 유지 | 현재 외부 HTTP API에는 고유 이점이 필요하지 않고 호스트·경로 라우팅은 별도 프록시에 의존 |
| ALB에서 API Pod로 직접 연결 | 기존 경로·본문·헤더·IP별 제한이 사라지므로 해당 정책 이전 없이 채택하지 않음 |
| ALB와 NLB를 함께 유지 | 현재 기능 요구 없이 비용·운영 경로를 추가하므로 채택하지 않음 |
| 자체 AWS Load Balancer Controller 설치 | Auto Mode가 ALB를 관리하므로 같은 책임의 컨트롤러를 중복 설치하지 않음 |

ALB는 시간·LCU 등의 비용을 만들고 nginx는 Pod 자원을 사용한다. 이 선택이 모든 부하에서
더 저렴하다는 주장은 하지 않는다. TLS는 ALB에서 종료하고 내부 구간은 HTTP다.
AWS WAF·사용자 로그인·운영 인증은 이번 변경으로 자동 제공되지 않는다.
이미 이전 형상을 배포했다면 Service 변경으로 기존 로드밸런서가 삭제될 수 있으므로
DNS 전환과 변경 창이 필요하다. 실제 환경 생성 없이 무중단을 보장하지 않는다.

## 검증

Terraform mock 계획과 DEV·PRD Helm 렌더링으로 선언 경계를 확인한다.
실제 nginx relay 통합 시험으로 신뢰된 ALB 형태의 전달 헤더, 위조·비신뢰 접속 차단,
기존 경로와 요청 제한을 확인한다. 배포 단위 시험은 잘못된 타입·보안 그룹·시간 초과를
거부하는지 확인한다. AWS 계정에서는 ALB 생성·target health·TLS·보안 그룹·NetworkPolicy를
[운영 절차](../../ops/aws-deployment.md)에 따라 검증한다.

## 근거

- [AWS EKS Auto Mode ALB 구성](https://docs.aws.amazon.com/eks/latest/userguide/auto-configure-alb.html)
- [ALB 전달 헤더 동작](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html)
- [nginx real IP 처리](https://nginx.org/en/docs/http/ngx_http_realip_module.html)
- [ALB 보안 그룹](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-update-security-groups.html)
