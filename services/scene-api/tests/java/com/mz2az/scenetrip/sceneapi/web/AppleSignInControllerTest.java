package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.GoogleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.IssuedToken;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.auth.SignInService.SignedIn;
import com.mz2az.scenetrip.sceneapi.auth.SocialIdentity;
import com.mz2az.scenetrip.sceneapi.auth.SocialTokenFailures;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Identity;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Profile;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /auth/apple} 과 탈퇴의 애플 끊기를 명세(계약 {@code /auth/apple}·{@code AppleSignIn}·{@code DELETE
 * /me}, 계획 §5·§6, MZ2AZ-337)에 비춰 HTTP 로 본다.
 *
 * <p>{@link AccessTokens} 는 진짜, {@link AppleLogin}·로그인 서비스·계정 저장소는 가짜다. {@code AppleLogin} 의 실제 동작은
 * {@code AppleLoginTest} 와 통합 레인({@code AppleSignInIntegrationTest})이 본다.
 */
@WebMvcTest(AuthController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, AppleSignInControllerTest.Tokens.class})
@DisplayName("AuthController — 애플 로그인")
class AppleSignInControllerTest {

  private static final byte[] SECRET = bytes(32, (byte) 17);
  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final long REFRESH_TTL_SECONDS = Duration.ofDays(60).toSeconds();

  private static final UUID INSTALL_ID = UUID.fromString("6a5b4c3d-2e1f-4a0b-9c8d-7e6f5a4b3c2d");
  private static final UUID USER = UUID.fromString("9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b");
  private static final OffsetDateTime REGISTERED_AT = OffsetDateTime.parse("2026-10-01T01:02:03Z");

