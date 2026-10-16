package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleClient;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.auth.TokenCipher;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code DELETE /me} 를 비회원도 부른다(계약 1.22.0, MZ2AZ-467) — 진짜 Store 와 진짜 PostgreSQL 로 본다.
 *
 * <p>명세에서 나온 기대:
 *
 * <ul>
 *   <li>토큰이 있으면 회원 탈퇴 — 토큰의 계정을 지우고 {@code X-Install-Id} 는 무시한다.
 *   <li>토큰 없이 {@code X-Install-Id} 만 있으면 그 설치본의 비회원 계정과 그 기록(장바구니·코스·찜·기기 연결)을 지우고 204.
 *   <li>그 설치본이 가입 계정의 것이면 토큰 없이는 401 {@code SESSION_REQUIRED} 이고 아무것도 지우지 않는다.
 *   <li>처음 보는 설치본이면 비회원 계정이 생겼다가 지워져 204 — 남는 것이 없다.
 *   <li>토큰도 {@code X-Install-Id} 도 없으면 401 {@code ACCESS_TOKEN_INVALID}, 아무것도 지우지 않는다.
 *   <li>만료되거나 깨진 토큰은 예전처럼 401({@code ACCESS_TOKEN_EXPIRED}·{@code ACCESS_TOKEN_INVALID}) — 비회원 지우기로
 *       넘어가지 않는다.
 * </ul>
 *
 * <p>스프링 컨텍스트 없이 {@link AuthController} 와 {@link CurrentAccount} 를 손으로 조립한다({@code
 * AuthFlowIntegrationTest} 와 같은 방식). 전부 되돌리는 트랜잭션 하나 안에서 돈다 — Store 가 같은 DataSource 를 써서 이 트랜잭션에
 * 들어온다. 그래서 정리 코드가 없고, 시험용 장소·작품도 끝나면 사라진다.
 */
@DisplayName("DELETE /me — 비회원 기록 지우기와 회원 탈퇴 (실제 DB)")
class GuestDeleteMeIntegrationTest {

  private static final byte[] SECRET = bytes(32, (byte) 7);
  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final Duration REFRESH_TTL = Duration.ofDays(60);

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;

