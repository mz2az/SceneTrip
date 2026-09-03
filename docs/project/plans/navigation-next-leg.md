# 여행 중 길찾기 백엔드 이관 (MZ2AZ-296)

- **티켓**: [MZ2AZ-296](https://mz2az.atlassian.net/browse/MZ2AZ-296) 길찾기를 앱에서 백엔드로 옮긴다 — 스토리 [MZ2AZ-199](https://mz2az.atlassian.net/browse/MZ2AZ-199) 의 하위, 에픽 [MZ2AZ-107](https://mz2az.atlassian.net/browse/MZ2AZ-107) "코스". [MZ2AZ-233](https://mz2az.atlassian.net/browse/MZ2AZ-233)(여행 전/중 구분·엔진 결정) 위에서 서버 구현을 한다
- **작성일**: 2026-09-03
- **상태**: 서버 구현 완료 — 이 문서는 코드보다 먼저가 아니라 **코드와 함께** 썼다. 이유는 §0
- **선행**: [course-api.md §7](./course-api.md#7-여행-전과-여행-중을-가른다-mz2az-233) 이 여행 전/중 구분과 엔진(카카오) 결정을 적어 뒀다. 이 문서는 그 뒤를 잇는다
- **ADR**: [0009 길찾기는 서버가 부른다](../../architecture/adr/0009-navigation-is-called-by-the-server.md) · [0010 서버는 안내문을 번역하지 않는다](../../architecture/adr/0010-server-does-not-translate-guidance.md)
- **근거**: 실측 두 벌 — 카카오 거리별 응답 13건(2026-09-02), 문-에서-문 검증 5건(2026-09-02), e2e 3건(2026-09-03)

---

## 0. 계획을 코드와 함께 쓴 이유

CLAUDE.md §3 은 계획을 먼저 쓰라고 한다. 이번에는 **이해가 먼저**였다 — 프로토타입 코드의 주석이 실측과
어긋나는 것이 하루에 두 번 드러났고(§2), 그 상태에서 계획을 쓰면 틀린 전제를 문서로 굳혔을 것이다.
그래서 단계마다 실측으로 전제를 확인하며 코드를 쓰고, 다 본 뒤에 이 문서를 썼다. 다음부터는 실측을
계획 앞에 두면 순서가 맞는다.

## 1. 무엇을 옮겼는가

앱(`apps/scenetrip-ios/src/RouteTab/KakaoTransit.swift`)이 카카오 길찾기 API 를 **직접** 부르고 있었다.
키(`Secrets.kakaoRestKey`)가 앱 바이너리에 박혀 있었고, 계약의 `POST /navigation/next-leg` 는 서버가
`501` 을 돌려주고 있었다.

이 작업으로 —

```
앱 ──▶ 카카오                         (전)
앱 ──▶ 우리 서버 ──▶ 카카오            (후)
```

서버가 부르고, 키는 서버에만 있고, 앱은 우리 계약(`NextLeg`)만 본다. 왜 서버냐는 ADR 0009.

**범위 밖** — 앱 쪽 갈아 끼우기(`KakaoTransit.swift` 삭제, 생성된 클라이언트로 교체)는 iOS 작업이라 따로.
챗봇이 찾아 준 가게로 갈아타기(코스 밖 좌표)는 계약에 없다 — §6.

## 2. 실측이 뒤집은 것

프로토타입 주석을 근거로 삼지 않고 **직접 재서** 넷을 고쳤다. 전부 카카오 REST 를 실제로 불러 확인했다.

### 2-1. 「비공식 엔드포인트」가 아니다

주석은 `dapi.kakao.com/v2/routing/*` 이 문서에 없다고 했다. 2024-01 데브톡에서 카카오가 「길찾기는
URL Scheme 으로 제공」이라 답한 때의 사실이고, 지금은 [공식 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api)에
대중교통·도보·자전거 셋이 있다. 파라미터 이름(`start_x/y`, `end_x/y`, `s_name`, `e_name`, `input_coord`,
`output_coord`)이 우리 코드와 정확히 같다. 남은 비공식은 `lang` 하나다(§4).

**이관 근거에서 「막힐 수 있으니 서버로」를 뺐다.** 남는 근거는 유료·쿼터(하루 1,000/1,000)와 호출
허용 IP 설정(서버 고정 IP 에서만 가능) — ADR 0009.

### 2-2. 「150 m 에서 NO_RESULTS」가 아니다 — 컷의 이유가 바뀐다

북촌 출발, 남쪽으로 150·300·500·700·900·1200·2000·3500 m. **어느 거리에서도 `NO_RESULTS` 가 안
났다.** 150 m 에도 버스 답이 왔고(821초), 같은 곳 도보는 758초 — 걷는 게 빠른데 버스를 타라고 한다.

900 m 컷은 유효하다. 다만 이유가 「실패 회피」에서 **「걷는 게 빠른데 버스 타라는 답 회피」**로 바뀐다.
900 이라는 숫자는 실측 없이 정한 어림값이라 설정(`walk-only-under-meters`)으로 뒀다.

### 2-3. 카카오의 합계는 문에서 문까지다 — 프로토타입이 두 번 셌다

대중교통 응답의 `totalDistance`·`totalTime` 에서 step 합을 빼면 600~800 m·600~750초가 남는다.
승차점·하차점을 도보 API 로 실제로 재니 그 차이와 **1 m 단위로 맞았다**(5건, 100~106%).

```
구간          합계−step합   도보 실측(→승차 + 하차→)
150 m           800          257 + 581 = 838   105%
광화문→돈화문    753          287 + 467 = 754   100%
```

즉 카카오는 양 끝 도보를 **step 으로는 안 주지만 합계에는 넣는다.** 프로토타입(`KakaoTransit.swift:152`)은
기운 도보 시간을 합계에 또 더했다 — 18분짜리가 30분으로 나갔다. 서버는 더하지 않는다. 걷는 거리도
`totalDistance − Σ차량 step` 뺄셈으로 구한다 — 호출 없이, 환승 도보까지 포함해서.

### 2-4. 양 끝 도보 누락은 87%, 그리고 카카오가 의도한 것이다

13건 중 양 끝 도보가 step 으로 온 건 0건. 카카오 데브톡([150809](https://devtalk.kakao.com/t/api/150809))에서
직원이 「대중교통 수단의 이용 경로 안내를 목적으로 하기 때문에 어떤 수단을 어디서 타고 어디서 내리는지를
중심으로 합니다」라 확인했다. 도보 API 를 따로 쓰라는 안내다. 이어붙이기(`stitch`)는 카카오 권장 방식이다.

환승 사이 도보는 걸을 것이 있을 때만 `WALKING` step 으로 온다(2000 m 케이스 157 m). 같은 정류장
환승(0 m)은 생략된다. 그래서 **기울지 말지는 첫/끝 step 의 종류로 판단**한다 — 프로토타입은 「`WALKING` 이
하나라도 있으면 안 기움」이라 BUS→WALK→BUS 에서 양 끝을 놓쳤다. e2e 에서 그 케이스가 실제로 나왔다.

### 2-5. 그 밖

- `fare` 는 `value` 하나거나 `min`/`max` 둘. 41개 후보 중 2개가 범위였고 둘 다 직행 1101 이 섞인 것.
  둘이 같이 오지 않는다. 범위면 중간값.
- step 의 시간 필드는 `time` 이다. 프로토타입이 읽던 `duration` 은 없다 — 늘 nil 이었다.
- 계단 문구는 13건 어디에도 없었다. 전부 버스 경로라 지하철이면 다를 수 있다.
- 저녁 7시 47분 응답에 심야버스(N15·N31)가 후보로 들어 있었다 — 카카오가 호출 시각으로 노선을 거르지
  않는다는 신호. 새벽 실측은 안 했다(§8).

## 3. 규칙 — `NextLegPlanner`

```
plan(here, target, lang)
│
├─ d = 직선거리
├─ d < 900 ──────────────────────────▶ 도보 1번
└─ 대중교통 1번 → status
     ├─ OK ──────────────────────────▶ routes[0]
     │     숫자: totalMinutes = ⌈totalTime/60⌉   walkMeters = totalDistance − Σ차량   fare = value ?? (min+max)/2
     │     legs: step 마다 하나 + 첫 step 이 차량이면 출발지→승차점 도보, 끝 step 이 차량이면 하차점→목적지 도보
     │           (도보 2번은 좌표·안내문만 보탠다 — 숫자는 안 건드린다. 실패하면 그 끝만 빠진다)
     ├─ EQUAL_POINTS ────────────────▶ 200, legs []  (이미 도착)
     ├─ NO_RESULTS · *NODES_NULL ───▶ 도보 1번, 상한 없음. 그것도 없으면 422
     └─ 그 외 ──────────────────────▶ 500 (우리가 잘못 보낸 것)
```

카카오 호출 1~3번. 캐시는 없다 — 카카오가 길찾기 응답 저장을 「실시간 호출만 가능」이라 답했다
([151435](https://devtalk.kakao.com/t/api/151435), 2026-09-01).

**폴백 상한을 두지 않는다.** 처음엔 3 km 를 넣었다가 뺐다 — 「도보 2시간」은 틀린 정보가 아니고 사용자가
택시를 판단할 재료다. 물 건너 섬이면 도보 API 도 `NO_RESULTS` 라 어차피 422 다.

**900 m 미만에서 도보가 실패하면 422 다.** 프로토타입은 직선거리 ÷ 4 km/h 로 분을 지어냈다. 좌표도 없어
선도 못 그렸으니 「못 찾았다」가 정직하다.

## 4. 다국어 — ADR 0010

앱이 ko·en·ja·zh-Hant 를 지원한다. 카카오의 비공식 `lang` 파라미터를 4개 언어로 찔러 봤다 —

| `lang` | 도보 안내문 |
| --- | --- |
| `ko` | 반석빌라까지 110m 이동(북촌로11길) |
| `en` | **Go 110m along Bukchon-ro 11-gil to Banseok Ville** — 고유명사까지 로마자 |
| `ja` | Go 110m along Bukchon-ro 11-gil to **반석빌라** — 반쪽 |
| `zh-Hant` | 한국어로 떨어짐 |

`vehicles[].type`(간선/마을/직행)은 어느 언어에서도 한국어다.

**계약을 문장에서 재료로 바꿨다.** 처음엔 서버가 `title`「마을 종로02」·`detail`「도보 3분 · 210 m」를
조립했는데, 그러면 서버가 그 문장의 언어를 떠안는다 — 이 서버가 표시용 문장을 만든 적이 없고, i18n 은
전부 DB 행이며, 문장은 앱이 만들어 왔다. `RouteLeg` 는 `guidance`(카카오 원문)·`meters`·`seconds`·
`stopCount`·`vehicleType`·`vehicleName` 을 들고, `NextLeg.guidanceLang`(ko|en)이 원문의 언어를 말한다.
서버는 ko 아니면 en 으로 카카오에 보내고 번역하지 않는다. 앱이 자기 로케일과 견줘 그대로 보여 줄지
기기 안에서 번역할지 정한다.

파이썬 프로토타입(`server.py` 의 `kakao_lang()`)이 이미 「ko 아니면 en」이었다. 스위프트로 옮겨지며 도보 쪽
`lang` 이 빠지고 로케일 연결도 안 됐던 것을 되살린 것이다.

## 5. 구조

```
web/NavigationController          통제 5 — 가입자(401) · 내 코스(404) · 여행 중(409) · 항목(404) → 전부 우리 DB
navigation/NextLegPlanner         규칙 (§3). 클라이언트를 생성자로 받는다 — 테스트가 가짜를 꽂는 자리
navigation/Coordinate             위경도 한 쌍. 카카오는 x=경도, GeoJSON 은 [경도,위도], 계약은 latitude·longitude — 뒤집힐 자리를 좁힌다
navigation/kakao/KakaoRoutingClient   전송만. status 를 보지 않는다. 전송 실패는 전부 503, 카카오 400 만 그대로(500)
navigation/kakao/Kakao*Response   카카오 응답 모양. 숫자는 전부 래퍼 — 0 이 「모름」을 가리면 그 후보가 1위가 된다
course/CourseStore.findItemLocation   항목 하나의 좌표. COALESCE(place.geom, custom_pin.geom)
```

- **첫 외부 HTTP.** `RestClient` 를 직접 만든다 — Boot 4 의 `RestClient.Builder` 자동 구성 모듈이 잠금
  파일에 없어서. 새 의존성 없음. 싱글턴 생성자에서 한 번, 연결 3초·응답 12초. OTel 에이전트가 JDK
  `HttpClient` 를 계측해 스팬·메트릭을 내고, 경로 태그가 없어 대중교통/도보를 가르지 못하므로 액추에이터의
  `ObservationRegistry` 를 빌더에 걸어 Spring 관측(`http.client.requests`, `uri` 태그)을 함께 켰다.
- **첫 Secret.** 카카오 키는 로컬이라도 진짜라 ConfigMap 에 둘 수 없다. 매니페스트 파일이 없고
  `just secrets-apply` 가 `.env` 에서 만든다 — `deploy.sh` 가 폴더째 apply 하므로 파일에 두면 빈 값으로
  덮어쓰기 때문. `secretKeyRef` + `optional` 이라 키 없는 개발자도 서버는 뜨고 길찾기만 503 이다.
- **`course` 와 `navigation` 은 서로 모른다.** `ItemLocation` 과 `Coordinate` 가 같은 모양이지만 일부러
  따로 두고 컨트롤러가 잇는다. 세 번째 사용처가 생기면 공용으로 뽑는다.

## 6. 계약이 바뀐 것

| 전 | 후 |
| --- | --- |
| `NextLeg { distanceMeters, durationMinutes, geometry }` — 선 하나 | `{ totalMinutes, transfers, walkMeters?, fareWon?, guidanceLang, legs[] }` |
| `RouteLeg` 없음 | `{ mode, guidance, meters?, seconds?, stopCount?, vehicleType?, vehicleName?, path, hasStairs }` |
| `501` | `422`(경로 없음 — `ROUTE_NOT_FOUND` / `NO_TRANSIT_NEARBY`) · `503`(`ROUTING_UNAVAILABLE`) |
| 「T맵 응답을 확인하며 바뀔 수 있다」 | 카카오 |

`walkMeters`·`fareWon` 은 nullable — 「모름」이 0 이 되면 「안 걸어도 되는 경로」「공짜 경로」로 읽혀 가장
좋아 보인다. 프로토타입이 같은 실수를 네 번 반복하고 얻은 규칙이다. `traceId` 는 `500`·`503` 에 실린다.

**갈아타기는 계약에 없다.** 앱에는 챗봇이 찾아 준 가게로 「여기로 길찾기」가 있는데 그 가게는 코스 항목이
아니라 `itemId` 가 없다. 좌표를 직접 받으면 「활성 코스의 항목만」이라는 호출 통제가 느슨해진다. 옮기는
것이 목적이지 기능을 늘리는 것이 아니라 뺐다 — 별도 티켓.

## 7. 검증

| 레인 | 무엇 | 개수 |
| --- | --- | --- |
| 단위 | 규칙(§3)·통제 순서·전송/예외 번역. 카카오 대신 JDK `HttpServer` 가짜 | 37 |
| 통합 | `findItemLocation` SQL — 촬영지·핀·코스 번호 어긋남. 실제 PostgreSQL | 3 |
| 스모크 | `just navigation-smoke` — 배포된 것이 살아 있나. 카카오 실제 호출, 사람이 배포 직후 | — |
| e2e | 없음 — 저장소 전체가 비어 있고 첫 e2e 는 ADR 0005 보정과 함께 팀이 정한다 | 0 |

실측 JSON 은 저장소에 넣지 않았다 — 정류장 이름·안내문이 카카오 데이터라 복제·저장에 해당할 수 있다
(운영정책 제5조 30호). 픽스처는 모양만 실측 그대로 베끼고 내용은 지어냈다.

e2e(2026-09-03, 로컬 클러스터): 북촌→홍익대부속중고 2 km — 31분·환승 1·도보 1,094 m·1,200원·legs 12.
응답이 BUS→WALK→BUS 였고 양 끝이 기워졌다(§2-4). 뺄셈 `walkMeters` 1,094 vs 실제 도보 합 1,098.

## 8. 남은 것

- **앱 갈아 끼우기** — `KakaoTransit.swift` 삭제, `RouteNavResult` 를 생성된 `NextLeg` 에서 채움, `detail` 을
  `meters`·`seconds` 로 앱이 조립, `guidanceLang` 과 로케일 비교. `BUILD.bazel` 의 `kakao_rest_key` genrule 제거.
- **남용 방지(MZ2AZ-205)** — 「한 계정이 하루 몇 번」은 아직 없다. 지금 통제는 가입·활성 코스·한 구간씩.
- **새벽 실측** — 카카오가 시간을 보는지. §2-5 의 심야버스 단서로는 안 보는 쪽. 본다면 `NO_RESULTS` 에
  「심야」가 더해지고, 안 본다면 안 다니는 버스를 안내하는 문제가 남는다.
- **900 의 근거** — 사용자가 어느 구간에서 길찾기를 누르는지 쌓이면 다듬는다. 배차 간격과 지형에 따라 답이
  다르다.
- **503 세분** — 쿼터 초과(`ROUTING_QUOTA_EXCEEDED`)를 따로 낼지. 앱 반응이 갈리는(재시도 vs 오늘 안 됨)
  실제 분기라 코드를 만들 만하지만, 실제로 나는 걸 보고 쪼갠다.
- **e2e 레인** — 스모크가 밑그림. 카카오 같은 외부 API 를 e2e 에서 진짜로 부를지(쿼터·불안정)가 팀 결정.
- **로그인** — 붙으면 스모크의 `registered_at` SQL 이 API 호출로 바뀐다.
