package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.GoogleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.IssuedToken;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Rotated;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Identity;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Profile;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * 서명 키가 없는 서버의 {@code /auth/refresh} — 로그인이 꺼져 있으면 갱신은 닫힌 쪽으로 실패하고, <b>리프레시 토큰을 쓰지 않는다</b>.
 *
 * <p>토큰을 먼저 쓰고 나서 액세스 토큰 발급에서 실패하면 앱의 리프레시 토큰은 「이미 쓴 것」 이 된다. 키를 고쳐 다시 띄운 서버에 그 토큰이 오면 재사용으로 판정되어 그
 * 계정의 로그인이 전부 끊긴다. 컨텍스트가 {@link AuthControllerTest} 와 달라 클래스를 나눴다.
 */
@WebMvcTest(AuthController.class)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  AuthControllerWithoutKeyTest.NoKey.class
})
@DisplayName("AuthController — 서명 키가 없을 때")
class AuthControllerWithoutKeyTest {

  private static final UUID USER = UUID.fromString("7a3e9c10-4b2d-4e8f-a1c6-0d9b8e7f6a52");

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

  // 구글 로그인 창구의 의존성. 이 시험의 대상(갱신·로그아웃·내 계정·탈퇴)이 아니라 비워 둔다.
  @MockitoBean private GoogleIdTokenVerifier google;

  @MockitoBean private SignInService signIn;

  @Test
  @DisplayName("키가 없으면 갱신은 401 REFRESH_TOKEN_INVALID 이고 리프레시 토큰을 교체(소비)하지 않는다")
  void refreshFailsClosedWithoutConsumingToken() throws Exception {
    // 교체가 불리면 응답과 상관없이 토큰은 「쓴 것」 이 된다 — 불려서는 안 된다.
    when(refreshTokens.rotate(any()))
        .thenReturn(new Rotated(USER, new IssuedToken("next-refresh-token", 5_184_000)));
    when(users.profile(USER))
        .thenReturn(
            Optional.of(
                new Profile(
                    USER,
                    OffsetDateTime.parse("2026-09-20T00:00:00Z"),
                    List.of(new Identity("google", "me@example.com", "김여행")))));

    mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"some-refresh-token\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

    verify(refreshTokens, never()).rotate(any());
  }
}
