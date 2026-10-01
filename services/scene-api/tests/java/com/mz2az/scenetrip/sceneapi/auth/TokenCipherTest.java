package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 외부 토큰 암호화를 명세({@code TokenCipher} 클래스 설명, V16 의 칸 설명 「12 바이트 nonce + 암호문」, 계획 §4)에 비춰 본다. */
@DisplayName("TokenCipher — 애플 refresh token 암호화 (AES-256-GCM)")
class TokenCipherTest {

  private static final byte[] KEY = bytes(32, (byte) 7);
  private static final byte[] OTHER_KEY = bytes(32, (byte) 8);
  private static final String TOKEN = "r.apple-refresh-token-0123456789abcdef.0.abcd";

  @Test
  @DisplayName("잠근 것을 풀면 원문이 나오고, 암호문에 원문이 보이지 않는다")
  void roundTrip() {
    TokenCipher cipher = new TokenCipher(KEY);

    byte[] sealed = cipher.encrypt(TOKEN);

    assertThat(cipher.enabled()).isTrue();
    assertThat(cipher.decrypt(sealed)).isEqualTo(TOKEN);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain(TOKEN);
    // 12 바이트 nonce + 원문 길이 + 16 바이트 태그
    assertThat(sealed).hasSize(12 + TOKEN.getBytes(StandardCharsets.UTF_8).length + 16);
  }

  @Test
  @DisplayName("한글·빈 문자열도 왕복한다")
  void roundTripEdgeValues() {
    TokenCipher cipher = new TokenCipher(KEY);

    assertThat(cipher.decrypt(cipher.encrypt("한글 토큰"))).isEqualTo("한글 토큰");
    assertThat(cipher.decrypt(cipher.encrypt(""))).isEqualTo("");
  }

  @Test
  @DisplayName("같은 원문도 잠글 때마다 다른 암호문이 된다")
  void sameInputDiffersEachTime() {
    TokenCipher cipher = new TokenCipher(KEY);

    byte[] a = cipher.encrypt(TOKEN);
    byte[] b = cipher.encrypt(TOKEN);

    assertThat(a).isNotEqualTo(b);
    assertThat(Arrays.copyOf(a, 12)).isNotEqualTo(Arrays.copyOf(b, 12));
    assertThat(cipher.decrypt(a)).isEqualTo(cipher.decrypt(b));
  }

  @Test
  @DisplayName("같은 키로 만든 다른 인스턴스도 풀 수 있다 — 재기동 뒤에도")
  void sameKeyAcrossInstances() {
    byte[] sealed = new TokenCipher(KEY).encrypt(TOKEN);

    assertThat(new TokenCipher(KEY.clone()).decrypt(sealed)).isEqualTo(TOKEN);
  }

  @Test
  @DisplayName("한 바이트라도 고친 암호문은 IllegalArgumentException — 어느 자리든")
  void tamperedIsRejected() {
    TokenCipher cipher = new TokenCipher(KEY);
    byte[] sealed = cipher.encrypt(TOKEN);

    for (int i : new int[] {0, 11, 12, sealed.length / 2, sealed.length - 1}) {
      byte[] tampered = sealed.clone();
      tampered[i] ^= 0x01;
      assertThatThrownBy(() -> cipher.decrypt(tampered))
          .as("byte %d", i)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("다른 키로 잠근 것은 IllegalArgumentException")
  void wrongKeyIsRejected() {
    byte[] sealed = new TokenCipher(OTHER_KEY).encrypt(TOKEN);

    assertThatThrownBy(() -> new TokenCipher(KEY).decrypt(sealed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("너무 짧은 입력(빈 것·nonce 만·태그 미만)은 IllegalArgumentException")
  void tooShortIsRejected() {
    TokenCipher cipher = new TokenCipher(KEY);

    for (int length : new int[] {0, 1, 11, 12, 27}) {
      assertThatThrownBy(() -> cipher.decrypt(new byte[length]))
          .as("length %d", length)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("다른 암호문의 nonce 와 본문을 섞으면 IllegalArgumentException")
  void splicedIsRejected() {
    TokenCipher cipher = new TokenCipher(KEY);
    byte[] a = cipher.encrypt(TOKEN);
    byte[] b = cipher.encrypt(TOKEN);
    byte[] spliced = a.clone();
    System.arraycopy(b, 0, spliced, 0, 12);

    assertThatThrownBy(() -> cipher.decrypt(spliced)).isInstanceOf(IllegalArgumentException.class);
  }

  // ───────────── 키 ─────────────

  @Test
  @DisplayName("키가 32 바이트가 아니면 IllegalArgumentException — 16·24·31·33·64")
  void wrongKeyLengthIsRejected() {
    for (int length : new int[] {1, 16, 24, 31, 33, 64}) {
      assertThatThrownBy(() -> new TokenCipher(new byte[length]))
          .as("length %d", length)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("키가 null 이거나 비면 꺼진다 — 잠그기·풀기는 IllegalStateException")
  void missingKeyDisables() {
    for (TokenCipher off :
        new TokenCipher[] {new TokenCipher((byte[]) null), new TokenCipher(new byte[0])}) {
      assertThat(off.enabled()).isFalse();
      assertThatThrownBy(() -> off.encrypt(TOKEN)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> off.decrypt(new byte[40])).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("설정 생성자 — 비어 있으면 꺼지고, base64 32 바이트면 켜져 byte[] 생성자와 같은 키다")
  void configConstructor() {
    assertThat(new TokenCipher("").enabled()).isFalse();
    TokenCipher fromConfig = new TokenCipher(Base64.getEncoder().encodeToString(KEY));

    assertThat(fromConfig.enabled()).isTrue();
    assertThat(new TokenCipher(KEY).decrypt(fromConfig.encrypt(TOKEN))).isEqualTo(TOKEN);
  }

  @Test
  @DisplayName("설정 생성자 — base64 가 아니면 IllegalArgumentException 이고 메시지에 값을 싣지 않는다")
  void configConstructorRejectsNonBase64WithoutEcho() {
    String bad = "FIXTURE*not*base64*cipher*key!!";

    assertThatThrownBy(() -> new TokenCipher(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(bad));
  }

  @Test
  @DisplayName("설정 생성자 — base64 이지만 32 바이트가 아니면 IllegalArgumentException 이고 값을 싣지 않는다")
  void configConstructorRejectsWrongLengthWithoutEcho() {
    String shortKey = Base64.getEncoder().encodeToString(bytes(31, (byte) 3));

    assertThatThrownBy(() -> new TokenCipher(shortKey))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(shortKey));
  }

  private static byte[] bytes(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }
}
