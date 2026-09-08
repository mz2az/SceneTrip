package com.mz2az.scenetrip.sceneapi.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatRequest;
import com.mz2az.scenetrip.sceneapi.api.model.GuideContext;
import com.mz2az.scenetrip.sceneapi.api.model.GuideMessage;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlan;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanRequest;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 에이전트 클라이언트의 전송과 예외 번역. 에이전트 대신 JDK 내장 {@code HttpServer} 로 가짜를 띄우고 {@code base-url} 을 거기로 돌린다 —
 * {@code KakaoRoutingClientTest} 와 같은 방식이고 새 의존성은 없다.
 *
 * <p>보는 것 — 요청 몸체가 계약 모양 그대로 나가는지({@code context.plan} 포함), 응답이 생성된 모델로 읽히는지, 에이전트의 400 은 400 그대로이고
 * 나머지 실패는 전부 503 {@code GUIDE_UNAVAILABLE} 하나로 접히는지. 타임아웃 셋(연결 지연·응답 지연·헤더 뒤 멈춤)은 클라이언트를 1초로 만들어 빨리
 * 끝낸다. 「헤더 뒤 멈춤」이 실측이다 — JDK {@code HttpClient} 의 요청 타임아웃이 몸체까지 덮는지 문서만으로 확답이 안 됐다(계획 §4-1).
 */
class GuideAgentClientTest {

  private static HttpServer server;
  private static String baseUrl;

  private static volatile int status;
  private static volatile String body;
  private static volatile long delayMs;
  private static volatile boolean headersThenStall;
  private static volatile String lastPath;
  private static volatile String lastAcceptLanguage;
  private static volatile String lastRequestBody;

  @BeforeAll
  static void startFakeAgent() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          lastPath = exchange.getRequestURI().getPath();
          lastAcceptLanguage = exchange.getRequestHeaders().getFirst("Accept-Language");
          lastRequestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          if (delayMs > 0) {
            sleep(delayMs);
          }
          if (headersThenStall) {
            // 헤더는 보내고 몸체는 영영 안 보낸다. 길이를 크게 알려 클라이언트가 몸체를 기다리게 한다.
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 100_000);
            sleep(5_000);
            exchange.close();
            return;
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    // 타임아웃 테스트가 핸들러를 재워 두면 다음 요청이 그 뒤에 줄을 선다. 요청마다 스레드를 준다.
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @AfterAll
  static void stopFakeAgent() {
    server.stop(0);
  }

  @BeforeEach
  void reset() {
    status = 200;
    body = CHAT_JSON;
    delayMs = 0;
    headersThenStall = false;
    lastPath = null;
    lastAcceptLanguage = null;
    lastRequestBody = null;
  }

  private static GuideAgentClient client() {
    return client(baseUrl, 1);
  }

  private static GuideAgentClient client(String url, int timeoutSeconds) {
    return new GuideAgentClient(url, timeoutSeconds, ObservationRegistry.NOOP);
  }

  /** 계약 모양의 챗봇 응답. 값은 지어낸 것이고 필수 필드는 다 있다. */
  private static final String CHAT_JSON =
      """
      {
        "reply": "서울중앙고를 담았어요.",
        "toolsUsed": [{"tool": "update_cart", "arguments": {"action": "add", "name": "서울중앙고"}}],
        "places": [{"id": 1187, "name": "서울중앙고", "category": "촬영지", "categoryGroup": "sight",
                    "latitude": 37.5826, "longitude": 126.991}],
        "route": null,
        "tookSeconds": 3.4,
        "effects": [{"op": "cart.add", "placeId": 1187, "name": "서울중앙고"}],
        "ui": [{"op": "map.focus", "placeIds": [1187]}],
        "이건_모르는_필드": "무시돼야 한다"
      }
      """;

  private static final String PLAN_JSON =
      """
      {
        "plan": {"titles": ["도깨비"], "pace": "relaxed", "travelBasis": "straight-line",
                 "days": [{"day": 1, "endMinute": 737, "totalMeters": 11878,
                           "stops": [{"order": 1, "placeId": 1187, "name": "서울중앙고", "arriveMinute": 540}]}]},
        "effects": [{"op": "plan.draft"}],
        "ui": [{"op": "course.open", "day": 1}],
        "tookSeconds": 0.02
      }
      """;

  private static GuideChatRequest chatRequest() {
    GuideChatRequest req =
        new GuideChatRequest(
            UUID.fromString("0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11"),
            37.5665,
            126.978,
            List.of(new GuideMessage(GuideMessage.RoleEnum.USER, "1번 담아 줘")));
    // 앱이 편집 중인 일정 — 그대로 에이전트까지 가야 한다.
    GuidePlan plan = new GuidePlan(List.of());
    plan.setTitles(List.of("도깨비"));
    GuideContext context = new GuideContext(List.of());
    context.setPlan(plan);
    req.setContext(context);
    return req;
  }

  @Test
  @DisplayName("챗봇 응답이 계약 모델로 읽힌다 — effects·ui 포함, 모르는 필드 무시")
  void parsesChatReply() {
    GuideChatReply reply = client().chat(chatRequest(), Lang.KO);

    assertThat(reply.getReply()).isEqualTo("서울중앙고를 담았어요.");
    assertThat(reply.getEffects()).hasSize(1);
    assertThat(reply.getEffects().get(0).getOp()).isEqualTo("cart.add");
    assertThat(reply.getEffects().get(0).getPlaceId()).isEqualTo(1187L);
    assertThat(reply.getUi().get(0).getOp()).isEqualTo("map.focus");
    assertThat(reply.getPlaces().get(0).getId()).isEqualTo(1187L);
    assertThat(reply.getTookSeconds()).isEqualTo(3.4);
  }

