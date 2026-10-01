package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.GoogleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.IssuedToken;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Reason;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Rejected;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Rotated;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Identity;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Profile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 로그인 창구({@code /auth/*}, {@code /me})를 명세(계약의 경로·스키마·{@code Unauthorized}·{@code
 * RefreshTokenInvalid}, 계획 §6, 오류 문서의 인증 절)에 비춰 HTTP 로 본다.
 *
 * <p>{@link CurrentAccount} 와 {@link AccessTokens} 는 진짜다 — 401 코드가 실제 헤더 처리에서 갈려야 한다. DB 를 아는
 * {@link RefreshTokenStore} · {@link UserStore} 만 가짜이고, 그 둘의 SQL 은 통합 레인({@code
 * AuthFlowIntegrationTest})이 본다.
 */
@WebMvcTest(AuthController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, AuthControllerTest.Tokens.class})
@DisplayName("AuthController — 갱신·로그아웃·내 계정·탈퇴")
class AuthControllerTest {

  private static final byte[] SECRET = bytes(32, (byte) 5);
  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final long REFRESH_TTL_SECONDS = Duration.ofDays(60).toSeconds();
  private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

  private static final UUID INSTALL_ID = UUID.fromString("5b0c2d71-3e4f-4a9b-8c1d-7e6f5a4b3c21");
  private static final UUID USER = UUID.fromString("7a3e9c10-4b2d-4e8f-a1c6-0d9b8e7f6a52");
  private static final OffsetDateTime REGISTERED_AT = OffsetDateTime.parse("2026-09-20T03:15:30Z");

  private static final String OLD_REFRESH = "old-refresh-token-value-aaaaaaaaaaaaaaaaaaaa";
  private static final String NEW_REFRESH = "new-refresh-token-value-bbbbbbbbbbbbbbbbbbbb";

  /** 컨텍스트는 테스트 사이에 재사용되므로 시계를 정적으로 두고 매번 되감는다. */
  static final MutableClock CLOCK = new MutableClock(T0);

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(SECRET, ACCESS_TTL, CLOCK);
    }
  }

  @Autowired private MockMvc mvc;

  @Autowired private AccessTokens tokens;

  @MockitoBean private RefreshTokenStore refreshTokens;

  @MockitoBean private UserStore users;

  // 구글 로그인 창구의 의존성. 이 시험의 대상(갱신·로그아웃·내 계정·탈퇴)이 아니라 비워 둔다.
  @MockitoBean private GoogleIdTokenVerifier google;

  @MockitoBean private SignInService signIn;

  // 애플 로그인 — 이 시험의 대상이 아니다. 목의 enabled() 는 false 라 /auth/apple 은 501, 탈퇴의 애플 끊기는 하지 않는다.
  @MockitoBean private AppleLogin apple;

  @BeforeEach
  void setUp() {
    CLOCK.set(T0);
  }

  // ───────────── POST /auth/refresh ─────────────

  @Test
  @DisplayName("유효한 리프레시 토큰이면 200 — 그 계정의 새 액세스 토큰, 새 리프레시 토큰, 설정 수명, 내 계정, isNewUser·merged=false")
  void refreshReturnsNewSession() throws Exception {
    when(refreshTokens.rotate(OLD_REFRESH))
        .thenReturn(new Rotated(USER, new IssuedToken(NEW_REFRESH, REFRESH_TTL_SECONDS)));
    when(users.profile(USER)).thenReturn(Optional.of(googleProfile()));
    List<Object> accessTokens = new ArrayList<>();

    refresh(OLD_REFRESH)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value(capturingInto(accessTokens)))
        .andExpect(jsonPath("$.accessTokenExpiresIn").value((int) ACCESS_TTL.toSeconds()))
        .andExpect(jsonPath("$.refreshToken").value(NEW_REFRESH))
        .andExpect(jsonPath("$.refreshTokenExpiresIn").value((int) REFRESH_TTL_SECONDS))
        .andExpect(jsonPath("$.user.id").value(USER.toString()))
        .andExpect(jsonPath("$.user.email").value("me@example.com"))
        .andExpect(jsonPath("$.user.displayName").value("김여행"))
        .andExpect(jsonPath("$.user.identities[0].provider").value("google"))
        .andExpect(jsonPath("$.user.registeredAt").value(sameInstantAs(REGISTERED_AT)))
        .andExpect(jsonPath("$.isNewUser").value(false))
        .andExpect(jsonPath("$.merged").value(false));

    String accessToken = String.valueOf(accessTokens.get(0));
    assertThat(tokens.verify(accessToken)).isEqualTo(USER);
    assertThat(NEW_REFRESH).isNotEqualTo(OLD_REFRESH);
  }

  @Test
  @DisplayName("액세스 토큰 없이 갱신할 수 있다 — 만료된 액세스 토큰이 함께 와도 200")
  void refreshDoesNotNeedAccessToken() throws Exception {
    // 갱신은 ACCESS_TOKEN_EXPIRED 를 받은 뒤 부르는 창구다. 앱이 습관대로 만료 토큰을 붙여
    // 보냈다고 거절하면 갱신할 길이 없다.
    when(refreshTokens.rotate(OLD_REFRESH))
        .thenReturn(new Rotated(USER, new IssuedToken(NEW_REFRESH, REFRESH_TTL_SECONDS)));
    when(users.profile(USER)).thenReturn(Optional.of(googleProfile()));
    String expired = tokens.issue(USER).value();
    CLOCK.advance(ACCESS_TTL.plusSeconds(1));

    mvc.perform(
            post("/auth/refresh")
                .header("Authorization", "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(OLD_REFRESH)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refreshToken").value(NEW_REFRESH));
  }

  @Test
  @DisplayName("거절 이유(모름·만료·폐기·재사용)와 상관없이 401 REFRESH_TOKEN_INVALID")
  void everyRejectionIsRefreshTokenInvalid() throws Exception {
    for (Reason reason : Reason.values()) {
      when(refreshTokens.rotate(OLD_REFRESH)).thenReturn(new Rejected(reason));

      refresh(OLD_REFRESH)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }
  }

  @Test
  @DisplayName("거절 이유는 응답에 실리지 않는다 — 네 경우의 message 가 모두 같다")
  void rejectionMessageIsSameForEveryReason() throws Exception {
    List<Object> messages = new ArrayList<>();
    for (Reason reason : Reason.values()) {
      when(refreshTokens.rotate(OLD_REFRESH)).thenReturn(new Rejected(reason));
      refresh(OLD_REFRESH).andExpect(jsonPath("$.message").value(capturingInto(messages)));
    }

    assertThat(messages).hasSize(Reason.values().length).doesNotContainNull();
    assertThat(messages.stream().distinct()).hasSize(1);
  }

  @Test
  @DisplayName("refreshToken 필드가 없으면 400 — 토큰을 쓰지 않는다")
  void refreshWithoutFieldIsBadRequest() throws Exception {
    mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(refreshTokens);
  }

  @Test
  @DisplayName("본문이 없으면 400")
  void refreshWithoutBodyIsBadRequest() throws Exception {
    mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(refreshTokens);
  }

  // ───────────── POST /auth/sign-out ─────────────

  @Test
  @DisplayName("로그아웃은 204 — 토큰의 계정으로 이 설치본을 떼어 낸다")
  void signOutDetachesInstallFromTokensAccount() throws Exception {
    when(refreshTokens.revokeFamily(OLD_REFRESH)).thenReturn(Optional.of(USER));
    when(users.detachInstall(INSTALL_ID, USER)).thenReturn(true);

    signOut(OLD_REFRESH).andExpect(status().isNoContent());

    verify(refreshTokens).revokeFamily(OLD_REFRESH);
    verify(users).detachInstall(INSTALL_ID, USER);
  }

  @Test
  @DisplayName("이 설치본이 이미 다른 계정을 가리켜도 204")
  void signOutIs204WhenInstallPointsElsewhere() throws Exception {
    when(refreshTokens.revokeFamily(OLD_REFRESH)).thenReturn(Optional.of(USER));
    when(users.detachInstall(INSTALL_ID, USER)).thenReturn(false);

    signOut(OLD_REFRESH).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("모르는·이미 폐기된 토큰이어도 204 이고, 설치본은 건드리지 않는다")
  void signOutWithUnknownTokenIs204AndKeepsInstall() throws Exception {
    // 설치 UUID 는 비밀이 아니다. 아무 토큰으로 남의 설치본을 떼어 낼 수 있으면 안 된다.
    when(refreshTokens.revokeFamily(any())).thenReturn(Optional.empty());

    signOut("never-issued").andExpect(status().isNoContent());

    verify(users, never()).detachInstall(any(), any());
  }

  @Test
  @DisplayName("액세스 토큰 없이도, 만료된 액세스 토큰이 와도 로그아웃할 수 있다")
  void signOutDoesNotNeedAccessToken() throws Exception {
    when(refreshTokens.revokeFamily(OLD_REFRESH)).thenReturn(Optional.of(USER));
    String expired = tokens.issue(USER).value();
    CLOCK.advance(ACCESS_TTL.plusSeconds(1));

    mvc.perform(
            post("/auth/sign-out")
                .header("X-Install-Id", INSTALL_ID.toString())
                .header("Authorization", "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(OLD_REFRESH)))
        .andExpect(status().isNoContent());

    verify(refreshTokens).revokeFamily(OLD_REFRESH);
  }

  @Test
  @DisplayName("X-Install-Id 가 없으면 400 MISSING_INSTALL_ID — 토큰을 폐기하지 않는다")
  void signOutWithoutInstallIdIsBadRequest() throws Exception {
    mvc.perform(
            post("/auth/sign-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(OLD_REFRESH)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));

    verifyNoInteractions(refreshTokens);
  }

  @Test
  @DisplayName("refreshToken 필드가 없으면 400")
  void signOutWithoutFieldIsBadRequest() throws Exception {
    mvc.perform(
            post("/auth/sign-out")
                .header("X-Install-Id", INSTALL_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(refreshTokens);
  }

  // ───────────── GET /me ─────────────

  @Test
  @DisplayName("유효한 토큰이면 200 Me — id·가입 시각·연결된 신분")
  void getMeReturnsProfile() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.profile(USER)).thenReturn(Optional.of(googleProfile()));

    getMe(bearer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(USER.toString()))
        .andExpect(jsonPath("$.registeredAt").value(sameInstantAs(REGISTERED_AT)))
        .andExpect(jsonPath("$.identities.length()").value(1))
        .andExpect(jsonPath("$.identities[0].provider").value("google"))
        .andExpect(jsonPath("$.identities[0].email").value("me@example.com"))
        .andExpect(jsonPath("$.displayName").value("김여행"))
        .andExpect(jsonPath("$.email").value("me@example.com"));
  }

  @Test
  @DisplayName("이름·이메일은 먼저 연결한 신분부터 보아 처음으로 값이 있는 것 — 각각 따로 고른다")
  void getMeTakesFirstNonEmptyNameAndEmail() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.profile(USER))
        .thenReturn(
            Optional.of(
                new Profile(
                    USER,
                    REGISTERED_AT,
                    List.of(
                        new Identity("google", "first@example.com", null),
                        new Identity("apple", "x@privaterelay.appleid.com", "애플이름")))));

    getMe(bearer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identities.length()").value(2))
        .andExpect(jsonPath("$.identities[0].provider").value("google"))
        .andExpect(jsonPath("$.identities[1].provider").value("apple"))
        .andExpect(jsonPath("$.displayName").value("애플이름"))
        .andExpect(jsonPath("$.email").value("first@example.com"));
  }

  @Test
  @DisplayName("Authorization 이 없으면 401 ACCESS_TOKEN_INVALID — 설치 UUID 로 계정을 찾지도 만들지도 않는다")
  void getMeWithoutTokenIsInvalid() throws Exception {
    mvc.perform(get("/me").header("X-Install-Id", INSTALL_ID.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));

    verify(users, never()).lookup(any());
    verify(users, never()).resolve(any());
  }

  @Test
  @DisplayName("만료된 토큰이면 401 ACCESS_TOKEN_EXPIRED")
  void getMeWithExpiredTokenIsExpired() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    String token = bearer();
    CLOCK.advance(ACCESS_TTL.plusSeconds(1));

    getMe(token)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));
  }

  @Test
  @DisplayName("탈퇴한 계정의 토큰이면 401 ACCESS_TOKEN_INVALID")
  void getMeForDeletedAccountIsInvalid() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(false);
    when(users.profile(USER)).thenReturn(Optional.empty());

    getMe(bearer())
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
  }

  @Test
  @DisplayName("살아 있다고 본 직후 프로필이 사라졌어도(탈퇴 경합) 500 이 아니라 401 ACCESS_TOKEN_INVALID")
  void getMeWhenProfileVanishesIsInvalid() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.profile(USER)).thenReturn(Optional.empty());

    getMe(bearer())
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
  }

  // ───────────── DELETE /me ─────────────

  @Test
  @DisplayName("탈퇴는 204 — 토큰의 계정을 지운다")
  void deleteMeDeletesTokensAccount() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.delete(USER)).thenReturn(true);

    mvc.perform(delete("/me").header("Authorization", bearer())).andExpect(status().isNoContent());

    verify(users).delete(USER);
  }

  @Test
  @DisplayName("토큰 없이 탈퇴하면 401 ACCESS_TOKEN_INVALID 이고 아무것도 지우지 않는다")
  void deleteMeWithoutTokenIsInvalid() throws Exception {
    mvc.perform(delete("/me").header("X-Install-Id", INSTALL_ID.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));

    verify(users, never()).delete(any());
  }

  @Test
  @DisplayName("만료된 토큰으로 탈퇴하면 401 ACCESS_TOKEN_EXPIRED 이고 아무것도 지우지 않는다")
  void deleteMeWithExpiredTokenIsExpired() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    String token = bearer();
    CLOCK.advance(ACCESS_TTL.plusSeconds(1));

    mvc.perform(delete("/me").header("Authorization", token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));

    verify(users, never()).delete(any());
  }

  @Test
  @DisplayName("탈퇴한 계정의 토큰으로 다시 탈퇴하면 401 ACCESS_TOKEN_INVALID")
  void deleteMeForDeletedAccountIsInvalid() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(false);

    mvc.perform(delete("/me").header("Authorization", bearer()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));

    verify(users, never()).delete(any());
  }

  // ───────────── 아직 없는 창구 ─────────────

  @Test
  @DisplayName("애플 로그인은 아직 501")
  void appleSignInIsNotImplemented() throws Exception {
    mvc.perform(
            post("/auth/apple")
                .header("X-Install-Id", INSTALL_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"identityToken\":\"identity-token\",\"authorizationCode\":\"code\","
                        + "\"nonce\":\"0123456789abcdef0123\"}"))
        .andExpect(status().isNotImplemented());
  }

  // ───────────── 도우미 ─────────────

  private ResultActions refresh(String refreshToken) throws Exception {
    return mvc.perform(
        post("/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(refreshBody(refreshToken)));
  }

  private ResultActions signOut(String refreshToken) throws Exception {
    return mvc.perform(
        post("/auth/sign-out")
            .header("X-Install-Id", INSTALL_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(refreshBody(refreshToken)));
  }

  private ResultActions getMe(String authorization) throws Exception {
    return mvc.perform(
        get("/me")
            .header("X-Install-Id", INSTALL_ID.toString())
            .header("Authorization", authorization));
  }

  private String bearer() {
    return "Bearer " + tokens.issue(USER).value();
  }

  private static String refreshBody(String refreshToken) {
    return "{\"refreshToken\":\"" + refreshToken + "\"}";
  }

  private static Profile googleProfile() {
    return new Profile(
        USER, REGISTERED_AT, List.of(new Identity("google", "me@example.com", "김여행")));
  }

  /** 직렬화 형식(오프셋 표기·소수 초)과 상관없이 같은 순간인가. */
  private static BaseMatcher<Object> sameInstantAs(OffsetDateTime expected) {
    return new BaseMatcher<>() {
      @Override
      public boolean matches(Object actual) {
        return actual instanceof String s && OffsetDateTime.parse(s).isEqual(expected);
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("date-time equal to " + expected);
      }
    };
  }

  /** 응답의 값을 모은다. 비교는 모두 모은 뒤에 한다. */
  private static BaseMatcher<Object> capturingInto(List<Object> sink) {
    return new BaseMatcher<>() {
      @Override
      public boolean matches(Object actual) {
        sink.add(actual);
        return true;
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("any value (captured)");
      }
    };
  }

  private static byte[] bytes(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }

  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void set(Instant instant) {
      now = instant;
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
