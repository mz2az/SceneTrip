---
number: 0027
title: PRD 데이터는 EKS 안 Metabase 로, analytics 스키마만 읽는 계정으로 본다
status: proposed
date: 2026-10-21
supersedes:
superseded-by:
amended-by:
---

# ADR 0027: PRD 데이터는 EKS 안 Metabase 로, analytics 스키마만 읽는 계정으로 본다

## 배경

PRD 가 공개된 뒤(ADR 0024) 이용 데이터를 팀이 함께 보고 싶다. DB 는 인터넷에서 닿지 않고(DB subnet, workload 보안 그룹만), DB 계정은
구조 변경용 `app_migrate` 와 앱용 `app_runtime`(쓰기 포함) 둘뿐이다. PRD DB 에는 소셜 계정·기기·챗봇 대화 같은 개인정보가 있다.
관측 화면은 이미 SigNoz 를 EKS 안에 두고 Cloudflare Access 뒤로 열어 쓰고 있다(ADR 0025).

## 결정

우리는 Metabase(오픈소스)를 PRD EKS 안에 두고 Cloudflare Access 뒤로 열며, Metabase 는 `scenetrip` DB 의 `analytics` 스키마(개인정보 칸을 뺀 뷰)만
SELECT 하는 `metabase_ro` 계정으로 읽고, 자기 설정은 같은 RDS 의 별도 DB `metabase` 에 저장한다.

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| 노트북에서 Metabase + 그때그때 터널 | 혼자만 쓰고 대시보드가 노트북에 남는다. 팀이 같이 볼 수 없다 |
| `app_runtime` 계정을 그대로 연결 | 쓰기 권한이 있고 개인정보 표를 그대로 본다 |
| `public` 표 전부에 SELECT 만 주는 계정 | 실수로 쓰지는 못하지만 이메일·소셜 ID·대화 내용을 그대로 본다. 가리는 일을 Metabase 설정에 맡기게 된다 |
| 읽기 전용 복제 DB 를 따로 | 돈이 든다. 지금 데이터 양에서는 질의 제한(연결 5·30 초)으로 충분하다고 본다 — 앱 영향이 보이면 다시 본다 |
| Metabase 내장 DB(H2) | 파드가 바뀌면 대시보드가 날아간다. 공식 문서도 운영에는 쓰지 말라고 한다 |
| 관리형 BI(QuickSight 등) | 사용자당 요금, DB 가 사설망이라 연결 설정이 따로 든다 |

## 결과

**좋아지는 것**
- 팀이 SQL 없이 같은 대시보드를 본다.
- 개인정보는 DB 권한으로 막힌다 — Metabase 설정을 잘못 건드려도 못 본다.

**나빠지는 것·감수하는 것**
- PRD 주 DB 에 분석 질의가 더해진다(연결 5 개·30 초 제한으로 묶는다).
- 노드가 하나 늘 수 있다(Metabase JVM 메모리 1~2Gi).
- 볼 것이 늘 때마다 `analytics` 뷰를 Flyway 로 더해야 한다(뷰가 곧 공개 범위라서 일부러 그렇게 한다).

**다시 볼 때**
- 분석 질의가 앱 지연에 보이면 읽기 전용 복제 DB.
- 팀 밖 사람이 보게 되면 접근 기록(감사 로그)이 필요하다.

## 검증

- DEV·PRD 에서 `metabase_ro` 로 `public.app_user` 를 읽으면 권한 오류가 난다(통합 테스트와 적용 뒤 직접 확인).
- Metabase 를 켠 뒤 일주일 동안 PRD API p95·DB CPU 가 켜기 전과 같은 범위다(SigNoz·CloudWatch).
- 팀이 정한 첫 질문 3~5 개가 저장된 대시보드로 답해진다.
