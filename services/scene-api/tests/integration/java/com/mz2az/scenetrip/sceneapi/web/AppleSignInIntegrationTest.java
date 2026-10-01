package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.AppleSignIn;
import com.mz2az.scenetrip.sceneapi.api.model.AuthSession;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleClient;
import com.mz2az.scenetrip.sceneapi.auth.AppleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.GoogleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.auth.TokenCipher;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * {@code POST /auth/apple} 부터 {@code DELETE /me} 의 애플 끊기까지 — 진짜 검증기(이 자리의 키 묶음), 진짜 {@link
 * AppleClient}(로컬 가짜 애플 서버), 진짜 {@link TokenCipher}, 진짜 Store, 진짜 PostgreSQL — 명세(계약 {@code
 * /auth/apple}·{@code DELETE /me}, 계획 §5·§6, MZ2AZ-337)에 비춰 본다.
 *
 * <p>애플을 부르지 않는다. identityToken 은 이 자리에서 만든 RSA 키로, client secret 은 일회용 EC P-256 키로 서명하고, 코드 교환·연결
 * 끊기는 JDK 내장 {@code HttpServer} 가 받는다.
 */
@DisplayName("애플 로그인 — 검증·교환부터 탈퇴의 연결 끊기까지 (실제 DB)")
class AppleSignInIntegrationTest {

  private static final String BUNDLE_ID = "com.mz2az.scenetrip";
  private static final byte[] SECRET = bytes(32, (byte) 23);
  private static final byte[] CIPHER_KEY = bytes(32, (byte) 29);
  private static final String RAW_NONCE = "apple-e2e-raw-nonce-0123456789";

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;
  private static RSAKey appleKey;
  private static ECPrivateKey clientSecretKey;

  private static HttpServer fakeApple;
  private static String fakeAppleUrl;
  private static final List<Map<String, String>> tokenRequests =
      Collections.synchronizedList(new ArrayList<>());
  private static final List<Map<String, String>> revokeRequests =
      Collections.synchronizedList(new ArrayList<>());
  private static volatile String issuedRefreshToken;

  private final Clock clock =
      Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens accessTokens = new AccessTokens(SECRET, Duration.ofMinutes(30), clock);
  private final RefreshTokenStore refreshTokens =
      new RefreshTokenStore(jdbc, transactions, Duration.ofDays(60), clock);
  private final CurrentAccount accounts = new CurrentAccount(request, accessTokens, users);
  private final AccountLinkStore links = new AccountLinkStore(jdbc);
  private final SignInService signIn = new SignInService(users, links, refreshTokens, transactions);
  private final TokenCipher cipher = new TokenCipher(CIPHER_KEY);
  private final AuthController controller = controller();

  private final Set<UUID> createdUsers = new LinkedHashSet<>();
  private final Set<String> subjects = new LinkedHashSet<>();

  @BeforeAll
  static void start() throws Exception {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
    appleKey = new RSAKeyGenerator(2048).keyID("apple-e2e-key").generate();
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    clientSecretKey = (ECPrivateKey) ec.generateKeyPair().getPrivate();

    fakeApple = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeApple.createContext(
        "/auth/token",
        exchange -> {
          tokenRequests.add(form(exchange.getRequestBody().readAllBytes()));
          respond(exchange, 200, "{\"refresh_token\":\"" + issuedRefreshToken + "\"}");
        });
    fakeApple.createContext(
        "/auth/revoke",
        exchange -> {
          revokeRequests.add(form(exchange.getRequestBody().readAllBytes()));
          respond(exchange, 200, "");
        });
    fakeApple.setExecutor(Executors.newCachedThreadPool());
    fakeApple.start();
    fakeAppleUrl = "http://127.0.0.1:" + fakeApple.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    fakeApple.stop(0);
  }

  @BeforeEach
  void reset() {
    tokenRequests.clear();
    revokeRequests.clear();
    issuedRefreshToken = "r.e2e-" + UUID.randomUUID();
  }

