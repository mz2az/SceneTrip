package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.auth.SocialTokenException.Reason;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 애플 서버로 나가는 두 요청(코드 교환·연결 끊기)을 명세({@code AppleClient} 클래스 설명, 계약 {@code /auth/apple}·{@code DELETE
 * /me}, 계획 §4·§6, MZ2AZ-337)에 비춰 본다.
 *
 * <p>애플을 부르지 않는다. JDK 내장 {@code HttpServer} 로 가짜 애플을 띄우고({@code KakaoRoutingClientTest} 와 같은 방식),
 * client secret 은 이 자리에서 만든 일회용 EC P-256 키로 서명한다.
 */
@DisplayName("AppleClient — 애플 코드 교환·연결 끊기")
class AppleClientTest {

  private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
  private static final String CLIENT_ID = "com.mz2az.scenetrip";
  private static final String TEAM_ID = "TEAMFIXTUR";
  private static final String KEY_ID = "KEYFIXTURE";

  private static HttpServer server;
  private static String baseUrl;
  private static KeyPair keyPair;

  private static volatile int status;
  private static volatile String body;
  private static final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());

  record Recorded(String method, String path, String contentType, Map<String, String> form) {}

  @BeforeAll
  static void start() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    keyPair = generator.generateKeyPair();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new Recorded(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("Content-Type"),
                  parseForm(raw)));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @BeforeEach
  void reset() {
    status = 200;
    body = "{}";
    requests.clear();
  }

  // ───────────── 켜짐 ─────────────

  @Test
  @DisplayName("개인 키가 있으면 enabled, 없으면 꺼진다")
  void enabledOnlyWithKey() {
    assertThat(client().enabled()).isTrue();
    assertThat(clientWithoutKey().enabled()).isFalse();
  }

  // ───────────── 코드 교환 ─────────────

  @Test
  @DisplayName(
      "교환은 /auth/token 에 폼으로 client_id·client_secret·code·grant_type 을 보내고 refresh_token 을 돌려준다")
  void exchangePostsFormAndReturnsRefreshToken() throws Exception {
    body =
        """
        {"access_token":"a.fixture","token_type":"Bearer","expires_in":3600,
         "refresh_token":"r.fixture-refresh-token","id_token":"x.y.z","extra":"ignored"}
        """;

    String refresh = client().exchange("c.fixture-authorization-code");

    assertThat(refresh).isEqualTo("r.fixture-refresh-token");
    assertThat(requests).hasSize(1);
    Recorded r = requests.get(0);
    assertThat(r.method()).isEqualTo("POST");
    assertThat(r.path()).isEqualTo("/auth/token");
    assertThat(r.contentType()).startsWith("application/x-www-form-urlencoded");
    assertThat(r.form())
        .containsEntry("client_id", CLIENT_ID)
        .containsEntry("code", "c.fixture-authorization-code")
        .containsEntry("grant_type", "authorization_code")
        .containsKey("client_secret");
    assertValidClientSecret(r.form().get("client_secret"));
  }

  @Test
  @DisplayName("애플이 400 invalid_grant(만료·재사용 코드)면 INVALID")
  void badRequestIsInvalid() {
    status = 400;
    body = "{\"error\":\"invalid_grant\"}";

    assertThat(exchangeReason("c.used")).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("애플이 5xx 면 UNAVAILABLE")
  void serverErrorIsUnavailable() {
    for (int code : new int[] {500, 502, 503}) {
      status = code;
      body = "{}";
      assertThat(exchangeReason("c.any")).as("status %d", code).isEqualTo(Reason.UNAVAILABLE);
    }
  }

  @Test
  @DisplayName("200 인데 refresh_token 이 없으면 UNAVAILABLE")
  void missingRefreshTokenIsUnavailable() {
    body = "{\"access_token\":\"a.fixture\",\"token_type\":\"Bearer\"}";

    assertThat(exchangeReason("c.any")).isEqualTo(Reason.UNAVAILABLE);
  }

  @Test
  @DisplayName("애플에 닿지 못하면(연결 거부) UNAVAILABLE")
  void connectionFailureIsUnavailable() throws IOException {
    AppleClient unreachable = clientAt(closedPortUrl());

    assertThatThrownBy(() -> unreachable.exchange("c.any"))
        .isInstanceOfSatisfying(
            SocialTokenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
  }

  // ───────────── 연결 끊기 ─────────────

  @Test
  @DisplayName(
      "revoke 는 /auth/revoke 에 폼으로 client_id·client_secret·token·token_type_hint 를 보내고 2xx 면 true")
  void revokePostsFormAndReturnsTrue() throws Exception {
    body = "";

    boolean revoked = client().revoke("r.fixture-refresh-token");

    assertThat(revoked).isTrue();
    assertThat(requests).hasSize(1);
    Recorded r = requests.get(0);
    assertThat(r.method()).isEqualTo("POST");
    assertThat(r.path()).isEqualTo("/auth/revoke");
    assertThat(r.contentType()).startsWith("application/x-www-form-urlencoded");
    assertThat(r.form())
        .containsEntry("client_id", CLIENT_ID)
        .containsEntry("token", "r.fixture-refresh-token")
        .containsEntry("token_type_hint", "refresh_token");
    assertValidClientSecret(r.form().get("client_secret"));
  }

  @Test
  @DisplayName("revoke 는 실패해도 던지지 않고 false — 400·500·연결 거부")
  void revokeNeverThrows() throws IOException {
    for (int code : new int[] {400, 401, 500, 503}) {
      status = code;
      body = "{\"error\":\"invalid_request\"}";
      boolean[] result = new boolean[1];
      assertThatCode(() -> result[0] = client().revoke("r.any")).doesNotThrowAnyException();
      assertThat(result[0]).as("status %d", code).isFalse();
    }

    AppleClient unreachable = clientAt(closedPortUrl());
    boolean[] result = new boolean[1];
    assertThatCode(() -> result[0] = unreachable.revoke("r.any")).doesNotThrowAnyException();
    assertThat(result[0]).isFalse();
  }

  @Test
  @DisplayName("개인 키가 없으면 revoke 는 던지지 않고 false")
  void revokeWithoutKeyIsFalse() {
    boolean[] result = new boolean[1];
    assertThatCode(() -> result[0] = clientWithoutKey().revoke("r.any")).doesNotThrowAnyException();
    assertThat(result[0]).isFalse();
  }

  @Test
  @DisplayName("client secret 은 요청마다 새로 만든다 — 시계가 가면 iat 도 간다")
  void clientSecretFollowsClock() throws Exception {
    Instant later = T0.plus(Duration.ofHours(3));
    AppleClient c =
        new AppleClient(
            RestClient.builder().baseUrl(baseUrl).build(),
            CLIENT_ID,
            TEAM_ID,
            KEY_ID,
            (ECPrivateKey) keyPair.getPrivate(),
            Clock.fixed(later, ZoneOffset.UTC));
    c.revoke("r.any");

    JWTClaimsSet claims =
        SignedJWT.parse(requests.get(0).form().get("client_secret")).getJWTClaimsSet();
    assertThat(claims.getIssueTime().toInstant()).isEqualTo(later);
  }

  // ───────────── 개인 키 읽기 ─────────────

  @Test
  @DisplayName("parsePrivateKey — PKCS#8 PEM 의 base64 를 읽어 같은 키를 돌려준다")
  void parsesBase64Pem() {
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    String base64Pem = Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));

    ECPrivateKey parsed = AppleClient.parsePrivateKey(base64Pem);

    assertThat(parsed).isNotNull();
    assertThat(parsed.getS()).isEqualTo(((ECPrivateKey) keyPair.getPrivate()).getS());
  }

  @Test
  @DisplayName("parsePrivateKey — 비었으면 null(애플 로그인 꺼짐)")
  void blankIsNull() {
    assertThat(AppleClient.parsePrivateKey("")).isNull();
    assertThat(AppleClient.parsePrivateKey("   ")).isNull();
    assertThat(AppleClient.parsePrivateKey(null)).isNull();
  }

  @Test
  @DisplayName("parsePrivateKey — 형식이 틀리면 IllegalArgumentException 이고 메시지에 값을 싣지 않는다")
  void garbageIsRejectedWithoutEcho() {
    String notBase64 = "FIXTURE*not*base64*apple*key!!";
    String notPem =
        Base64.getEncoder().encodeToString("FIXTURE plain text".getBytes(StandardCharsets.UTF_8));
    String pemWithGarbage =
        Base64.getEncoder()
            .encodeToString(
                ("-----BEGIN PRIVATE KEY-----\nRklYVFVSRWJhZGtleQ==\n-----END PRIVATE KEY-----\n")
                    .getBytes(StandardCharsets.US_ASCII));

    for (String bad : List.of(notBase64, notPem, pemWithGarbage)) {
      assertThatThrownBy(() -> AppleClient.parsePrivateKey(bad))
          .as("input %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(bad));
    }
  }

  // ───────────── 도우미 ─────────────

  private static void assertValidClientSecret(String secret) throws Exception {
    assertThat(secret).isNotBlank();
    SignedJWT jwt = SignedJWT.parse(secret);

    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    assertThat(jwt.getHeader().getKeyID()).isEqualTo(KEY_ID);
    assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic()))).isTrue();
    JWTClaimsSet claims = jwt.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo(TEAM_ID);
    assertThat(claims.getSubject()).isEqualTo(CLIENT_ID);
    assertThat(claims.getAudience()).containsExactly("https://appleid.apple.com");
    assertThat(claims.getIssueTime()).isNotNull();
    assertThat(claims.getExpirationTime()).isNotNull();
    Instant iat = claims.getIssueTime().toInstant();
    Instant exp = claims.getExpirationTime().toInstant();
    assertThat(iat).isEqualTo(T0);
    assertThat(exp).isAfter(iat);
    assertThat(Duration.between(iat, exp)).isLessThanOrEqualTo(Duration.ofMinutes(5));
  }

  private Reason exchangeReason(String code) {
    try {
      String accepted = client().exchange(code);
      throw new AssertionError("실패해야 하는데 받았다: " + accepted);
    } catch (SocialTokenException e) {
      return e.reason();
    }
  }

  private static AppleClient client() {
    return clientAt(baseUrl);
  }

  private static AppleClient clientAt(String url) {
    return new AppleClient(
        RestClient.builder().baseUrl(url).build(),
        CLIENT_ID,
        TEAM_ID,
        KEY_ID,
        (ECPrivateKey) keyPair.getPrivate(),
        CLOCK);
  }

  private static AppleClient clientWithoutKey() {
    return new AppleClient(
        RestClient.builder().baseUrl(baseUrl).build(), CLIENT_ID, TEAM_ID, KEY_ID, null, CLOCK);
  }

  /** 열었다 닫은 포트 — 연결이 거부된다. */
  private static String closedPortUrl() throws IOException {
    // HttpServer 는 start 하지 않은 채 stop 하면 멈춘다 — 소켓으로 포트만 잡았다 놓는다.
    int port;
    try (java.net.ServerSocket socket =
        new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    return "http://127.0.0.1:" + port;
  }

  private static Map<String, String> parseForm(String raw) {
    Map<String, String> form = new LinkedHashMap<>();
    if (raw.isEmpty()) {
      return form;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value =
          eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      form.put(key, value);
    }
    return form;
  }
}
