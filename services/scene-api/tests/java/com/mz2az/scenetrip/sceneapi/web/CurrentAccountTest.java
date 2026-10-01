package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 요청의 계정을 정하는 규칙을 명세(계약 「인증」 절과 {@code Unauthorized}, 계획 §3·§6, 오류 문서의 인증 절)에 비춰 본다.
 *
 * <p>{@link CurrentAccount} 를 직접 부르지 않고 HTTP 로 친다. 헤더를 읽는 쪽이 진짜여야 스킴 대소문자·빈 토큰 같은 경계가 실제 요청 그대로
 * 검증된다. 그래서 {@link CurrentAccount} 와 {@link AccessTokens} 는 진짜이고, DB 를 아는 {@link UserStore} 와 장바구니
 * Store 만 가짜다. 장바구니를 고른 것은 계정이 필요하면서 가입 벽이 없는 창구라서다 — 결과가 이 규칙에서만 갈린다.
 */
@WebMvcTest(CartController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, CurrentAccountTest.Tokens.class})
@DisplayName("CurrentAccount — 요청의 계정 결정")
class CurrentAccountTest {

  private static final byte[] SECRET = bytes(32, (byte) 7);
  private static final byte[] OTHER_SECRET = bytes(32, (byte) 9);
  private static final Duration TTL = Duration.ofMinutes(30);
  private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

  private static final UUID INSTALL_ID = UUID.fromString("3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44");

  /** 토큰이 가리키는 계정. */
  private static final UUID TOKEN_USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  /** 설치 UUID 가 가리키는 계정 — 토큰의 계정과 일부러 다르다. */
  private static final UUID INSTALL_USER = UUID.fromString("1b6a0e33-2f4d-4c8e-9a71-5d0c3e8f7b26");

