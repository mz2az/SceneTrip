# 가이드 에이전트의 읽기 요청을 사용자 몫으로 센다

상태: 구현 (2026-10-11, MZ2AZ-412) — §6 실측 통과. 관련: [rate-limit.md](rate-limit.md)(MZ2AZ-334) §3, ADR 0013 · [0023](../../architecture/adr/0023-agent-reads-carry-rate-subject-not-credentials.md).

## 1. 왜

가이드 챗봇 한 턴 안에서 에이전트(`agents/trip-guide`)는 장소를 읽으려고 scene-api 를 다시 부른다
(`/v1/places`·`/v1/contents`·`/v1/pois`, 도구 하나에 GET 1~2 번, 한 턴에 도구 최대 4 번). 이 요청에는
`Authorization` 도 `X-Install-Id` 도 없다(`agents/trip-guide/src/sceneapi.py:51`). 그래서 일반 요청 한도
(`RequestRateLimitFilter`)는 이것을 **에이전트 주소 하나**로 세고 비회원 상한(분당 60)을 건다.

모든 사용자의 챗봇이 그 60 을 나눠 쓴다. 다 차면 그 분 동안 **누가 묻든** 챗봇이 실패한다 — 자기 챗봇
한도(1 시간 15 턴)가 남아 있어도.

### 실측 (2026-10-11, 로컬 클러스터)

| 시험 | 결과 |
| --- | --- |
| 헤더 없는 `GET /v1/places` 70 번 | 1~60 번째 200, **61 번째부터 429** `RATE_LIMITED` (`RateLimit-Limit: 60`) |
| 같은 분에 `X-Install-Id` 를 단 같은 요청 | 200 — 다른 칸으로 센다 |
| 챗봇 「도깨비 촬영지 알려줘」, 칸이 빈 때 | 200, 3.6 초, `list_title_places` 로 네 곳 |
| 같은 질문, 헤더 없는 요청 60 번으로 칸을 채운 직후 | **503** `GUIDE_UNAVAILABLE` (0.8 초) |

로컬은 에이전트가 맥에서 돌아 scene-api 가 에이전트와 curl 을 같은 주소로 본다. EKS 에서는 에이전트 파드
주소 하나다 — 같은 일이 난다. 마지막 고리(에이전트가 429 를 받아 `SceneApiError` → 503)는 에이전트 로그가
비어 있어 직접 보지 못했고, 위 비교와 코드 경로(`web/server.py:449`, `internal_server.py:119`)로 판단했다.
고친 뒤 같은 시험에서 503 이 200 이 되면 이 고리도 확인된다(§6).

## 2. 결정

**scene-api 가 이 요청을 누구로 셌는지(열쇠)를 에이전트에 넘기고, 에이전트는 읽기 요청에 그 열쇠와 서비스
비밀값을 달아 보낸다. 필터는 비밀값이 맞을 때만 그 열쇠로 센다.**

```
앱 ── POST /v1/guide/chat (JWT) ──▶ scene-api
                                    필터: 열쇠 = user:abc, +1
                                    │
                                    └─▶ 에이전트  X-Rate-Subject: user:abc
                                              │
              ┌───────────────────────────────┘
              ▼
scene-api ◀── GET /v1/places
              X-Service-Token: <비밀값>
              X-Rate-Subject: user:abc
              필터: 비밀값 맞음 → 열쇠 = user:abc, +1   (철수가 직접 보낸 요청과 같은 칸)
```

### 넘기는 것은 자격 증명이 아니라 열쇠다

| 넘길 수 있던 것 | 쥔 쪽이 할 수 있는 일 | 고르지 않은 이유 |
| --- | --- | --- |
| JWT · `X-Install-Id` 그대로 | 그 사용자로 **모든 API**(찜·코스 고치기·리뷰·탈퇴) | ADR 0013 이 기각한 안 그대로다 — 에이전트가 쓰기 권한을 들게 된다 |
| 열쇠(`user:<계정 UUID>`) + 서비스 비밀값 | **한도 칸을 고르는 것뿐** | 고른다 |

계정을 정하는 `CurrentAccount` 는 `Authorization` 과 `X-Install-Id` 만 읽는다. `X-Rate-Subject` 는 한도 필터만
읽으므로 이것으로 열리는 창구가 없다. 에이전트가 사용자 데이터를 읽어야 하는 기능이 생기면 0013 의 방식대로
scene-api 가 읽어 요청에 싣는다 — 이 계획이 그 길을 바꾸지 않는다.

### 헤더