  @AfterEach
  void cleanUp() {
    Set<UUID> all = new LinkedHashSet<>(createdUsers);
    for (String subject : subjects) {
      jdbc.sql("SELECT user_id FROM user_identity WHERE subject = :s")
          .param("s", subject)
          .query(UUID.class)
          .list()
          .forEach(all::add);
    }
    for (UUID id : all) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  @Test
  @DisplayName(
      "비회원이 애플로 가입(이름 포함) → isNewUser=true, 「김철수」 저장, 암호문 저장 → 탈퇴하면 가짜 애플이 원래 refresh token 으로"
          + " revoke 를 받고 계정이 사라진다")
  void signUpThenDeleteRevokesAtApple() {
    UUID install = UUID.randomUUID();
    UUID guest = users.resolve(install);
    createdUsers.add(guest);
    String subject = newSubject();

    ResponseEntity<AuthSession> response =
        controller.signInWithApple(
            install,
            new AppleSignIn(identityToken(subject, sha256Hex(RAW_NONCE)), "c.e2e-code", RAW_NONCE)
                .givenName("철수")
                .familyName("김"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    AuthSession session = response.getBody();
    assertThat(session).isNotNull();
    assertThat(session.getIsNewUser()).isTrue();
    assertThat(session.getMerged()).isFalse();
    assertThat(session.getUser().getId()).isEqualTo(guest);
    assertThat(session.getUser().getDisplayName()).isEqualTo("김철수");
    assertThat(displayNameOf(subject)).isEqualTo("김철수");
    assertThat(tokenRequests).hasSize(1);
    assertThat(tokenRequests.get(0))
        .containsEntry("code", "c.e2e-code")
        .containsEntry("grant_type", "authorization_code")
        .containsEntry("client_id", BUNDLE_ID);
    byte[] stored = storedCipherText(subject);
    assertThat(stored).isNotNull();
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(issuedRefreshToken);
    assertThat(cipher.decrypt(stored)).isEqualTo(issuedRefreshToken);

    request.addHeader("Authorization", "Bearer " + session.getAccessToken());
    ResponseEntity<Void> deleted = controller.deleteMe();

    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(revokeRequests).hasSize(1);
    assertThat(revokeRequests.get(0))
        .containsEntry("token", issuedRefreshToken)
        .containsEntry("token_type_hint", "refresh_token")
        .containsEntry("client_id", BUNDLE_ID)
        .containsKey("client_secret");
    assertThat(userExists(guest)).isFalse();
    assertThat(identityCount(subject)).isZero();
  }

  @Test
  @DisplayName("nonce 가 맞지 않으면 401 — 가짜 애플의 코드 교환도 부르지 않고 아무도 가입되지 않는다")
  void badNonceNeverExchanges() {
    UUID install = UUID.randomUUID();
    UUID guest = users.resolve(install);
    createdUsers.add(guest);
    String subject = newSubject();

    // 해시가 아니라 원문을 토큰에 넣은 경우 — 명세상 거절된다.
    assertThatThrownBy(
            () ->
                controller.signInWithApple(
                    install,
                    new AppleSignIn(identityToken(subject, RAW_NONCE), "c.e2e-code", RAW_NONCE)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(e.getCode()).isEqualTo("SOCIAL_TOKEN_INVALID");
            });

    assertThat(tokenRequests).isEmpty();
    assertThat(identityCount(subject)).isZero();
    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(guest, false));
  }

  // ───────────── 도우미 ─────────────

  private AuthController controller() {
    GoogleIdTokenVerifier google =
        new GoogleIdTokenVerifier(new ImmutableJWKSet<>(new JWKSet()), List.of(), clock);
    AppleLogin apple =
        new AppleLogin(
            new AppleIdTokenVerifier(
                new ImmutableJWKSet<>(new JWKSet(appleKey.toPublicJWK())), BUNDLE_ID, clock),
            new AppleClient(
                RestClient.builder().baseUrl(fakeAppleUrl).build(),
                BUNDLE_ID,
                "TEAMFIXTUR",
                "KEYFIXTURE",
                clientSecretKey,
                clock),
            cipher,
            links);
    return new AuthController(accessTokens, refreshTokens, users, accounts, google, signIn, apple);
  }

  private String identityToken(String subject, String nonceClaim) {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://appleid.apple.com")
            .audience(BUNDLE_ID)
            .subject(subject)
            .issueTime(Date.from(clock.instant()))
            .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(10))))
            .claim("nonce", nonceClaim)
            .claim("email", "e2e@privaterelay.appleid.com")
            .claim("email_verified", "true")
            .build();
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(appleKey.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(appleKey));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private String newSubject() {
    String subject = "apple-e2e-" + UUID.randomUUID();
    subjects.add(subject);
    return subject;
  }

  private static String sha256Hex(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] storedCipherText(String subject) {
    return jdbc.sql("SELECT apple_refresh_token_enc FROM user_identity WHERE subject = :s")
        .param("s", subject)
        .query((rs, n) -> rs.getBytes(1))
        .single();
  }

  private static String displayNameOf(String subject) {
    return jdbc.sql("SELECT display_name FROM user_identity WHERE subject = :s")
        .param("s", subject)
        .query(String.class)
        .single();
  }

  private static long identityCount(String subject) {
    return jdbc.sql("SELECT count(*) FROM user_identity WHERE subject = :s")
        .param("s", subject)
        .query(Long.class)
        .single();
  }

  private static boolean userExists(UUID id) {
    return jdbc.sql("SELECT count(*) FROM app_user WHERE id = CAST(:id AS UUID)")
            .param("id", id.toString())
            .query(Long.class)
            .single()
        > 0;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> form(byte[] raw) {
    Map<String, String> form = new LinkedHashMap<>();
    String text = new String(raw, StandardCharsets.UTF_8);
    if (text.isEmpty()) {
      return form;
    }
    for (String pair : text.split("&")) {
      int eq = pair.indexOf('=');
      form.put(
          URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
          eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return form;
  }

  private static byte[] bytes(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }
}
