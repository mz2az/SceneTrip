package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.auth.SocialTokenException.Reason;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * 애플 로그인 묶음을 명세({@code AppleLogin} 클래스 설명, 계약 {@code /auth/apple}·{@code DELETE /me}, 계획 §5·§6·「애플
 * 이름」, MZ2AZ-337)에 비춰 본다.
 *
 * <p>검증기와 애플 클라이언트는 목이다 — 둘의 실제 동작은 {@code AppleIdTokenVerifierTest}·{@code AppleClientTest} 가 본다.
 * 암호기는 진짜다.
 */
@DisplayName("AppleLogin — 검증·교환·암호화·탈퇴 때 끊기")
class AppleLoginTest {

  private static final String IDENTITY_TOKEN = "h.p.s-identity-token";
  private static final String CODE = "c.authorization-code";
  private static final String RAW_NONCE = "raw-nonce-0123456789abcdef";
  private static final String REFRESH = "r.apple-refresh-token";
  private static final UUID USER = UUID.fromString("3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b9a");
  private static final SocialIdentity VERIFIED =
      new SocialIdentity("apple", "apple-sub-1", "x@privaterelay.appleid.com", null);

  private final AppleIdTokenVerifier verifier = mock(AppleIdTokenVerifier.class);
  private final AppleClient client = mock(AppleClient.class);
  private final TokenCipher cipher = new TokenCipher(bytes(32, (byte) 21));
  private final AccountLinkStore links = mock(AccountLinkStore.class);

  // ───────────── 켜짐 ─────────────

  @Test
  @DisplayName("애플 개인 키와 암호화 키가 둘 다 있어야 켜진다")
  void enabledOnlyWithBoth() {
    TokenCipher off = new TokenCipher((byte[]) null);

    when(client.enabled()).thenReturn(true);
    assertThat(new AppleLogin(verifier, client, cipher, links).enabled()).isTrue();
    assertThat(new AppleLogin(verifier, client, off, links).enabled()).isFalse();

    when(client.enabled()).thenReturn(false);
    assertThat(new AppleLogin(verifier, client, cipher, links).enabled()).isFalse();
    assertThat(new AppleLogin(verifier, client, off, links).enabled()).isFalse();
  }

  // ───────────── 로그인 ─────────────