  private static final String IDENTITY_TOKEN = "header.payload.signature-from-apple";
  private static final String CODE = "c.authorization-code-from-apple";
  private static final String NONCE = "raw-nonce-0123456789abcdef";
  private static final String REFRESH = "refresh-from-sign-in-service-dddddddddddddddd";
  private static final SocialIdentity IDENTITY =
      new SocialIdentity("apple", "apple-sub-1", "x@privaterelay.appleid.com", "김철수")
          .withAppleRefreshTokenEnc(new byte[] {1, 2, 3});

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(SECRET, ACCESS_TTL, Clock.systemUTC());
    }
  }

  @Autowired private MockMvc mvc;

  @Autowired private AccessTokens tokens;

  @MockitoBean private RefreshTokenStore refreshTokens;

  @MockitoBean private UserStore users;

  @MockitoBean private GoogleIdTokenVerifier google;

  @MockitoBean private SignInService signIn;

  @MockitoBean private AppleLogin apple;

  // ───────────── 501 ─────────────

  @Test
  @DisplayName("애플 로그인이 꺼져 있으면 501 — 검증도 로그인도 하지 않는다")
  void disabledIs501() throws Exception {
    when(apple.enabled()).thenReturn(false);

    signInRequest(body(IDENTITY_TOKEN, CODE, NONCE, "철수", "김"))
        .andExpect(status().isNotImplemented());

    verify(apple, never()).verify(any(), any(), any(), any(), any());
    verifyNoInteractions(signIn, refreshTokens);
  }

  // ───────────── 200 ─────────────

  @Test
  @DisplayName("가입이면 200 AuthSession — 앱이 보낸 값을 그대로 AppleLogin 에 넘기고, 그 신분으로 로그인한다")
  void signUpReturnsSession() throws Exception {
    when(apple.enabled()).thenReturn(true);
    when(apple.verify(IDENTITY_TOKEN, CODE, NONCE, "철수", "김")).thenReturn(IDENTITY);
    when(signIn.signIn(IDENTITY, INSTALL_ID))
        .thenReturn(new SignedIn(USER, true, false, new IssuedToken(REFRESH, REFRESH_TTL_SECONDS)));
    when(users.profile(USER)).thenReturn(Optional.of(profile()));
    List<Object> accessTokens = new ArrayList<>();

    signInRequest(body(IDENTITY_TOKEN, CODE, NONCE, "철수", "김"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value(capturingInto(accessTokens)))
        .andExpect(jsonPath("$.accessTokenExpiresIn").value((int) ACCESS_TTL.toSeconds()))
        .andExpect(jsonPath("$.refreshToken").value(REFRESH))
        .andExpect(jsonPath("$.refreshTokenExpiresIn").value((int) REFRESH_TTL_SECONDS))
        .andExpect(jsonPath("$.user.id").value(USER.toString()))
        .andExpect(jsonPath("$.user.displayName").value("김철수"))
        .andExpect(jsonPath("$.user.identities[0].provider").value("apple"))
        .andExpect(jsonPath("$.isNewUser").value(true))
        .andExpect(jsonPath("$.merged").value(false));

    assertThat(tokens.verify(String.valueOf(accessTokens.get(0)))).isEqualTo(USER);
    verify(apple).verify(IDENTITY_TOKEN, CODE, NONCE, "철수", "김");
    verify(signIn).signIn(IDENTITY, INSTALL_ID);
  }

  @Test
  @DisplayName("이름 없이(두 번째 로그인) 보내도 200 — 이름 자리는 null 로 넘어간다")
  void withoutNamesIsAccepted() throws Exception {
    when(apple.enabled()).thenReturn(true);
    when(apple.verify(IDENTITY_TOKEN, CODE, NONCE, null, null)).thenReturn(IDENTITY);
    when(signIn.signIn(IDENTITY, INSTALL_ID))
        .thenReturn(new SignedIn(USER, false, true, new IssuedToken(REFRESH, REFRESH_TTL_SECONDS)));
    when(users.profile(USER)).thenReturn(Optional.of(profile()));

    signInRequest(
            "{\"identityToken\":\""
                + IDENTITY_TOKEN
                + "\",\"authorizationCode\":\""
                + CODE
                + "\",\"nonce\":\""
                + NONCE
                + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isNewUser").value(false))
        .andExpect(jsonPath("$.merged").value(true));
  }

  // ───────────── 401 · 503 ─────────────

  @Test
  @DisplayName("INVALID 면 401 SOCIAL_TOKEN_INVALID — 원인과 상관없이 같은 문구, 원인을 드러내지 않고 로그인하지 않는다")
  void invalidIs401() throws Exception {
    when(apple.enabled()).thenReturn(true);
    List<String> causes =
        List.of("nonce sha256 mismatch", "invalid_grant: code already used", "aud com.evil");
    List<String> messages = new ArrayList<>();
    for (String cause : causes) {
      doThrow(SocialTokenFailures.invalid(cause))
          .when(apple)
          .verify(any(), any(), any(), any(), any());
      List<Object> captured = new ArrayList<>();

      MvcResult result =
          signInRequest(body(IDENTITY_TOKEN, CODE, NONCE, null, null))
              .andExpect(status().isUnauthorized())
              .andExpect(jsonPath("$.code").value("SOCIAL_TOKEN_INVALID"))
              .andExpect(jsonPath("$.message").value(capturingInto(captured)))
              .andReturn();
      assertThat(result.getResponse().getContentAsString()).doesNotContain(cause);
      messages.add(String.valueOf(captured.get(0)));
    }

    assertThat(messages).allMatch(m -> m.equals(messages.get(0)));
    verifyNoInteractions(signIn);
  }

  @Test
  @DisplayName("UNAVAILABLE 이면 503 AUTH_PROVIDER_UNAVAILABLE — 로그인하지 않는다")
  void unavailableIs503() throws Exception {
    when(apple.enabled()).thenReturn(true);
    doThrow(SocialTokenFailures.unavailable("appleid.apple.com connect timed out"))
        .when(apple)
        .verify(any(), any(), any(), any(), any());

    MvcResult result =
        signInRequest(body(IDENTITY_TOKEN, CODE, NONCE, null, null))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AUTH_PROVIDER_UNAVAILABLE"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain("appleid.apple.com");
    verifyNoInteractions(signIn);
  }

  // ───────────── 400 ─────────────

  @Test
  @DisplayName(
      "필수 값(identityToken·authorizationCode·nonce)이 빠졌거나 nonce 가 16 자보다 짧으면 400 — 검증하지 않는다")
  void malformedBodyIs400() throws Exception {
    when(apple.enabled()).thenReturn(true);
    for (String json :
        List.of(
            "{\"authorizationCode\":\"" + CODE + "\",\"nonce\":\"" + NONCE + "\"}",
            "{\"identityToken\":\"" + IDENTITY_TOKEN + "\",\"nonce\":\"" + NONCE + "\"}",
            "{\"identityToken\":\"" + IDENTITY_TOKEN + "\",\"authorizationCode\":\"" + CODE + "\"}",
            body(IDENTITY_TOKEN, CODE, "0123456789abcde", null, null))) {
      signInRequest(json).andExpect(status().isBadRequest());
    }

    verify(apple, never()).verify(any(), any(), any(), any(), any());
    verifyNoInteractions(signIn);
  }

  @Test
  @DisplayName("X-Install-Id 가 없으면 400 MISSING_INSTALL_ID — 검증하지 않는다")
  void missingInstallIdIs400() throws Exception {
    when(apple.enabled()).thenReturn(true);

    mvc.perform(
            post("/auth/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(IDENTITY_TOKEN, CODE, NONCE, null, null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));

    verify(apple, never()).verify(any(), any(), any(), any(), any());
    verifyNoInteractions(signIn);
  }

  // ───────────── DELETE /me ─────────────

  @Test
  @DisplayName("탈퇴는 계정을 지우기 전에 애플 연결을 끊는다 — 204")
  void deleteMeRevokesBeforeDeleting() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.delete(USER)).thenReturn(true);

    mvc.perform(delete("/me").header("Authorization", bearer())).andExpect(status().isNoContent());

    InOrder order = inOrder(apple, users);
    order.verify(apple).revokeFor(USER);
    order.verify(users).delete(USER);
  }

  @Test
  @DisplayName("애플 끊기가 뜻밖에 던져도 탈퇴는 진행되어 204")
  void deleteMeProceedsWhenRevokeThrows() throws Exception {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.delete(USER)).thenReturn(true);
    doThrow(new IllegalStateException("apple down")).when(apple).revokeFor(USER);

    mvc.perform(delete("/me").header("Authorization", bearer())).andExpect(status().isNoContent());

    verify(users).delete(USER);
  }

  @Test
  @DisplayName("토큰 없이 탈퇴하면 애플 끊기도 하지 않는다")
  void deleteMeWithoutTokenDoesNotRevoke() throws Exception {
    mvc.perform(delete("/me").header("X-Install-Id", INSTALL_ID.toString()))
        .andExpect(status().isUnauthorized());

    verify(apple, never()).revokeFor(any());
    verify(users, never()).delete(any());
  }

  // ───────────── 도우미 ─────────────

  private ResultActions signInRequest(String json) throws Exception {
    return mvc.perform(
        post("/auth/apple")
            .header("X-Install-Id", INSTALL_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private String bearer() {
    return "Bearer " + tokens.issue(USER).value();
  }

  private static String body(
      String identityToken, String code, String nonce, String givenName, String familyName) {
    return "{\"identityToken\":\""
        + identityToken
        + "\",\"authorizationCode\":\""
        + code
        + "\",\"nonce\":\""
        + nonce
        + "\",\"givenName\":"
        + json(givenName)
        + ",\"familyName\":"
        + json(familyName)
        + "}";
  }

  private static String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static Profile profile() {
    return new Profile(
        USER, REGISTERED_AT, List.of(new Identity("apple", "x@privaterelay.appleid.com", "김철수")));
  }

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
}