| 헤더 | 보내는 쪽 → 받는 쪽 | 값 |
| --- | --- | --- |
| `X-Rate-Subject` | scene-api → 에이전트, 에이전트 → scene-api | 필터가 그 요청에 쓴 열쇠 그대로: `user:<UUID>` · `install:<UUID>` · `ip:<주소>` |
| `X-Service-Token` | 에이전트 → scene-api | 서비스 비밀값. 게이트웨이가 바깥 요청에서 이미 지운다(`config.yaml:100`, `aws-dev-prd.md`) |

`X-Rate-Subject` 는 **필터가 이미 정한 열쇠**를 그대로 넘긴다. 그래서 챗봇(가입자, `user:`)과 마법사
(`/guide/plan`, 설치 UUID 가 없어도 된다)가 같은 규칙을 탄다. 상한은 열쇠의 앞머리로 정한다 — `user:` 는 가입자
상한(120), 나머지는 비회원 상한(60). 지금 필터와 같다.

### 필터의 판단 순서

```
X-Service-Token 일치 + X-Rate-Subject 형식 맞음 → 그 열쇠              ← 추가
Authorization 서명 맞음                        → user:<UUID>
X-Install-Id 형식 맞음                         → install:<UUID>
그 밖                                          → ip:<주소>
```

- 비밀값은 상수 시간으로 비교한다(`MessageDigest.isEqual`).
- 비밀값이 틀리거나 없으면 `X-Rate-Subject` 를 **무시하고** 아래 규칙으로 센다. 거절하지 않는다 — 읽기 창구는
  누구나 부를 수 있고, 무시하면 지금과 같아질 뿐이다.
- 형식이 틀린 열쇠(앞머리가 셋 중 하나가 아니거나 UUID 가 아님)도 무시한다.
- 필터가 정한 열쇠는 요청 속성에 남겨 `GuideAgentClient` 가 읽는다. 열쇠를 두 번 계산하지 않는다.

### 비밀값

- 이름 `SCENETRIP_SERVICE_TOKEN`. 환경마다 하나(로컬·DEV·PRD), 난수 32 바이트.
- DEV·PRD 는 Secrets Manager → `scene-api-secrets`·`trip-guide-secrets` 둘 다에. 로컬은 `.env`.
- **비어 있으면** 양쪽 다 기동은 한다. scene-api 는 `X-Rate-Subject` 를 늘 무시하고(지금과 같음), 에이전트는
  헤더를 달지 않는다. 기동 때 경고를 한 줄 남긴다 — 조용히 옛 동작으로 돌지 않게.
- 로그·추적(SigNoz)에 값을 남기지 않는다. 에이전트는 요청 헤더를 통째로 찍지 않는다.
- 교체는 이번 범위가 아니다. 지금은 바꾸면 두 파드를 함께 다시 띄운다.

## 3. 범위

| 모듈 | 바꾸는 것 |
| --- | --- |
| `services/scene-api` | `RequestRateLimitFilter` — 추가 규칙, 열쇠를 요청 속성에 · `GuideAgentClient` — 챗봇·마법사 요청에 `X-Rate-Subject` · 설정 `scenetrip.service-token` |
| `agents/trip-guide` | 내부 서버가 `X-Rate-Subject` 를 받아 턴 동안 들고 있는다(`contextvars`, 턴 예산과 같은 방식 `production.py`) · `sceneapi.py` 의 `_get` 이 두 헤더를 단다 · CLI·웹 개발 서버는 열쇠가 없으면 달지 않는다 |
| `platform/helm` | 게이트웨이가 `X-Rate-Subject` 도 지운다(`proxy_set_header X-Rate-Subject ""`) · 두 시크릿에 `SCENETRIP_SERVICE_TOKEN` |
| `tools/aws`·`tools/scripts` | 비밀값 생성·적용(`secrets-apply.sh`), 로컬 `.env` 안내 |
| 문서 | [ADR 0023](../../architecture/adr/0023-agent-reads-carry-rate-subject-not-credentials.md)(0013 보정: 에이전트는 자격 증명 대신 한도 열쇠를 받는다) · `rate-limit.md` §3 「누구로 세나」 · `agents/trip-guide/docs/design/backend-agent-contract.md` · 두 모듈 README |

**계약(`contracts/openapi`)은 바꾸지 않는다.** 두 헤더는 앱이 보내는 것이 아니고 서비스끼리의 약속이다. 공개
계약에 실으면 앱 생성 클라이언트에 쓸 일 없는 인자가 생긴다. 대신 에이전트 쪽 계약 문서와 ADR 에 적는다.

