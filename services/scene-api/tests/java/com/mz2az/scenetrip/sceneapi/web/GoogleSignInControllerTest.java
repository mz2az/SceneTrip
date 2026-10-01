package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /auth/google} 을 명세(계약의 경로·{@code GoogleSignIn}·{@code AuthSession}·{@code
 * SocialTokenInvalid}·{@code AuthProviderUnavailable}, 오류 문서의 인증·503 절)에 비춰 HTTP 로 본다.
 *
 * <p>{@link AccessTokens} 는 진짜다 — 응답의 액세스 토큰이 로그인한 계정으로 풀려야 한다. 검증기와 로그인 서비스는 가짜이고, 그 둘의 실제 동작은
 * {@code GoogleIdTokenVerifierTest} 와 통합 레인({@code SignInServiceIntegrationTest} · {@code
 * GoogleSignInIntegrationTest})이 본다.
 */
@WebMvcTest(AuthController.class)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  GoogleSignInControllerTest.Tokens.class
})
@DisplayName("AuthController — 구글 로그인")
class GoogleSignInControllerTest {

  private static final byte[] SECRET = bytes(32, (byte) 11);
  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final long REFRESH_TTL_SECONDS = Duration.ofDays(60).toSeconds();

  private static final UUID INSTALL_ID = UUID.fromString("0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21");
  private static final UUID USER = UUID.fromString("1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b");
  private static final OffsetDateTime REGISTERED_AT = OffsetDateTime.parse("2026-10-01T01:02:03Z");

  private static final String ID_TOKEN = "header.payload.signature-from-google-sdk";
  private static final String NONCE = "a1b2c3d4e5f6a7b8c9d0";
  private static final String REFRESH = "refresh-from-sign-in-service-cccccccccccccccc";
  private static final SocialIdentity IDENTITY =
      new SocialIdentity("google", "google-sub-1", "me@example.com", "김여행");

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

  // 애플 로그인 — 이 시험의 대상이 아니다. 목의 enabled() 는 false 라 /auth/apple 은 501, 탈퇴의 애플 끊기는 하지 않는다.
  @MockitoBean private AppleLogin apple;

  // ───────────── 200 ─────────────

