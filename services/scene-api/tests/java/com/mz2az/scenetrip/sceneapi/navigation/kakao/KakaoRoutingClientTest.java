package com.mz2az.scenetrip.sceneapi.navigation.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.navigation.Coordinate;
import com.mz2az.scenetrip.sceneapi.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 카카오 클라이언트의 전송과 예외 번역. 카카오 대신 JDK 내장 {@code HttpServer} 로 가짜를 띄우고 {@code base-url} 을 거기로 돌린다 —
 * 설정으로 뺀 이유가 이것이다. 새 의존성은 없다.
 *
 * <p>규칙 테스트({@code NextLegPlannerTest})가 못 보는 것을 본다 — JSON 이 record 로 제대로 들어가는지(모르는 필드 무시, {@code
 * time} 필드, {@code fare} 두 모양), 카카오 4xx·5xx 가 503 하나로 접히는지, 400 만 그대로 던지는지, 키가 비면 부르지 않는지.
 *
 * <p>응답 JSON 은 실측(2026-09-02)과 <b>모양이 같고 내용은 지어낸 것</b>이다 — 정류장 이름·안내문이 카카오 데이터라 저장소에 넣지 않는다.
 */
class KakaoRoutingClientTest {

  private static HttpServer server;
  private static String baseUrl;

  private static volatile int status;
  private static volatile String body;
  private static volatile long delayMs;
  private static volatile String lastRequestUri;

