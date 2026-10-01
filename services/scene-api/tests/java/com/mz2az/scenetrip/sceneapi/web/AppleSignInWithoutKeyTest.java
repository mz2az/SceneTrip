package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.GoogleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
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

/**
 * 애플 로그인은 켜져 있지만 JWT 서명 키가 없는 서버의 {@code POST /auth/apple}.
 *
 * <p>가입시켜 놓고 액세스 토큰을 못 주면 안 된다 — 계약의 {@code 500} 이고 아무도 가입시키지 않는다. 컨텍스트가 {@link
 * AppleSignInControllerTest} 와 달라 클래스를 나눴다.
 */
@WebMvcTest(AuthController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, AppleSignInWithoutKeyTest.NoKey.class})
@DisplayName("AuthController — 서명 키가 없을 때의 애플 로그인")
class AppleSignInWithoutKeyTest {

  @TestConfiguration
  static class NoKey {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(null, Duration.ofMinutes(30), Clock.systemUTC());
    }
  }

  @Autowired private MockMvc mvc;

  @MockitoBean private RefreshTokenStore refreshTokens;

  @MockitoBean private UserStore users;

  @MockitoBean private GoogleIdTokenVerifier google;

  @MockitoBean private SignInService signIn;

  @MockitoBean private AppleLogin apple;

  @Test
  @DisplayName("애플이 켜져 있어도 서명 키가 없으면 500 INTERNAL_ERROR — 아무도 가입시키지 않는다")
  void signInStopsBeforeRegistering() throws Exception {
    when(apple.enabled()).thenReturn(true);

    mvc.perform(
            post("/auth/apple")
                .header("X-Install-Id", "0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"identityToken\":\"h.p.s\",\"authorizationCode\":\"c.code\","
                        + "\"nonce\":\"a1b2c3d4e5f6a7b8c9d0\",\"givenName\":\"철수\","
                        + "\"familyName\":\"김\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    verifyNoInteractions(signIn, refreshTokens);
    // 코드는 5 분짜리 일회용이다 — 가입시키지 못할 거면 교환해 써 버리지도 않는다.
    verify(apple, never()).verify(any(), any(), any(), any(), any());
  }
}
