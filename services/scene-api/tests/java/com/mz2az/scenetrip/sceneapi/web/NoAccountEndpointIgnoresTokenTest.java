package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.search.SuggestionStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 계정이 필요 없는 창구(검색)는 {@code Authorization} 을 보지 않는다 — 계약 「인증」 절과 {@link CurrentAccount} 의 설명.
 *
 * <p>진짜 {@link CurrentAccount} 와 {@link AccessTokens} 를 컨텍스트에 함께 올린다. 그래야 누군가 토큰 검사를 전역(필터·인터셉터·
 * 컨트롤러 어드바이스)으로 옮겼을 때 이 테스트가 깨진다 — 둘이 없으면 검사할 수단이 없어 늘 통과한다.
 */
@WebMvcTest(SearchController.class)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  NoAccountEndpointIgnoresTokenTest.Tokens.class
})
@DisplayName("계정이 필요 없는 창구 — Authorization 무시")
class NoAccountEndpointIgnoresTokenTest {

  private static final Duration TTL = Duration.ofMinutes(30);

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      byte[] key = new byte[32];
      Arrays.fill(key, (byte) 7);
      return new AccessTokens(key, TTL, Clock.systemUTC());
    }
  }

  @Autowired private MockMvc mvc;

  @MockitoBean private SuggestionStore store;

  @MockitoBean private UserStore users;

  @Test
  @DisplayName("깨진 토큰·만료된 토큰·다른 스킴을 달아도 검색은 200")
  void searchIgnoresAuthorization() throws Exception {
    when(store.suggest(any(), any(), anyInt()))
        .thenReturn(new SuggestionStore.Result(List.of(), false));
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 7);
    // 같은 키로 서명했지만 오래전에 만료된 토큰. 키가 맞아야 「만료」 판정까지 간다.
    String expired =
        new AccessTokens(
                key, TTL, Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC))
            .issue(UUID.randomUUID())
            .value();

    for (String authorization :
        List.of("Bearer garbage", "Bearer " + expired, "Basic dXNlcjpwYXNz", "Bearer ", "")) {
      mvc.perform(
              get("/search/suggestions").param("q", "도깨비").header("Authorization", authorization))
          .andExpect(status().isOk());
    }
    verifyNoInteractions(users);
  }
}
