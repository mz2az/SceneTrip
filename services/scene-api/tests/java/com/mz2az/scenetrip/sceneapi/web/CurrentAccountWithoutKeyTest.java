package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Account;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 서명 키가 없는 서버 — 로그인이 꺼진 상태에서 {@link CurrentAccount} 가 닫힌 쪽으로 실패하는가.
 *
 * <p>컨텍스트가 {@link CurrentAccountTest} 와 달라(키 없는 {@link AccessTokens}) 클래스를 나눴다.
 */
@WebMvcTest(CartController.class)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  CurrentAccountWithoutKeyTest.NoKey.class
})
@DisplayName("CurrentAccount — 서명 키가 없을 때")
class CurrentAccountWithoutKeyTest {

  private static final Duration TTL = Duration.ofMinutes(30);
  private static final UUID INSTALL_ID = UUID.fromString("3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44");
  private static final UUID INSTALL_USER = UUID.fromString("1b6a0e33-2f4d-4c8e-9a71-5d0c3e8f7b26");
  private static final UUID TOKEN_USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @TestConfiguration
  static class NoKey {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(null, TTL, Clock.systemUTC());
    }
  }

  @Autowired private MockMvc mvc;

  @Autowired private AccessTokens tokens;

  @MockitoBean private UserStore users;

  @MockitoBean private CartStore store;

  @Test
  @DisplayName("키가 없으면 어떤 Bearer 토큰이든 401 ACCESS_TOKEN_INVALID — 키로 서명된 진짜 토큰이어도")
  void anyBearerIsInvalid() throws Exception {
    when(users.touchSignedIn(any())).thenReturn(true);
    byte[] someKey = new byte[32];
    Arrays.fill(someKey, (byte) 7);
    String signedElsewhere =
        new AccessTokens(someKey, TTL, Clock.systemUTC()).issue(TOKEN_USER).value();

    assertThat(tokens.enabled()).isFalse();
    for (String authorization : List.of("Bearer " + signedElsewhere, "Bearer not-a-jwt")) {
      mvc.perform(
              get("/cart")
                  .header("X-Install-Id", INSTALL_ID.toString())
                  .header("Authorization", authorization))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("키가 없어도 토큰 없는 비회원은 그대로 200")
  void guestStillWorks() throws Exception {
    // 키가 없으면 로그인만 꺼지고 비회원 기능은 그대로 돈다(AccessTokens 의 「키가 없으면」).
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));
    when(store.list(any(), any())).thenReturn(new CartStore.Contents(List.of(), false));

    mvc.perform(get("/cart").header("X-Install-Id", INSTALL_ID.toString()))
        .andExpect(status().isOk());

    verify(store).list(eq(INSTALL_USER), any());
  }
}
