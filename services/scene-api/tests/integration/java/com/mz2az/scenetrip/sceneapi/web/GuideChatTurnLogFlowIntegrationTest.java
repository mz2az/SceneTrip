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
import com.mz2az.scenetrip.sceneapi.guide.GuideTurnLog;
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
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * 챗봇 처리 기록(MZ2AZ-410)을 끝까지 — 진짜 Store·진짜 PostgreSQL·가짜 에이전트(로컬 HTTP 서버) 위에서 한 턴이 {@code guide_turn}
 * 에 몇 줄, 무엇으로 남는지. 에이전트를 부른 턴만 한 줄, 그 앞에서 거절된 턴은 0 줄, 기록이 실패해도 응답은 같다.
 *
 * <p>공유 로컬 DB 다. 시작 때 {@code guide_turn} 의 최대 id 를 적어 두고 끝나면 그 위만 지운다. 만든 계정도 지운다.
 */
@DisplayName("가이드 챗봇 — 처리 기록 흐름 (실제 DB, 가짜 에이전트)")
class GuideChatTurnLogFlowIntegrationTest {

  private static final byte[] SECRET = filled(32, (byte) 7);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String MESSAGE = "제 번호 010-9876-5432, 숙소는 을지로 근처인데 촬영지 알려 줘";
  private static final String REPLY_TEXT = "을지로 근처 촬영지 두 곳이에요.";

  private static final String RICH_REPLY =
      """
      {"reply":"%s",
       "toolsUsed":[{"tool":"search_places","arguments":{}},{"tool":"places_near","arguments":{}}],
       "places":[
         {"id":11,"name":"A","category":"카페","categoryGroup":"food","latitude":37.56,"longitude":126.99,"source":"place"},
         {"id":12,"name":"B","category":"관광지","categoryGroup":"sight","latitude":37.57,"longitude":126.98,"source":"poi"}],
       "route":null,"tookSeconds":2.1,
       "effects":[{"op":"plan.draft","plan":null}],
       "ui":[]}
      """
          .formatted(REPLY_TEXT);

  private static final String CHAT_BODY =
      """
      {"sessionId":"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11","latitude":37.5665,"longitude":126.978,
       "messages":[{"role":"user","content":"%s"}],"context":{"stops":[]}}
      """
          .formatted(MESSAGE);

  private static HttpServer agentServer;
  private static String agentUrl;
  private static final AtomicInteger agentHits = new AtomicInteger();
  private static volatile int agentStatus;
  private static volatile String agentBody;
  private static volatile long agentDelayMs;

  private static JdbcClient jdbc;
  private static UserStore users;

  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final List<UUID> created = new ArrayList<>();
  private UUID install;
  private UUID user;
  private long watermark;

