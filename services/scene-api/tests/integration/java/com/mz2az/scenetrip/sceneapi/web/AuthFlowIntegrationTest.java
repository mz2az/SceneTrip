package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.AuthProvider;
import com.mz2az.scenetrip.sceneapi.api.model.AuthSession;
import com.mz2az.scenetrip.sceneapi.api.model.LinkedIdentity;
import com.mz2az.scenetrip.sceneapi.api.model.Me;
import com.mz2az.scenetrip.sceneapi.api.model.RefreshTokenBody;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.IssuedToken;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
 * 갱신 → 로그아웃 → 내 계정 → 탈퇴를 <b>진짜 Store 와 진짜 PostgreSQL</b> 위에서 명세(계약 {@code /auth/refresh} · {@code
 * /auth/sign-out} · {@code /me}, 계획 §6, 마이그레이션 V8·V9·V10·V15)에 비춰 본다.
 *
 * <p>스프링 컨텍스트를 띄우지 않고 {@link AuthController} 와 {@link CurrentAccount} 를 손으로 조립한다. 요청은 {@link
 * MockHttpServletRequest} 하나를 헤더만 바꿔 가며 쓴다 — {@link CurrentAccount} 는 주입된 요청에서 헤더를 읽기 때문이다. HTTP
 * 직렬화와 상태 코드 변환은 단위 레인({@code AuthControllerTest})이 보고, 여기서는 「그 결과로 DB 가 명세대로 바뀌었는가」 를 본다. 거절은
 * {@link ApiException} 의 상태·코드로 확인한다 — 예외 처리기가 그것을 그대로 응답으로 옮긴다.
 *
 * <p>로그인({@code /auth/google})은 아직 없으므로 「로그인한 상태」 는 계약이 말하는 결과를 직접 만든다: 설치본의 비회원 계정에 {@code
 * registered_at} 을 채우고 신분을 붙이고, {@link RefreshTokenStore#issue} 로 첫 리프레시 토큰을 받는다.
 */
@DisplayName("로그인 흐름 — 갱신·로그아웃·내 계정·탈퇴 (실제 DB)")
class AuthFlowIntegrationTest {

  private static final byte[] SECRET = bytes(32, (byte) 3);
  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final Duration REFRESH_TTL = Duration.ofDays(60);

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;

  // DB 의 timestamptz 는 마이크로초까지라 나노초를 잘라 둔다.
  private final MutableClock clock =
      new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens accessTokens = new AccessTokens(SECRET, ACCESS_TTL, clock);
  private final RefreshTokenStore refreshTokens =
      new RefreshTokenStore(jdbc, transactions, REFRESH_TTL, clock);
  private final CurrentAccount accounts = new CurrentAccount(request, accessTokens, users);
  // 구글 로그인은 이 테스트의 대상이 아니라 검증기를 넣지 않는다(null). 로그인 서비스는 진짜다 —
  // 갱신이 합쳐진 빈 행을 쓸어 오는 일을 그것에 맡기기 때문이다.
  private final SignInService signIn =
      new SignInService(users, new AccountLinkStore(jdbc), refreshTokens, transactions);
  private final AuthController controller =
      new AuthController(accessTokens, refreshTokens, users, accounts, null, signIn);

  private final List<UUID> createdUsers = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    // merged_into 에는 CASCADE 가 없어 가리키는 쪽을 먼저 끊어야 지울 수 있다.
    for (UUID id : createdUsers) {
      jdbc.sql(
              "UPDATE app_user SET merged_into = NULL"
                  + " WHERE id = CAST(:id AS UUID) OR merged_into = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ───────────── 갱신 ─────────────

  @Test
  @DisplayName("갱신하면 그 계정의 새 액세스 토큰과 새 리프레시 토큰, 설정 수명, 내 계정, isNewUser·merged=false")
  void refreshIssuesNewSession() {
    Login login = signedIn();

    AuthSession session = refreshOk(login.refresh().value());

    assertThat(accessTokens.verify(session.getAccessToken())).isEqualTo(login.userId());
    assertThat(session.getRefreshToken()).isNotBlank().isNotEqualTo(login.refresh().value());
    assertThat(session.getAccessTokenExpiresIn().longValue()).isEqualTo(ACCESS_TTL.toSeconds());
    assertThat(session.getRefreshTokenExpiresIn().longValue()).isEqualTo(REFRESH_TTL.toSeconds());
    assertThat(session.getUser().getId()).isEqualTo(login.userId());
    assertThat(session.getUser().getEmail()).isEqualTo(login.email());
    assertThat(session.getIsNewUser()).isFalse();
    assertThat(session.getMerged()).isFalse();
  }

  @Test
  @DisplayName("새 리프레시 토큰으로 다시 갱신할 수 있다")
  void refreshedTokenRefreshesAgain() {
    Login login = signedIn();

    AuthSession second = refreshOk(refreshOk(login.refresh().value()).getRefreshToken());

    assertThat(accessTokens.verify(second.getAccessToken())).isEqualTo(login.userId());
  }

  @Test
  @DisplayName("쓴 리프레시 토큰을 다시 보내면 401 REFRESH_TOKEN_INVALID — 그 뒤로는 새 토큰도 거절된다")
  void reusedTokenRevokesEverything() {
    Login login = signedIn();
    String next = refreshOk(login.refresh().value()).getRefreshToken();

    expectRefreshInvalid(login.refresh().value());

    expectRefreshInvalid(next);
  }

  @Test
  @DisplayName("재사용이 잡히면 같은 계정의 다른 로그인(다른 설치본)도 끊긴다")
  void reuseRevokesOtherLoginsOfSameUser() {
    Login login = signedIn();
    IssuedToken tablet = refreshTokens.issue(login.userId(), UUID.randomUUID());
    refreshOk(login.refresh().value());

    expectRefreshInvalid(login.refresh().value());

    expectRefreshInvalid(tablet.value());
  }

  @Test
  @DisplayName("모르는 리프레시 토큰은 401 REFRESH_TOKEN_INVALID")
  void unknownTokenIsInvalid() {
    expectRefreshInvalid("never-issued-" + UUID.randomUUID());
  }

  @Test
  @DisplayName("수명이 지난 리프레시 토큰은 401 REFRESH_TOKEN_INVALID")
  void expiredTokenIsInvalid() {
    Login login = signedIn();
    clock.advance(REFRESH_TTL.plusSeconds(1));

    expectRefreshInvalid(login.refresh().value());
  }

  @Test
  @DisplayName("로그아웃으로 폐기된 리프레시 토큰은 401 REFRESH_TOKEN_INVALID")
  void revokedTokenIsInvalid() {
    Login login = signedIn();
    signOut(login.install(), login.refresh().value());

    expectRefreshInvalid(login.refresh().value());
  }

  @Test
  @DisplayName("서명 키가 없으면 갱신은 401 REFRESH_TOKEN_INVALID 이고 토큰을 쓰지 않는다 — 키가 있는 서버에서는 그 토큰이 갱신된다")
  void refreshWithoutKeyDoesNotConsumeToken() {
    Login login = signedIn();
    AccessTokens keyless = new AccessTokens(null, ACCESS_TTL, clock);
    AuthController keylessController =
        new AuthController(
            keyless,
            refreshTokens,
            users,
            new CurrentAccount(request, keyless, users),
            null,
            signIn);

    assertThatThrownBy(
            () -> keylessController.refreshSession(new RefreshTokenBody(login.refresh().value())))
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID"));

    AuthSession session = refreshOk(login.refresh().value());
    assertThat(accessTokens.verify(session.getAccessToken())).isEqualTo(login.userId());
  }

  // ───────────── 로그아웃 ─────────────

  @Test
  @DisplayName("로그아웃하면 이 설치본은 새 비회원 계정을 가리키고, 로그아웃한 계정의 데이터는 그대로다")
  void signOutMovesInstallToNewGuest() {
    Login login = signedIn();
    long contentId = anyContentId();
    saveContent(login.userId(), contentId);

    signOut(login.install(), login.refresh().value());

    UUID owner = track(deviceOwner(login.install()).orElseThrow());
    assertThat(owner).isNotEqualTo(login.userId());
    assertThat(registeredAt(owner)).isNull();
    assertThat(users.lookup(login.install())).isEqualTo(new UserStore.Account(owner, false));

    assertThat(registeredAt(login.userId())).isNotNull();
    assertThat(count("user_identity", "user_id", login.userId())).isEqualTo(1);
    assertThat(count("saved_content", "user_id", login.userId())).isEqualTo(1);
    assertThat(count("saved_content", "user_id", owner)).isZero();
  }

  @Test
  @DisplayName("로그아웃 뒤 이 설치본은 토큰 없이 비회원으로 열린다 — SESSION_REQUIRED 가 아니다")
  void signedOutInstallResolvesAsGuest() {
    Login login = signedIn();

    signOut(login.install(), login.refresh().value());

    withoutAuthorization();
    UUID resolved = accounts.resolve(login.install());
    assertThat(resolved).isNotEqualTo(login.userId()).isEqualTo(deviceOwner(login.install()).get());
    track(resolved);
  }

  @Test
  @DisplayName("로그아웃은 그 로그인의 사슬만 끊는다 — 같은 계정의 다른 설치본은 계속 갱신된다")
  void signOutRevokesOnlyThisLogin() {
    Login login = signedIn();
    IssuedToken tablet = refreshTokens.issue(login.userId(), UUID.randomUUID());

    signOut(login.install(), login.refresh().value());

    expectRefreshInvalid(login.refresh().value());
    AuthSession session = refreshOk(tablet.value());
    assertThat(accessTokens.verify(session.getAccessToken())).isEqualTo(login.userId());
  }

  @Test
  @DisplayName("갱신해 받은 최신 토큰으로 로그아웃해도 그 사슬이 끊긴다")
  void signOutWithLatestTokenOfChain() {
    Login login = signedIn();
    String latest = refreshOk(login.refresh().value()).getRefreshToken();

    signOut(login.install(), latest);

    expectRefreshInvalid(latest);
    assertThat(deviceOwner(login.install())).isNotEqualTo(Optional.of(login.userId()));
    track(deviceOwner(login.install()).orElseThrow());
  }

  @Test
  @DisplayName("이 설치본이 토큰과 다른 계정을 가리키면 설치본을 바꿔 달지 않는다")
  void signOutDoesNotDetachForeignInstall() {
    Login login = signedIn();
    UUID otherInstall = UUID.randomUUID();
    UUID otherGuest = track(users.resolve(otherInstall));
    long usersBefore = appUserCount();

    signOut(otherInstall, login.refresh().value());

    assertThat(deviceOwner(otherInstall)).contains(otherGuest);
    assertThat(deviceOwner(login.install())).contains(login.userId());
    assertThat(appUserCount()).as("새 비회원 행이 생기지 않았다").isEqualTo(usersBefore);
  }

  @Test
  @DisplayName("모르는 토큰으로 로그아웃하면 204 이고 설치본도 그 계정의 로그인도 그대로다")
  void signOutWithUnknownTokenChangesNothing() {
    Login login = signedIn();

    signOut(login.install(), "never-issued-" + UUID.randomUUID());

    assertThat(deviceOwner(login.install())).contains(login.userId());
    refreshOk(login.refresh().value());
  }

  @Test
  @DisplayName("이미 로그아웃한 토큰으로 다시 로그아웃해도 204 — 두 번째는 설치본을 또 바꾸지 않는다")
  void signOutTwiceIsHarmless() {
    Login login = signedIn();
    signOut(login.install(), login.refresh().value());
    UUID guest = track(deviceOwner(login.install()).orElseThrow());

    signOut(login.install(), login.refresh().value());

    assertThat(deviceOwner(login.install())).contains(guest);
  }

  // ───────────── 내 계정 ─────────────

  @Test
  @DisplayName("내 계정은 id·가입 시각·연결 순서대로의 신분, 이름·이메일은 먼저 연결한 것부터 값이 있는 것")
  void getMeReturnsProfile() {
    Login login = signedIn();
    OffsetDateTime later = registeredAt(login.userId()).plusMinutes(5);
    // 먼저 붙은 구글 신분에 이름이 없고(signedIn 이 이메일만 넣는다) 나중 애플 신분에 이름이 있다.
    jdbc.sql("UPDATE user_identity SET display_name = NULL WHERE user_id = CAST(:u AS UUID)")
        .param("u", login.userId().toString())
        .update();
    addIdentity(login.userId(), "apple", "relay@privaterelay.appleid.com", "애플이름", later);
    withBearer(accessTokens.issue(login.userId()).value());

    Me me = controller.getMe().getBody();

    assertThat(me).isNotNull();
    assertThat(me.getId()).isEqualTo(login.userId());
    assertThat(me.getRegisteredAt().isEqual(registeredAt(login.userId()))).isTrue();
    assertThat(me.getIdentities())
        .extracting(LinkedIdentity::getProvider, LinkedIdentity::getEmail)
        .containsExactly(
            tuple(AuthProvider.GOOGLE, login.email()),
            tuple(AuthProvider.APPLE, "relay@privaterelay.appleid.com"));
    assertThat(me.getDisplayName()).isEqualTo("애플이름");
    assertThat(me.getEmail()).isEqualTo(login.email());
  }

  @Test
  @DisplayName("Authorization 없이 내 계정을 부르면 401 ACCESS_TOKEN_INVALID — 설치 UUID 가 있어도")
  void getMeWithoutTokenIsInvalid() {
    Login login = signedIn();
    withoutAuthorization();
    request.addHeader("X-Install-Id", login.install().toString());

    assertThatThrownBy(controller::getMe)
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID"));
  }

  @Test
  @DisplayName("만료된 액세스 토큰이면 401 ACCESS_TOKEN_EXPIRED")
  void getMeWithExpiredTokenIsExpired() {
    Login login = signedIn();
    withBearer(accessTokens.issue(login.userId()).value());
    clock.advance(ACCESS_TTL.plusSeconds(1));

    assertThatThrownBy(controller::getMe)
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_EXPIRED"));
  }

  // ───────────── 탈퇴 ─────────────

  @Test
  @DisplayName("탈퇴하면 계정과 그 계정의 설치본·장바구니·찜·코스·마켓 코스·좋아요·신분·리프레시 토큰이 모두 사라진다")
  void deleteMeRemovesEverythingOfAccount() {
    IntegrationDatabase.requireSeeded(jdbc);
    Login login = signedIn();
    UUID me = login.userId();
    long placeId = anyPlaceId();
    long contentId = anyContentId();
    UUID secondInstall = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO user_device (install_uuid, user_id) VALUES (CAST(:i AS UUID), CAST(:u AS"
                + " UUID))")
        .param("i", secondInstall.toString())
        .param("u", me.toString())
        .update();
    refreshTokens.issue(me, secondInstall);
    jdbc.sql("INSERT INTO saved_place (user_id, place_id) VALUES (CAST(:u AS UUID), :p)")
        .param("u", me.toString())
        .param("p", placeId)
        .update();
    saveContent(me, contentId);
    long courseId = insertCourse(me, placeId);
    long myMarketCourse = insertMarketCourse(me, courseId);
    UUID otherAuthor = track(users.resolve(UUID.randomUUID()));
    long othersMarketCourse = insertMarketCourse(otherAuthor, null);
    like(me, myMarketCourse);
    like(me, othersMarketCourse);
    like(otherAuthor, myMarketCourse);
    withBearer(accessTokens.issue(me).value());

    ResponseEntity<Void> response = controller.deleteMe();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(count("app_user", "id", me)).isZero();
    assertThat(count("user_device", "user_id", me)).isZero();
    assertThat(count("saved_place", "user_id", me)).isZero();
    assertThat(count("saved_content", "user_id", me)).isZero();
    assertThat(count("course", "user_id", me)).isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM course_item WHERE course_id = :c")
                .param("c", courseId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(count("market_course", "author_id", me)).isZero();
    assertThat(count("market_like", "user_id", me)).isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM market_like WHERE market_course_id = :m")
                .param("m", myMarketCourse)
                .query(Long.class)
                .single())
        .as("남이 내 마켓 코스에 누른 좋아요도 코스와 함께 사라진다")
        .isZero();
    assertThat(count("user_identity", "user_id", me)).isZero();
    assertThat(count("refresh_token", "user_id", me)).isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM market_course WHERE id = :m")
                .param("m", othersMarketCourse)
                .query(Long.class)
                .single())
        .as("남의 마켓 코스는 남는다")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("이 계정으로 합쳐진 비회원 행(merged_into)이 있어도 탈퇴가 막히지 않는다")
  void deleteMeWithMergedGuests() {
    Login login = signedIn();
    UUID mergedGuest = track(users.resolve(UUID.randomUUID()));
    jdbc.sql("UPDATE app_user SET merged_into = CAST(:u AS UUID) WHERE id = CAST(:g AS UUID)")
        .param("u", login.userId().toString())
        .param("g", mergedGuest.toString())
        .update();
    withBearer(accessTokens.issue(login.userId()).value());

    ResponseEntity<Void> response = controller.deleteMe();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(count("app_user", "id", login.userId())).isZero();
  }

  @Test
  @DisplayName("탈퇴 뒤 같은 액세스 토큰은 401 ACCESS_TOKEN_INVALID, 리프레시 토큰은 401 REFRESH_TOKEN_INVALID")
  void tokensAreDeadAfterDelete() {
    Login login = signedIn();
    String access = accessTokens.issue(login.userId()).value();
    withBearer(access);

    controller.deleteMe();

    assertThatThrownBy(controller::getMe)
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID"));
    assertThatThrownBy(controller::deleteMe)
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID"));
    expectRefreshInvalid(login.refresh().value());
  }

  @Test
  @DisplayName("탈퇴 뒤 같은 설치본이 토큰 없이 오면 처음 보는 설치본처럼 새 비회원 계정이 생긴다")
  void installAfterDeleteGetsNewGuest() {
    Login login = signedIn();
    withBearer(accessTokens.issue(login.userId()).value());
    controller.deleteMe();

    withoutAuthorization();
    UUID resolved = track(accounts.resolve(login.install()));

    assertThat(resolved).isNotEqualTo(login.userId());
    assertThat(registeredAt(resolved)).isNull();
    assertThat(deviceOwner(login.install())).contains(resolved);
  }

  // ───────────── 전체 흐름 ─────────────

  @Test
  @DisplayName("한 사람의 흐름 — 갱신 → 내 계정 → 로그아웃 → 다른 설치본에서 갱신 → 탈퇴")
  void fullFlow() {
    Login phone = signedIn();
    IssuedToken tablet = refreshTokens.issue(phone.userId(), UUID.randomUUID());

    AuthSession refreshed = refreshOk(phone.refresh().value());
    withBearer(refreshed.getAccessToken());
    assertThat(controller.getMe().getBody().getId()).isEqualTo(phone.userId());

    withoutAuthorization();
    signOut(phone.install(), refreshed.getRefreshToken());
    track(deviceOwner(phone.install()).orElseThrow());
    expectRefreshInvalid(refreshed.getRefreshToken());

    AuthSession tabletSession = refreshOk(tablet.value());
    withBearer(tabletSession.getAccessToken());
    assertThat(controller.deleteMe().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(count("app_user", "id", phone.userId())).isZero();
    expectRefreshInvalid(tabletSession.getRefreshToken());
    assertThatThrownBy(controller::getMe)
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID"));
  }

  // ───────────── 도우미 ─────────────

  /** 로그인한 상태 — 계약이 말하는 로그인의 결과를 직접 만든다. */
  private record Login(UUID install, UUID userId, String email, IssuedToken refresh) {}

  private Login signedIn() {
    UUID install = UUID.randomUUID();
    UUID userId = track(users.resolve(install));
    OffsetDateTime registered = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    jdbc.sql("UPDATE app_user SET registered_at = :t WHERE id = CAST(:id AS UUID)")
        .param("t", registered)
        .param("id", userId.toString())
        .update();
    String email = "u-" + userId + "@example.com";
    addIdentity(userId, "google", email, "김여행", registered);
    return new Login(install, userId, email, refreshTokens.issue(userId, install));
  }

  private static void addIdentity(
      UUID userId, String provider, String email, String displayName, OffsetDateTime at) {
    jdbc.sql(
            """
            INSERT INTO user_identity (provider, subject, user_id, email, display_name, created_at)
            VALUES (:p, :s, CAST(:u AS UUID), :e, :d, :t)
            """)
        .param("p", provider)
        .param("s", "sub-" + UUID.randomUUID())
        .param("u", userId.toString())
        .param("e", email)
        .param("d", displayName)
        .param("t", at)
        .update();
  }

  private AuthSession refreshOk(String refreshToken) {
    ResponseEntity<AuthSession> response =
        controller.refreshSession(new RefreshTokenBody(refreshToken));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private void expectRefreshInvalid(String refreshToken) {
    assertThatThrownBy(() -> controller.refreshSession(new RefreshTokenBody(refreshToken)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> expect(e, HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID"));
  }

  private void signOut(UUID install, String refreshToken) {
    ResponseEntity<Void> response = controller.signOut(install, new RefreshTokenBody(refreshToken));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  private static void expect(ApiException e, HttpStatus status, String code) {
    assertThat(e.getStatus()).isEqualTo(status);
    assertThat(e.getCode()).isEqualTo(code);
  }

  private void withBearer(String accessToken) {
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + accessToken);
  }

  private void withoutAuthorization() {
    request.removeHeader("Authorization");
  }

  private UUID track(UUID id) {
    createdUsers.add(id);
    return id;
  }

  private static Optional<UUID> deviceOwner(UUID install) {
    return jdbc.sql("SELECT user_id FROM user_device WHERE install_uuid = CAST(:i AS UUID)")
        .param("i", install.toString())
        .query(UUID.class)
        .optional();
  }

  private static OffsetDateTime registeredAt(UUID id) {
    return jdbc.sql("SELECT registered_at FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(OffsetDateTime.class)
        .optional()
        .orElse(null);
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

  private static long anyPlaceId() {
    return jdbc.sql("SELECT id FROM place ORDER BY id LIMIT 1").query(Long.class).single();
  }

  private static long anyContentId() {
    return jdbc.sql("SELECT id FROM content ORDER BY id LIMIT 1").query(Long.class).single();
  }

  private static void saveContent(UUID userId, long contentId) {
    jdbc.sql("INSERT INTO saved_content (user_id, content_id) VALUES (CAST(:u AS UUID), :c)")
        .param("u", userId.toString())
        .param("c", contentId)
        .update();
  }

  private static long insertCourse(UUID userId, long placeId) {
    long courseId =
        jdbc.sql(
                """
                INSERT INTO course (user_id, title, day_count, origin)
                VALUES (CAST(:u AS UUID), '탈퇴 테스트 코스', 1, 'self')
                RETURNING id
                """)
            .param("u", userId.toString())
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO course_item (course_id, day_no, place_id, sort_order) VALUES (:c, 1, :p,"
                + " 0)")
        .param("c", courseId)
        .param("p", placeId)
        .update();
    return courseId;
  }

  private static long insertMarketCourse(UUID authorId, Long sourceCourseId) {
    return jdbc.sql(
            """
            INSERT INTO market_course (author_id, source_course_id, title, description, day_count)
            VALUES (CAST(:a AS UUID), CAST(:s AS BIGINT), '탈퇴 테스트 마켓 코스', '', 1)
            RETURNING id
            """)
        .param("a", authorId.toString())
        .param("s", sourceCourseId)
        .query(Long.class)
        .single();
  }

  private static void like(UUID userId, long marketCourseId) {
    jdbc.sql("INSERT INTO market_like (user_id, market_course_id) VALUES (CAST(:u AS UUID), :m)")
        .param("u", userId.toString())
        .param("m", marketCourseId)
        .update();
  }

  private static byte[] bytes(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