  /** 컨텍스트는 테스트 사이에 재사용되므로 시계를 정적으로 두고 매번 되감는다. */
  static final MutableClock CLOCK = new MutableClock(T0);

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(SECRET, TTL, CLOCK);
    }
  }

  @Autowired private MockMvc mvc;

  @Autowired private AccessTokens tokens;

  @MockitoBean private UserStore users;

  @MockitoBean private CartStore store;

  @BeforeEach
  void setUp() {
    CLOCK.set(T0);
    when(store.list(any(), any())).thenReturn(new CartStore.Contents(List.of(), false));
  }

  private String validToken() {
    return tokens.issue(TOKEN_USER).value();
  }

  private ResultActions getCart(String authorization) throws Exception {
    var request = get("/cart").header("X-Install-Id", INSTALL_ID.toString());
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    return mvc.perform(request);
  }

  private ResultActions expectInvalid(String authorization) throws Exception {
    return getCart(authorization)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
  }

  // ───────────── 토큰이 있으면 ─────────────

  @Test
  @DisplayName("유효한 토큰이면 그 토큰의 계정으로 Store 를 부른다")
  void validTokenResolvesToTokenAccount() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);

    getCart("Bearer " + validToken()).andExpect(status().isOk());

    verify(store).list(eq(TOKEN_USER), any());
  }

  @Test
  @DisplayName("토큰과 설치 UUID 가 다른 계정을 가리키면 토큰이 이긴다 — 설치 UUID 로 계정을 찾지도 않는다")
  void tokenWinsOverInstallId() throws Exception {
    // lookup 은 처음 보는 설치본에 비회원 계정을 만든다. 토큰이 있는 요청에서 그것이 불리면
    // 로그인한 사람의 요청마다 쓰레기 계정이 생길 수 있다.
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);

    getCart("Bearer " + validToken()).andExpect(status().isOk());

    verify(store).list(eq(TOKEN_USER), any());
    verify(store, never()).list(eq(INSTALL_USER), any());
    verify(users, never()).lookup(any());
  }

  @Test
  @DisplayName("설치 UUID 가 가입 계정을 가리켜도 토큰이 있으면 SESSION_REQUIRED 가 아니다")
  void tokenBypassesSessionRequired() throws Exception {
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, true));
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);

    getCart("Bearer " + validToken()).andExpect(status().isOk());

    verify(store).list(eq(TOKEN_USER), any());
  }

  @Test
  @DisplayName("스킴 이름은 대소문자를 가리지 않는다 — bearer · BEARER (RFC 7235)")
  void schemeIsCaseInsensitive() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);

    getCart("bearer " + validToken()).andExpect(status().isOk());
    getCart("BEARER " + validToken()).andExpect(status().isOk());
  }

  @Test
  @DisplayName("수명이 지난 진짜 토큰은 401 ACCESS_TOKEN_EXPIRED")
  void expiredTokenIsExpired() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);
    String token = validToken();
    CLOCK.advance(TTL.plusSeconds(1));

    getCart("Bearer " + token)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));

    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("만료된 토큰이면 설치 UUID 로 물러서지 않는다")
  void expiredTokenDoesNotFallBackToInstallId() throws Exception {
    // 비회원 계정으로 조용히 열어 주면 앱은 갱신할 기회를 잃고, 로그인한 사람이 빈 장바구니를 본다.
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));
    String token = validToken();
    CLOCK.advance(TTL.plusSeconds(1));

    getCart("Bearer " + token).andExpect(status().isUnauthorized());

    verify(users, never()).lookup(any());
  }

  @Test
  @DisplayName("Bearer 가 아닌 스킴은 401 ACCESS_TOKEN_INVALID")
  void nonBearerSchemeIsInvalid() throws Exception {
    expectInvalid("Basic dXNlcjpwYXNz");
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("스킴 없이 토큰만 오면 401 ACCESS_TOKEN_INVALID")
  void tokenWithoutSchemeIsInvalid() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);

    expectInvalid(validToken());
  }

  @Test
  @DisplayName("Bearer 뒤가 비어 있으면 401 ACCESS_TOKEN_INVALID")
  void emptyBearerTokenIsInvalid() throws Exception {
    expectInvalid("Bearer ");
    expectInvalid("Bearer");
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("Authorization 헤더가 빈 값으로 오면 토큰이 없는 것이 아니라 깨진 것이다")
  void emptyAuthorizationHeaderIsInvalid() throws Exception {
    // 헤더가 「있다」 는 사실이 갈림길이다. 빈 값을 없는 것으로 보면 비회원 경로로 빠지는데,
    // 앱이 토큰을 붙이려다 실패한 요청이 조용히 다른 계정으로 열린다.
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));

    expectInvalid("");
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("JWT 모양이 아닌 문자열은 401 ACCESS_TOKEN_INVALID")
  void malformedTokenIsInvalid() throws Exception {
    expectInvalid("Bearer not-a-jwt");
    expectInvalid("Bearer a.b.c");
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("다른 키로 서명한 토큰은 401 ACCESS_TOKEN_INVALID")
  void tokenFromOtherKeyIsInvalid() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);
    String forged = new AccessTokens(OTHER_SECRET, TTL, CLOCK).issue(TOKEN_USER).value();

    expectInvalid("Bearer " + forged);
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("다른 키로 서명한 토큰은 만료됐어도 EXPIRED 가 아니라 INVALID")
  void expiredForgedTokenIsInvalid() throws Exception {
    // 위조 토큰에 만료라고 답하면 앱이 갱신을 시도하고, 공격자에게는 그럴듯했다는 신호가 된다.
    String forged = new AccessTokens(OTHER_SECRET, TTL, CLOCK).issue(TOKEN_USER).value();
    CLOCK.advance(TTL.plusSeconds(1));

    expectInvalid("Bearer " + forged);
  }

  @Test
  @DisplayName("서명은 맞는데 계정이 살아 있지 않으면(탈퇴·합쳐짐·미가입) 401 ACCESS_TOKEN_INVALID")
  void validTokenForDeadAccountIsInvalid() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(false);
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));

    expectInvalid("Bearer " + validToken());

    verify(users).touchSignedIn(TOKEN_USER);
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("ACCESS_TOKEN_INVALID 의 message 는 원인이 무엇이든 같은 문구이고 원인을 드러내지 않는다")
  void invalidMessageDoesNotRevealCause() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(false);
    String otherKey = new AccessTokens(OTHER_SECRET, TTL, CLOCK).issue(TOKEN_USER).value();
    String[] parts = validToken().split("\\.");
    String badSignature = parts[0] + "." + parts[1] + "." + "A".repeat(parts[2].length());

    List<Object> messages = new ArrayList<>();
    for (String authorization :
        List.of(
            "Basic dXNlcjpwYXNz",
            "Bearer ",
            "Bearer not-a-jwt",
            "Bearer " + otherKey,
            "Bearer " + badSignature,
            // 서명은 맞고 계정이 죽었다 — DB 판정 쪽 문구도 같아야 한다.
            "Bearer " + validToken())) {
      expectInvalid(authorization).andExpect(jsonPath("$.message").value(capturingInto(messages)));
    }

    assertThat(messages).doesNotContainNull();
    assertThat(messages.stream().distinct()).hasSize(1);
    String message = String.valueOf(messages.get(0)).toLowerCase();
    assertThat(message)
        .doesNotContain("서명")
        .doesNotContain("발급자")
        .doesNotContain("signature")
        .doesNotContain("issuer")
        .doesNotContain("key")
        .doesNotContain("탈퇴")
        .doesNotContain("합쳐");
  }

  // ───────────── 토큰이 없으면 ─────────────

  @Test
  @DisplayName("토큰이 없고 설치 UUID 가 비회원 계정이면 그 계정으로 200")
  void guestWithoutTokenUsesInstallAccount() throws Exception {
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));

    getCart(null).andExpect(status().isOk());

    verify(store).list(eq(INSTALL_USER), any());
    verify(users, never()).touchSignedIn(any());
  }

  @Test
  @DisplayName("토큰 없이 설치 UUID 만으로 가입 계정에 닿으면 401 SESSION_REQUIRED")
  void registeredAccountWithoutTokenNeedsSession() throws Exception {
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, true));

    getCart(null)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));

    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("SESSION_REQUIRED 는 가입 판정 설정(isRegistered)과 무관하게 lookup 의 registered 로 정해진다")
  void sessionRequiredIgnoresRegistrationBypass() throws Exception {
    // 로컬 우회(require-registration=false)에서는 isRegistered 가 언제나 true 다. 그 값으로
    // 판정하면 우회 환경의 모든 비회원이 SESSION_REQUIRED 로 막히고, 반대로 쓰면 가입 계정이
    // 헤더 하나로 열린다. 보안 판정은 lookup 의 registered 하나여야 한다.
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, false));
    when(users.isRegistered(any())).thenReturn(true);

    getCart(null).andExpect(status().isOk());
    verify(store).list(eq(INSTALL_USER), any());

    reset(store);
    when(users.lookup(INSTALL_ID)).thenReturn(new Account(INSTALL_USER, true));
    when(users.isRegistered(any())).thenReturn(false);

    getCart(null)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
  }

  @Test
  @DisplayName("X-Install-Id 가 없으면 토큰이 있어도 400 MISSING_INSTALL_ID — 헤더 요구는 그대로다")
  void installIdStillRequiredWithToken() throws Exception {
    when(users.touchSignedIn(TOKEN_USER)).thenReturn(true);

    mvc.perform(get("/cart").header("Authorization", "Bearer " + validToken()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
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
