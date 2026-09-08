# 가이드 챗봇 백엔드 창구 — scene-api 가 trip-guide 에이전트를 부른다 (MZ2AZ-319)

- **티켓**: [MZ2AZ-319](https://mz2az.atlassian.net/browse/MZ2AZ-319) 에이전트와 백엔드 연결 전체. [MZ2AZ-285](https://mz2az.atlassian.net/browse/MZ2AZ-285)(백엔드 이식 임시 티켓)의 실체
- **작성일**: 2026-09-08
- **상태**: 계약 확정(PR #79·#80 병합) — **구현 전.** 이 문서대로 짜고, 어긋나면 문서를 고친다
- **ADR**: [0013 가이드 챗봇은 파이썬 에이전트가 맡고 scene-api 는 프록시한다](../../architecture/adr/0013-guide-chat-is-served-by-the-python-agent.md)
- **계약**: `contracts/openapi/scene-api-v1.yaml` 1.2.0 의 `POST /guide/chat`·`POST /guide/plan`, `contracts/schemas/guide/` (PR #79 #80 #81)
- **같이 가는 티켓**: [MZ2AZ-320](https://mz2az.atlassian.net/browse/MZ2AZ-320) 에이전트를 계약에 맞춤(태환) · [MZ2AZ-321](https://mz2az.atlassian.net/browse/MZ2AZ-321) 앱을 창구에 붙임(승길)
- **에이전트 쪽 명세**: [`agents/trip-guide/docs/design/backend-handoff.md`](../../../agents/trip-guide/docs/design/backend-handoff.md) — 에이전트가 무엇을 받고 주는지

---

## 0. 한 줄 요약

**컨트롤러 둘, 클라이언트 하나, 처리기 하나.** 새 DB 표도 새 계산도 없다. scene-api 는 앱의 요청을
에이전트에 넘기고, 응답의 `effects` 중 `cart.*` 만 저장하고, 나머지를 그대로 앱에 돌려준다.

```
앱 ──POST /guide/chat──▶ GuideController ──▶ GuideAgentClient ──POST /guide/chat──▶ trip-guide(:8899)
                              │                                                          │
                              │◀── GuideChatReply ◀─────────────────────────────────────┘
                              ▼
                     GuideEffectApplier ── cart.* ──▶ CartStore ──▶ DB
                              │            plan.* · ui ── 손대지 않음
                              ▼
                        앱에 그대로 반환

앱 ──POST /guide/plan──▶ GuideController ──▶ GuideAgentClient ──POST /plan──▶ trip-guide
                              │◀── GuidePlanReply (저장 없음, 그대로 반환)
```

## 1. 왜 이 모양인가 — 이미 정해진 것

ADR 0013 과 계약이 정했고 여기서는 다시 논하지 않는다.

| 규칙 | 출처 |
| --- | --- |
| 에이전트는 신원(`X-Device-Id`)을 모른다. 저장은 scene-api 가 대신한다 | ADR 0013 |
| `cart.*` 는 즉시 저장. `placeId` null 이면 그 하나만 건너뛴다 | 계약 `GuideEffect` |
| `plan.*` 은 저장하지 않는다. 앱에 통과. 저장은 「완료」의 `PUT /courses/{id}` 뿐 | 계약 `GuideEffect` |
| `ui` 는 검증 없이 통과 | 계약 `GuideUiDirective` |
| 편집 중인 코스는 앱이 정본 — `context.plan` 을 그대로 넘기고 DB 에서 읽어 넣지 않는다 | 계약 `GuideContext.plan` |
| 에이전트가 안 뜨면 `503 GUIDE_UNAVAILABLE`. 규칙 기반으로 떨어지지 않는다 | 계약, `docs/api/errors.md` |
| 프록시는 **옮겨 담지 않는다** — 필드 이름·모양은 계약 그대로 통과 | ADR 0013 |

마지막 줄이 구현을 단순하게 만든다. 에이전트가 계약과 같은 JSON 을 주므로, scene-api 는 그것을
**생성된 모델 클래스(`GuideChatReply`·`GuidePlanReply`)로 읽어 그대로 돌려준다.** 별도 DTO 가 없다.

## 2. 무엇을 만드는가 — 파일 넷

전부 `services/scene-api/src/main/java/com/mz2az/scenetrip/sceneapi/` 아래. 패키지 `guide/` 를 새로 둔다
(`navigation/`·`poi/` 와 같은 층).

| 파일 | 하는 일 | 본보기 |
| --- | --- | --- |
| `web/GuideController.java` | 생성된 `GuideApi` 구현. 통제·조립만. `try/catch` 없음 | `web/NavigationController.java` |
| `guide/GuideAgentClient.java` | 에이전트를 부르는 **유일한 곳.** 전송과 예외 번역만 | `navigation/kakao/KakaoRoutingClient.java` |
| `guide/GuideEffectApplier.java` | `effects[]` 를 돌며 `cart.*` 를 `CartStore` 에 적용 | 새것 — 아래 §4 |
| `application.yaml` `scenetrip.guide.*` | 에이전트 주소·타임아웃 | `scenetrip.navigation.kakao.*` |

`BUILD.bazel` 은 `glob(["src/main/java/**/*.java"])` 라 소스 추가에 손댈 것이 없다. 의존성도 추가하지
않는다 — `RestClient`(`spring-web`)와 Jackson 은 이미 있다.

### 2-1. `GuideController`

```java
@RestController
class GuideController implements GuideApi {
  private final GuideAgentClient agent;
  private final GuideEffectApplier effects;
  private final UserStore users;

  @Override
  public ResponseEntity<GuideChatReply> chatWithGuide(
      UUID xDeviceId, GuideChatRequest request, Lang acceptLanguage) {
    UUID user = users.resolve(xDeviceId);          // 저장 주체. 에이전트에는 안 넘긴다
    GuideChatReply reply = agent.chat(request, acceptLanguage);
    effects.apply(user, reply.getEffects());       // cart.* 만. 나머지는 손대지 않는다
    return ResponseEntity.ok(reply);               // 옮겨 담지 않는다
  }

  @Override
  public ResponseEntity<GuidePlanReply> planWithGuide(
      GuidePlanRequest request, Lang acceptLanguage) {
    return ResponseEntity.ok(agent.plan(request, acceptLanguage));   // 저장 없음
  }
}
```

가입 여부는 보지 않는다 — `CartController` 도 안 본다. 장바구니는 비회원도 쓴다.

### 2-2. `GuideAgentClient`

카카오 클라이언트와 같은 구조다. `RestClient` 를 직접 만들고(`spring-boot-restclient` 자동 구성을 안
받는 이유는 그쪽 머리말), 연결 3초·응답 `timeout-seconds`, `ObservationRegistry` 로 Spring 관측을 켠다.

```java
public GuideChatReply chat(GuideChatRequest request, Lang lang)   // POST {base-url}/guide/chat
public GuidePlanReply plan(GuidePlanRequest request, Lang lang)   // POST {base-url}/plan
```

- 요청 몸체는 **생성된 모델을 그대로 JSON 으로** 보낸다. 앱이 보낸 `context.plan` 이 그 안에 실려
  에이전트까지 간다 — DB 에서 읽어 넣지 않는다는 규칙이 코드 없이 지켜진다.
- `Accept-Language` 헤더를 그대로 넘긴다. 지금 에이전트는 이 헤더를 안 읽지만(사용자 발화의 언어로
  답한다) 계약이 받으니 넘겨 둔다.
- 응답은 `GuideChatReply`·`GuidePlanReply` 로 읽는다. **에이전트가 계약과 다른 필드를 주면 여기서
  깨진다** — 그것이 의도다. 모르는 필드는 무시하되 필수 필드가 비면 503 이다.

**예외 번역** — 카카오와 같은 원칙, 갈래만 다르다.

| 에이전트가 | scene-api 가 | 왜 |
| --- | --- | --- |
| 200 | 그대로 | |
| 400 `{code, message}` | **400 그대로** (`INVALID_PARAMETER`, 에이전트의 message) | 앱이 잘못 보낸 것이다. 작품 못 찾음·일수 범위 밖·`context.plan` 모양 오류. 재시도해도 같다 |
| 503 `{code, message}` | 503 `GUIDE_UNAVAILABLE` | 에이전트가 모델을 못 불렀다 |
| 그 외 4xx·5xx | 503 `GUIDE_UNAVAILABLE` (로그 warn) | 우리가 모르는 상태 |
| 연결 실패·시간 초과 | 503 `GUIDE_UNAVAILABLE` (로그 warn) | 에이전트 프로세스가 없다 |
| 응답을 모델로 못 읽음 | 503 `GUIDE_UNAVAILABLE` (로그 warn) | 에이전트가 계약을 어겼다 — 태환님께 |

카카오와 다른 점 하나 — 카카오의 400 은 *우리* 결함이라 500 으로 보냈지만, 에이전트의 400 은 *앱*의
잘못이라 400 으로 돌려준다. 에이전트가 `/guide/chat` 에서 이미 `{code:"INVALID_PARAMETER"}` 를 주므로
그대로 통과시키면 된다(`/plan` 도 그 모양으로 맞추는 것이 MZ2AZ-320 §3 에 있다).

**타임아웃은 세 층이고, 안쪽이 바깥보다 먼저 포기한다.**

```
에이전트 턴 예산 30초  <  scene-api 벽 40초  <  앱 50초
```

| 층 | 값 | 성격 | 누가 |
| --- | --- | --- | --- |
| 에이전트 → DeepSeek | 호출 하나 15초 · **턴 전체 30초** | 턴 시작 때 마감을 정하고 호출마다 남은 시간을 배분. 재시도 포함. 넘기면 스스로 503 | MZ2AZ-320 §7 |
| scene-api → 에이전트 | 연결 3초 · 응답 **40초** | **벽.** 안에서 뭘 하는지 모르고 기다리기만 | 이 문서 |
| 앱 → scene-api | 50초 | 벽. 자동 재시도 없음 | MZ2AZ-321 §6 |

바깥이 먼저 끊으면 안쪽은 모른 채 끝까지 돈다 — 에이전트는 토큰을 쓰고 이력에 답을 남기고, 서버는
뒤늦게 `cart.add` 를 저장하는데 앱은 실패로 보인다. 안쪽이 먼저 포기하면 각 층이 제대로 된 오류를
위로 올리고, 바깥 값은 안전망으로만 남는다. 정상 운영에서 scene-api 가 40초를 다 기다리는 일은
에이전트가 제 예산을 못 지켰을 때뿐이다.

**재시도는 없다.** `/guide/chat` 은 멱등이 아니다 — 토큰을 쓰고, 세션 이력이 쌓이고, `cart.add` 가
두 번 올 수 있다. 클라이언트에 재시도 코드를 넣지 않고, 이유를 머리말에 적는다. 앱도 자동 재시도를
하지 않는다.

한 턴 안에서 무슨 일이 일어나는지는 scene-api 가 알 필요가 없다 — 「도깨비로 1박 2일」이면 모델 호출
2번과 scene-api GET 2번, 도구를 4번 도는 턴이면 모델 호출 5번. 전부 합쳐 40초 안에 오느냐만 본다.

### 2-3. 설정 — `scenetrip.guide.*`

```yaml
scenetrip:
  guide:
    # 에이전트(agents/trip-guide web/server.py). 로컬은 노트북의 8899, 클러스터는 Service 이름 —
    # rules_python 이 켜져 에이전트 컨테이너가 생기면 그때 정한다.
    agent-base-url: http://localhost:8899
    # 실측 3~5 초(DeepSeek). 에이전트 자체 상한 60 초보다 짧게 — 앱이 먼저 포기하고 「잠시 뒤 다시」를 보인다.
    timeout-seconds: 30
```

환경마다 다른 값은 `SCENETRIP_GUIDE_AGENT_BASE_URL` 로 덮어쓴다(기존 `SCENETRIP_AUTH_*` 와 같은 방식).
키는 없다 — 에이전트가 모델 키를 들고 있고 scene-api 는 모른다.

주소가 비어 있어도 기동은 된다. 카카오처럼 부를 때 503 을 낸다 — 챗봇 없이도 나머지 API 는 돌아야 한다.

### 2-4. 가상 스레드

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

이 창구가 요청당 3~5 초를 기다리는 **첫 경로**다. 톰캣 스레드 200 개가 전부 에이전트를 기다리면 CPU 가
놀면서도 `/places` 같은 0.01 초 요청이 줄을 선다. 가상 스레드는 기다리는 동안 캐리어를 놓아 주므로 병목이
톰캣 스레드에서 사라진다. 자바 21 · Boot 4.1 이라 설정 한 줄이다.

병목은 사라지는 것이 아니라 **DB 커넥션 풀(HikariCP 기본 10)로 옮겨 간다.** 이 경로는 `cart.*` 가 올 때만
DB 를 잠깐 쓰므로 괜찮지만, 알고 켜는 것이다. 컨테이너 런타임은 `Dockerfile` 의 `eclipse-temurin:21-jre`
라 동작한다.

**진짜 이유는 재진입이다.** `plan_course` 는 에이전트가 작품마다 scene-api 에 GET 을 두 번씩 보낸다 —
scene-api 가 에이전트를 기다리는 동안 에이전트는 scene-api 를 부른다. 플랫폼 스레드였다면 톰캣 스레드
200 개가 전부 에이전트를 기다리고 있을 때 에이전트의 GET 을 받아 줄 스레드가 없어 **양쪽이 서로를
기다린다.** 부하가 걸리면 챗봇이 느려지는 정도가 아니라 `/places` 까지 같이 멈춘다. 가상 스레드는
기다리는 요청이 캐리어를 놓아 주므로 GET 이 받아진다.

## 3. `effects` 처리 — 여기가 유일한 로직

`GuideEffectApplier.apply(UUID user, List<GuideEffect> effects)`:

```
for e in effects:
  switch e.op:
    "cart.add"    → placeId null? 건너뛰고 warn
                    !placeExists? 건너뛰고 warn        (에이전트가 준 id 가 우리 DB 에 없다)
                    add(user, placeId, null, ko) 가 empty(이미 담김)? 건너뛰고 info
    "cart.remove" → placeId null? 건너뛰고 warn
                    remove(user, placeId) 가 false(안 담겨 있음)? 건너뛰고 info
    "plan.*"      → 아무것도 안 함
    그 외          → 아무것도 안 함 (모르는 op. 앱도 무시한다)
```

**건너뛰는 것과 실패하는 것을 가른다.**

- `placeId` null · 없는 장소 · 이미 담김 · 안 담겨 있음 — **건너뛴다.** 에이전트나 데이터의 문제이고
  답변(`reply`)은 이미 사용자에게 가야 한다. 계약 `GuideEffect` 가 「그 effect 하나를 건너뛰고 나머지를
  수행한다」고 못 박았다. 다만 건너뛴 사실을 사용자는 모르므로 로그에는 남긴다.
- DB 가 죽어 `CartStore` 가 예외를 던짐 — **500 으로 나간다.** 서버 결함이다. 「담았어요」라고 답하고
  저장이 안 된 채 200 을 주면 사용자는 됐다고 믿는다.

`sourceContentId` 는 null 로 넣는다. 챗봇은 「어느 작품 때문에 담았는지」를 아직 안 준다 — 필요해지면
`GuideEffect` 에 필드를 더한다(계약 먼저).

`CartController.addCartItem` 과 규칙이 다르다 — 거기서는 없는 장소가 404, 중복이 409 다. 여기서 같은
상황이 「건너뜀」인 이유는 위와 같다. 답변은 성공했고 부수효과 하나만 못 한 것이다.

## 4. 시험

### 4-1. 단위 — `just test` 가 집는다

| 시험 | 방식 | 확인하는 것 |
| --- | --- | --- |
| `guide/GuideAgentClientTest` | JDK `HttpServer` 가짜 에이전트(`KakaoRoutingClientTest` 와 같다) | 200 → 모델로 읽힌다 · 400 → 400 그대로 · 503 → 503 · **연결 거부** → 즉시 503 · **연결 지연**(listen 안 함) → 3초에 503 · **응답 지연**(헤더도 안 보냄) → 타임아웃에 503 · **헤더 뒤 멈춤**(200 헤더만 보내고 몸체 안 보냄) → 타임아웃에 503 · 깨진 JSON → 503 · 요청 몸체에 `context.plan` 이 그대로 실린다 |
| `guide/GuideEffectApplierTest` | `CartStore` Mockito | `cart.add` → `add` 호출 · `placeId` null → 호출 없음 · 없는 장소 → 호출 없음 · 중복 → 예외 없음 · `plan.draft` → `CartStore` 를 전혀 안 건드림 · 모르는 `op` → 무시 · `CartStore` 예외 → 그대로 올라감 |
| `web/GuideControllerTest` | `@WebMvcTest` + Mockito (`NavigationControllerTest` 와 같다) | 응답 JSON 이 에이전트 것과 같다(`effects`·`ui` 포함) · `X-Device-Id` 없으면 400 · 클라이언트가 503 을 던지면 503 `GUIDE_UNAVAILABLE` · `/guide/plan` 은 `X-Device-Id` 없이 200 · `/guide/plan` 은 `GuideEffectApplier` 를 부르지 않는다 |

### 4-2. 실제 요청 — 게이트가 초록이어도 이것 없이는 끝이 아니다

에이전트를 노트북에서 띄우고 scene-api 를 그쪽으로 돌린다.

```sh
cd agents/trip-guide && export DEEPSEEK_API_KEY=… && python3 -m web.server --port 8899
just stack-up …                            # scene-api(:8081) + DB
```

| # | 요청 | 확인 |
| --- | --- | --- |
| 1 | `/guide/plan` 「도깨비, 2일」 | 200, `plan.days` 가 2, `effects[0].op == plan.draft`. **DB `course` 표에 행이 안 생긴다** |
| 2 | `/guide/chat` 「도깨비 촬영지 알려줘」 | 200, `places` 에 좌표, `ui[0].op == map.focus`, `effects` 빈 배열 |
| 3 | `/guide/chat` 「1번 담아 줘」 | 200, `effects[0].op == cart.add`. **`cart_item` 에 행이 생긴다.** 같은 말을 한 번 더 → 200, 행은 그대로(중복 건너뜀) |
| 4 | `/guide/chat` 「도깨비로 1박 2일 짜 줘」 → `context.plan` 없이 「2일차에서 X 빼 줘」 | 두 번째가 「짜 둔 일정이 없다」류의 답. 첫 응답의 `plan` 을 `context.plan` 에 실어 다시 → `plan.revise` 가 온다 |
| 5 | 에이전트를 끄고 `/guide/chat` | **503 `GUIDE_UNAVAILABLE`**, 3 초 안에 |
| 6 | `grep -r "system prompt\|deepseek\|plan_course" services/scene-api/src` | **0 건.** 있으면 0012 로 되돌아간 것 |

4 번은 MZ2AZ-320 이 끝난 뒤에야 `/guide/plan` 경로로도 된다(지금은 `start` 배열). 「헤더 뒤 멈춤」은
JDK `HttpClient` 의 요청 타임아웃이 몸체까지 덮는지 문서만으로 확답이 안 되는 자리라 시험이 곧 실측이다 —
실패하면 몸체 읽기에 별도 상한을 두는 쪽으로 고친다.

## 5. 순서

1. `application.yaml` — 설정 두 줄과 가상 스레드. 기동 확인.
2. `GuideAgentClient` + 시험. 가짜 서버로 예외 번역 표 §2-2 를 전부 덮는다.
3. `GuideEffectApplier` + 시험.
4. `GuideController` + 시험. 여기서 처음 두 창구가 `501` 이 아니게 된다.
5. `just check`.
6. §4-2 실제 요청. 결과(응답 JSON 요약·DB 행 수)를 이 문서 §6 에 적는다.
7. scene-api README 에 절 하나(「가이드 챗봇 — 에이전트를 부른다」), `docs/api/README` 확인.

한 PR. 커밋은 위 번호대로 나눈다.

## 6. 실측 (구현 뒤 채운다)

_비어 있음 — §4-2 를 돌린 뒤 여기에._

## 7. 이 문서 밖

- **배포.** `rules_python` 이 꺼져 있어 에이전트 컨테이너가 없다. 켜는 것은 `MODULE.bazel` 팀 결정.
  그전까지 클러스터의 scene-api 는 `/guide/*` 에 503 을 낸다 — 설정된 주소에 아무것도 없으니까.
  그것이 맞는 동작이다.
- **Bedrock 자격 증명** — MZ2AZ-317. 에이전트 쪽 일이고 scene-api 는 모른다.
- **에이전트가 계약에 맞출 것** — MZ2AZ-320 (8 항목). `/guide/plan` 의 실제 요청 검증(§4-2 의 1·4)은 그 뒤.
- **앱** — MZ2AZ-321. `RouteGuide.ask` 를 생성 클라이언트로, `context.plan` 을 싣게, `effects`·`ui` 처리,
  `places[].source`, 타임아웃 50초, 마법사가 `/guide/plan` 을 부르게.
- **기기당 호출 제한** — 공개 전. 지금은 `X-Device-Id` 만 있으면 무제한으로 토큰을 쓴다.
- **스트리밍.** 응답이 10 초를 넘기 시작하면 검토. 지금은 3~5 초라 「생각하는 중」 표시로 충분하다.
