---
number: 0024
title: PRD 는 DEV 와 같은 AWS 계정에 두고 인터넷 전체에 연다
status: accepted
date: 2026-10-12
supersedes:
superseded-by:
amended-by:
amends: 0019
---

# ADR 0024: PRD 는 DEV 와 같은 AWS 계정에 두고 인터넷 전체에 연다

> [0019](./0019-dev-api-is-public.md) 의 「PRD 는 허용 CIDR 을 유지한다」 를 바꾼다. 배포 문서의 「DEV·PRD 를 다른 계정으로」
> 권장도 따르지 않는다.

## 배경

PRD 를 올리기 전에 두 가지를 정해야 했다(권호 2026-10-12).

- **계정** — `docs/ops/aws-deployment.md` §1 은 DEV·PRD 를 분리된 계정으로 권장한다. 지금 계정(`583845872485`)은 DEV 하나에
  쓰고 있고, bootstrap·OIDC provider·runner·인증서가 다 여기 있다.
- **공개 범위** — 0019 는 PRD 를 허용 CIDR 로 남겼다. 근거는 「사용자별 요청 제한과 유료 API 한도가 없다」 였다. 그런데 앱을
  스토어에 내면 사용자는 어디서나 붙는다 — 허용 목록 방식으로는 출시할 수 없다.

0019 가 PRD 공개의 선행 조건으로 꼽은 MZ2AZ-334(계정별 요청 제한·챗봇·길찾기 하루 한도·멱등 키)는 서버에 들어가 있다
(`scenetrip.limits`, `PaidQuota`).

## 결정

우리는 **PRD 를 DEV 와 같은 AWS 계정에 두고, PRD 의 HTTPS 도 인터넷 전체에 연다.**

- 환경은 이름으로 나눈다 — 이미 그렇게 되어 있다: bootstrap 스택·배포 역할·state 버킷·ECR·Secrets Manager 경로가 전부
  `scenetrip-<env>`·`/scenetrip/<env>/` 다. GitHub Environment `prd` 에 검토자를 두어 PRD 배포는 사람이 승인한다.
- 공개 판단을 코드에서 바꾼다(0019 의 세 겹 그대로, 값만): Terraform `local.ingress_public` 을 환경과 무관하게 참으로, 배포기의
  「PRD 에서 공개면 거부」 와 Helm 의 「dev 가 아니면 `gateway.public` 실패」 를 뺀다. gateway 의 「ALB 를 거쳐 왔는가」 검사와
  IP 당 요청 제한은 그대로다.
- **EKS 관리 API 허용 CIDR(`eks_public_access_cidrs`)은 PRD 도 그대로다** — 관리자 문이라 사용자 접속과 무관하다.

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| PRD 용 새 계정 | 회사 조직에서 계정을 새로 받고 OIDC·bootstrap 역할·runner·인증서를 다시 만들어야 한다. 두 계정의 비용·권한을 따로 본다. 지금 팀 규모에 비해 크다 |
| PRD 는 허용 CIDR 유지 | 스토어에 낸 앱이 붙지 못한다 |
| 앞단 인증(Cloudflare Access 등) | 0019 와 같은 이유 — 앱이 통과할 수단을 새로 가져야 한다 |

## 결과

**좋아지는 것**

- 이미 있는 계정 준비물(OIDC provider·bootstrap 역할·runner EC2·ACM 발급 절차)을 PRD 가 그대로 쓴다.
- 출시된 앱이 어디서나 붙는다.

**나빠지는 것 / 감수하는 비용**

- **한 계정의 사고가 두 환경에 미친다** — 관리자 키 유출, 계정 단위 한도(EKS·EIP·NAT 수), 실수로 PRD 자원을 지우는 일. 막는
  것은 이름 규칙, 배포 역할의 자원 범위(`scenetrip-<env>-*`), PRD 검토자, PRD 삭제 보호(RDS `deletion_protection`, MZ2AZ-364
  1 번 — PRD 사용자 직전)다.
- **계정이 회사 조직의 샌드박스다**(SCP 로 비용 조회·일부 리전 차단). 샌드박스 계정의 수명·정리 정책을 회사에 확인해야 한다 —
  PRD 데이터가 계정과 함께 사라질 수 있다.
- 비용이 한 청구서에 섞인다 — 태그(`Environment`)로 나눠 본다. 비용 조회(ce)가 막혀 있어 콘솔에서 보지 못할 수 있다.
- 로그인 없는 API 를 누구나 부른다 — gateway 의 IP 당 제한과 계정별 한도(334)로 버틴다.

**후속 작업**

- 공개 코드 변경(Terraform·배포기·Helm 과 그 시험) — PRD 준비 PR 에서
- 구글 OAuth 동의 화면을 「프로덕션」 으로 — 지금은 테스트 사용자만 가입된다
- 샌드박스 계정의 수명·정리 정책 확인(회사)
- `docs/ops/aws-deployment.md` §1 의 「분리 계정 권장」 옆에 이 결정을 적는다

## 검증

- PRD 반영 뒤 허용 목록 밖(휴대폰 LTE)에서 `https://api.scenetrip.io/v1/contents` 가 200, `/v1/actuator` 가 404.
- PRD 배포 역할로 `scenetrip-dev-*` 자원을 건드릴 수 없다(역할 정책의 자원 범위).