  @Test
  @DisplayName("가입이면 200 — 로그인한 계정의 액세스 토큰, 서비스가 준 리프레시 토큰, 수명, 내 계정, isNewUser=true·merged=false")
  void signUpReturnsSession() throws Exception {
    when(google.verify(ID_TOKEN, NONCE)).thenReturn(IDENTITY);
    when(signIn.signIn(IDENTITY, INSTALL_ID))
        .thenReturn(new SignedIn(USER, true, false, new IssuedToken(REFRESH, REFRESH_TTL_SECONDS)));
    when(users.profile(USER)).thenReturn(Optional.of(profile()));
    List<Object> accessTokens = new ArrayList<>();

    signInRequest(body(ID_TOKEN, NONCE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value(capturingInto(accessTokens)))
        .andExpect(jsonPath("$.accessTokenExpiresIn").value((int) ACCESS_TTL.toSeconds()))
        .andExpect(jsonPath("$.refreshToken").value(REFRESH))
        .andExpect(jsonPath("$.refreshTokenExpiresIn").value((int) REFRESH_TTL_SECONDS))
        .andExpect(jsonPath("$.user.id").value(USER.toString()))
        .andExpect(jsonPath("$.user.email").value("me@example.com"))
        .andExpect(jsonPath("$.user.displayName").value("김여행"))
        .andExpect(jsonPath("$.user.identities[0].provider").value("google"))
        .andExpect(jsonPath("$.user.identities[0].email").value("me@example.com"))
        .andExpect(jsonPath("$.user.registeredAt").exists())
        .andExpect(jsonPath("$.isNewUser").value(true))
        .andExpect(jsonPath("$.merged").value(false));

    assertThat(tokens.verify(String.valueOf(accessTokens.get(0)))).isEqualTo(USER);
    verify(google).verify(ID_TOKEN, NONCE);
    verify(signIn).signIn(IDENTITY, INSTALL_ID);
  }

  @Test
  @DisplayName("합쳤으면 merged=true·isNewUser=false 를 그대로 싣고, 액세스 토큰은 합쳐진 계정의 것이다")
  void mergePassesFlagsThrough() throws Exception {
    when(google.verify(ID_TOKEN, NONCE)).thenReturn(IDENTITY);
    when(signIn.signIn(IDENTITY, INSTALL_ID))
        .thenReturn(new SignedIn(USER, false, true, new IssuedToken(REFRESH, REFRESH_TTL_SECONDS)));
    when(users.profile(USER)).thenReturn(Optional.of(profile()));
    List<Object> accessTokens = new ArrayList<>();

    signInRequest(body(ID_TOKEN, NONCE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value(capturingInto(accessTokens)))
        .andExpect(jsonPath("$.isNewUser").value(false))
        .andExpect(jsonPath("$.merged").value(true))
        .andExpect(jsonPath("$.user.id").value(USER.toString()));

    assertThat(tokens.verify(String.valueOf(accessTokens.get(0)))).isEqualTo(USER);
  }

  @Test
  @DisplayName("nonce 가 정확히 16 자면 받는다 — minLength 의 경계")
  void sixteenCharNonceIsAccepted() throws Exception {
    String nonce = "0123456789abcdef";
    when(google.verify(ID_TOKEN, nonce)).thenReturn(IDENTITY);
    when(signIn.signIn(IDENTITY, INSTALL_ID))
        .thenReturn(
            new SignedIn(USER, false, false, new IssuedToken(REFRESH, REFRESH_TTL_SECONDS)));
    when(users.profile(USER)).thenReturn(Optional.of(profile()));

    signInRequest(body(ID_TOKEN, nonce)).andExpect(status().isOk());
  }

  // ───────────── 401 · 503 ─────────────

  @Test
  @DisplayName(
      "검증기가 INVALID 면 401 SOCIAL_TOKEN_INVALID — 원인과 상관없이 같은 문구이고 원인을 드러내지 않는다, 로그인은 하지 않는다")
  void invalidTokenIs401WithUniformMessage() throws Exception {
    List<String> causes =
        List.of(
            "서명이 맞지 않습니다 kid=google-key-1",
            "aud 700188854872-a36b6471q8h03lvkdilnubtmeu658ocd 는 허용 목록에 없습니다",
            "nonce 불일치",
            "만료됨 exp=2030-01-01T00:00:00Z");
    List<String> messages = new ArrayList<>();
    for (String cause : causes) {
      doThrow(SocialTokenFailures.invalid(cause)).when(google).verify(any(), any());
      List<Object> captured = new ArrayList<>();

      MvcResult result =
          signInRequest(body(ID_TOKEN, NONCE))
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
  @DisplayName("검증기가 UNAVAILABLE 이면 503 AUTH_PROVIDER_UNAVAILABLE — 로그인은 하지 않는다")
  void unavailableProviderIs503() throws Exception {
    doThrow(SocialTokenFailures.unavailable("www.googleapis.com connect timed out"))
        .when(google)
        .verify(any(), any());

    MvcResult result =
        signInRequest(body(ID_TOKEN, NONCE))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AUTH_PROVIDER_UNAVAILABLE"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain("googleapis");
    verifyNoInteractions(signIn);
  }

  // ───────────── 400 ─────────────

  @Test
  @DisplayName("X-Install-Id 가 없으면 400 MISSING_INSTALL_ID — 검증도 로그인도 하지 않는다")
  void missingInstallIdIs400() throws Exception {
    mvc.perform(
            post("/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ID_TOKEN, NONCE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));

    verifyNoInteractions(google, signIn);
  }

  @Test
  @DisplayName("X-Install-Id 가 UUID 가 아니면 400 MISSING_INSTALL_ID")
  void malformedInstallIdIs400() throws Exception {
    mvc.perform(
            post("/auth/google")
                .header("X-Install-Id", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ID_TOKEN, NONCE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));

    verifyNoInteractions(google, signIn);
  }

  @Test
  @DisplayName("idToken 이 없으면 400")
  void missingIdTokenIs400() throws Exception {
    signInRequest("{\"nonce\":\"" + NONCE + "\"}").andExpect(status().isBadRequest());

    verifyNoInteractions(google, signIn);
  }

  @Test
  @DisplayName("nonce 가 없으면 400")
  void missingNonceIs400() throws Exception {
    signInRequest("{\"idToken\":\"" + ID_TOKEN + "\"}").andExpect(status().isBadRequest());

    verifyNoInteractions(google, signIn);
  }

  @Test
  @DisplayName("nonce 가 16 자보다 짧으면 400")
  void shortNonceIs400() throws Exception {
    signInRequest(body(ID_TOKEN, "0123456789abcde")).andExpect(status().isBadRequest());

    verifyNoInteractions(google, signIn);
  }

  @Test
  @DisplayName("본문이 없으면 400")
  void missingBodyIs400() throws Exception {
    mvc.perform(
            post("/auth/google")
                .header("X-Install-Id", INSTALL_ID.toString())
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(google, signIn);
  }

  // ───────────── 도우미 ─────────────

  private ResultActions signInRequest(String json) throws Exception {
    MockHttpServletRequestBuilder request =
        post("/auth/google")
            .header("X-Install-Id", INSTALL_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json);
    return mvc.perform(request);
  }

  private static String body(String idToken, String nonce) {
    return "{\"idToken\":\"" + idToken + "\",\"nonce\":\"" + nonce + "\"}";
  }

  private static Profile profile() {
    return new Profile(
        USER, REGISTERED_AT, List.of(new Identity("google", "me@example.com", "김여행")));
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
