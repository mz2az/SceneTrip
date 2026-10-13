---
number: 0025
title: AWS 관측은 EKS 안 SigNoz, 바깥 감시 알람 두 개, UI 는 Cloudflare Access
status: accepted
date: 2026-10-13
supersedes:
superseded-by:
amended-by:
---

# ADR 0025: AWS 관측은 EKS 안 SigNoz, 바깥 감시 알람 두 개, UI 는 Cloudflare Access

## 배경

PRD 가 2026-10-13 에 올라갔는데 AWS 에는 관측 도구가 없다. SigNoz 는 로컬(kind)에만 있고 AWS 이전 때 범위에서 빠졌다 —
scene-api 는 `otel.endpoint` 가 비어 OTEL 을 끈 채 돈다. DEV 에서 부하 테스트를 할 예정이라 원인(DB·외부 호출·커넥션 풀)을
가르는 trace 가 필요하다. EKS API 는 러너 IP 에서만 열리고 운영자(권호)의 노트북 IP 는 유동이라, 허용 IP 로 UI 를 열 수 없다.
DNS 는 Cloudflare 에 있다. 세부: [observability-aws.md](../../project/plans/observability-aws.md).

## 결정

우리는 DEV·PRD 의 EKS 안에 **버전 고정한 SigNoz** 를 배포기로 설치하고, 「서버가 죽었다」(ALB 정상 대상 0)와 「비용 초과」(Budgets)
**두 알람만 AWS 쪽 바깥 감시**로 두며, SigNoz UI 는 **Cloudflare Access**(허용 메일 + Cloudflare IP 대역만 받는 ALB) 뒤에 둔다.

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| CloudWatch 만 | 가볍지만 서비스 사이 trace 가 약하다 — 부하 테스트의 「왜 느린가」 를 못 가른다. 로컬과 보는 도구가 달라진다 |
| SigNoz Cloud(SaaS) | 운영 부담은 없지만 부하 테스트 때 데이터량에 따라 요금이 튄다. 데이터가 외부로 나간다 |
| 알람도 전부 SigNoz | SigNoz 가 같은 클러스터에 있어 클러스터가 죽으면 알람도 죽는다 |
| UI 허용 IP | 운영자 IP 가 유동 |
| SSM 포트 포워딩(러너 경유) | 웹 주소가 없어 팀원이 쓰기 어렵고, 러너가 꺼져 있으면 못 쓴다 |
| UI 를 SigNoz 로그인만으로 공개 | 관측 데이터(요청 경로·오류)가 인터넷에 노출되는 면이 한 겹뿐 |

## 결과

**좋아지는 것**

- 로컬과 같은 도구로 DEV 부하 테스트와 PRD 운영을 본다
- 노트북 IP 와 무관하게 UI 접근, 팀원 추가는 메일 한 줄
- 같은 Access 를 나중 어드민 화면에도 쓴다 — 단 서버가 `Cf-Access-Jwt-Assertion` 을 직접 검증하고 자기 권한 검사를 둔다

**나빠지는 것 / 감수하는 비용**

- SigNoz(ClickHouse 포함)가 클러스터 자원과 EBS 를 쓴다 — 비용은 설치 뒤 실측해 적는다
- SigNoz 업그레이드·디스크 관리를 우리가 한다
- `signoz*.scenetrip.io` 만 Cloudflare 프록시를 켠다 — API 와 DNS 방식이 둘로 갈린다

**후속 작업**

- 계획 §6 순서: DEV 1 차 → trip-guide 계측 → PRD + 바깥 감시 → SigNoz 세부 알람

## 검증

- DEV 에서 실제 요청이 trace 로 보이고 부하 테스트에서 병목을 한 번이라도 짚었는가
- 바깥 감시 알람을 일부러 울려 메일이 오는가(PRD 적용 때)
- 석 달 뒤(2027-01) SigNoz 비용·관리 부담이 SaaS·CloudWatch 대비 여전히 맞는지 다시 본다