  private final Clock clock = Clock.systemUTC();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens accessTokens = new AccessTokens(SECRET, ACCESS_TTL, clock);
  private final RefreshTokenStore refreshTokens =
      new RefreshTokenStore(jdbc, transactions, REFRESH_TTL, clock);
  private final CurrentAccount accounts = new CurrentAccount(request, accessTokens, users);
  private final SignInService signIn =
      new SignInService(users, new AccountLinkStore(jdbc), refreshTokens, transactions);
  private final AuthController controller =
      new AuthController(
          accessTokens, refreshTokens, users, accounts, null, signIn, disabledApple());

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
  }

  @Test
  @DisplayName("토큰 없이 비회원 설치본이 부르면 204 — 비회원 계정과 기기 연결·장바구니·코스·찜이 사라진다")
  void guestInstallWithoutTokenDeletesGuestAndRecords() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          long content = content();
          UUID install = UUID.randomUUID();
          UUID guest = users.resolve(install);
          cart(guest, place);
          long course = course(guest);
          favorite(guest, content);

          ResponseEntity<Void> response = controller.deleteMe(install);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(count("app_user", "id", guest)).isZero();
          assertThat(count("user_device", "install_uuid", install)).isZero();
          assertThat(count("user_device", "user_id", guest)).isZero();
          assertThat(count("saved_place", "user_id", guest)).isZero();
          assertThat(count("saved_content", "user_id", guest)).isZero();
          assertThat(courseExists(course)).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("토큰 없이 가입 계정의 설치본이 부르면 401 SESSION_REQUIRED — 계정·기기 연결·장바구니·코스·찜이 그대로")
  void memberInstallWithoutTokenIsSessionRequired() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          long content = content();
          UUID install = UUID.randomUUID();
          UUID member = member(install);
          cart(member, place);
          long course = course(member);
          favorite(member, content);

          assertThatThrownBy(() -> controller.deleteMe(install))
              .isInstanceOfSatisfying(
                  ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED"));

          assertThat(count("app_user", "id", member)).isEqualTo(1);
          assertThat(count("user_device", "install_uuid", install)).isEqualTo(1);
          assertThat(count("saved_place", "user_id", member)).isEqualTo(1);
          assertThat(count("saved_content", "user_id", member)).isEqualTo(1);
          assertThat(courseExists(course)).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("처음 보는 설치본이면 204 — 그 설치본으로 남는 계정·기기 연결이 없다")
  void unseenInstallLeavesNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();
          long before = appUserCount();

          ResponseEntity<Void> response = controller.deleteMe(install);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(count("user_device", "install_uuid", install)).isZero();
          assertThat(appUserCount()).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("토큰도 X-Install-Id 도 없으면 401 ACCESS_TOKEN_INVALID — 아무것도 지우지 않는다")
  void neitherTokenNorInstallIsInvalid() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID guestInstall = UUID.randomUUID();
          UUID guest = users.resolve(guestInstall);
          long before = appUserCount();

          assertThatThrownBy(() -> controller.deleteMe(null))
              .isInstanceOfSatisfying(
                  ApiException.class,
                  e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID"));

          assertThat(appUserCount()).isEqualTo(before);
          assertThat(count("app_user", "id", guest)).isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("토큰이 있으면 회원 탈퇴 — 다른 비회원 설치본을 실어도 토큰의 계정만 지우고 그 비회원은 남는다")
  void tokenPathIgnoresInstallId() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID memberInstall = UUID.randomUUID();
          UUID member = member(memberInstall);
          UUID guestInstall = UUID.randomUUID();
          UUID guest = users.resolve(guestInstall);
          withBearer(accessTokens.issue(member).value());

          ResponseEntity<Void> response = controller.deleteMe(guestInstall);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(count("app_user", "id", member)).isZero();
          assertThat(count("user_identity", "user_id", member)).isZero();
          assertThat(count("app_user", "id", guest)).isEqualTo(1);
          assertThat(count("user_device", "install_uuid", guestInstall)).isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("토큰이 있고 X-Install-Id 가 없어도(null) 회원 탈퇴 — 204")
  void tokenPathWithoutInstallId() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID member = member(UUID.randomUUID());
          withBearer(accessTokens.issue(member).value());

          assertThat(controller.deleteMe(null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(count("app_user", "id", member)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("만료된 토큰이면 비회원 설치본을 실어도 401 ACCESS_TOKEN_EXPIRED — 회원도 비회원도 그대로")
  void expiredTokenDoesNotFallBackToGuest() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID member = member(UUID.randomUUID());
          UUID guestInstall = UUID.randomUUID();
          UUID guest = users.resolve(guestInstall);
          AccessTokens past =
              new AccessTokens(
                  SECRET,
                  ACCESS_TTL,
                  Clock.fixed(
                      Instant.now().minus(ACCESS_TTL).minus(Duration.ofMinutes(5)),
                      ZoneOffset.UTC));
          withBearer(past.issue(member).value());

          assertThatThrownBy(() -> controller.deleteMe(guestInstall))
              .isInstanceOfSatisfying(
                  ApiException.class,
                  e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_EXPIRED"));

          assertThat(count("app_user", "id", member)).isEqualTo(1);
          assertThat(count("app_user", "id", guest)).isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("깨진 토큰이면 비회원 설치본을 실어도 401 ACCESS_TOKEN_INVALID — 비회원은 그대로")
  void brokenTokenDoesNotFallBackToGuest() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID guestInstall = UUID.randomUUID();
          UUID guest = users.resolve(guestInstall);
          withBearer("not-a-token");

          assertThatThrownBy(() -> controller.deleteMe(guestInstall))
              .isInstanceOfSatisfying(
                  ApiException.class,
                  e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID"));

          assertThat(count("app_user", "id", guest)).isEqualTo(1);
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  /** 가입 계정 — 설치본의 비회원 계정에 가입 시각을 채우고 신분을 붙인다(로그인의 결과를 직접 만든다). */
  private static UUID member(UUID install) {
    UUID id = users.resolve(install);
    jdbc.sql("UPDATE app_user SET registered_at = now() WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .update();
    jdbc.sql(
            """
            INSERT INTO user_identity (provider, subject, user_id, email, display_name, created_at)
            VALUES ('google', :s, CAST(:u AS UUID), :e, '김여행', now())
            """)
        .param("s", "sub-it-guest-delete-" + UUID.randomUUID())
        .param("u", id.toString())
        .param("e", "u-" + id + "@example.com")
        .update();
    return id;
  }

  private void withBearer(String accessToken) {
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + accessToken);
  }

  private static void expect(ApiException e, HttpStatus status, String code) {
    assertThat(e.getStatus()).isEqualTo(status);
    assertThat(e.getCode()).isEqualTo(code);
  }

  private static void cart(UUID user, long place) {
    jdbc.sql("INSERT INTO saved_place (user_id, place_id) VALUES (CAST(:u AS UUID), :p)")
        .param("u", user.toString())
        .param("p", place)
        .update();
  }

  private static long course(UUID user) {
    return jdbc.sql(
            "INSERT INTO course (user_id, title, day_count, origin) VALUES (CAST(:u AS UUID),"
                + " 'it-guest-delete', 1, 'self') RETURNING id")
        .param("u", user.toString())
        .query(Long.class)
        .single();
  }

  private static boolean courseExists(long id) {
    return jdbc.sql("SELECT count(*) FROM course WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        == 1;
  }

  private static void favorite(UUID user, long content) {
    jdbc.sql("INSERT INTO saved_content (user_id, content_id) VALUES (CAST(:u AS UUID), :c)")
        .param("u", user.toString())
        .param("c", content)
        .update();
  }

  private static long place() {
    return jdbc.sql(
            "INSERT INTO place (type, geom) VALUES ('시험',"
                + " ST_SetSRID(ST_MakePoint(126.98, 37.58), 4326)::geography) RETURNING id")
        .query(Long.class)
        .single();
  }

  private static long content() {
    return jdbc.sql("INSERT INTO content (category) VALUES ('drama') RETURNING id")
        .query(Long.class)
        .single();
  }

  /** 표 이름과 열 이름은 이 파일 안의 상수로만 들어온다. */
  private static long count(String table, String column, UUID id) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(Long.class)
        .single();
  }

  private static long appUserCount() {
    return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
  }

  private static byte[] bytes(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }

  private static AppleLogin disabledApple() {
    return new AppleLogin(
        null,
        new AppleClient(null, "com.example", "TEAM", "KEY", null, Clock.systemUTC()),
        new TokenCipher((byte[]) null),
        new AccountLinkStore(IntegrationDatabase.jdbcClient()));
  }
}
