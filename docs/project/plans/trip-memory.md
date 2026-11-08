# 정한 여행(도시·며칠) 기억 — `/me/trip`

- **작성일**: 2026-11-09
- **티켓**: MZ2AZ-542
- **상태**: 진행
- **앞선 문서**: [web-trip-first-prototypes.md](./web-trip-first-prototypes.md)(홈 「어디로 가세요?」), [web-proto-apply.md](./web-proto-apply.md) §3 B2

---

## 1. 왜

홈 질문(도시 → 며칠 → 어떻게)에서 정한 「내 부산 여행 · 2일」 을 웹은 이 브라우저 localStorage 에만 둔다. 기기를 바꾸거나 iOS·Android 에서는
모른다. 코스·장바구니처럼 계정(비회원 포함)에 둔다.

## 2. 계약 (1.25.0 — 더하기만)

| 창구 | 하는 일 |
| --- | --- |
| `GET /me/trip` | 정한 여행. 없으면 `404 TRIP_NOT_FOUND` |
| `PUT /me/trip` | 정한 여행을 통째로 바꾼다(없으면 만든다) → 200 `TripPlan` |
| `DELETE /me/trip` | 지운다. 없어도 204(멱등) |

- 누가: 장바구니·코스와 같이 **비회원도**(`X-Install-Id` 의 설치 계정, 토큰이 있으면 그 계정). 홈의 첫 질문은 가입 전이 대부분이다.
- `TripPlan { city, days, pace, courseId?, updatedAt }`. `city` 는 앱이 보이는 도시 이름(1~40자, 앞뒤 공백 제거) — 서버는 도시 목록을 갖지 않는다
  (도시 묶기는 웹 `homeCities.ts` 의 몫). `days` 1~15, `pace` `relaxed`·`normal`·`packed`.
- `courseId` 는 **내 코스**만(남의 것·없는 것은 `400 TRIP_COURSE_INVALID`). 코스를 지우면 여행은 남고 `courseId` 만 비워진다.
- 계정 하나에 여행 하나(지금 화면이 하나만 다룬다). 여럿은 필요해지면 새 창구로.

## 3. DB (V45)

`user_trip (user_id PK → app_user CASCADE, city, days, pace, course_id → course SET NULL, updated_at)`. 비회원이 가입하며 합쳐질 때
(`AccountLinkStore.moveData`) 가입 계정에 여행이 없으면 옮기고, 있으면 가입 계정 것을 둔다.

## 4. 시험

| 갈래 | 무엇 |
| --- | --- |
| 단위 | 범위 밖 `days`·잘못된 `pace`·빈 `city` → 400, 없으면 404 |
| 통합 | 넣고 읽기, 덮어쓰기, 지우기 멱등, 남의 코스 → 400, 코스 지우면 `courseId` null, 비회원 → 가입 합치기에서 옮겨 감·가입 계정 것이 이김, 탈퇴하면 지워짐 |
| 실제 요청 | `just update scene-api` 뒤 curl |
