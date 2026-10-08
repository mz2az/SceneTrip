package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatRequest;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.cart.CartStores;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.guide.GuideEffectAppliers;
import com.mz2az.scenetrip.sceneapi.guide.IdempotencyStore;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota;
import com.mz2az.scenetrip.sceneapi.limit.UsageStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가이드 챗봇 한 턴을 끝까지 — <b>진짜 Store·진짜 PostgreSQL·가짜 에이전트(로컬 HTTP 서버)</b> 위에서 한도(계약 「요청 한도」)와 멱등 키
 * (Idempotency-Key)가 함께 명세대로 도는지 본다. 실제 에이전트·모델은 부르지 않는다.
 *
 * <p>스프링 컨텍스트 없이 {@link GuideController} 를 손으로 조립한다({@code AuthFlowIntegrationTest} 와 같은 방식). 가입한
 * 계정은 액세스 토큰으로 온다 — {@link CurrentAccount} 가 그 헤더를 읽는다. 거절은 {@link ApiException} 의 상태·코드로 본다.
 */
@DisplayName("가이드 챗봇 — 한도·멱등 키 흐름 (실제 DB, 가짜 에이전트)")
class GuideChatQuotaFlowIntegrationTest {

  private static final byte[] SECRET = filled(32, (byte) 5);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String REPLY_JSON =
      """
      {"reply":"경복궁은 오늘 열려 있어요.","toolsUsed":[],"places":[],"route":null,
       "tookSeconds":1.2,"effects":[],"ui":[]}
      """;

  private static final String CHAT_BODY =
      """
      {"sessionId":"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11","latitude":37.5665,"longitude":126.978,
       "messages":[{"role":"user","content":"경복궁 열었어?"}],"context":{"stops":[]}}
      """;

  private static HttpServer agentServer;
  private static String agentUrl;
  private static final AtomicInteger agentHits = new AtomicInteger();
  private static volatile int agentStatus;
  private static volatile long agentDelayMs;

  private static JdbcClient jdbc;
  private static UserStore users;

  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final List<UUID> created = new ArrayList<>();
  private UUID install;
  private UUID user;

  private static byte[] filled(int n, byte b) {
    byte[] out = new byte[n];
    Arrays.fill(out, b);
    return out;
  }

