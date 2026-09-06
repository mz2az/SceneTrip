# 백엔드 연결 명세 — 권호님께

> 2026-09-05 · 김태환 (`agents/trip-guide`)
> 대상: 정권호 (`services/scene-api`)
>
> **에이전트가 무엇을 하고, 무엇을 부르고, 무엇을 돌려주는지**를 코드 작성에 필요한
> 만큼만 적었다. 왜 이 구조인지는 [backend-agent-contract.md](backend-agent-contract.md),
> 조사 근거는 [chatbot-and-planner-survey.md](chatbot-and-planner-survey.md) 에 있다.

---

## 0. 백엔드가 만들 것 — 요약

**엔드포인트 하나.** 새 DB 표도, 새 계산 로직도 없다.

```
POST /v1/chat
  ① 앱 요청을 에이전트에 넘긴다
  ② 응답의 effects 를 두 갈래로 처리한다  ← cart 는 저장, plan 은 통과
  ③ ui 는 검증 없이 통과시킨다
  ④ 앱에 돌려준다
```

---

## 1. 에이전트가 하는 일

| 기능 | 무엇 | LLM |
| --- | --- | --- |
| **코스 추천 엔진** | 작품·기간·속도 → 일차별 일정 (시각·순서·이동거리) | **안 씀** |
| **챗봇** | 말 → 도구 10 개 → 답 | ①이해 ③설명에만 |
| **일정 수정** | 「2일차에서 개뿔 빼줘」 → 그 일차만 재계산 | 도구 선택에만 |

계산에는 모델이 없다. 그래서 같은 입력에 같은 일정이 나오고, 시험 99 개가 모델 없이 돈다.

---

## 1-1. 백엔드 계약에 맞춘 것 (2026-09-05)

권호님이 정의한 `POST /guide/chat`(MZ2AZ-239)과 편의시설 API(MZ2AZ-314)에 맞춰 세 가지를 고쳤다.

| 맞춘 것 | 어떻게 |
| --- | --- |
| **응답 필드 이름** | `POST /guide/chat` 경로를 새로 내고 `reply`·`toolsUsed`·`places`·`route`·`tookSeconds` 를 계약과 똑같이 쓴다. 백엔드가 옮겨 담지 않고 그대로 앱에 넘길 수 있다 |
| **`places` 모양** | `PoiSummary` 와 같은 필드(`id`·`name`·`category`·`categoryGroup`·`address`·`latitude`·`longitude`) |
| **편의시설 도구** | `poi_nearby` 를 더했다. 인자 이름은 `contracts/schemas/guide/tools/poi_nearby.schema.json` 그대로 |

기존 `POST /api/chat` 은 **앱이 지금 쓰는 임시 규약**이라 그대로 두었다 — 백엔드 창구가
생기면 그쪽이 사라진다. 두 경로가 같은 엔진을 쓴다.

### 오류도 계약대로

| 상황 | `/guide/chat` |
| --- | --- |
| `messages` 가 비었다 | `400 {"code":"INVALID_PARAMETER"}` |
| 모델이 안 뜬다·시간 초과 | `503 {"code":"GUIDE_UNAVAILABLE"}` — 규칙 기반으로 조용히 떨어지지 않는다 |

---

## 2. 에이전트가 부르는 API — 전부 GET, 6 종

**`X-Device-Id` 를 보내지 않는다.** 이 다섯은 그 헤더를 요구하지 않는 공개 조회다.
사용자 신원은 에이전트가 알 필요가 없고, 알면 권한이 새는 구조가 된다.

| # | 호출 | 언제 | 쓰는 필드 |
| --- | --- | --- | --- |
| 1 | `GET /places?q={말}&limit={n}` | 자유 검색 (작품·배우·장소·주소) | `items[].id·name·address·latitude·longitude·type·imageUrl·contents·sceneDescription` |
| 2 | `GET /contents?q={작품}&limit=5` | 작품 id 찾기 | `items[].id·title` |
| 3 | `GET /contents/{id}/places?limit={n}` | 그 작품의 촬영지 | 1 과 같음 |
| 4 | `GET /places?lat=&lng=&radiusMeters=&limit=&sort=distance` | 「이 근처」 | 1 + `distanceMeters` |
| 5 | `GET /places/{placeId}` | 상세 (사용자가 한 곳을 콕 집었을 때만) | `scenes[]·naverUrl` |
| 6 | `GET /pois?lat=&lng=&radiusMeters=&categoryGroup=&sort=distance&limit=` | **편의시설** — 「근처 카페」 | `items[].id·name·category·categoryGroup·address·latitude·longitude·distanceMeters` |

