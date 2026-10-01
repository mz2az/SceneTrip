package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.AuthSession;
import com.mz2az.scenetrip.sceneapi.api.model.GoogleSignIn;
import com.mz2az.scenetrip.sceneapi.api.model.RefreshTokenBody;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.GoogleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /auth/google} 을 처음부터 끝까지 — 진짜 검증기(이 자리에서 만든 키 묶음), 진짜 Store, 진짜 PostgreSQL — 명세(계약
 * {@code /auth/google}·{@code /auth/refresh}·「인증」 절, 계획 §3·§5)에 비춰 본다.
 *
 * <p>구글을 부르지 않는다. 검증기에 공개키 한 장짜리 키 묶음을 주고, 그 짝 개인키로 「구글처럼 생긴」 ID 토큰을 만든다. 컨트롤러는 {@code
 * AuthFlowIntegrationTest} 처럼 손으로 조립한다.
 */
@DisplayName("구글 로그인 — 검증부터 계정까지 (실제 DB)")
class GoogleSignInIntegrationTest {

  private static final String WEB_CLIENT =
      "700188854872-3dl36cm33ndb2m1e8j04svnrnjpjleep.apps.googleusercontent.com";
  private static final byte[] SECRET = bytes(32, (byte) 13);
  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final Duration REFRESH_TTL = Duration.ofDays(60);
  private static final String NONCE = "e2e-nonce-0123456789abcdef";

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;
  private static RSAKey googleKey;
  private static long[] places;

  private final Clock clock =
      Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens accessTokens = new AccessTokens(SECRET, ACCESS_TTL, clock);
  private final RefreshTokenStore refreshTokens =
      new RefreshTokenStore(jdbc, transactions, REFRESH_TTL, clock);
  private final CurrentAccount accounts = new CurrentAccount(request, accessTokens, users);
  private final SignInService signIn =
      new SignInService(users, new AccountLinkStore(jdbc), refreshTokens, transactions);
  private final AuthController controller = controllerWith(keys());

  private final Set<UUID> createdUsers = new LinkedHashSet<>();
  private final Set<UUID> installs = new LinkedHashSet<>();
  private final Set<String> subjects = new LinkedHashSet<>();

  @BeforeAll
  static void connect() throws JOSEException {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
    googleKey = new RSAKeyGenerator(2048).keyID("e2e-key").generate();
    places =
        jdbc.sql("SELECT id FROM place ORDER BY id LIMIT 3").query(Long.class).list().stream()
            .mapToLong(Long::longValue)
            .toArray();
    assertThat(places).hasSize(3);
  }

