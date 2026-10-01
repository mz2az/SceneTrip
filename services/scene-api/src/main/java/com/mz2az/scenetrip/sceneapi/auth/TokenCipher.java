package com.mz2az.scenetrip.sceneapi.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DB 에 둘 외부 토큰을 잠그고 푼다 — 지금은 애플 refresh token 하나 (V16, MZ2AZ-337).
 *
 * <p><b>해시가 아니라 대칭 암호화다.</b> 탈퇴 때 원문을 애플에 보내야 하므로 되돌릴 수 있어야 한다. AES-256-GCM — 기밀성과 함께 위·변조를 잡는다(풀 때
 * 태그가 맞지 않으면 실패한다). 저장 형식은 12 바이트 nonce 뒤에 암호문(태그 포함)이다. nonce 는 잠글 때마다 새 난수라 같은 토큰도 매번 다른 암호문이 된다.
 *
 * <p>키는 JWT 서명 키와 <b>따로</b> 둔다(SCENETRIP_AUTH_TOKEN_ENCRYPTION_KEY). 하나가 새도 다른 하나는 안전하다 — JWT 키로
 * 잠갔다면 그 키가 샐 때 저장된 애플 토큰까지 풀린다. DEV·PRD 는 배포가 환경당 한 번 만든다({@code tools/aws/deploy.py} 의 {@code
 * auth_secret}).
 *
 * <p>키가 없으면 서버는 뜨고 이것을 쓰는 기능(애플 로그인)만 꺼진다 — {@link AccessTokens} 와 같은 판단이다. 있는데 32 바이트가 아니면 기동을
 * 멈춘다.
 */
@Component
public class TokenCipher {

  /** AES-256. */
  static final int KEY_BYTES = 32;

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);

  private final SecretKeySpec key;

  @Autowired
  public TokenCipher(@Value("${scenetrip.auth.token-encryption-key:}") String keyBase64) {
    this(decode(keyBase64));
    if (key == null) {
      log.warn("토큰 암호화 키가 없습니다 (SCENETRIP_AUTH_TOKEN_ENCRYPTION_KEY) — 애플 로그인이 꺼집니다.");
    }
  }

  /**
   * 테스트와 다른 패키지가 직접 만든다.
   *
   * @param key 32 바이트. {@code null} 이거나 비어 있으면 꺼진다
   */
  public TokenCipher(byte[] key) {
    if (key == null || key.length == 0) {
      this.key = null;
      return;
    }
    if (key.length != KEY_BYTES) {
      throw new IllegalArgumentException(
          "토큰 암호화 키는 " + KEY_BYTES + " 바이트여야 합니다 (지금 " + key.length + "). openssl rand -base64 32");
    }
    this.key = new SecretKeySpec(Arrays.copyOf(key, key.length), "AES");
  }

  /** 키가 있어 쓸 수 있는가. */
  public boolean enabled() {
    return key != null;
  }

  /**
   * 잠근다.
   *
   * @throws IllegalStateException 키가 없다
   */
  public byte[] encrypt(String plaintext) {
    requireKey();
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(NONCE_BYTES + cipherText.length)
          .put(nonce)
          .put(cipherText)
          .array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("토큰을 암호화하지 못했습니다", e);
    }
  }

  /**
   * 푼다.
   *
   * @throws IllegalStateException 키가 없다
   * @throws IllegalArgumentException 형식이 아니거나, 다른 키로 잠갔거나, 고쳐졌다 — 셋을 가르지 않는다
   */
  public String decrypt(byte[] cipherText) {
    requireKey();
    if (cipherText == null || cipherText.length <= NONCE_BYTES) {
      throw new IllegalArgumentException("암호문 형식이 아닙니다");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, cipherText, 0, NONCE_BYTES));
      byte[] plain = cipher.doFinal(cipherText, NONCE_BYTES, cipherText.length - NONCE_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("암호문을 풀 수 없습니다 — 다른 키이거나 고쳐졌습니다");
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException("토큰 암호화 키가 없습니다");
    }
  }

  private static byte[] decode(String keyBase64) {
    if (keyBase64 == null || keyBase64.isBlank()) {
      return null;
    }
    try {
      return Base64.getDecoder().decode(keyBase64.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "SCENETRIP_AUTH_TOKEN_ENCRYPTION_KEY 가 base64 가 아닙니다. openssl rand -base64 32 로 만드세요");
    }
  }
}