**하지 않는 것:** scene-api 가 실제 클라이언트 IP 를 읽지 못하는 문제(`getRemoteAddr` 가 게이트웨이 파드 주소라
헤더 없는 바깥 요청이 모두 한 칸이다) — 앱은 늘 `X-Install-Id` 를 달아 당장 드러나지 않는다. 따로 티켓으로.

## 4. 순서 (PR 하나)

1. ADR 0023 — 0013 보정. 무엇을 넘기고 무엇을 넘기지 않는지. ✅
2. scene-api: 필터 규칙 + 단위 시험 → `GuideAgentClient` 헤더 + 단위 시험.
3. 에이전트: 열쇠 받기·달기 + 시험.
4. helm 게이트웨이 한 줄 + `test_gateway.py` 에 `X-Rate-Subject` 가 지워지는지.
5. 비밀값 적용 스크립트·`.env` 안내.
6. `just check`, §6 실측.

## 5. 시험 (명세)

시험은 이 명세만 보고 새 컨텍스트에서 짜고 돌린다.

**scene-api 필터**

- 비밀값 일치 + `X-Rate-Subject: user:<UUID>` → 그 열쇠로 세고 상한 120. 같은 사용자의 JWT 요청과 같은 칸이다.
- 비밀값 일치 + `install:<UUID>` → 상한 60.
- 비밀값 불일치·없음 + `X-Rate-Subject` → 헤더를 무시하고 기존 규칙으로 센다(JWT → 설치 UUID → 주소).
- 비밀값 일치 + 형식이 틀린 열쇠(`admin:x`, `user:not-a-uuid`, 빈 값) → 무시하고 기존 규칙.
- 설정의 비밀값이 비어 있으면 어떤 `X-Service-Token` 이 와도 무시한다(빈 문자열끼리 일치로 보지 않는다).
- `X-Rate-Subject` 는 계정을 정하지 않는다 — 그 헤더만 단 쓰기 요청(`PUT /v1/cart/items/...`)은 지금처럼
  거절된다.

**scene-api → 에이전트**

- `/guide/chat`·`/guide/plan` 을 부를 때 필터가 정한 열쇠가 `X-Rate-Subject` 로 간다.
- `Authorization`·`X-Install-Id` 는 에이전트로 가지 않는다(지금도 그렇다 — 회귀 시험으로 남긴다).

**에이전트**

- 내부 서버가 받은 `X-Rate-Subject` 가 그 턴의 모든 scene-api 읽기에 같은 값으로 붙는다. 동시에 도는 두 턴의
  열쇠가 섞이지 않는다.
- 비밀값이 설정돼 있으면 `X-Service-Token` 이 붙고, 비어 있으면 두 헤더 모두 붙지 않는다.
- 열쇠 없이 들어온 턴(개발 서버·CLI)은 헤더를 달지 않는다.
- 로그에 비밀값이 찍히지 않는다.

**게이트웨이**

- 바깥에서 보낸 `X-Rate-Subject`·`X-Service-Token` 이 upstream 에 닿지 않는다.

## 6. 완료 기준 — 실제 요청으로

§1 의 실측을 그대로 다시 한다. 칸을 헤더 없는 요청 60 번으로 채운 직후 챗봇이 **200** 으로 답해야 한다. 그 턴의
읽기 요청이 `user:<그 계정>` 칸에 쌓였는지는, 턴 직후 같은 JWT 로 `GET /v1/places` 를 하나 보내 응답의
`RateLimit-Remaining` 이 턴 전보다 (그 턴의 도구 GET 수 + 1) 만큼 줄었는지로 본다(챗봇 창구 자체는 이 헤더를 달지
않는다 — `PAID_PATHS`). 게이트 초록만으로는
「비밀값이 비어 옛 동작으로 도는」 조용한 실패를 못 잡는다.

### 결과 (2026-10-11, 로컬 클러스터)

| | 공유 칸 | 결과 |
| --- | --- | --- |
| 고치기 전 (§1) | 헤더 없는 요청 60 번으로 채움 | 503 `GUIDE_UNAVAILABLE` |
| 고친 뒤 | 같음 (헤더 없는 확인 요청은 429) | **200**, 3.6 초, `list_title_places` 로 5 곳 |

같은 설치 UUID 의 `RateLimit-Remaining` 이 턴 전 59 → 턴 뒤 55. 줄어든 4 = 챗봇 요청 1 + 도구의 읽기 2
(`/contents?q=`, `/contents/{id}/places`) + 확인용 GET 1 — 에이전트의 읽기가 그 사용자 칸에 쌓였다. §1 에서 직접 보지 못한
고리(에이전트가 받은 429 → 503)도 이것으로 확인된다.
