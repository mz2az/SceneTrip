# 가이드 작품 정보와 카드 「길찾기」 (MZ2AZ-554)

상태: 구현됨 — 에이전트·iOS·웹. Android 는 범위 밖.

## 1. 무엇을, 왜

| 문제 | 바꾼 것 |
| --- | --- |
| 「기생충 무슨 내용이야」·「출연진 누구야」 에 답할 도구가 없었다. 모델이 아는 척 줄거리·배우를 지어낼 수 있었다 | 에이전트 도구 `title_info` — 작품 상세(`ContentDetail`)를 받아 종류·연도·장르·출연·줄거리·촬영지 수를 준다. 프롬프트가 줄거리·출연은 이 도구로만 말하라고 못 박는다 |
| 답의 장소 카드에서 그곳으로 바로 가는 길이 없었다. 「여기로 길찾기」 는 여행 중이고 이미 담긴 곳에만 떴다 | 카드 「길찾기」 — 담겨 있든 아니든, 여행 중이든 아니든 뜬다. 누르면 아래 단계를 차례로 한다 |

## 2. 에이전트 — `title_info`

- 스키마: `agents/trip-guide/schemas/tools.json` — 인자 `title`(문자열, 필수) 하나, `additionalProperties: false`.
- 실행(`src/tools.py`): 「성지·촬영지」 같은 일반 단어를 떼고(`_clean_query`) `book.title_detail(title)` 을 부른다.
  못 찾으면 「「X」 라는 작품 정보가 없다」 로 거절한다 — 지어낼 재료를 주지 않는다.
- 창구(`src/sceneapi.py` `SceneApiPlaceBook.title_detail`): `GET /contents?q=`(5 개) → 제목이 정확히 같은 것, 없으면 1 위 →
  `GET /contents/{id}`. 검색이 비면 한 번만 부르고 None. CSV 창구(`PlaceSource` 기본)는 줄거리 열이 없어 None.
- 결과 키: `작품`·`종류`(category 그대로)·`연도`·`장르`·`출연`(roleType `actor` 인 이름)·`줄거리`·`촬영지 수`. 빈 칸은 싣지 않는다.
- 프롬프트 §3 표에 한 줄, §4 에 「title_info 가 준 것만 — 지어내지 마라」. 평가 `evals/cases.json` 에 「기생충 무슨 내용이야」.
- 서버 계약은 그대로다 — `GuideToolUse.tool` 은 문자열이라 옛 앱도 새 이름을 읽는다(설명의 도구 표만 열 개로).

## 3. 앱 — 카드 「길찾기」 단계

`RouteGuideDirections.steps`(iOS) · `guideDirectionsSteps`(웹, `guideRules.ts`) — 같은 값이다.

| 로그인 | 코스에 있음 | 여행 중 | 단계 |
| --- | --- | --- | --- |
| 아니오 | — | — | `signIn` — 길찾기 로그인 안내만 |
| 예 | 아니오 | 아니오 | `add` → `startTrip` |
| 예 | 예 | 아니오 | `startTrip` |
| 예 | 아니오 | 예 | `add` → `switchTarget` |
| 예 | 예 | 예 | `switchTarget` |

- `signIn`: iOS 는 검색 탭 길찾기와 같은 안내(「로그인하면 바로 길안내를 시작해요」), 웹은 `promptSignIn(directionsSignInReason())`.
  편집 화면은 덮개 안이라 iOS 는 편집 화면에도 로그인 시트 자리를 단다.
- `add`: 가이드의 기존 담기 길(iOS `addGuidePlace`, 웹 `addPlace`).
- `startTrip`: 「코스 시작」 과 같은 순서 — 코스를 여행 중으로 바꾸고 서버가 안 뒤에 그곳으로 안내(`startTrip(to:)`/`startTripTo`).
  갈 수 없는 곳(`canNavigate` 아님)이면 카드만 연다.
- `switchTarget`: 기존 「여기로 길찾기」 와 같은 `startTrip(to:)`/`startTripTo`.
- 담긴 줄이 다른 일차에 있으면 그 일차로 옮긴 뒤 안내한다(번호는 일차 안의 순서). 끝나면 가이드 창을 접는다.
- 웹은 담은 줄이 다음 렌더에야 보이므로 할 일을 적어 두고 그 줄이 나타나면 안내를 켠다(`tripExtensions.tsx`).

## 4. 시험

- 에이전트: `tests/test_title_info_554.py`(스키마·결과·거절·scene-api 두 번 부름·프롬프트), 평가 사례 하나.
- iOS: `tests/RouteGuideDirectionsTests.swift`. 웹: `guideDirections.test.ts`, `GuideSheet.directions.test.tsx`.
