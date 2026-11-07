# 요청 언어 — `Accept-Language` 를 그 턴의 scene-api 읽기에 싣는다

> 요청: 정승길(2026-11-07 메모) — 영어 앱에서도 `/guide/plan`·`/guide/chat` 의 장소 이름·주소가
> 늘 한국어로 온다. 앱은 MZ2AZ-530 으로 임시로 막아 두었다.

## 1. 무엇이 잘못됐나

| 자리 | 지금 |
| --- | --- |
| scene-api → 에이전트 | 앱 언어를 `Accept-Language` 로 넘긴다(`GuideAgentClient.java`, 값은 `Lang` 열거형 하나: `ko`·`en`·`ja`·`zh-Hant`) |
| 에이전트 운영 서버 | 장소 창구를 `SceneApiPlaceBook(...)` 하나로 만들고 `lang` 은 기본값 `ko` (`src/internal_server.py`) |
| 창구의 읽기 | 매번 scene-api 를 부르지만 `Accept-Language: self.lang` — 늘 `ko` (`src/sceneapi.py`) |
| `web/server.py` 의 `/plan`·`/guide/chat` | 요청 헤더를 보지 않는다 |

데이터를 한 번만 받아 두는 것은 아니다. **읽을 때마다 같은 언어로 묻는 것**이 문제다.

## 2. 고치는 방식

**창구의 언어를 바꾸지 않는다.** 한 서버가 4 턴을 함께 돌린다([concurrent-turns.md](concurrent-turns.md)).
공유하는 창구의 `lang` 을 고치면 영어 턴과 일본어 턴이 서로의 언어로 읽는다.

한도 열쇠(`X-Rate-Subject`, ADR 0023)와 같은 길로 간다 — 그 턴 동안만 들고 있는 값이다.

- `src/production.py` 에 `request_lang(value)` 문맥과 `current_lang()` 을 둔다.
- `web/server.py` 의 `Handler.do_POST` 가 요청의 `Accept-Language` 로 그 문맥을 연다. 운영 서버
  (`InternalHandler`)는 `super().do_POST()` 를 부르므로 같이 받는다.
- `SceneApiPlaceBook._get` 은 `current_lang()` 이 있으면 그것을, 없으면 `self.lang` 을 싣는다.
- 받는 값은 계약의 `Lang` 넷뿐이다(대소문자 무시, `zh-hant` → `zh-Hant`). 그 밖의 값·빈 값은
  **없는 것으로 본다** — 창구의 기본 언어(설정의 `ko`)로 읽는다. 받은 값을 다른 요청의 헤더로
  옮기므로 모양을 좁혀 둔다.
- CLI·`--lang` 은 그대로다. 헤더가 없는 턴은 지금과 똑같이 돈다.

## 3. 언어가 바뀌면 같이 깨지는 곳

영어 주소는 순서가 거꾸로이고 쉼표로 나뉜다 — `121 Jahamun-ro, Jongno-gu, Seoul`.

| 자리 | 한국어 주소를 가정한 것 | 고침 |
| --- | --- | --- |
| `llm_planner.location()` | 앞 두 마디 → 「서울 종로구」. 영어면 「121 Jahamun-ro,」 가 모델에게 간다. AI 일정은 **이 값만 보고** 날짜를 지역으로 나눈다 | 쉼표 주소는 뒤 두 마디를 거꾸로 — 「Seoul Jongno-gu」 |
| `tools._in_region()` | 끝이 도·시·군·구인 마디만 본다. 영어면 아무것도 안 걸려 지역 검색에 다른 지역이 다시 섞인다(MZ2AZ-457 이전으로) | 쉼표 주소는 첫 마디(번지·도로)를 빼고 나머지를 대소문자 무시로 본다 |
| `prompts/system.txt` §2 | 「장소 이름과 주소는 한국어 원문 그대로」 — 영어만 받은 모델이 한국어 이름을 지어낼 수 있다 | 「도구가 준 이름·주소 그대로」 |

| `llm_planner.PlaceScores.get()` | 성지점수 표(`config/place_scores.json`)를 **한국어 작품명**으로 고른 뒤 장소명, 안 되면 같은 작품 안 300 m 좌표로 찾는다. 영어면 작품명부터 못 찾아 인기도가 전부 0 — AI 일정이 대표 성지를 모른다. scene-api 는 `contents[].popularity` 를 아직 싣지 않는다(2026-11-07 로컬 실측) | 작품명이 표에 없으면 **모든 작품의 행**에서 300 m 안의 가장 가까운 것을 쓴다. 표 98 행 중 96 행에 좌표가 있다 |

그대로 두는 것:
- **편의시설(`/pois`)** 의 `name`·`address` 는 계약상 언제나 한국어 원본이다. `GuidePlace` 에 요청 언어
  칸이 없어 이번 범위 밖이다.
