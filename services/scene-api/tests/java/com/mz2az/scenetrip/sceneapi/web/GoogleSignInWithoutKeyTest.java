package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
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
 * 서명 키가 없어 로그인이 꺼진 서버의 {@code POST /auth/google}.
 *
 * <p>가입시켜 놓고 액세스 토큰을 못 주면 「가입은 됐는데 로그인이 안 된다」 가 된다. 그래서 검증도 로그인 서비스도 부르기 전에 멈춰야 하고, 서버 설정 문제라 계약의
 * {@code 500} 이다. 컨텍스트가 {@link GoogleSignInControllerTest} 와 달라 클래스를 나눴다.
 */
@WebMvcTest(AuthController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, GoogleSignInWithoutKeyTest.NoKey.class})
@DisplayName("AuthController — 서명 키가 없을 때의 구글 로그인")
class GoogleSignInWithoutKeyTest {

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

  @Test
  @DisplayName("키가 없으면 500 INTERNAL_ERROR — 검증기도 로그인 서비스도 부르지 않는다(아무도 가입시키지 않는다)")
  void signInStopsBeforeAnything() throws Exception {
    mvc.perform(
            post("/auth/google")
                .header("X-Install-Id", "0e1d2c3b-4a59-4687-9a8b-7c6d5e4f3a21")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"h.p.s\",\"nonce\":\"a1b2c3d4e5f6a7b8c9d0\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    verifyNoInteractions(google, signIn, refreshTokens);
  }
}