  @Test
  @DisplayName("요청은 에이전트의 /guide/chat 으로, 계약 이름 그대로, context.plan 을 실어서")
  void sendsContractShapedRequest() {
    client().chat(chatRequest(), Lang.EN);

    assertThat(lastPath).isEqualTo("/guide/chat");
    assertThat(lastAcceptLanguage).isEqualTo("en");
    assertThat(lastRequestBody)
        .contains("\"sessionId\":\"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11\"")
        .contains("\"messages\":[{\"role\":\"user\",\"content\":\"1번 담아 줘\"}]")
        .contains("\"context\":{")
        .contains("\"plan\":{")
        .contains("\"titles\":[\"도깨비\"]");
  }

  @Test
  @DisplayName("마법사 응답은 /plan 으로 가고 GuidePlanReply 로 읽힌다")
  void parsesPlanReply() {
    body = PLAN_JSON;

    GuidePlanReply reply = client().plan(new GuidePlanRequest(List.of("도깨비"), 2), Lang.KO);

    assertThat(lastPath).isEqualTo("/plan");
    assertThat(reply.getPlan().getDays()).hasSize(1);
    assertThat(reply.getPlan().getDays().get(0).getStops().get(0).getArriveMinute()).isEqualTo(540);
    assertThat(reply.getEffects().get(0).getOp()).isEqualTo("plan.draft");
  }

  @Test
  @DisplayName("에이전트의 400 은 앱의 잘못 — 400 그대로, code·message 를 살려서")
  void passesThrough400() {
    status = 400;
    body = "{\"code\":\"INVALID_PARAMETER\",\"message\":\"「도깨비2」 로 찾은 촬영지가 없다\"}";

    assertThatThrownBy(() -> client().plan(new GuidePlanRequest(List.of("도깨비2"), 2), Lang.KO))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.getCode()).isEqualTo("INVALID_PARAMETER");
              assertThat(e.getMessage()).contains("도깨비2");
            });
  }

  @Test
  @DisplayName("에이전트가 스스로 낸 503(모델 안 뜸) → 503 GUIDE_UNAVAILABLE, 그 message 를 살려서")
  void translatesAgent503() {
    status = 503;
    body = "{\"code\":\"GUIDE_UNAVAILABLE\",\"message\":\"DeepSeek 에 닿지 못했다\"}";

    assertUnavailable(() -> client().chat(chatRequest(), Lang.KO), "DeepSeek");
  }

  @Test
  @DisplayName("그 외 4xx(404 등) → 503 — 우리가 모르는 상태")
  void translatesOther4xx() {
    status = 404;
    body = "{\"error\":\"그런 주소가 없다\"}";

    assertUnavailable(() -> client().chat(chatRequest(), Lang.KO), null);
  }

  @Test
  @DisplayName("연결 거부(닫힌 포트) → 즉시 503")
  void connectionRefused() {
    GuideAgentClient closed = client("http://127.0.0.1:1", 1);

    long started = System.nanoTime();
    assertUnavailable(() -> closed.chat(chatRequest(), Lang.KO), null);
    assertThat(elapsedMs(started)).isLessThan(3_000);
  }

  @Test
  @DisplayName("연결 지연(라우팅 안 되는 주소) → 연결 타임아웃 3초 안에 503")
  void connectionTimeout() {
    // 10.255.255.1 은 관례상 아무도 안 받는 주소다. 환경에 따라 즉시 거부가 올 수도 있어 시간 상한만 본다.
    GuideAgentClient unreachable = client("http://10.255.255.1:9", 1);

    long started = System.nanoTime();
    assertUnavailable(() -> unreachable.chat(chatRequest(), Lang.KO), null);
    assertThat(elapsedMs(started)).isLessThan(5_000);
  }

  @Test
  @DisplayName("응답 지연(헤더도 안 옴) → 응답 타임아웃에 503")
  void readTimeout() {
    delayMs = 3_000;

    long started = System.nanoTime();
    assertUnavailable(() -> client().chat(chatRequest(), Lang.KO), null);
    assertThat(elapsedMs(started)).isBetween(900L, 2_500L);
  }

  @Test
  @DisplayName("헤더 뒤 멈춤(200 헤더만 오고 몸체 안 옴) → 응답 타임아웃에 503 — JDK 타임아웃이 몸체까지 덮는지의 실측")
  void headersThenStallTimesOut() {
    headersThenStall = true;

    long started = System.nanoTime();
    assertUnavailable(() -> client().chat(chatRequest(), Lang.KO), null);
    assertThat(elapsedMs(started)).isLessThan(4_500);
  }

  @Test
  @DisplayName("깨진 JSON → 503 — 에이전트가 계약을 어긴 것")
  void brokenJson() {
    body = "{\"reply\": ";

    assertUnavailable(() -> client().chat(chatRequest(), Lang.KO), null);
  }

  @Test
  @DisplayName("주소가 비어 있으면 부르지 않고 503")
  void emptyBaseUrl() {
    GuideAgentClient none = client("  ", 1);

    assertUnavailable(() -> none.chat(chatRequest(), Lang.KO), null);
    assertThat(lastPath).isNull();
  }

  private static void assertUnavailable(Runnable call, String messageContains) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.getCode()).isEqualTo(GuideAgentClient.CODE_UNAVAILABLE);
              if (messageContains != null) {
                assertThat(e.getMessage()).contains(messageContains);
              }
            });
  }

  private static long elapsedMs(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }
}