  @Test
  @DisplayName("검증 → 코드 교환 순서이고, 신분에 refresh token 의 암호문과 앱이 보낸 이름이 붙는다")
  void verifyThenExchangeAndEncrypt() {
    when(client.enabled()).thenReturn(true);
    when(verifier.verify(IDENTITY_TOKEN, RAW_NONCE)).thenReturn(VERIFIED);
    when(client.exchange(CODE)).thenReturn(REFRESH);

    SocialIdentity identity = login().verify(IDENTITY_TOKEN, CODE, RAW_NONCE, "Jane", "Doe");

    InOrder order = Mockito.inOrder(verifier, client);
    order.verify(verifier).verify(IDENTITY_TOKEN, RAW_NONCE);
    order.verify(client).exchange(CODE);
    assertThat(identity.provider()).isEqualTo("apple");
    assertThat(identity.subject()).isEqualTo("apple-sub-1");
    assertThat(identity.email()).isEqualTo("x@privaterelay.appleid.com");
    assertThat(identity.displayName()).isEqualTo("Jane Doe");
    assertThat(identity.appleRefreshTokenEnc()).isNotNull();
    assertThat(
            new String(
                identity.appleRefreshTokenEnc(), java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(REFRESH);
    assertThat(cipher.decrypt(identity.appleRefreshTokenEnc())).isEqualTo(REFRESH);
  }

  @Test
  @DisplayName("이름을 보내지 않으면(두 번째 로그인) 이름은 null")
  void noNameIsNull() {
    when(client.enabled()).thenReturn(true);
    when(verifier.verify(IDENTITY_TOKEN, RAW_NONCE)).thenReturn(VERIFIED);
    when(client.exchange(CODE)).thenReturn(REFRESH);

    SocialIdentity identity = login().verify(IDENTITY_TOKEN, CODE, RAW_NONCE, null, null);

    assertThat(identity.displayName()).isNull();
    assertThat(cipher.decrypt(identity.appleRefreshTokenEnc())).isEqualTo(REFRESH);
  }

  @Test
  @DisplayName("토큰이 INVALID·UNAVAILABLE 이면 그대로 던지고 코드 교환은 하지 않는다")
  void badTokenNeverReachesTokenEndpoint() {
    for (SocialTokenException failure :
        new SocialTokenException[] {
          SocialTokenFailures.invalid("nonce mismatch"),
          SocialTokenFailures.unavailable("jwks down")
        }) {
      AppleIdTokenVerifier v = mock(AppleIdTokenVerifier.class);
      AppleClient c = mock(AppleClient.class);
      when(c.enabled()).thenReturn(true);
      when(v.verify(anyString(), anyString())).thenThrow(failure);

      assertThatThrownBy(
              () ->
                  new AppleLogin(v, c, cipher, links)
                      .verify(IDENTITY_TOKEN, CODE, RAW_NONCE, "Jane", "Doe"))
          .isInstanceOfSatisfying(
              SocialTokenException.class, e -> assertThat(e.reason()).isEqualTo(failure.reason()));
      verify(c, never()).exchange(any());
    }
  }

  @Test
  @DisplayName("코드 교환이 INVALID(만료·재사용 코드)·UNAVAILABLE 이면 그 이유 그대로 던진다")
  void exchangeFailurePropagates() {
    for (Reason reason : Reason.values()) {
      AppleClient c = mock(AppleClient.class);
      when(c.enabled()).thenReturn(true);
      when(verifier.verify(IDENTITY_TOKEN, RAW_NONCE)).thenReturn(VERIFIED);
      when(c.exchange(CODE))
          .thenThrow(
              reason == Reason.INVALID
                  ? SocialTokenFailures.invalid("invalid_grant")
                  : SocialTokenFailures.unavailable("apple 503"));

      assertThatThrownBy(
              () ->
                  new AppleLogin(verifier, c, cipher, links)
                      .verify(IDENTITY_TOKEN, CODE, RAW_NONCE, null, null))
          .isInstanceOfSatisfying(
              SocialTokenException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
  }

  // ───────────── 이름 ─────────────

  @Test
  @DisplayName("한글이면 성+이름 붙여 쓰기, 아니면 「이름 성」, 한쪽만 있으면 그쪽, 둘 다 비면 null")
  void displayNameRule() {
    assertThat(AppleLogin.displayName("철수", "김")).isEqualTo("김철수");
    assertThat(AppleLogin.displayName("여행", "남궁")).isEqualTo("남궁여행");
    assertThat(AppleLogin.displayName("Jane", "Doe")).isEqualTo("Jane Doe");
    assertThat(AppleLogin.displayName("Jane", null)).isEqualTo("Jane");
    assertThat(AppleLogin.displayName(null, "Doe")).isEqualTo("Doe");
    assertThat(AppleLogin.displayName("철수", null)).isEqualTo("철수");
    assertThat(AppleLogin.displayName("", "김")).isEqualTo("김");
    assertThat(AppleLogin.displayName(null, null)).isNull();
    assertThat(AppleLogin.displayName("", "")).isNull();
    assertThat(AppleLogin.displayName("  ", " ")).isNull();
  }

  @Test
  @DisplayName("한글 이름은 로그인 신분에도 「김철수」 로 붙는다")
  void hangulNameThroughVerify() {
    when(client.enabled()).thenReturn(true);
    when(verifier.verify(IDENTITY_TOKEN, RAW_NONCE)).thenReturn(VERIFIED);
    when(client.exchange(CODE)).thenReturn(REFRESH);

    SocialIdentity identity = login().verify(IDENTITY_TOKEN, CODE, RAW_NONCE, "철수", "김");

    assertThat(identity.displayName()).isEqualTo("김철수");
  }

  // ───────────── 탈퇴 때 끊기 ─────────────

  @Test
  @DisplayName("저장한 암호문을 풀어 그 원문으로 revoke 한다")
  void revokeForDecryptsAndRevokes() {
    when(client.enabled()).thenReturn(true);
    when(links.appleRefreshTokenEnc(USER)).thenReturn(Optional.of(cipher.encrypt(REFRESH)));
    when(client.revoke(REFRESH)).thenReturn(true);

    login().revokeFor(USER);

    verify(client).revoke(REFRESH);
  }

  @Test
  @DisplayName("꺼져 있으면 아무것도 하지 않는다")
  void revokeForDisabledIsNoOp() {
    when(client.enabled()).thenReturn(false);

    assertThatCode(() -> login().revokeFor(USER)).doesNotThrowAnyException();

    verify(client, never()).revoke(any());
    verifyNoInteractions(links);
  }

  @Test
  @DisplayName("암호화 키가 없어 꺼져 있어도 아무것도 하지 않는다")
  void revokeForWithoutCipherIsNoOp() {
    when(client.enabled()).thenReturn(true);

    assertThatCode(
            () ->
                new AppleLogin(verifier, client, new TokenCipher((byte[]) null), links)
                    .revokeFor(USER))
        .doesNotThrowAnyException();

    verify(client, never()).revoke(any());
  }

  @Test
  @DisplayName("애플 신분이 없으면(구글 계정) 던지지 않고 revoke 하지 않는다")
  void revokeForWithoutAppleIdentity() {
    when(client.enabled()).thenReturn(true);
    when(links.appleRefreshTokenEnc(USER)).thenReturn(Optional.empty());

    assertThatCode(() -> login().revokeFor(USER)).doesNotThrowAnyException();

    verify(client, never()).revoke(any());
  }

  @Test
  @DisplayName("풀 수 없는 암호문(키가 바뀜·망가짐)이면 던지지 않고 revoke 하지 않는다")
  void revokeForUndecryptable() {
    when(client.enabled()).thenReturn(true);
    byte[] otherKeys = new TokenCipher(bytes(32, (byte) 99)).encrypt(REFRESH);
    for (byte[] stored : new byte[][] {otherKeys, new byte[5], new byte[0]}) {
      when(links.appleRefreshTokenEnc(USER)).thenReturn(Optional.of(stored));

      assertThatCode(() -> login().revokeFor(USER)).doesNotThrowAnyException();
    }

    verify(client, never()).revoke(any());
  }

  @Test
  @DisplayName("애플이 거절해도(false) 던지지 않는다")
  void revokeForAppleRefuses() {
    when(client.enabled()).thenReturn(true);
    when(links.appleRefreshTokenEnc(USER)).thenReturn(Optional.of(cipher.encrypt(REFRESH)));
    when(client.revoke(REFRESH)).thenReturn(false);

    assertThatCode(() -> login().revokeFor(USER)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("애플 클라이언트가 뜻밖에 던져도 revokeFor 는 던지지 않는다")
  void revokeForClientThrows() {
    when(client.enabled()).thenReturn(true);
    when(links.appleRefreshTokenEnc(USER)).thenReturn(Optional.of(cipher.encrypt(REFRESH)));
    when(client.revoke(REFRESH)).thenThrow(new IllegalStateException("unexpected"));

    assertThatCode(() -> login().revokeFor(USER)).doesNotThrowAnyException();
  }

  private AppleLogin login() {
    return new AppleLogin(verifier, client, cipher, links);
  }

  private static byte[] bytes(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }
}
