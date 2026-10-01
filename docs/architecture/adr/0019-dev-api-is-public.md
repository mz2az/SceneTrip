---
number: 0019
title: DEV API 를 인터넷 전체에 연다
status: accepted
date: 2026-10-01
supersedes:
superseded-by:
amended-by:
amends: 0016
---

# ADR 0019: DEV API 를 인터넷 전체에 연다

> [0016](./0016-alb-http-ingress.md) 의 「승인 CIDR 만 허용」 을 **DEV 에 한해** 바꾼다. PRD 는 그대로다.

## 배경

DEV API(`api-dev.scenetrip.io`)는 허용 CIDR(운영자 집 IP·runner)에서만 열렸다. 세 곳이 전체 공개를 막았다 —
Terraform 변수 검증(`/0` 거부), Helm(`0.0.0.0/0` 이면 fail), gateway nginx(복원한 client IP 재검사). 근거는
Helm README 의 한 줄이었다: 「`X-Install-Id` 는 인증 수단이 아니므로 허용 CIDR 은 초기 제한 운영의 필수 경계다」.

실제 제약:

- 팀원이 DEV 를 쓸 수 없다. 실기기를 LTE 로 붙이면 IP 가 계속 바뀌어 허용 목록 방식이 아예 안 된다.
- 로그인(ADR 0018, MZ2AZ-331)이 생겼다. 가입 계정은 토큰 없이 열리지 않고(`SESSION_REQUIRED`), 유료 API(카카오
  길찾기·DeepSeek 챗봇)는 가입자만 부른다.
- 구글 동의 화면이 「테스트」 라 **가입은 등록한 테스트 사용자만** 된다 — DEV 에서 유료 API 에 닿는 사람은 팀원뿐이다.
- 「검색에 등록하지 않았으니 아무도 모른다」 는 성립하지 않는다. 인증서 투명성(CT) 로그에 도메인이 공개되고 IPv4 전체
  스캔이 상시 돈다. 열면 스캐너는 온다.

## 결정

우리는 **DEV 의 HTTPS 를 인터넷 전체에 열고, PRD 는 허용 CIDR 을 유지한다.**

- 판단은 Terraform 이 환경으로 내린다 — `local.ingress_public = var.environment == "dev"`. ALB SG 의 443 규칙이
  `0.0.0.0/0` 하나가 되고, 출력 `ingress_public` 으로 배포기에 알린다.
- 배포기는 그 값이 PRD 에서 켜져 오면 거부하고, Helm 은 `gateway.public` 이 dev 가 아니면 렌더를 실패시킨다(세 겹 유지).
- gateway 는 공개여도 **ALB 를 거쳐 왔는지**는 그대로 본다 — 직접 연결·위조 헤더 경계는 0016 그대로다.
- **EKS 관리 API 허용 CIDR(`eks_public_access_cidrs`)은 그대로다.** 클러스터 관리자 문이라 사용자 접속과 무관하다.

같은 변경에서 gateway 경로 허용 목록에 `/v1/auth`·`/v1/me` 를 더했다 — 로그인 PR(#112)이 빠뜨려 DEV 에서 로그인이
`404` 였다.

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| 팀원 IP 를 허용 목록에 더한다 | 실기기 LTE 가 안 된다. IP 가 바뀔 때마다 GitHub Environment 변수를 고치고 다시 올려야 한다 |
| VPN·Cloudflare Access 같은 앞단 인증 | 앱이 그것을 통과할 수단(클라이언트 인증서, 서비스 토큰)을 새로 가져야 한다. DEV 테스트를 위한 비용으로 크다 |
| PRD 까지 연다 | 사용자별 요청 제한과 유료 API 한도가 없다. 동의 화면을 「프로덕션」 으로 바꾸면 누구나 가입해 유료 API 를 한도 없이 부른다 — MZ2AZ-334 가 선행 조건 |
| 환경 변수(TF_VAR_FILE_JSON)로 공개 여부를 받는다 | 같은 저장소 코드가 환경마다 다른 보안 경계를 갖게 되고 그 값이 저장소 밖에 산다. 「DEV 만 공개」 는 정책이라 코드에 둔다 |

## 결과

**좋아지는 것**

- 팀원과 실기기가 DEV 를 쓴다.
- 공개 여부가 코드(Terraform local)에 있어 리뷰로 바뀐다.

**나빠지는 것 / 감수하는 비용**

- 로그인 없는 API(검색·지도·작품·장소)를 누구나 부른다. gateway 의 IP 당 요청 제한(10 r/s, burst 20, 동시 20,
  초과 429)이 이미 있어 대량 호출은 줄지만, 분산된 호출에는 DEV 가 느려질 수 있다 — DEV 라 감수한다.
- 스캐너가 온다. `/v1` 밖·actuator·internal 은 `404`.
- 비회원 데이터는 설치 UUID 로 열린다(추측 불가 UUID, 공개 촬영지 수준의 데이터).
- 팀원(테스트 사용자)의 유료 API 호출에는 한도가 없다 — 앱 버그로 인한 폭주 위험. 제공자 쪽 안전장치(DeepSeek 선불
  잔액, 카카오 일일 한도)를 확인해 둔다.

**후속 작업**

- MZ2AZ-334: 사용자별 요청 제한·유료 API 한도 — PRD 공개와 동의 화면 프로덕션 전환의 선행 조건

## 검증

- DEV 반영 뒤 허용 목록 밖(휴대폰 LTE)에서 `https://api-dev.scenetrip.io/v1/contents` 가 200 이고 `/v1/actuator` 가 404.
- PRD 렌더·배포는 `gateway.public` 을 거부한다(Helm·배포기 시험).
- DEV 를 올려 둔 동안 gateway 로그의 429 와 처음 보는 경로 비율을 본다. 대량 호출로 팀원이 못 쓰는 일이 생기면 앞단
  인증을 다시 검토한다.
