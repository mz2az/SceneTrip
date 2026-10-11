package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.Set;
import java.util.UUID;
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

/**
 * {@code X-Rate-Subject} 는 한도 칸을 고를 뿐 계정을 정하지 않는다(ADR 0023, guide-rate-subject.md §5).
 *
 * <p>서비스 비밀값을 설정하고 맞는 {@code X-Service-Token} 까지 실어 보낸다 — 분당 필터가 그 열쇠를 <b>믿는</b> 상태에서도 쓰기 창구가 지금처럼
 * 거절되는지 본다. 그래서 요청을 {@code /v1} 아래로 보내 필터를 실제로 태우고, {@link CurrentAccount} 와 {@link AccessTokens} 는
 * 진짜다. DB 를 아는 {@link UserStore} 와 장바구니 Store 만 가짜다.
 */
@WebMvcTest(
    controllers = CartController.class,
    properties = "scenetrip.guide.service-token=" + RateSubjectIsNotCredentialTest.SERVICE)
@Import({
  LanguageConfiguration.class,
  CurrentAccount.class,
  RateSubjectIsNotCredentialTest.Tokens.class
})
@DisplayName("X-Rate-Subject — 계정을 정하지 않는다")
class RateSubjectIsNotCredentialTest {

  /** 서비스 비밀값 픽스처 — 진짜 값이 아니다. */
  static final String SERVICE = "FIXTURE-service-token-0123456789abcdef";

  private static final UUID INSTALL_ID = UUID.fromString("3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44");

  /** 열쇠가 가리키는 가입 계정 — 이 계정으로 열리면 안 된다. */
  private static final UUID VICTIM = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  /** 설치 UUID 의 계정. */
  private static final UUID INSTALL_USER = UUID.fromString("1b6a0e33-2f4d-4c8e-9a71-5d0c3e8f7b26");

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      byte[] secret = new byte[32];
      Arrays.fill(secret, (byte) 7);
      return new AccessTokens(secret, Duration.ofMinutes(30), Clock.systemUTC());
    }
  }

  @Autowired private MockMvc mvc;

  @MockitoBean private UserStore users;
  @MockitoBean private CartStore store;

  @BeforeEach
  void setUp() {
    when(store.list(any(), any())).thenReturn(new CartStore.Contents(List.of(), Set.of()));
    when(users.touchSignedIn(any())).thenReturn(true);
    // 담기는 장소가 있는지를 계정보다 먼저 볼 수 있다 — 결과가 계정 규칙에서만 갈리게 장소는 있다고 둔다.
    when(store.placeExists(anyLong())).thenReturn(true);
  }

  @Test
  @DisplayName("열쇠만 단 쓰기(POST /v1/cart/items)는 지금처럼 400 MISSING_INSTALL_ID — Store 에 닿지 않는다")
  void writeWithOnlySubjectIsRejected() throws Exception {
    mvc.perform(
            post("/v1/cart/items")
                .contextPath("/v1")
                .header("X-Service-Token", SERVICE)
                .header("X-Rate-Subject", "user:" + VICTIM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":2,\"sourceContentId\":2}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));

    verify(store, never()).add(any(), anyLong(), any(), any());
    verify(users, never()).touchSignedIn(any());
  }

  @Test
  @DisplayName("열쇠만 단 지우기(DELETE /v1/cart/items/{id})도 거절 — Store 에 닿지 않는다")
  void deleteWithOnlySubjectIsRejected() throws Exception {
    mvc.perform(
            delete("/v1/cart/items/2")
                .contextPath("/v1")
                .header("X-Service-Token", SERVICE)
                .header("X-Rate-Subject", "user:" + VICTIM))
        .andExpect(status().is4xxClientError());

    verify(store, never()).remove(any(), anyLong());
  }

  @Test
  @DisplayName("가입 계정의 설치 UUID + 그 계정의 열쇠여도 토큰이 없으면 401 SESSION_REQUIRED")
  void subjectDoesNotStandInForSession() throws Exception {
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(VICTIM, true));

    mvc.perform(
            post("/v1/cart/items")
                .contextPath("/v1")
                .header("X-Install-Id", INSTALL_ID.toString())
                .header("X-Service-Token", SERVICE)
                .header("X-Rate-Subject", "user:" + VICTIM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":2,\"sourceContentId\":2}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));

    verify(store, never()).add(any(), anyLong(), any(), any());
  }

  @Test
  @DisplayName("비회원 설치 UUID + 다른 가입자의 열쇠 — 계정은 설치 UUID 의 것이다")
  void accountComesFromInstallIdNotSubject() throws Exception {
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));

    mvc.perform(
            get("/v1/cart")
                .contextPath("/v1")
                .header("X-Install-Id", INSTALL_ID.toString())
                .header("X-Service-Token", SERVICE)
                .header("X-Rate-Subject", "user:" + VICTIM))
        .andExpect(status().isOk());

    verify(store).list(eq(INSTALL_USER), any());
    verify(store, never()).list(eq(VICTIM), any());
  }
}
