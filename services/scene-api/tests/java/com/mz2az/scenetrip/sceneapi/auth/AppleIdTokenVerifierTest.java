package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.auth.SocialTokenException.Reason;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 애플 identityToken 검증을 명세(계약 {@code POST /auth/apple}·{@code AppleSignIn.nonce}, 계획 §5, {@code
 * application.yaml} 의 {@code scenetrip.auth.apple}, MZ2AZ-337)에 비춰 본다.
 *
 * <p>애플을 부르지 않는다. 이 자리에서 만든 RSA 키로 「애플처럼 생긴」 토큰을 서명하고 검증기에는 그 공개키 한 장짜리 키 묶음을 준다.
 */
@DisplayName("AppleIdTokenVerifier — 애플 identityToken 검증")
class AppleIdTokenVerifierTest {

  private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  private static final String BUNDLE_ID = "com.mz2az.scenetrip";
  private static final String ISSUER = "https://appleid.apple.com";
  private static final String KID = "apple-key-1";
  private static final String RAW_NONCE = "raw-nonce-7c1e9a3b5d2f4a60";
  private static final String SUB = "001234.abcdef0123456789abcdef0123456789.0123";

  private static RSAKey appleKey;
  private static RSAKey otherKey;

  private final AppleIdTokenVerifier verifier =
      new AppleIdTokenVerifier(
          new ImmutableJWKSet<>(new JWKSet(appleKey.toPublicJWK())), BUNDLE_ID, CLOCK);

  @BeforeAll
  static void keys() throws JOSEException {
    appleKey = new RSAKeyGenerator(2048).keyID(KID).generate();
    otherKey = new RSAKeyGenerator(2048).keyID(KID).generate();
  }

  // ───────────── 받아 주는 것 ─────────────

  @Test
  @DisplayName("올바른 토큰이면 provider=apple, 그 sub, 확인된 이메일, 이름은 null")
  void validTokenYieldsIdentity() {
    SocialIdentity identity = verifier.verify(signed(valid().build()), RAW_NONCE);

    assertThat(identity.provider()).isEqualTo("apple");
    assertThat(identity.subject()).isEqualTo(SUB);
    assertThat(identity.email()).isEqualTo("abc123@privaterelay.appleid.com");
    assertThat(identity.displayName()).isNull();
    assertThat(identity.appleRefreshTokenEnc()).isNull();
  }

  @Test
  @DisplayName("email_verified 가 문자열 \"true\" 여도 이메일을 돌려준다")
  void stringTrueEmailVerifiedIsAccepted() {
    SocialIdentity identity =
        verifier.verify(signed(valid().claim("email_verified", "true").build()), RAW_NONCE);

    assertThat(identity.email()).isEqualTo("abc123@privaterelay.appleid.com");
  }

  @Test
  @DisplayName("aud 가 목록이고 번들 ID 를 담고 있으면 받는다")
  void audienceListContainingBundleIdIsAccepted() {
    SocialIdentity identity =
        verifier.verify(
            signed(valid().audience(List.of("com.example.other", BUNDLE_ID)).build()), RAW_NONCE);

    assertThat(identity.subject()).isEqualTo(SUB);
  }

  @Test
  @DisplayName("만료가 지났어도 1 분 여유 안이면 받는다")
  void expiredWithinSkewIsAccepted() {
    SocialIdentity identity =
        verifier.verify(
            signed(valid().expirationTime(Date.from(T0.minusSeconds(30))).build()), RAW_NONCE);

    assertThat(identity.subject()).isEqualTo(SUB);
  }

  // ───────────── 이메일 ─────────────

  @Test
  @DisplayName("email_verified 가 false·\"false\"·없음이면 이메일을 돌려주지 않는다 — 로그인은 된다")
  void unverifiedEmailIsDropped() {
    for (Object flag : new Object[] {false, "false", null}) {
      SocialIdentity identity =
          verifier.verify(signed(valid().claim("email_verified", flag).build()), RAW_NONCE);

      assertThat(identity.email()).as("email_verified=%s", flag).isNull();
      assertThat(identity.subject()).isEqualTo(SUB);
    }
  }

  // ───────────── nonce (해시 비교) ─────────────