  record Row(
      String lang,
      String outcome,
      List<String> tools,
      Integer placeCount,
      List<String> effects,
      int tookMs) {}

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
          byte[] bytes = agentBody.getBytes(StandardCharsets.UTF_8);
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
    watermark = jdbc.sql("SELECT coalesce(max(id), 0) FROM guide_turn").query(Long.class).single();
    agentHits.set(0);
    agentStatus = 200;
    agentBody = RICH_REPLY;
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
    jdbc.sql("DELETE FROM guide_turn WHERE id > :w").param("w", watermark).update();
    for (UUID id : created) {
      jdbc.sql("DELETE FROM usage_counter WHERE subject = :s").param("s", id.toString()).update();
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  private GuideController controller(int perHour, GuideTurnLog turns) {
    return new GuideController(
        new GuideAgentClient(agentUrl, 5, ObservationRegistry.NOOP),
        GuideEffectAppliers.create(CartStores.create(jdbc)),
        users,
        new CurrentAccount(request, tokens, users),
        new PaidQuota(new UsageStore(jdbc), perHour, 100, 10, 300),
        new IdempotencyStore(jdbc),
        JSON,
        turns);
  }

  private GuideController controller(int perHour) {
    return controller(perHour, new GuideTurnLog(jdbc, 90));
  }

  private static GuideChatRequest body() {
    return JSON.readValue(CHAT_BODY, GuideChatRequest.class);
  }

  private static List<String> strings(ResultSet rs, String column) throws SQLException {
    Array a = rs.getArray(column);
    return a == null ? null : Arrays.asList((String[]) a.getArray());
  }

  private List<Row> rows() {
    return jdbc.sql(
            "SELECT lang, outcome, tools, place_count, effects, took_ms FROM guide_turn"
                + " WHERE id > :w ORDER BY id")
        .param("w", watermark)
        .query(
            (rs, i) ->
                new Row(
                    rs.getString("lang"),
                    rs.getString("outcome"),
                    strings(rs, "tools"),
                    (Integer) rs.getObject("place_count"),
                    strings(rs, "effects"),
                    rs.getInt("took_ms")))
        .list();
  }

  private static void expect(Throwable e, HttpStatus status, String code) {
    assertThat(e).isInstanceOf(ApiException.class);
    ApiException api = (ApiException) e;
    assertThat(api.getStatus()).isEqualTo(status);
    assertThat(api.getCode()).isEqualTo(code);
  }

  @Test
  @DisplayName("답한 턴 — 한 줄: 언어, ok, 도구(쓴 순서), 장소 수, 효과 op, 걸린 시간 ≥ 0")
  void answeredTurnWritesOneRow() {
    agentDelayMs = 50;
    ResponseEntity<GuideChatReply> res =
        controller(15).chatWithGuide(install, body(), Lang.JA, null);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Row> rows = rows();
    assertThat(rows).hasSize(1);
    Row row = rows.get(0);
    assertThat(row.lang()).isEqualTo("ja");
    assertThat(row.outcome()).isEqualTo("ok");
    assertThat(row.tools()).containsExactly("search_places", "places_near");
    assertThat(row.placeCount()).isEqualTo(2);
    assertThat(row.effects()).containsExactly("plan.draft");
    assertThat(row.tookMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("Accept-Language 가 없으면 lang 은 en")
  void missingLanguageIsEn() {
    controller(15).chatWithGuide(install, body(), null, null);

    assertThat(rows()).extracting(Row::lang).containsExactly("en");
  }

  @Test
  @DisplayName("원문·사용자·기기·멱등 키는 어느 칸에도 없다")
  void nothingIdentifyingIsStored() {
    controller(15).chatWithGuide(install, body(), Lang.KO, "turn-secret-key");

    String whole =
        jdbc.sql("SELECT string_agg(t::text, ' ') FROM guide_turn t WHERE id > :w")
            .param("w", watermark)
            .query(String.class)
            .single();
    assertThat(whole)
        .isNotNull()
        .doesNotContain("010-9876-5432")
        .doesNotContain("을지로")
        .doesNotContain(user.toString())
        .doesNotContain(install.toString())
        .doesNotContain("turn-secret-key");
  }

  @Test
  @DisplayName("에이전트 503 — 한 줄: outcome GUIDE_UNAVAILABLE, 장소 수 NULL, 도구·효과 빈 배열")
  void agentUnavailableWritesFailureRow() {
    agentStatus = 503;
    agentBody = "{\"code\":\"X\",\"message\":\"down\"}";

    assertThatThrownBy(() -> controller(15).chatWithGuide(install, body(), Lang.KO, null))
        .satisfies(e -> expect(e, HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_UNAVAILABLE"));

    List<Row> rows = rows();
    assertThat(rows).hasSize(1);
    Row row = rows.get(0);
    assertThat(row.lang()).isEqualTo("ko");
    assertThat(row.outcome()).isEqualTo("GUIDE_UNAVAILABLE");
    assertThat(row.placeCount()).isNull();
    assertThat(row.tools()).isEmpty();
    assertThat(row.effects()).isEmpty();
    assertThat(row.tookMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("에이전트 400 — outcome 은 그 오류 코드(INVALID_PARAMETER)")
  void agentBadRequestWritesItsCode() {
    agentStatus = 400;
    agentBody = "{\"code\":\"INVALID_PARAMETER\",\"message\":\"context.plan 모양\"}";

    assertThatThrownBy(() -> controller(15).chatWithGuide(install, body(), Lang.EN, null))
        .satisfies(e -> expect(e, HttpStatus.BAD_REQUEST, "INVALID_PARAMETER"));

    assertThat(rows()).extracting(Row::outcome).containsExactly("INVALID_PARAMETER");
  }

  @Test
  @DisplayName("미가입(401) — 0 줄")
  void unregisteredWritesNothing() {
    jdbc.sql("UPDATE app_user SET registered_at = NULL WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
    request.removeHeader("Authorization");

    assertThatThrownBy(() -> controller(15).chatWithGuide(install, body(), Lang.KO, null))
        .satisfies(e -> expect(e, HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED"));

    assertThat(rows()).isEmpty();
  }

  @Test
  @DisplayName("한도 초과(429) — 넘은 턴은 0 줄, 앞의 두 턴만 두 줄")
  void quotaExceededWritesNothing() {
    GuideController controller = controller(2);
    controller.chatWithGuide(install, body(), Lang.KO, null);
    controller.chatWithGuide(install, body(), Lang.KO, null);

    assertThatThrownBy(() -> controller.chatWithGuide(install, body(), Lang.KO, null))
        .satisfies(e -> expect(e, HttpStatus.TOO_MANY_REQUESTS, "GUIDE_LIMIT_REACHED"));

    assertThat(rows()).hasSize(2);
  }

  @Test
  @DisplayName("같은 키 재생 — 첫 턴 한 줄, 재생은 0 줄")
  void replayWritesNothing() {
    GuideController controller = controller(15);
    controller.chatWithGuide(install, body(), Lang.KO, "turn-1");
    ResponseEntity<GuideChatReply> replay =
        controller.chatWithGuide(install, body(), Lang.KO, "turn-1");

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(agentHits.get()).isEqualTo(1);
    assertThat(rows()).hasSize(1);
  }

  @Test
  @DisplayName("같은 키 다른 내용(422) — 0 줄 더")
  void mismatchWritesNothing() {
    GuideController controller = controller(15);
    controller.chatWithGuide(install, body(), Lang.KO, "turn-1");

    assertThatThrownBy(() -> controller.chatWithGuide(install, body(), Lang.EN, "turn-1"))
        .satisfies(e -> expect(e, HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED"));

    assertThat(rows()).hasSize(1);
  }

  @Test
  @DisplayName("처리 중 같은 키(409) — 0 줄 더, 첫 턴만 한 줄")
  void inProgressWritesNothing() throws Exception {
    GuideController controller = controller(15);
    agentDelayMs = 1_500;

    CompletableFuture<ResponseEntity<GuideChatReply>> first =
        CompletableFuture.supplyAsync(
            () -> controller.chatWithGuide(install, body(), Lang.KO, "turn-1"));
    long deadline = System.currentTimeMillis() + 5_000;
    while (agentHits.get() == 0 && System.currentTimeMillis() < deadline) {
      Thread.onSpinWait();
    }
    assertThatThrownBy(() -> controller.chatWithGuide(install, body(), Lang.KO, "turn-1"))
        .satisfies(e -> expect(e, HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS"));
    assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);

    List<Row> rows = rows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).tookMs()).isGreaterThanOrEqualTo(1_000);
  }

  @Test
  @DisplayName("기록이 실패해도(DB 에 못 닿는 기록기) 응답은 같다 — 200 의 본문·헤더, 503 의 코드")
  void logFailureDoesNotChangeResponse() {
    GuideTurnLog dead =
        new GuideTurnLog(
            JdbcClient.create(
                new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=1")),
            90);

    ResponseEntity<GuideChatReply> withLog =
        controller(15).chatWithGuide(install, body(), Lang.KO, null);
    ResponseEntity<GuideChatReply> withoutLog =
        controller(15, dead).chatWithGuide(install, body(), Lang.KO, "turn-dead");

    assertThat(withoutLog.getStatusCode()).isEqualTo(withLog.getStatusCode());
    assertThat(withoutLog.getBody()).isEqualTo(withLog.getBody());
    assertThat(withoutLog.getBody().getReply()).isEqualTo(REPLY_TEXT);
    assertThat(withoutLog.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("15");
    assertThat(withoutLog.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("13");
    assertThat(
            jdbc.sql(
                    "SELECT state FROM idempotency_key WHERE user_id = CAST(:u AS UUID) AND key ="
                        + " :k")
                .param("u", user.toString())
                .param("k", "turn-dead")
                .query(String.class)
                .list())
        .containsExactly("completed");

    agentStatus = 503;
    agentBody = "{\"code\":\"X\",\"message\":\"down\"}";
    assertThatThrownBy(() -> controller(15, dead).chatWithGuide(install, body(), Lang.KO, null))
        .satisfies(e -> expect(e, HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_UNAVAILABLE"));

    assertThat(rows()).hasSize(1); // 기록기가 살아 있던 첫 턴만
  }

  @Test
  @DisplayName("에이전트 답의 toolsUsed·effects 에 null 원소가 섞여도 기록 때문에 응답이 바뀌지 않는다(200)")
  void nullElementsInReplyDoNotBreakResponse() {
    agentBody =
        """
        {"reply":"답","toolsUsed":[null,{"tool":"places_near","arguments":{}}],"places":[],
         "route":null,"tookSeconds":1.0,"effects":[],"ui":[]}
        """;

    ResponseEntity<GuideChatReply> res =
        controller(15).chatWithGuide(install, body(), Lang.KO, null);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