- **일본어·번체 주소**는 한국어 순서(「ソウル特別市 鍾路区 清雲洞」)라 `location()` 은 그대로 맞다.
  `_in_region()` 은 끝 글자(区·市)가 달라 거르지 못하고, 그때는 지금처럼 거르지 않은 결과를 준다.

## 4. 기능 테스트 목록

| # | 입력 | 기대 결과 |
| --- | --- | --- |
| 1 | 운영 서버에 `Accept-Language: en` 으로 `/plan` | 그 턴의 scene-api 읽기가 모두 `Accept-Language: en` |
| 2 | 같은 요청을 `/guide/chat` 으로 | 1 과 같다 |
| 3 | `en` 턴과 `ja` 턴을 동시에 | 각 턴의 읽기는 제 언어만 싣는다 |
| 4 | 헤더 없이 | 창구의 기본 언어(`ko`) — 지금과 같다 |
| 5 | `fr` · 빈 값 · `en-US,en;q=0.9` · 줄바꿈이 든 값 | 없는 것으로 본다 → 기본 언어 |
| 6 | `zh-hant` · `EN` | `zh-Hant` · `en` 으로 싣는다 |
| 7 | `en` 턴이 끝난 뒤 헤더 없는 턴 | 기본 언어 — 앞 턴의 값이 남지 않는다 |
| 8 | `/plan` 에 `en`, scene-api 가 영어 이름·주소를 줄 때 | 응답 `plan.days[].stops[].name`·`address` 가 영어 |
| 9 | `location("121 Jahamun-ro, Jongno-gu, Seoul")` | `"Seoul Jongno-gu"` |
| 10 | `location("서울특별시 종로구 청운동")` · `location("ソウル特別市 鍾路区 清雲洞")` | `"서울 종로구"` · `"ソウル特別市 鍾路区"` (지금과 같다) |
| 11 | `_in_region("San 240 Seungbong-ri, Jawol-myeon, Ongjin-gun, Incheon", "incheon")` | 참 |
| 12 | `_in_region("12 Jeju-ro, Jongno-gu, Seoul", "Jeju")` | 거짓 — 도로 이름은 보지 않는다 |
| 13 | `_in_region` 한국어 주소 | 지금과 같다 |
| 14 | `PlaceScores.get("Do Gae-bi", 영어 이름·표의 행과 같은 좌표인 장소)` | 표의 그 행 점수 (0 이 아니다) |
| 15 | `PlaceScores.get` 한국어 작품명·이름 | 지금과 같다 |
| 16 | `PlaceScores.get` 표에 없는 작품명, 300 m 안에 아무 행도 없는 장소 | 0 |

결과는 §5 에 적는다.

## 5. 결과 (2026-11-07)

시험은 구현을 보지 않고 §4 만 보고 따로 썼다(`tests/test_request_language.py`). 구현 전에는 새 시험이
모두 의도한 이유로 실패했다 — 새 이름이 없어서, 읽기가 늘 `ko` 여서, 영어 주소를 못 다뤄서.

| # | 단위 시험 | 비고 |
| --- | --- | --- |
| 1–8 | 통과 | 5 번은 처음 구현이 값의 앞뒤 공백을 깎아 `"en\n"` 을 `en` 으로 받아서 실패했다 — 깎지 않게 고쳤다 |
| 9–13 | 통과 | 10·13 은 회귀 행 |
| 14–16 | 통과 | 15·16 은 회귀 행 |

`just test //agents/trip-guide:unit_test //agents/trip-guide:eval_test` 407 개 통과, `just check` 통과.

로컬 스택에서 앱 경로(scene-api `:8081` → 에이전트 `:8899`, 실제 Bedrock)로 확인했다.

| 요청 | 결과 |
| --- | --- |
| `POST /guide/plan` 도깨비 2일, `en` | `titles: ["Do Gae-bi"]`, 정지점 「Dokkaebi Breakwater · 1609 Haean-ro, Jumunjin-eup, Gangneung-si, Gangwon-do」 등 영어 |
| 같은 요청, `ko` · `ja` | 「도깨비방파제」 · 「トッケビ防波堤」 — 각 언어 |
| `POST /guide/chat` 「Show me Goblin filming locations」, `en` | 답과 `places[]` 카드가 영어 이름·주소, 장면 설명도 영어 |
| 같은 물음 한국어, `ko` | 전과 같다 |
| AI 일정에 넘기는 값, 도깨비 `en` | 인기도 5 곳 중 5 곳이 0 보다 큼(한국어와 같은 값, 예 39.1), 지역 「Gangwon-do Gangneung-si」 |

남은 것:

- `plan.notes` 는 에이전트가 쓰는 한국어 문장이라 영어 요청에도 한국어다. 앱이 이 칸을 그리는지에 따라
  따로 다룬다.
- 편의시설(`source: "poi"`) 카드는 계약대로 한국어 원본이다(§3).
