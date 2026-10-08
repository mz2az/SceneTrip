# 요청 한도 — 계정별 제한, 유료 API 한도, 챗봇 멱등 키

상태: 계획 (2026-10-08, MZ2AZ-334). 앱 쪽: MZ2AZ-366(재시도 규칙·한도 안내). 선행이 되는 것: PRD 허용 CIDR 해제, 구글
동의 화면 「프로덕션」 전환 — 둘 다 이 작업 뒤.

## 1. 왜

로그인으로 **누가** 쓰는지는 정했지만 **얼마나** 쓰는지는 막지 않는다. 우리가 부를 때마다 돈이 나가는 외부 API 가 둘이다 —
카카오 길찾기(`POST /navigation/next-leg`), DeepSeek 가이드 챗봇(`POST /guide/chat`). 가입자 한 명이 몇 번을 부르든, 앱 버그로
반복되든 비용이 그대로 나간다. 지금은 구글 동의 화면이 「테스트」 라 팀만 가입할 수 있어 드러나지 않는다.

지금 있는 것은 DEV·PRD 게이트웨이(nginx)의 **IP 당** 제한(10 r/s · burst 20 · 동시 20, 초과 429)뿐이다. 측정 근거가 없는
출발값이고(2026-09-20 ALB 배포), 게이트웨이 파드마다 따로 센다. 그리고 IP 는 사람이 아니다 — LTE 는 통신사 CGNAT 로 많은
사용자가 공인 IP 하나를 함께 쓰고, 한 사람은 IP 를 바꿀 수 있다. 실제 한도는 **계정**에 건다.

## 2. 결정 (2026-10-08)

초반은 무료다. 한도는 **정상 사용자를 막지 않고 폭주·악용만 막는** 값으로 두고, 기능마다 다 쓴 뒤의 길을 다르게 둔다.
나중에 유료화하면 요금제별로 숫자만 바꾼다(§4).

| 기능 | 짧은 창 | 하루 상한 | 넘으면 |
| --- | --- | --- | --- |
| 가이드 챗봇 `POST /guide/chat` | 1 시간 15 턴 | 100 턴 | 429 `GUIDE_LIMIT_REACHED` — 「잠시 뒤 다시」, 일정짜기 마법사(`/guide/plan`, 모델을 부르지 않아 비용 0)로 안내 |
| 길찾기 `POST /navigation/next-leg` | 1 분 10 번 | 300 번 | 429 `NAVIGATION_LIMIT_REACHED` — 앱이 **카카오맵·네이버지도 앱으로 넘긴다**(우리 비용 0, 여행은 이어진다) |
| 그 밖의 모든 API (가입자) | 1 분 120 번 | 없음 | 429 `RATE_LIMITED` + `Retry-After` — 잠깐 느려질 뿐 |
| 그 밖의 모든 API (비회원, 설치 UUID) | 1 분 60 번 | 없음 | 같음 |
| 게이트웨이 IP 당 | 30 r/s · burst 60 | — | 폭주만 막는 바깥 울타리로 느슨하게 |

하루는 **한국 시간 자정**에 다시 찬다. 짧은 창은 고정 창(그 분·그 시간)으로 센다 — 경계에서 두 배까지 몰릴 수 있지만 단순하고,
하루 상한이 최대 비용을 막는다.

**길찾기는 막지 않는다.** 여행 중의 핵심이라 딱 막으면 앱이 멈춘다 — 넉넉히 두고(정상 하루 20~30 번), 넘으면 외부 지도 앱으로.

## 3. 어디서 세나

| 무엇 | 어디 | 이유 |
| --- | --- | --- |
| 유료 API(챗봇·길찾기)의 짧은 창·하루 상한 | **DB**(`usage_counter`) | 비용과 직결 — 서버가 여러 대여도 정확해야 하고 재시작에 날아가면 안 된다 |
| 그 밖의 분당 제한 | **서버 메모리**(파드마다) | 모든 요청마다 DB 에 쓰지 않는다. 폭주만 막으면 되니 파드마다 따로 세도 충분하다(게이트웨이와 같은 성격) |
| IP 당 | 게이트웨이(nginx) | 지금 그대로, 값만 느슨하게 |

```sql
-- 유료 API 사용량. (누가, 무엇을, 어느 창) 하나에 한 줄. 늘리기는 원자적 UPSERT 한 번 —
-- INSERT … ON CONFLICT DO UPDATE SET count = count + 1 RETURNING count
usage_counter (subject TEXT, feature TEXT, window_start TIMESTAMPTZ, count INT,
               PRIMARY KEY (subject, feature, window_start))
```

**세는 시점:** 실제로 외부 API 를 부르기 **직전**에 센다. 400·404 처럼 우리가 거절한 요청은 세지 않는다. 외부 API 가 실패해
(503) 아무것도 받지 못했으면 되돌린다 — 사용자 잘못이 아니다. 오래된 줄은 이틀 뒤 지운다.