### 왜 2→3 을 두 번 부르나

`GET /places?q=도깨비` 로 한 번에 받으면 **장면 설명이 비어 온다.**
`GET /contents/{id}/places` 는 작품을 정확히 지목했을 때만 그것을 채워 준다.
장면 설명은 사용자가 가장 보고 싶어 하는 문장이라 두 번 부르는 값을 한다.

### 정렬

- `sort=distance` 는 「이 근처」일 때만 보낸다
- 인기도순은 **정렬 인자를 아예 안 보낸다** — 서버 기본값이 그것이고, 없는 값을 지어 보내면 400 이 온다

### 지금 안 쓰는 것

쓰기 API(`/cart`·`/courses`·`/favorites`·`/market`)를 **하나도 부르지 않는다.**
저장은 백엔드가 한다(§4). `/search/suggestions`·`/navigation/next-leg`·`/pois/{id}/card` 는 아직 안 쓴다.

---

## 3. 백엔드가 에이전트를 부르는 법

**`POST /guide/chat` 을 쓴다.** 계약과 같은 필드 이름으로 돌려주므로 옮겨 담을 필요가 없다.
(`/api/chat` 은 앱이 지금 8899 로 직접 부르는 임시 규약이라 필드 이름이 옛것이다.)

```
POST http://{에이전트}/guide/chat
Content-Type: application/json
```

### 요청

```json
{
  "sessionId": "s-8f21",
  "latitude": 37.5826, "longitude": 126.9910,
  "messages": [{"role": "user", "content": "2일차에서 한미서점 빼줘"}],
  "context": {
    "courseId": 7,
    "editing": true,
    "plan": { "...앱이 들고 있는 편집 사본 전체..." }
  }
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `sessionId` | ○ | 대화 식별자. 에이전트가 이것으로 세션을 잇는다 |
| `messages` | ○ | 마지막 `role:"user"` 만 쓴다. 없으면 `{error}` |
| `latitude`·`longitude` | ✕ | 없으면 「이 근처」 질문이 거절된다 |
| `context.plan` | ✕ | **DB 에서 읽지 말 것.** §5 참조 |

### 응답 (성공, HTTP 200)

```json
{
  "reply":   "2일차에서 한미서점을 뺐어요. 4곳이 되어 12:01에 마칩니다.",
  "toolsUsed": [{"tool": "revise_plan", "arguments": {"day": 2, "remove": ["한미서점"]}}],
  "places":  [{"id": 1187, "name": "순보석", "category": "관광지", "categoryGroup": "sight",
               "address": "인천…", "latitude": 37.47, "longitude": 126.62}],
  "route":   null,
  "effects": [{"op": "plan.revise", "day": 2, "add": [], "remove": ["한미서점"],
               "plan": {"...갱신된 일정 전체..."}}],
  "ui":      [{"op": "course.focus", "day": 2, "changed": ["한미서점"]}],
  "tookSeconds": 3.3
}
```

| 필드 | 항상 있나 | 백엔드가 할 일 |
| --- | --- | --- |
| `reply` | ○ | 앱에 그대로 |
| `toolsUsed` | ○ (빈 배열 가능) | 앱에 그대로 — 「무엇을 근거로」 표시용 |
| `places` | ○ (빈 배열 가능) | 앱에 그대로 — 지도 핀 |
| `effects` | ○ (빈 배열 가능) | **§4 대로 갈라 처리** |
| `ui` | ○ (빈 배열 가능) | 검증 없이 통과 |
| `tookSeconds` | ○ | 앱에 그대로 |

### 응답 (실패) — 계약대로

```json
400 {"code": "INVALID_PARAMETER", "message": "messages 가 비었다"}
503 {"code": "GUIDE_UNAVAILABLE", "message": "…"}
```

`503` 은 계약 §오류 의 「LLM 이 안 뜬다·시간 초과」다 — **규칙 기반으로 조용히
떨어지지 않는다.**

### 두 번째 창구 — 마법사용 (선택)

```
POST http://{에이전트}/plan
{"titles": ["도깨비"], "days": 2, "pace": "normal",
 "start": [37.58, 126.99], "must": [], "avoid": []}
