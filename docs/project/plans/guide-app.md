# 앱을 가이드 창구에 붙인다 — 챗봇 시트는 `POST /guide/chat`, 마법사는 `POST /guide/plan` (MZ2AZ-321)

> 2026-09-11 작성 · iOS. 계약 `contracts/openapi/scene-api-v1.yaml` 1.2.0 이 정본이고 앱은
> 생성된 클라이언트(`GuideAPI`)만 쓴다. 백엔드 쪽은 [guide-endpoints.md](./guide-endpoints.md),
> 구조 결정은 ADR 0013.

## 0. 한 줄 요약

main 의 챗봇은 「준비 중」이었고(MZ2AZ-297) 마법사는 앱 안의 규칙(`RoutePlanner`)으로 짰다.
둘 다 **백엔드 창구로 잇고, 앱 안의 대체 로직은 지운다.** 에이전트가 꺼져 있으면 「잠시 뒤 다시」가
뜨는 것이 정상이고 규칙 기반 답이 나오면 잘못된 것이다(티켓 §확인).

## 1. 무엇이 어디로 가나

| 화면 | 전 | 후 |
| --- | --- | --- |
| 챗봇 시트 `RouteGuideSheet` | `RouteGuide.ask` 가 `notReady` 를 던짐 | `GuideAPI.chatWithGuide` — 이력·위치·**화면 상태** 를 싣고 답·근거·장소·`effects`·`ui` 를 받는다 |
| 일정짜기 마법사 `RouteWizardView` | `RouteStore.aiDraft` → `RoutePlanner`(인기순+지리) | `GuideAPI.planWithGuide` — 작품·일수·속도·좌표를 보내고 `GuidePlan` 초안을 받는다 |
| `RoutePlanner.swift` · `LocalModel` | 규칙 기반 폴백 | **삭제.** 일정은 에이전트의 코스 엔진 한 곳에서만 나온다 |

## 2. 챗봇 — 요청에 싣는 것

`GuideContext` 는 질문마다 다시 만든다(`RouteEditorGuide.swift`). 셋을 매번 싣는다.

| 칸 | 어디서 | 왜 |
| --- | --- | --- |
| `stops` | 지금 일차의 정지점, 번호는 지도 핀과 같다 | 「2번 주변 카페」를 서버가 좌표로 바꾼다. 다녀옴(`visited`)도 같이 |
| `trip` | `TripSession` 단계 + `FootprintStore` 최근 하루 거리 | 「다 돌았어?」를 도구 없이 답한다. 여행 중이 아니면 `nil` |
| `plan` | 편집 중인 `RouteCourse` 를 `GuidePlan` 모양으로(`RouteGuidePlan.plan(from:)`) | 편집 중엔 앱이 정본이다 — 없으면 「2일차에서 빼 줘」가 거절된다 |

**타임아웃 50초, 자동 재시도 없음.** 서버 벽이 40초라 앱이 그보다 길어야 한다 — 앱이 먼저 끊으면
서버가 뒤늦게 `cart.add` 를 저장하는데 앱은 실패로 본다. `/guide/chat` 은 멱등이 아니라 재시도하면
이력이 두 번 쌓인다. 50초는 `RouteGuideTimeout.run` 이 요청 태스크를 취소하는 것으로 지킨다
(생성 클라이언트의 `execute()` 가 `withTaskCancellationHandler` 로 취소를 전달한다).

## 3. 챗봇 — 응답을 나눠 처리한다

```
GuideChatReply
├─ reply · toolsUsed · tookSeconds   → 말풍선 + 「근거」 줄 (RouteGuideSession)
├─ places[] (source: place | poi)    → 목록·지도 점. id 는 "place-N" | "poi-N" (RouteGuide.Place)
├─ effects[]                          → 앱이 할 일 (RouteEditorView.applyGuideAnswer)
│    plan.draft · plan.revise · plan.move  편집 사본을 plan 으로 갈아 끼운다. **저장하지 않는다**
│    cart.add · cart.remove               서버가 이미 저장했다 — 장바구니만 다시 읽는다
│    (모르는 op)                            무시
└─ ui[]                               → 화면 명령 (같은 자리)
     map.focus · route.draw · place.card · course.open · course.focus · sheet.collapse
     (모르는 op)                            무시 — 에이전트가 늘려도 옛 앱이 안 깨진다
```