**누구로 세나:** 가입자는 계정 id, 비회원은 설치 UUID 로 찾은 계정 id(`CurrentAccount`). 둘 다 계정 행이 있으므로 같은 열쇠다.

## 4. 요금제 — 숫자는 설정으로

```yaml
scenetrip:
  limits:
    plans:
      free:   # 지금은 이것 하나
        guide-chat:   { per-hour: 15, per-day: 100 }
        navigation:   { per-minute: 10, per-day: 300 }
        requests-per-minute: 120
    guest-requests-per-minute: 60
```

계정에 요금제 칸은 아직 두지 않는다 — 모두 `free` 다. 유료화할 때 `app_user.plan` 과 요금제 하나를 더하면 코드는 그대로다.

응답 헤더로 남은 양을 알린다(IETF `RateLimit` 헤더 초안을 따른다): `RateLimit-Limit` · `RateLimit-Remaining` ·
`RateLimit-Reset`. 앱이 「오늘 3 번 남았어요」 를 보이거나, 유료화 뒤 안내를 붙이기 좋다.

## 5. 챗봇 멱등 키

`POST /guide/chat` 은 멱등이 아니다 — 부를 때마다 토큰 비용이 나가고, 대화 이력이 쌓이고, 장바구니에 담는다(`cart.add` 는 같은
장소면 409 라 그 자체는 안전하다). 그래서 지금은 「서버도 앱도 재시도하지 않는다」 로 막아 두었다(guide-endpoints.md §2-2).
그래도 응답 직전에 끊기면 사용자가 같은 질문을 다시 보내 두 번 처리되고, 한도가 생기면 한 번 물었는데 두 번 깎인다.

**`Idempotency-Key` 헤더**(IETF 초안 draft-ietf-httpapi-idempotency-key-header, Stripe 등 결제 API 의 방식):

```
앱: 「전송」 을 누를 때마다 새 UUID. 재시도(자동·버튼)는 같은 키, 다음 메시지는 새 키
서버: idempotency_key (user_id, key, request_hash, state, response, created_at) 에
      (user, key, 해시, 처리 중) 을 넣어 본다 — 키에 UNIQUE, ON CONFLICT DO NOTHING
  넣어졌다          → 처리(모델·이력·장바구니) → 결과 저장, 완료 → 응답      ← 한도는 여기서만 깎는다
  있다 · 완료 · 해시 같음 → 저장한 응답 그대로(모델을 다시 부르지 않는다)
  있다 · 처리 중         → 409 IDEMPOTENCY_IN_PROGRESS (두 번 누른 것)
  있다 · 해시 다름       → 422 IDEMPOTENCY_KEY_REUSED (앱 버그)
  처리가 실패(503 등)    → 키를 지운다 — 같은 키 재시도를 다시 처리하게
  처리 중 1 분 넘음      → 실패로 본다(서버가 죽은 경우. 챗봇 응답 상한 40 초)
```

먼저 조회하고 넣으면 동시에 온 둘이 모두 「없음」 을 본다 — 넣기 자체를 판정으로 쓴다. 보관 24 시간. **키가 없는 요청은
지금처럼 처리한다**(이미 나가 있는 앱). 키가 생기면 앱은 끊김·503·409 를 안전하게 재시도할 수 있다 — 규칙은 MZ2AZ-366.

`/guide/plan` 은 모델을 부르지 않아 비용·부작용이 없다 — 키가 필요 없고 한도도 일반 요청의 것만.

## 6. 계약 (1.5.0, 더하기만)

- 응답 `429` — `ApiError` 에 `code`(`RATE_LIMITED` · `GUIDE_LIMIT_REACHED` · `NAVIGATION_LIMIT_REACHED`), 헤더 `Retry-After`
  (초) · `RateLimit-*`. 하루 상한이면 `Retry-After` 가 다음 자정까지라 앱은 기다리지 않고 안내한다.
- `POST /guide/chat` 에 선택 헤더 `Idempotency-Key`, 응답 `409 IDEMPOTENCY_IN_PROGRESS` · `422 IDEMPOTENCY_KEY_REUSED`.
- 계약 맨 위 「요청 한도」 절과 `docs/api/errors.md`.

## 7. 제공자 쪽 안전장치 — 사람이 확인

서버가 뚫리거나 버그가 나도 최대 손실이 정해져 있게:

- DeepSeek: 선불 잔액 상한, 자동 충전 꺼짐 확인
- 카카오: 하루 호출 한도와 초과 시 과금 여부 확인

## 8. 순서

```
1. 계약 1.5.0 + errors.md                → 앱 티켓 MZ2AZ-366 에 반영(재시도·한도 안내·길찾기 넘기기)
2. V24 usage_counter · idempotency_key   + 설정(요금제)
3. 서버: 일반 분당 제한(필터) · 챗봇/길찾기 한도 · 챗봇 멱등 키 · RateLimit 헤더
4. 게이트웨이 IP 제한 완화(30 r/s · burst 60)
5. 시험(새 컨텍스트) · 로컬 실제 요청 · DEV 에서 화면당 실제 요청 수를 재고 숫자 조정
```