```

**모델을 부르지 않는다.** 앱의 AI 일정짜기 마법사처럼 값이 이미 구조화돼 있을 때 쓴다.
실측 **0.02 초**(챗봇 경로는 3~5 초)이고 **API 키가 없어도 동작한다.**
응답은 `{plan, effects, ui, tookSeconds}` 로 챗봇 경로와 같은 모양이다.

---

## 4. `effects` 처리 — 여기가 핵심

```java
for (Effect e : agent.effects()) {
    if (e.op().startsWith("cart.")) applyToDatabase(e);   // 저장
    // plan.* 은 아무것도 하지 않는다 → 앱으로 그대로 넘어간다
}
```

| `op` | 함께 오는 값 | 백엔드가 할 일 |
| --- | --- | --- |
| `cart.add` | `placeId`(정수·nullable), `name` | `POST /cart/items {placeId}` |
| `cart.remove` | `placeId`, `name` | `DELETE /cart/items/{placeId}` |
| `plan.draft` | `plan` | **저장하지 않는다.** 앱에 전달 |
| `plan.revise` | `day`, `add[]`, `remove[]`, `plan` | **저장하지 않는다.** 앱에 전달 |
| `plan.move` | `name`, `day`(출발), `toDay`(도착), `plan` | **저장하지 않는다.** 앱에 전달 |

### `placeId` 가 `null` 이면 거절할 것

계약대로 **정수**(`PlaceSummary.id`)를 보낸다. 다만 id 를 모르는 경로(CSV 시험 창구)에서는
`null` 이 온다. **그때 조용히 넘기지 말고 거절해야 한다** — 이름으로 대신 쓰면 동명 장소에
걸린다. (에이전트 쪽에서 이름으로 폴백하던 것을 2026-09-04 에 없앴다.)

### `plan.*` 을 저장하지 않는 이유

계약이 못 박고 있다 — *"편집 화면의 「완료」가 부르는 하나뿐인 요청이다. …편집하는 동안
서버로는 아무것도 나가지 않는다"* (`PUT /courses/{id}` 설명).

챗봇만 예외로 두면 **사용자가 「취소」를 눌러도 이미 저장돼 있는** 화면이 생긴다.
8/11 회의 확정과도 같다 — *"AI로 초안을 만들고 바로 저장시키는 게 아니고, 거기서
수정할 수 있게 해 줘야"* (`RouteWizardView.swift` 머리말).

**저장은 사용자의 「완료」가 부르는 `PUT /courses/{id}` 한 번뿐이다.**

---

## 5. 편집 중인 코스는 **DB 가 아니라 앱이 정본이다**

백엔드가 DB 에서 코스를 읽어 에이전트에 넘기면 **틀린 것을 넘기게 된다.**

```
📱 앱 메모리   1일차 5곳 · 2일차 4곳   ← 사용자가 방금 손으로 고친 것
🗄️ DB         1일차 5곳 · 2일차 5곳   ← 어제 저장한 것
```

DB 것을 넘기면 챗봇이 **이미 없는 것을 빼려 하거나 옛 일정을 고쳐서** 돌려준다.

| | 정본 | 왜 |
| --- | --- | --- |
| 장바구니 | 🗄️ DB | 담을 때마다 즉시 저장되므로 |
| **코스 (편집 중)** | 📱 **앱** | 저장 전이므로 |
| 코스 (편집 아님) | 🗄️ DB | 마지막 저장본이 최신 |

→ **앱이 보낸 `context.plan` 을 그대로 통과시킨다.**

---

## 6. `ui` — 통과만 시키면 된다

앱이 화면에서 할 일이다. 백엔드는 검증하지 않는다.

| `op` | 값 | 앱이 하는 일 |
| --- | --- | --- |
| `map.focus` | `placeIds[]` | 핀들이 다 보이게 지도를 맞춘다 |
| `route.draw` | `placeIds[]` | 선을 그린다 |
| `place.card` | `placeId` | 장소 카드를 띄운다 |
| `course.open` | `day` | 코스 화면을 연다 |
| `course.focus` | `day`, `changed[]` | 그 일차를 펼치고 바뀐 줄을 강조 |
| `sheet.collapse` | — | 챗봇 시트를 내린다 |

계약 파일: `agents/trip-guide/schemas/effects.json`

---

## 7. 도구 10 개 — 무엇이 어떤 호출·지시를 내는가

| 도구 | 사용자 말 | 부르는 GET | effects | ui |
| --- | --- | --- | --- | --- |
| `search_places` | "공유 나온 데 어디야" | `/places?q=` | — | `map.focus` |
| `list_title_places` | "도깨비 촬영지" | `/contents` → `/contents/{id}/places` | — | `map.focus` |
| `places_near` | "이 근처" | `/places?lat&lng&radiusMeters` | — | `map.focus` |
| `place_detail` | "거기 무슨 장면이야" | `/places/{id}` | — | `place.card` |
| `update_cart` | "그거 담아줘" | — | `cart.add`/`cart.remove` | `map.focus` |
| `draft_course` | "담은 걸로 동선" | — | — | `route.draw` |
| `plan_course` | "도깨비로 2박 3일" | `/contents/{id}/places?limit=20` | `plan.draft` | `course.open`+`sheet.collapse` |
| `revise_plan` | "2일차에서 빼줘" | — | `plan.revise` | `course.focus` |
| `move_stop` | "1일차로 옮겨줘" | — | `plan.move` | `course.focus` |
| `poi_nearby` | "근처 카페" | `/pois?lat&lng&radiusMeters&categoryGroup` | — | `map.focus` |

**도구가 거절되면 `effects`·`ui` 가 빈 배열로 나간다.** 실패했는데 화면이 바뀌거나
DB 가 바뀌면 사용자는 됐다고 믿는다.

---

## 8. 부탁 하나 — 점수 신호 둘이 죽어 있다

코스 엔진의 점수는 네 항의 합인데, **scene-api 경로에서는 둘이 0 이다.**

| 신호 | CSV 창구 | scene-api |
| --- | --- | --- |
| 대표성 (창구가 준 순서) | ✅ | ✅ |
| 작품 커버리지 | ✅ | ✅ |
| **언급량** | ✅ | ❌ `PlaceSummary` 에 필드 없음 |
| **선별등급 (S·A·B·C)** | ✅ | ❌ `PlaceSummary` 에 필드 없음 |

없는 값을 지어내지 않고 그 항을 죽이는 쪽을 골랐지만, 결과적으로 **추천 품질이
CSV 로 돌릴 때보다 낮다.** 데이터에는 있다(`seed/v6.csv` 의 `famous_rank`·`audience_acc` 계열).

`PlaceSummary` 에 실어 주실 수 있는지 검토 부탁드립니다. 필수는 아니고, 있으면 좋아진다.

---

## 9. 배포 — 팀 결정이 필요하다

`MODULE.bazel` 에서 `rules_python` 이 주석 처리돼 있어 `BUILD.bazel` 도 컨테이너
이미지도 만들 수 없다. 지금은 노트북에서 `python3 -m web.server` 로만 돈다.
초안은 [build-draft.md](build-draft.md).

---

## 10. 확인하는 법

에이전트를 띄우고 직접 불러 보면 위 응답 모양을 그대로 볼 수 있다.

```sh
cd agents/trip-guide
export DEEPSEEK_API_KEY=sk-...          # 챗봇 경로에만 필요
python3 -m web.server --port 8899

# 마법사 경로 — 키 없이도 된다
curl -X POST localhost:8899/plan -H 'Content-Type: application/json' \
  -d '{"titles":["도깨비"],"days":2,"pace":"normal"}'

# 챗봇 경로
curl -X POST localhost:8899/guide/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"t","latitude":37.58,"longitude":126.99,
       "messages":[{"role":"user","content":"도깨비 촬영지로 1박 2일 짜줘"}]}'
```

시험과 평가는 모델도 네트워크도 부르지 않는다.

```sh
python3 -m unittest discover -s tests -t .   # 99 개
python3 -m evals.plan_eval                   # 지표 5 종
```
