# 일정의 두 모양 — 모델이 읽는 글과 앱이 그리는 데이터

> MZ2AZ-318 · 2026-09-08 · 김태환 (`agents/trip-guide`)
>
> 발단은 정권호님의 「[일정 모양과 편집 사본](https://mz2az.atlassian.net/browse/MZ2AZ-318)」
> (2026-09-07) 이다. 백엔드 창구 `POST /guide/chat`·`POST /guide/plan` 의 계약을 쓰다가
> 에이전트 쪽에서 고쳐야 할 것 둘을 찾아 주셨고, 원인이 같아 해법도 하나로 묶였다.

---

## 0. 한 줄

**같은 일정을 두 벌로 내보낸다.** 모델에게는 읽을 글을, 앱에는 그릴 데이터를.
그리고 **편집 중에는 앱이 보낸 것이 정본이다.**

| | 전 | 후 |
| --- | --- | --- |
| 모델이 받는 것 | `plan_to_dict` — 한글 키, 좌표·점수 없음 | 그대로 |
| 앱이 받는 것 | **같은 것** | `plan_to_api` — camelCase, 좌표·`placeId` |
| 앱이 보낸 `context.plan` | 안 읽음 | **매 턴 이것으로 갈아 끼움** |

---

## 1. 왜 한 벌로는 안 되는가

`plan_to_dict` 는 모델이 읽고 「1일차는 서울중앙고에서 시작해 12시 17분에 마칩니다」라고
말하도록 만든 것이다. 좌표와 점수를 **일부러** 뺐다 — 좌표를 주면 모델이 거리를 다시
계산하려 들고 그 계산은 틀리며, 점수를 주면 그 숫자를 조금씩 바꿔 말한다.

문제는 그것을 앱에도 보낸 것이다.

| 앱이 해야 하는 일 | 막히던 이유 |
| --- | --- |
| 지도에 핀 찍기 | 좌표가 없다 |
| 「완료」→ `PUT /courses/{id}` | 코스 항목은 **장소 id** 로 저장한다. 이름뿐이라 「개뿔」이 둘이면 어느 것인지 모른다 |
| 순서 바꾸고 시간 다시 계산 | `"12:17"`·`"약 3200m"` 이 문자열이다 |
| Swift·Kotlin 클라이언트 생성 | 키가 한글이라 생성기가 필드를 못 만든다 |

여행사 직원이 손님에게 **읽어 주는** 일정표와, 같은 직원이 예약 시스템에 **입력하는**
데이터의 차이다. 같은 일정이지만 용도가 달라 두 벌이어야 한다.

**새로 계산할 것은 없었다.** `Plan` 은 정지점마다 `Place` 를 그대로 들고 있고 `Place` 에는
`place_id`·`lat`·`lng` 이 있다. 좌표 없는 후보는 `make_plan` 이 애초에 걸러 낸다. 든 값을
다른 키로 꺼내기만 하면 됐다.

### 모양

`schemas/effects.json` 의 `$defs/guidePlan` 이 정본이다. 요지만 —

```json
{
  "titles": ["도깨비"], "pace": "relaxed", "considered": 28,
  "travelBasis": "straight-line", "notes": [],
  "days": [{
    "day": 1, "endMinute": 737, "totalMeters": 11878,
    "stops": [{
      "order": 1, "placeId": 1187, "name": "서울중앙고", "address": "서울 종로구 …",
      "latitude": 37.5826, "longitude": 126.9910,
      "arriveMinute": 540, "dwellMinutes": 40,
      "titles": ["도깨비"], "sceneDescription": "김신이 …",
      "mealAfter": false, "metersToNext": 3200
    }],
    "dropped": [{"placeId": 2201, "name": "잠수교", "reason": "하루 정지점 상한 3 곳"}]
  }]
}
```

시각은 **0 시 기준 정수 분**이다. `"12:17"` 같은 표시 문자열은 앱이 만든다.

`placeId` 는 **정수 아니면 `null`** 이고, 없을 때 이름으로 대체하지 않는다. 이름을 id 로
알고 저장하면 동명 장소에 걸린다. `null` 이면 앱은 저장하지 않고 백엔드는 `cart.add` 를
거절한다 — 없는 것을 없다고 말하는 편이 낫다.

### 구간 소요 시간은 넣지 않았다 — 권호님 초안과 다른 점

권호님 초안에는 `minutesToNext` 가 있었다. 뺐다.

우리가 가진 값은 직선거리에 우회 계수(1.3)를 곱하고 3km 이상이면 대중교통 18km/h,
아니면 도보 4km/h 로 나눈 **어림**이다. 실제 소요 시간은 길찾기 창구만 답할 수 있고,
계약이 코스 편집 응답에 「구간 소요 시간 — **주지 않는다**」 고 이미 못 박아 두었다.
초안이 자기 계약과 어긋난 자리라 계약 쪽을 따랐다.

거리는 남기되 `travelBasis: "straight-line"` 으로 어림임을 밝힌다 (기존 계약 필드다 — 하이픈, 밑줄이 아니다).

**왕복은 어긋나지 않는다.** `plan_from_api` 가 좌표에서 `travel_minutes` 로 다시 재고,
같은 함수가 같은 좌표로 같은 값을 낸다. 어림값을 선에 실어 「사실인 척」 하지 않으면서
편집은 그대로 된다 (`tests/test_plan_shape.py`).

---

## 2. 편집 중에는 앱이 정본

### 무슨 일이 생기던가

```
1. 「도깨비로 1박 2일 짜 줘」   에이전트 3곳 · 앱 3곳
2. 사용자가 편집 화면에서 손으로 마포소금구이를 지운다
                               에이전트 3곳 · 앱 2곳   ← 갈라짐
   (편집 중엔 서버로 아무것도 안 나간다 — PUT /courses/{id} 는 「완료」만 부른다)
3. 「1일차에 잠수교 넣어 줘」   앱은 context.plan 에 2곳짜리를 실어 보낸다
4. 에이전트가 context 를 안 읽는다 → 자기 3곳에 넣어 4곳을 돌려준다
5. 사용자 눈: 「지웠는데 챗봇이 다시 넣었다」
```

원인은 정본이 둘인 것이다. 규칙은 정해 뒀는데 구현이 없었다.

### 고친 방법

`Session.adopt_plan(context)` 를 만들고 `/guide/chat` 이 `guide.ask()` **전에** 부른다.

```python
guide.session.adopt_plan(body.get("context"))  # 없으면 self.plan = None
```

**`context.plan` 이 없으면 일정이 없는 것으로 본다.** 세션에 남은 낡은 일정을 몰래 쓰지
않는다 — 「짜 둔 일정이 없다」고 거절하는 편이, 사용자가 보고 있는 것과 다른 일정을
말없이 고치는 것보다 낫다.

덤이 둘 붙는다. 에이전트 프로세스가 재시작돼도 앱이 일정을 들고 있으면 대화가 이어지고,
이 창구가 상태를 거의 안 들게 되어 나중에 인스턴스를 여러 개 띄워도 답이 갈리지 않는다.
세션이 계속 기억하는 것은 「보여 준 장소」와 장바구니뿐이다.

비용은 작다 — 일정은 최대 7일 × 7곳 = 49 항목, JSON 몇 KB. `POST /guide/chat` 계약이
이미 대화 이력 전체를 매 요청에 보내는 방식이라 같은 결이다.

---

## 3. 무엇을 바꿨나

| 파일 | 무엇 |
| --- | --- |
| `src/planner.py` | `plan_to_api` · `plan_from_api` 추가. `plan_to_dict` 는 그대로 |
| `src/session.py` | `Session.adopt_plan(context)` |
| `src/tools.py` | `effects` 에 실리는 일정 셋(`plan.draft`·`revise`·`move`)을 `plan_to_api` 로. **모델에게 가는 것은 그대로** |
| `web/server.py` | `/guide/chat` 이 `adopt_plan` 을 부른다. `/plan` 응답도 앱용 모양 |
| `schemas/effects.json` | `$defs/guidePlan` 정의 |
| `tests/test_plan_shape.py` | 시험 12 종 (아래) |

모델이 좌표를 보지 않는다는 원칙은 그대로다. 구조화된 모양은 도구 결과가 아니라
**응답에만** 실린다.

## 4. 시험으로 잡아 둔 것

| 무리 | 지키는 것 |
| --- | --- |
| 왕복 | `make_plan → plan_to_api → plan_from_api` 가 id·순서·도착 분을 보존. 거리·시간도 좌표에서 복원. 키가 전부 영문 |
| 앱이 정본 | 앱에서 두 곳을 지우고 하나만 다시 넣어 달라고 하면, 나머지 하나는 **되살아나지 않는다.** 모델이 보는 맥락에도 앱이 보낸 것이 실린다 |
| 사본 없음 | `context.plan` 이 없으면 세션에 일정이 있어도 `revise_plan` 이 거절한다 |
| `placeId` | CSV 창구면 `null` 이고 이름으로 대체하지 않는다. scene-api 창구면 문자열이 아니라 정수 |

```
python3 -m unittest discover -s tests -t .    # agents/trip-guide 에서
```

`rules_python` 이 꺼져 있어 아직 `just check` 가 이 시험을 못 집는다
([build-draft.md](build-draft.md)). 켜지면 붙는다.

## 5. 남은 것

- 권호님이 계약(`contracts/openapi/scene-api-v1.yaml`)에 `GuidePlan`·`GuideContext.plan` 을
  확정하면 필드 이름을 맞춘다. `minutesToNext` 건은 위 §1 대로 계약 쪽을 따르자고 제안해 둔다.
- 앱이 `context.plan` 을 매 요청에 싣고 `GuidePlan` 으로 초안을 그린다 (승길님, MZ2AZ-234·242).