`places[].source` 가 상세 API 를 정한다 — `place` 는 `GET /places/{id}`, `poi` 는 `GET /pois/{id}/card`.
촬영지와 편의시설은 다른 표라 숫자 id 만으로는 못 가른다(계약 `GuidePlace` 설명).

`route.draw` 는 지금 **핀만 찍고 순서대로 맞춘다.** 편집 지도의 선(`legs`)은 계약 `NextLeg` 의
실제 길 좌표를 그리는 자리라, 임의의 장소 사이에 직선을 긋는 그림은 따로 없다. 필요해지면 그때
`RouteMapView` 에 「가이드 선」을 더한다.

오류는 `RouteGuideFailure` 가 계약 응답별로 가른다 — 401 가입 · 400 요청 문제(`message` 는 화면에
안 띄운다, `docs/api/errors.md`) · 503 `GUIDE_UNAVAILABLE` 「잠시 뒤 다시」 · 50초 초과 · 연결 실패.

## 4. 마법사 — `GuidePlan` 을 편집 화면에 그린다

`RouteGuidePlan.course(from:)` 가 `GuidePlan` 을 `RouteCourse` 로 옮긴다. 저장된 코스와 다른 모양이라
(계약이 「견적서와 계약서」라고 부른다) 화면 타입에 칸 셋을 더했다.

| 칸 | 어디서 | 화면 |
| --- | --- | --- |
| `RouteStop.arriveMinute` | `GuidePlanStop.arriveMinute` (0시 기준 정수 분) | 줄에 「09:00 도착」 — 표시 문자열은 앱이 만든다 |
| `RouteStop.placeMissing` | `placeId == null` | 「저장 안 됨」 표시. **이름으로 대체하지 않는다** — `RouteBridge.replace` 가 이 줄을 뺀다 |
| `RouteCourse.draftNotes` | `dropped`(뺀 곳과 이유) · `notes` · `travelBasis` | AI 띠 아래 한 줄씩. 요청한 작품의 촬영지가 말없이 사라지면 추천이 틀렸다고 느낀다 |

속도는 `RoutePace` 둘(빡빡·널널)을 계약의 셋(`packed`·`normal`·`relaxed`) 중 양끝으로 보낸다.
작품을 하나도 안 골랐으면 인기 작품 셋의 제목을 보낸다(계약 `titles` 는 `minItems: 1`).

**「완료」** 는 그대로다 — `RouteBridge.replace(from:)` 가 정지점마다 `placeId`·`dwellMinutes` 를 꺼내
`PUT /courses/{id}` 로 보낸다. `placeMissing` 인 줄만 빠진다.

## 5. 시험

| 시험 | 못 박는 것 |
| --- | --- |
| `RouteGuideFailureTests` | 401·400·503·연결 실패·50초 초과의 갈래와 문구. 어느 갈래에도 「준비 중」이 없다 |
| `RouteGuidePlanTests` | `GuidePlan` → `RouteCourse` → `GuidePlan` 왕복에서 `placeId`·순서·체류가 보존된다. `placeId == null` 은 저장에서 빠진다. `540` → 「09:00」 |
| `RouteGuideDirectiveTests` | 아는 `op` 여섯은 갈래로, 모르는 `op` 는 `nil` — 응답 전체를 버리지 않는다 |

실제 서버로 보는 것(티켓 §확인)은 시뮬레이터에서 한다 — 에이전트를 끄면 「잠시 뒤 다시」,
「도깨비로 1박 2일」 → 코스 화면이 1일차로 열리고 시트가 내려간다.

## 6. 이 문서 밖

- **Android.** `apps/scenetrip-android` 에는 챗봇 시트도 마법사도 아직 없다(2026-09-11 확인).
  iOS 를 그대로 옮기는 것이 규칙이라 그쪽은 화면부터 만들어야 하고, 이 계획의 범위가 아니다.
- 온보딩의 「빡빡 5곳 · 널널 3곳」은 `RoutePlanner.perDay` 의 값이었다. 이제 하루 정지점 수는
  에이전트 설정(계약 `GuidePlanRequest.pace`: 여유 3 · 보통 5 · 빡빡 7)이라 문구를 7 · 3 으로 맞췄다.