  @BeforeAll
  static void startFakeKakao() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          lastRequestUri = exchange.getRequestURI().toString();
          if (delayMs > 0) {
            try {
              Thread.sleep(delayMs);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    // 기본은 한 스레드다. 타임아웃 테스트가 핸들러를 재워 두면 다음 테스트의 요청이 그 뒤에 줄을 서서
    // 같이 시간을 넘긴다 — 테스트 순서에 따라 엉뚱한 케이스가 503 이 된다. 요청마다 스레드를 준다.
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopFakeKakao() {
    server.stop(0);
  }

  @BeforeEach
  void reset() {
    status = 200;
    body = "{}";
    delayMs = 0;
    lastRequestUri = null;
  }

  private static KakaoRoutingClient client() {
    return new KakaoRoutingClient(baseUrl, "test-key", 1, ObservationRegistry.NOOP);
  }

  private static final Coordinate FROM = new Coordinate(37.0, 127.0);
  private static final Coordinate TO = new Coordinate(37.02, 127.0);

  /** 실측 모양. 최상위 {@code properties}·{@code landingURL} 은 우리 record 에 없다 — 무시돼야 한다. */
  private static final String TRANSIT_JSON =
      """
      {
        "status": "OK",
        "properties": {"total": 1, "bus": 1, "subway": 0, "landingURL": "https://example.invalid"},
        "routes": [{
          "properties": {"type": "BUS", "totalDistance": 2000, "totalTime": 1500, "transfers": 0,
                         "fare": {"value": 1200}},
          "steps": [{
            "properties": {"type": "BUS", "guidance": "마을 가상02 (가상정류장A > 가상정류장B)",
                           "distance": 1300, "time": 400,
                           "stops": [{"name": "가상정류장A"}, {"name": "가상정류장B"}],
                           "vehicles": [{"name": "가상02", "type": "마을"}]},
            "path": {"points": [[127.0, 37.005], [127.0, 37.018]]}
          }]
        }]
      }
      """;

  private static final String WALK_JSON =
      """
      {
        "status": "OK",
        "route": {
          "properties": {"landingUrl": "https://example.invalid", "totalDistance": 300, "totalTime": 280},
          "legs": [{
            "properties": {"distance": 300, "time": 280},
            "steps": [{
              "properties": {"distance": 300, "time": 280, "guidance": "가상빌라까지 300m 이동",
                             "x": 127.0, "y": 37.0},
              "path": {"points": [[127.0, 37.0], [127.0, 37.002]]}
            }]
          }]
        }
      }
      """;

  @Test
  @DisplayName("대중교통 JSON 이 record 로 들어간다 — time 필드, fare.value, 모르는 필드 무시")
  void parsesTransit() {
    body = TRANSIT_JSON;

    KakaoTransitResponse res = client().transit(FROM, TO, "ko");

    assertThat(res.status()).isEqualTo("OK");
    assertThat(res.routes()).hasSize(1);
    KakaoTransitResponse.Route r = res.routes().get(0);
    assertThat(r.properties().totalDistance()).isEqualTo(2000);
    assertThat(r.properties().fare().value()).isEqualTo(1200);
    assertThat(r.properties().fare().min()).isNull();
    KakaoTransitResponse.StepProperties sp = r.steps().get(0).properties();
    assertThat(sp.type()).isEqualTo("BUS");
    assertThat(sp.time()).as("duration 이 아니라 time 이다").isEqualTo(400);
    assertThat(sp.stops()).hasSize(2);
    assertThat(sp.vehicles().get(0).type()).isEqualTo("마을");
    assertThat(r.steps().get(0).path().points().get(0)).containsExactly(127.0, 37.005);
  }

  @Test
  @DisplayName("도보 JSON — route.legs[].steps[] 한 겹 더, x/y 좌표")
  void parsesWalk() {
    body = WALK_JSON;

    KakaoWalkResponse res = client().walk(FROM, TO, "ko");

    assertThat(res.route().properties().totalDistance()).isEqualTo(300);
    KakaoWalkResponse.Step step = res.route().legs().get(0).steps().get(0);
    assertThat(step.properties().guidance()).isEqualTo("가상빌라까지 300m 이동");
    assertThat(step.properties().x()).isEqualTo(127.0);
    assertThat(step.properties().y()).isEqualTo(37.0);
  }

  @Test
  @DisplayName("요금 범위(min/max)도 읽힌다")
  void parsesFareRange() {
    body =
        TRANSIT_JSON.replace(
            "\"fare\": {\"value\": 1200}", "\"fare\": {\"min\": 1500, \"max\": 3200}");

    KakaoTransitResponse.Fare fare =
        client().transit(FROM, TO, "ko").routes().get(0).properties().fare();

    assertThat(fare.value()).isNull();
    assertThat(fare.min()).isEqualTo(1500);
    assertThat(fare.max()).isEqualTo(3200);
  }

  @Test
  @DisplayName("빠진 숫자 필드는 0 이 아니라 null 이다")
  void missingNumbersAreNull() {
    body = TRANSIT_JSON.replace("\"distance\": 1300, ", "");

    KakaoTransitResponse.StepProperties sp =
        client().transit(FROM, TO, "ko").routes().get(0).steps().get(0).properties();

    assertThat(sp.distance()).isNull();
    assertThat(sp.time()).isEqualTo(400);
  }

  @Test
  @DisplayName("카카오가 아는 대로 보낸다 — x 가 경도, y 가 위도, lang, 경로")
  void sendsParamsAsKakaoExpects() {
    body = WALK_JSON;

    client().walk(FROM, TO, "en");

    assertThat(lastRequestUri)
        .startsWith("/v2/routing/walk?")
        .contains("start_x=127.0")
        .contains("start_y=37.0")
        .contains("end_x=127.0")
        .contains("end_y=37.02")
        .contains("lang=en")
        .contains("input_coord=WGS84");
  }

  @Test
  @DisplayName("429 한도 초과 → 503 ROUTING_UNAVAILABLE")
  void quotaExceededIs503() {
    status = 429;
    body = "{\"code\": -10, \"msg\": \"API limit has been exceeded\"}";

    assertUnavailable(() -> client().transit(FROM, TO, "ko"));
  }

  @Test
  @DisplayName("401 키 거부 → 503 (앱에는 같은 얼굴, 로그는 다름)")
  void keyRejectedIs503() {
    status = 401;
    body = "{}";

    assertUnavailable(() -> client().transit(FROM, TO, "ko"));
  }

  @Test
  @DisplayName("카카오 5xx → 503")
  void upstream5xxIs503() {
    status = 502;
    body = "";

    assertUnavailable(() -> client().walk(FROM, TO, "ko"));
  }

  @Test
  @DisplayName("응답이 시간을 넘기면 503")
  void timeoutIs503() {
    body = WALK_JSON;
    delayMs = 2500; // 클라이언트 읽기 타임아웃 1초

    assertUnavailable(() -> client().walk(FROM, TO, "ko"));
  }

  @Test
  @DisplayName("깨진 JSON → 503 — 제공자가 모양을 바꾼 것")
  void malformedBodyIs503() {
    body = "<html>not json</html>";

    assertUnavailable(() -> client().walk(FROM, TO, "ko"));
  }

  @Test
  @DisplayName("400 은 우리가 잘못 보낸 것 — 503 으로 접지 않고 그대로 던진다")
  void badRequestPropagates() {
    status = 400;
    body = "{\"code\": -2, \"msg\": \"sx is required\"}";

    assertThatThrownBy(() -> client().transit(FROM, TO, "ko"))
        .isInstanceOf(HttpClientErrorException.class)
        .isNotInstanceOf(ApiException.class);
  }

  @Test
  @DisplayName("키가 비어 있으면 부르지 않고 503")
  void blankKeyDoesNotCall() {
    KakaoRoutingClient noKey = new KakaoRoutingClient(baseUrl, "  ", 1, ObservationRegistry.NOOP);

    assertUnavailable(() -> noKey.transit(FROM, TO, "ko"));
    assertThat(lastRequestUri).as("카카오에 닿지 않아야 한다").isNull();
  }

  @Test
  @DisplayName("언어 변환 — ko 만 ko, 나머지는 en")
  void toKakaoLang() {
    assertThat(KakaoRoutingClient.toKakaoLang(com.mz2az.scenetrip.sceneapi.api.model.Lang.KO))
        .isEqualTo("ko");
    assertThat(KakaoRoutingClient.toKakaoLang(com.mz2az.scenetrip.sceneapi.api.model.Lang.EN))
        .isEqualTo("en");
    assertThat(KakaoRoutingClient.toKakaoLang(com.mz2az.scenetrip.sceneapi.api.model.Lang.JA))
        .isEqualTo("en");
    assertThat(KakaoRoutingClient.toKakaoLang(com.mz2az.scenetrip.sceneapi.api.model.Lang.ZH_HANT))
        .isEqualTo("en");
  }

  private static void assertUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.getCode()).isEqualTo("ROUTING_UNAVAILABLE");
            });
  }
}