  @AfterEach
  void cleanUp() {
    Set<UUID> all = new LinkedHashSet<>(createdUsers);
    for (UUID install : installs) {
      deviceOwner(install).ifPresent(all::add);
    }
    for (String subject : subjects) {
      jdbc.sql("SELECT user_id FROM user_identity WHERE subject = :s")
          .param("s", subject)
          .query(UUID.class)
          .list()
          .forEach(all::add);
    }
    // merged_into 에는 CASCADE 가 없어 가리키는 쪽을 먼저 끊어야 지울 수 있다.
    for (UUID id : all) {
      jdbc.sql(
              "UPDATE app_user SET merged_into = NULL"
                  + " WHERE id = CAST(:id AS UUID) OR merged_into = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
    for (UUID id : all) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  @Test
  @DisplayName(
      "장바구니가 있는 비회원이 구글로 가입 — isNewUser=true, 받은 액세스 토큰으로 같은 계정·같은 장바구니, 토큰 없이 설치 UUID 만 오면"
          + " SESSION_REQUIRED")
  void guestSignsUpAndKeepsCart() {
    UUID install = newInstall();
    UUID guest = guestOf(install);
    savePlace(guest, places[0]);
    String subject = newSubject();

    AuthSession session = signInOk(install, idToken(subject, NONCE), NONCE);

    assertThat(session.getIsNewUser()).isTrue();
    assertThat(session.getMerged()).isFalse();
    assertThat(session.getUser().getId()).isEqualTo(guest);
    assertThat(session.getUser().getEmail()).isEqualTo("e2e@example.com");
    assertThat(session.getUser().getDisplayName()).isEqualTo("끝까지");
    assertThat(session.getUser().getRegisteredAt()).isNotNull();
    assertThat(session.getAccessTokenExpiresIn().longValue()).isEqualTo(ACCESS_TTL.toSeconds());
    assertThat(session.getRefreshTokenExpiresIn().longValue()).isEqualTo(REFRESH_TTL.toSeconds());

    withBearer(session.getAccessToken());
    assertThat(accounts.resolve(install)).isEqualTo(guest);
    assertThat(savedPlaces(guest)).containsExactly(places[0]);

    withoutAuthorization();
    assertThatThrownBy(() -> accounts.resolve(install))
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED"));
  }

  @Test
  @DisplayName("이미 가입한 구글 계정으로 다른 설치본에서 로그인 — merged=true, 그 설치본의 장바구니가 합쳐지고 액세스 토큰은 그 계정")
  void secondInstallMergesIntoExistingAccount() {
    String subject = newSubject();
    UUID installA = newInstall();
    UUID x = guestOf(installA);
    savePlace(x, places[0]);
    signInOk(installA, idToken(subject, NONCE), NONCE);
    UUID installB = newInstall();
    UUID g = guestOf(installB);
    savePlace(g, places[1]);

    AuthSession session = signInOk(installB, idToken(subject, NONCE), NONCE);

    assertThat(session.getMerged()).isTrue();
    assertThat(session.getIsNewUser()).isFalse();
    assertThat(session.getUser().getId()).isEqualTo(x);
    withBearer(session.getAccessToken());
    assertThat(accounts.resolve(installB)).isEqualTo(x);
    assertThat(savedPlaces(x)).containsExactlyInAnyOrder(places[0], places[1]);
    assertThat(savedPlaces(g)).isEmpty();
  }

  @Test
  @DisplayName("nonce 가 다른 토큰이면 401 SOCIAL_TOKEN_INVALID — 이 설치본의 비회원은 가입되지 않는다")
  void nonceMismatchRegistersNothing() {
    UUID install = newInstall();
    UUID guest = guestOf(install);
    String subject = newSubject();

    assertThatThrownBy(
            () ->
                controller.signInWithGoogle(
                    install,
                    new GoogleSignIn(idToken(subject, "nonce-from-another-attempt"), NONCE)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "SOCIAL_TOKEN_INVALID"));

    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(guest, false));
    assertThat(identityCount(subject)).isZero();
  }

  @Test
  @DisplayName("구글 공개키를 받아 오지 못하면 503 AUTH_PROVIDER_UNAVAILABLE — 아무것도 가입되지 않는다")
  void unreachableProviderRegistersNothing() {
    JWKSource<SecurityContext> down =
        (selector, context) -> {
          throw new RemoteKeySourceException("connect timed out", null);
        };
    AuthController offline = controllerWith(down);
    UUID install = newInstall();
    UUID guest = guestOf(install);
    String subject = newSubject();

    assertThatThrownBy(
            () ->
                offline.signInWithGoogle(install, new GoogleSignIn(idToken(subject, NONCE), NONCE)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> expect(e, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE"));

    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(guest, false));
    assertThat(identityCount(subject)).isZero();
  }

  @Test
  @DisplayName("합친 뒤 G 에 늦게 떨어진 장바구니를 /auth/refresh 가 그 계정으로 쓸어 온다")
  void refreshSweepsStragglerIntoAccount() {
    String subject = newSubject();
    UUID installA = newInstall();
    UUID x = guestOf(installA);
    signInOk(installA, idToken(subject, NONCE), NONCE);
    UUID installB = newInstall();
    UUID g = guestOf(installB);
    AuthSession merged = signInOk(installB, idToken(subject, NONCE), NONCE);
    assertThat(merged.getMerged()).isTrue();
    // 「알려진 틈」 — 합치기 전에 계정을 G 로 정한 요청이 합친 뒤에 쓴다.
    savePlace(g, places[2]);
    assertThat(savedPlaces(x)).doesNotContain(places[2]);

    ResponseEntity<AuthSession> refreshed =
        controller.refreshSession(new RefreshTokenBody(merged.getRefreshToken()));

    assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refreshed.getBody().getMerged()).isFalse();
    assertThat(refreshed.getBody().getIsNewUser()).isFalse();
    assertThat(savedPlaces(x)).contains(places[2]);
    assertThat(savedPlaces(g)).isEmpty();
  }

  // ───────────── 도우미 ─────────────

  private JWKSource<SecurityContext> keys() {
    return new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK()));
  }

  private AuthController controllerWith(JWKSource<SecurityContext> keySource) {
    GoogleIdTokenVerifier verifier =
        new GoogleIdTokenVerifier(keySource, List.of(WEB_CLIENT), clock);
    return new AuthController(accessTokens, refreshTokens, users, accounts, verifier, signIn);
  }

  private AuthSession signInOk(UUID install, String idToken, String nonce) {
    ResponseEntity<AuthSession> response =
        controller.signInWithGoogle(install, new GoogleSignIn(idToken, nonce));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private String idToken(String subject, String nonce) {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://accounts.google.com")
            .audience(WEB_CLIENT)
            .subject(subject)
            .issueTime(Date.from(clock.instant()))
            .expirationTime(Date.from(clock.instant().plus(Duration.ofHours(1))))
            .claim("nonce", nonce)
            .claim("email", "e2e@example.com")
            .claim("email_verified", true)
            .claim("name", "끝까지")
            .build();
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(googleKey.getKeyID()).build(),
              claims);
      jwt.sign(new RSASSASigner(googleKey));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private String newSubject() {
    String subject = "e2e-" + UUID.randomUUID();
    subjects.add(subject);
    return subject;
  }

  private UUID newInstall() {
    UUID install = UUID.randomUUID();
    installs.add(install);
    return install;
  }

  private UUID guestOf(UUID install) {
    UUID id = users.resolve(install);
    createdUsers.add(id);
    return id;
  }

  private void withBearer(String accessToken) {
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + accessToken);
  }

  private void withoutAuthorization() {
    request.removeHeader("Authorization");
  }

  private static void expect(ApiException e, HttpStatus status, String code) {
    assertThat(e.getStatus()).isEqualTo(status);
    assertThat(e.getCode()).isEqualTo(code);
  }

  private static Optional<UUID> deviceOwner(UUID install) {
    return jdbc.sql("SELECT user_id FROM user_device WHERE install_uuid = CAST(:i AS UUID)")
        .param("i", install.toString())
        .query(UUID.class)
        .optional();
  }

  private static long identityCount(String subject) {
    return jdbc.sql("SELECT count(*) FROM user_identity WHERE subject = :s")
        .param("s", subject)
        .query(Long.class)
        .single();
  }

  private static List<Long> savedPlaces(UUID userId) {
    return jdbc.sql("SELECT place_id FROM saved_place WHERE user_id = CAST(:id AS UUID)")
        .param("id", userId.toString())
        .query(Long.class)
        .list();
  }

  private static void savePlace(UUID userId, long placeId) {
    jdbc.sql("INSERT INTO saved_place (user_id, place_id) VALUES (CAST(:u AS UUID), :p)")
        .param("u", userId.toString())
        .param("p", placeId)
        .update();
  }

  private static byte[] bytes(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }
}