  @BeforeAll
  static void start() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    users = new UserStore(jdbc);
    agentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    agentServer.createContext(
        "/",
        exchange -> {
          agentHits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (agentDelayMs > 0) {
            try {
              Thread.sleep(agentDelayMs);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] bytes =
              (agentStatus == 200 ? REPLY_JSON : "{\"code\":\"X\",\"message\":\"down\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(agentStatus, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    agentServer.setExecutor(Executors.newCachedThreadPool());
    agentServer.start();
    agentUrl = "http://127.0.0.1:" + agentServer.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    agentServer.stop(0);
  }

  @BeforeEach
  void signedInUser() {
    agentHits.set(0);
    agentStatus = 200;
    agentDelayMs = 0;
    install = UUID.randomUUID();
    user = users.resolve(install);
    created.add(user);
    jdbc.sql("UPDATE app_user SET registered_at = now() WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
    request.addHeader("Authorization", "Bearer " + tokens.issue(user).value());
  }

  @AfterEach
  void cleanUp() {
    for (UUID id : created) {
      jdbc.sql("DELETE FROM usage_counter WHERE subject = :s").param("s", id.toString()).update();
      // idempotency_key 는 ON DELETE CASCADE 로 함께 지워진다
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  private GuideController controller(int perHour, int perDay) {
    UsageStore usage = new UsageStore(jdbc);
    return new GuideController(
        new GuideAgentClient(agentUrl, 5, ObservationRegistry.NOOP),
        GuideEffectAppliers.create(CartStores.create(jdbc)),
        users,
        new CurrentAccount(request, tokens, users),
        new PaidQuota(usage, perHour, perDay, 10, 300),
        new IdempotencyStore(jdbc),
        JSON);
  }

  private static GuideChatRequest body(String text) {
    return JSON.readValue(CHAT_BODY.replace("경복궁 열었어?", text), GuideChatRequest.class);
  }

  private int used() {
    return jdbc.sql("SELECT coalesce(sum(count), 0) FROM usage_counter WHERE subject = :s")
        .param("s", user.toString())
        .query(Integer.class)
        .single();
  }

  private List<String> keyStates(String key) {
    return jdbc.sql(
            "SELECT state FROM idempotency_key WHERE user_id = CAST(:u AS UUID) AND key = :k")
        .param("u", user.toString())
        .param("k", key)
        .query(String.class)
        .list();
  }

  private static void expect(Throwable e, HttpStatus status, String code) {
    assertThat(e).isInstanceOf(ApiException.class);
    ApiException api = (ApiException) e;
    assertThat(api.getStatus()).isEqualTo(status);
    assertThat(api.getCode()).isEqualTo(code);
  }

  @Test
  @DisplayName("키 없이 — 매번 에이전트를 부르고 매번 센다, RateLimit 헤더는 시간 창(15) 기준")
  void withoutKeyEveryTurnCounts() {
    GuideController controller = controller(15, 100);

    ResponseEntity<GuideChatReply> first =
        controller.chatWithGuide(install, body("a"), Lang.KO, null);
    ResponseEntity<GuideChatReply> second =
        controller.chatWithGuide(install, body("a"), Lang.KO, null);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody().getReply()).isEqualTo("경복궁은 오늘 열려 있어요.");
    assertThat(first.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("15");
    assertThat(first.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("14");
    assertThat(second.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("13");
    assertThat(agentHits.get()).isEqualTo(2);
    assertThat(used()).isEqualTo(4); // 두 턴 × (시간 + 하루)
  }

  @Test
  @DisplayName("같은 키로 두 번 — 두 번째는 저장한 답, 에이전트는 한 번, 한도도 한 번")
  void sameKeyReplays() {
    GuideController controller = controller(15, 100);

    ResponseEntity<GuideChatReply> first =
        controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1");
    ResponseEntity<GuideChatReply> replay =
        controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1");

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(agentHits.get()).isEqualTo(1);
    assertThat(used()).isEqualTo(2);
    assertThat(keyStates("turn-1")).containsExactly("completed");
  }

  @Test
  @DisplayName("같은 키에 다른 본문·다른 언어 — 422 IDEMPOTENCY_KEY_REUSED, 에이전트를 부르지 않는다")
  void sameKeyDifferentContentIs422() {
    GuideController controller = controller(15, 100);
    controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1");

    assertThatThrownBy(() -> controller.chatWithGuide(install, body("b"), Lang.KO, "turn-1"))
        .satisfies(e -> expect(e, HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED"));
    assertThatThrownBy(() -> controller.chatWithGuide(install, body("a"), Lang.EN, "turn-1"))
        .satisfies(e -> expect(e, HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED"));
    assertThat(agentHits.get()).isEqualTo(1);
    assertThat(used()).isEqualTo(2);
  }

  @Test
  @DisplayName("처리 중에 같은 키 — 409 IDEMPOTENCY_IN_PROGRESS, 첫 턴은 정상으로 끝난다")
  void sameKeyWhileProcessingIs409() throws Exception {
    GuideController controller = controller(15, 100);
    agentDelayMs = 1_500;

    CompletableFuture<ResponseEntity<GuideChatReply>> first =
        CompletableFuture.supplyAsync(
            () -> controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1"));
    // 첫 턴이 키를 잡고 에이전트를 기다리는 동안
    long deadline = System.currentTimeMillis() + 5_000;
    while (agentHits.get() == 0 && System.currentTimeMillis() < deadline) {
      Thread.onSpinWait();
    }
    assertThatThrownBy(() -> controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1"))
        .satisfies(e -> expect(e, HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS"));

    assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(agentHits.get()).isEqualTo(1);
    assertThat(used()).isEqualTo(2);
  }

  @Test
  @DisplayName("에이전트 503 — 한도를 되돌리고 키를 놓는다. 고쳐진 뒤 같은 키로 다시 보내면 처리된다")
  void agentFailureRefundsAndReleasesKey() {
    GuideController controller = controller(15, 100);
    agentStatus = 503;

    assertThatThrownBy(() -> controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1"))
        .satisfies(e -> expect(e, HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_UNAVAILABLE"));
    assertThat(used()).isZero();
    assertThat(keyStates("turn-1")).isEmpty();

    agentStatus = 200;
    ResponseEntity<GuideChatReply> retry =
        controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1");
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(agentHits.get()).isEqualTo(2);
    assertThat(used()).isEqualTo(2);
    assertThat(keyStates("turn-1")).containsExactly("completed");
  }

  @Test
  @DisplayName("시간 한도(2)를 넘으면 429 GUIDE_LIMIT_REACHED·Retry-After, 에이전트는 안 불리고 키는 놓인다")
  void hourlyLimitIs429AndReleasesKey() {
    GuideController controller = controller(2, 100);
    controller.chatWithGuide(install, body("a"), Lang.KO, null);
    controller.chatWithGuide(install, body("b"), Lang.KO, null);

    assertThatThrownBy(() -> controller.chatWithGuide(install, body("c"), Lang.KO, "turn-3"))
        .satisfies(
            e -> {
              expect(e, HttpStatus.TOO_MANY_REQUESTS, "GUIDE_LIMIT_REACHED");
              var headers = ((ApiException) e).getHeaders();
              assertThat(Long.parseLong(headers.get("Retry-After"))).isBetween(1L, 3600L);
              assertThat(headers.get("RateLimit-Limit")).isEqualTo("2");
              assertThat(headers.get("RateLimit-Remaining")).isEqualTo("0");
            });
    assertThat(agentHits.get()).isEqualTo(2);
    assertThat(used()).isEqualTo(4); // 넘은 시도는 세지 않았다
    assertThat(keyStates("turn-3")).isEmpty();
  }

  @Test
  @DisplayName("미가입 계정 — 401 SIGN_IN_REQUIRED, 세지도 키를 잡지도 않는다")
  void unregisteredIsNotCounted() {
    jdbc.sql("UPDATE app_user SET registered_at = NULL WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
    request.removeHeader("Authorization");
    GuideController controller = controller(15, 100);

    assertThatThrownBy(() -> controller.chatWithGuide(install, body("a"), Lang.KO, "turn-1"))
        .satisfies(e -> expect(e, HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED"));
    assertThat(agentHits.get()).isZero();
    assertThat(used()).isZero();
    assertThat(keyStates("turn-1")).isEmpty();
  }
}