  @Test
  @DisplayName("토큰의 nonce 가 해시가 아니라 원문 그대로면 INVALID")
  void rawNonceInTokenIsInvalid() {
    assertThat(reasonOf(verifier, signed(valid().claim("nonce", RAW_NONCE).build()), RAW_NONCE))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("토큰의 nonce 가 대문자 16 진 해시면 INVALID — 16 진 소문자만")
  void uppercaseHexNonceIsInvalid() {
    String upper = sha256Hex(RAW_NONCE).toUpperCase();
    assertThat(reasonOf(verifier, signed(valid().claim("nonce", upper).build()), RAW_NONCE))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("다른 원문을 보내면 INVALID")
  void differentRawNonceIsInvalid() {
    assertThat(reasonOf(verifier, signed(valid().build()), "another-raw-nonce-value"))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("요청이 해시를 보내면(원문 대신) INVALID")
  void sendingTheHashInsteadOfRawIsInvalid() {
    assertThat(reasonOf(verifier, signed(valid().build()), sha256Hex(RAW_NONCE)))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("토큰에 nonce 가 없거나 요청 nonce 가 null 이면 INVALID")
  void missingNonceIsInvalid() {
    assertInvalid(signed(valid().claim("nonce", null).build()));
    assertThat(reasonOf(verifier, signed(valid().build()), null)).isEqualTo(Reason.INVALID);
    assertThat(reasonOf(verifier, signed(valid().claim("nonce", null).build()), null))
        .isEqualTo(Reason.INVALID);
  }

  // ───────────── 서명 ─────────────

  @Test
  @DisplayName("다른 키로 서명했으면(키 id 는 같아도) INVALID")
  void wrongKeyIsInvalid() {
    assertInvalid(sign(new RSASSASigner(rsa(otherKey)), JWSAlgorithm.RS256, valid().build()));
  }

  @Test
  @DisplayName("RS256 이 아니면 INVALID — 같은 키의 RS512, HS256, alg=none")
  void nonRs256IsInvalid() throws JOSEException {
    assertInvalid(sign(new RSASSASigner(rsa(appleKey)), JWSAlgorithm.RS512, valid().build()));
    assertInvalid(
        sign(
            new MACSigner(appleKey.toRSAPublicKey().getEncoded()),
            JWSAlgorithm.HS256,
            valid().build()));
    assertInvalid(new PlainJWT(valid().build()).serialize());
  }

  @Test
  @DisplayName("JWT 가 아니면 INVALID")
  void garbageIsInvalid() {
    assertInvalid("not-a-jwt");
    assertInvalid("a.b.c");
    assertInvalid("");
  }

  // ───────────── 발급자·대상·만료·sub ─────────────

  @Test
  @DisplayName("발급자가 정확히 https://appleid.apple.com 이 아니면 INVALID")
  void wrongIssuerIsInvalid() {
    for (String issuer :
        new String[] {
          "appleid.apple.com",
          "https://appleid.apple.com/",
          "https://appleid.apple.com.evil.example",
          "https://accounts.google.com",
          null
        }) {
      assertThat(reasonOf(verifier, signed(valid().issuer(issuer).build()), RAW_NONCE))
          .as("iss=%s", issuer)
          .isEqualTo(Reason.INVALID);
    }
  }

  @Test
  @DisplayName("aud 가 번들 ID 가 아니거나 없으면 INVALID")
  void wrongAudienceIsInvalid() {
    assertInvalid(signed(valid().audience("com.example.other").build()));
    assertInvalid(signed(valid().audience("com.mz2az.scenetrip.evil").build()));
    assertInvalid(signed(valid().audience(List.of("a.b", "c.d")).build()));
    assertInvalid(signed(valid().audience((String) null).build()));
  }

  @Test
  @DisplayName("만료가 1 분 여유를 넘겨 지났거나 없으면 INVALID")
  void expiredIsInvalid() {
    assertInvalid(
        signed(valid().expirationTime(Date.from(T0.minus(Duration.ofMinutes(2)))).build()));
    assertInvalid(signed(valid().expirationTime(null).build()));
  }

  @Test
  @DisplayName("sub 가 없거나 비었으면 INVALID")
  void missingSubjectIsInvalid() {
    assertInvalid(signed(valid().subject(null).build()));
    assertInvalid(signed(valid().subject("").build()));
  }

  // ───────────── 공개키를 못 받음 ─────────────

  @Test
  @DisplayName("공개키를 받아 오지 못하면 UNAVAILABLE")
  void keySourceFailureIsUnavailable() {
    JWKSource<SecurityContext> down =
        (selector, context) -> {
          throw new RemoteKeySourceException("connect timed out", null);
        };
    JWKSource<SecurityContext> broken =
        (selector, context) -> {
          throw new KeySourceException("rate limited");
        };

    for (JWKSource<SecurityContext> source : List.of(down, broken)) {
      AppleIdTokenVerifier offline = new AppleIdTokenVerifier(source, BUNDLE_ID, CLOCK);
      assertThat(reasonOf(offline, signed(valid().build()), RAW_NONCE))
          .isEqualTo(Reason.UNAVAILABLE);
    }
  }

  // ───────────── 도우미 ─────────────

  private static JWTClaimsSet.Builder valid() {
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(BUNDLE_ID)
        .subject(SUB)
        .issueTime(Date.from(T0.minusSeconds(60)))
        .expirationTime(Date.from(T0.plus(Duration.ofMinutes(10))))
        .claim("nonce", sha256Hex(RAW_NONCE))
        .claim("nonce_supported", true)
        .claim("email", "abc123@privaterelay.appleid.com")
        .claim("email_verified", true)
        .claim("is_private_email", true);
  }

  /** 명세가 정한 값을 테스트가 직접 계산한다 — 검증기의 도우미를 쓰지 않는다. */
  private static String sha256Hex(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String signed(JWTClaimsSet claims) {
    return sign(new RSASSASigner(rsa(appleKey)), JWSAlgorithm.RS256, claims);
  }

  private static String sign(JWSSigner signer, JWSAlgorithm alg, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(KID).build(), claims);
      jwt.sign(signer);
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static java.security.interfaces.RSAPrivateKey rsa(RSAKey key) {
    try {
      return key.toRSAPrivateKey();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertInvalid(String token) {
    assertThat(reasonOf(verifier, token, RAW_NONCE))
        .as("token %s", token)
        .isEqualTo(Reason.INVALID);
  }

  private static Reason reasonOf(AppleIdTokenVerifier v, String token, String nonce) {
    try {
      SocialIdentity accepted = v.verify(token, nonce);
      throw new AssertionError("거절되어야 하는데 받아들였다: " + accepted);
    } catch (SocialTokenException e) {
      return e.reason();
    }
  }
}
