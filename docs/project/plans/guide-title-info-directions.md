# 가이드 작품 정보와 카드 「길찾기」 (MZ2AZ-554) · 작품 이름 장소 검색 (MZ2AZ-555)

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
- 창구(`src/sceneapi.py` `SceneApiPlaceBook.title_detail`): `GET /contents?q=`(5 개). 목록 제목이 정확히 같으면 그 상세
  (`GET /contents/{id}`). 아니면 후보마다(최대 5) 상세를 받아 별칭까지 보고, 제목·별칭이 같은 것 → 세 글자 이상인 물음이
  제목·별칭에 든 것 순으로 받는다. 둘 다 없으면 None — 검색 1 위가 설명·출연으로 걸린 다른 작품일 수 있어서다(검증 지적).
  검색이 비면 한 번만 부르고 None. CSV 창구(`PlaceSource` 기본)는 줄거리 열이 없어 None.
- 결과 키: `작품`·`종류`(category 그대로)·`연도`·`장르`·`출연`(roleType `actor`)·`감독`(roleType `director`)·`줄거리`(600 자에서 「…」)·`촬영지 수`. 빈 칸은 싣지 않는다.
- 프롬프트 §3 표에 한 줄(「감독 누구야」 포함), §4 에 「title_info 가 준 것만 — 지어내지 마라」. 평가 `evals/cases.json` 에 「기생충 무슨 내용이야」.
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
- 에이전트(555): `tests/test_title_query_search_555.py`(두 창구의 `exact_title`·`search_places` 작품 모드와 부분 일치·도구 설명·프롬프트).

## 5. 장소 검색이 작품 이름이면 그 작품의 촬영지만 (MZ2AZ-555)

dev 에서 「kpop demon hunters 성지 알려줘」 에 모델이 `search_places` 를 불렀고, `/places?q=` 가 낱말이 걸린 인기 장소를
돌려줘 다른 작품(경이로운 소문)의 원주시청이 섞이고 실제 촬영지 몇 곳은 빠졌다.

| 문제 | 바꾼 것 |
| --- | --- |
| `search_places` 가 작품 이름도 `/places?q=` 로 찾았다 | 지역 결과(주소로 거른 곳)가 아닐 때만, 일반 단어를 뗀 검색어가 작품 제목·별칭과 **정확히** 같으면 `book.exact_title(query, limit)` 로 그 작품의 촬영지만, `list_title_places` 와 같은 모양(`작품`·`안내`·`촬영지`)으로 준다. 기억하고 지도에 띄운다. 부분 일치(제목의 일부·지역·배우)는 예전 장소 검색 그대로 |
| 도구 설명이 「작품 이름」 으로도 찾으라 했다 | `schemas/tools.json` `search_places` 설명에서 작품 이름을 빼고, 작품을 말했으면 `list_title_places` 를 부르라고 적었다 |
| 프롬프트 표에 영어 제목·「성지」 예가 없었다 | §3 표에 「kpop demon hunters 성지」 · 「Crash Landing on You filming spots」 → `list_title_places`, 그리고 한 문장 — 작품 이름이 들어 있으면 `search_places` 말고 `list_title_places` 부터 |

- 창구 `exact_title`: scene-api(`src/sceneapi.py`)는 `title_detail` 과 같은 고르기(`_match_content`)를 부분 일치 없이 쓴다 —
  `GET /contents?q=` 목록 제목이 같으면 그것, 아니면 후보마다(최대 5) `GET /contents/{id}` 의 별칭까지. 찾으면
  `GET /contents/{id}/places?limit=`, 못 찾으면 (None, []) 이고 촬영지는 부르지 않는다. CSV(`src/places.py`)는 `by_title` 과
  같은 훑기(`_title_rows`)를 부분 일치 없이 — 제목·영문 제목이 같은 것만. `PlaceSource` 기본은 (None, []).
- 지역이 먼저다 — 「해운대」·「밀양」 처럼 지역과 이름이 같은 작품이 있고, 지역 검색에 작품 확인 호출(scene-api 최대 6 번)을 더하지 않으려고 지역 결과면 `exact_title` 을 아예 부르지 않는다.
