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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 구글 ID 토큰 검증을 명세(계약 {@code POST /auth/google} · {@code SocialTokenInvalid} · {@code
 * AuthProviderUnavailable}, 계획 §5·§9, {@code application.yaml} 의 {@code scenetrip.auth.google})에 비춰
 * 본다.
 *
 * <p>구글을 부르지 않는다. 이 자리에서 만든 RSA 키로 「구글처럼 생긴」 토큰을 서명하고, 검증기에는 그 공개키 한 장짜리 키 묶음을 준다.
 */
@DisplayName("GoogleIdTokenVerifier — 구글 ID 토큰 검증")
class GoogleIdTokenVerifierTest {

  // 실제 시각과 멀리 떨어뜨린다. 검증기가 주입한 시계 대신 시스템 시계를 보면 만료 판정이 어긋나 드러난다.
  private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  // 계획 §9 「등록된 구글 클라이언트」.
  private static final String WEB_CLIENT =
      "700188854872-3dl36cm33ndb2m1e8j04svnrnjpjleep.apps.googleusercontent.com";
  private static final String IOS_CLIENT =
      "700188854872-7v9hphkb4phavae7stepil7q6vp7lbig.apps.googleusercontent.com";
  private static final String ANDROID_CLIENT =
      "700188854872-a36b6471q8h03lvkdilnubtmeu658ocd.apps.googleusercontent.com";
  private static final List<String> CLIENT_IDS = List.of(WEB_CLIENT, IOS_CLIENT);

  private static final String KID = "google-key-1";
  private static final String NONCE = "n0nce-3f9a1c7e5b2d4a60";
  private static final String SUB = "109876543210987654321";

  private static RSAKey googleKey;
  private static RSAKey otherKey;

  private final GoogleIdTokenVerifier verifier =
      new GoogleIdTokenVerifier(
          new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK())), CLIENT_IDS, CLOCK);

  @BeforeAll
  static void keys() throws JOSEException {
    googleKey = new RSAKeyGenerator(2048).keyID(KID).generate();
    // 같은 키 id 를 단 다른 키 — 키 id 만 맞춘 위조다.
    otherKey = new RSAKeyGenerator(2048).keyID(KID).generate();
  }

  // ───────────── 받아 주는 것 ─────────────

  @Test
  @DisplayName("올바른 토큰이면 provider=google, 그 sub, name 을 이름으로 돌려준다")
  void validTokenYieldsIdentity() {
    SocialIdentity identity = verifier.verify(signed(valid().build()), NONCE);

    assertThat(identity.provider()).isEqualTo("google");
    assertThat(identity.subject()).isEqualTo(SUB);
    assertThat(identity.displayName()).isEqualTo("김여행");
    assertThat(identity.email()).isEqualTo("traveler@example.com");
  }

  @Test
  @DisplayName("발급자가 스킴 없는 accounts.google.com 이어도 받는다")
  void issuerWithoutSchemeIsAccepted() {
    SocialIdentity identity =
        verifier.verify(signed(valid().issuer("accounts.google.com").build()), NONCE);

    assertThat(identity.subject()).isEqualTo(SUB);
  }

  @Test
  @DisplayName("aud 가 iOS 클라이언트 ID 여도 받는다 — 허용 목록은 웹·iOS 둘")
  void iosAudienceIsAccepted() {
    SocialIdentity identity = verifier.verify(signed(valid().audience(IOS_CLIENT).build()), NONCE);

    assertThat(identity.subject()).isEqualTo(SUB);
  }

  @Test
  @DisplayName("aud 가 목록이고 그중 하나가 우리 클라이언트 ID 면 받는다")
  void audienceListContainingAllowedIdIsAccepted() {
    SocialIdentity identity =
        verifier.verify(
            signed(
                valid()
                    .audience(List.of("someone-else.apps.googleusercontent.com", WEB_CLIENT))
                    .build()),
            NONCE);

    assertThat(identity.subject()).isEqualTo(SUB);
  }

  @Test
  @DisplayName("만료가 지났어도 1 분 여유 안이면 받는다")
  void expiredWithinSkewIsAccepted() {
    SocialIdentity identity =
        verifier.verify(signed(valid().expirationTime(at(T0.minusSeconds(30))).build()), NONCE);

    assertThat(identity.subject()).isEqualTo(SUB);
  }

  @Test
  @DisplayName("name 이 없으면 이름은 null 이다")
  void missingNameIsNull() {
    SocialIdentity identity = verifier.verify(signed(valid().claim("name", null).build()), NONCE);

    assertThat(identity.displayName()).isNull();
  }

  // ───────────── 이메일 ─────────────

  @Test
  @DisplayName("email_verified 가 false 면 이메일을 돌려주지 않는다")
  void unverifiedEmailIsDropped() {
    SocialIdentity identity =
        verifier.verify(signed(valid().claim("email_verified", false).build()), NONCE);

    assertThat(identity.email()).isNull();
    assertThat(identity.subject()).isEqualTo(SUB);
  }

  @Test
  @DisplayName("email_verified 가 없으면 이메일을 돌려주지 않는다")
  void missingEmailVerifiedDropsEmail() {
    SocialIdentity identity =
        verifier.verify(signed(valid().claim("email_verified", null).build()), NONCE);

    assertThat(identity.email()).isNull();
  }

  // ───────────── 서명 ─────────────

  @Test
  @DisplayName("다른 키로 서명했으면(키 id 는 같아도) INVALID")
  void wrongKeyIsInvalid() {
    assertInvalid(sign(new RSASSASigner(rsa(otherKey)), JWSAlgorithm.RS256, valid().build()));
  }

  @Test
  @DisplayName("HS256 으로 서명했으면 INVALID — 아무 비밀값이든")
  void hs256IsInvalid() {
    byte[] secret = "an-attacker-chosen-hmac-secret-of-32+bytes".getBytes(StandardCharsets.UTF_8);

    assertInvalid(sign(macSigner(secret), JWSAlgorithm.HS256, valid().build()));
  }

  @Test
  @DisplayName("공개키 바이트를 HMAC 비밀값으로 쓴 HS256(알고리즘 혼동)도 INVALID")
  void hs256WithPublicKeyAsSecretIsInvalid() throws JOSEException {
    byte[] publicKeyBytes = googleKey.toRSAPublicKey().getEncoded();

    assertInvalid(sign(macSigner(publicKeyBytes), JWSAlgorithm.HS256, valid().build()));
  }

  @Test
  @DisplayName("alg=none(서명 없음)이면 INVALID")
  void algNoneIsInvalid() {
    assertInvalid(new PlainJWT(valid().build()).serialize());
  }

  @Test
  @DisplayName("alg=none 헤더에 원래 서명을 붙여도 INVALID")
  void algNoneHeaderWithRealSignatureIsInvalid() {
    String[] parts = signed(valid().build()).split("\\.");
    String noneHeader =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"alg\":\"none\",\"kid\":\"" + KID + "\"}").getBytes(StandardCharsets.UTF_8));

    assertInvalid(noneHeader + "." + parts[1] + "." + parts[2]);
  }

  @Test
  @DisplayName("서명 부분을 바꾸면 INVALID")
  void tamperedPayloadIsInvalid() {
    String[] parts = signed(valid().build()).split("\\.");
    String otherPayload = signed(valid().subject("someone-else").build()).split("\\.")[1];

    assertInvalid(parts[0] + "." + otherPayload + "." + parts[2]);
  }

  // ───────────── 발급자·대상 ─────────────

  @Test
  @DisplayName("발급자가 구글이 아니면 INVALID")
  void wrongIssuerIsInvalid() {
    assertInvalid(signed(valid().issuer("https://evil.example.com").build()));
    assertInvalid(signed(valid().issuer("https://accounts.google.com.evil.example").build()));
  }

  @Test
  @DisplayName("발급자가 없으면 INVALID")
  void missingIssuerIsInvalid() {
    assertInvalid(signed(valid().issuer(null).build()));
  }

  @Test
  @DisplayName("aud 가 우리 클라이언트 ID 가 아니면 INVALID")
  void foreignAudienceIsInvalid() {
    assertInvalid(signed(valid().audience("someone-else.apps.googleusercontent.com").build()));
  }

  @Test
  @DisplayName("aud 가 Android 클라이언트 ID 면 INVALID — Android 토큰의 aud 는 웹 클라이언트 ID 다(계획 §9)")
  void androidClientAudienceIsInvalid() {
    assertInvalid(signed(valid().audience(ANDROID_CLIENT).build()));
  }

  @Test
  @DisplayName("aud 목록에 우리 것이 하나도 없으면 INVALID")
  void audienceListWithoutAllowedIdIsInvalid() {
    assertInvalid(
        signed(valid().audience(List.of(ANDROID_CLIENT, "x.apps.googleusercontent.com")).build()));
  }

  @Test
  @DisplayName("aud 가 없으면 INVALID")
  void missingAudienceIsInvalid() {
    assertInvalid(signed(valid().audience((String) null).build()));
  }

  @Test
  @DisplayName("허용 목록이 비어 있으면 어떤 토큰도 INVALID")
  void emptyClientIdListRejectsEverything() {
    GoogleIdTokenVerifier closed =
        new GoogleIdTokenVerifier(
            new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK())), List.of(), CLOCK);

    assertThat(reasonOf(closed, signed(valid().build()), NONCE)).isEqualTo(Reason.INVALID);
    assertThat(reasonOf(closed, signed(valid().audience(IOS_CLIENT).build()), NONCE))
        .isEqualTo(Reason.INVALID);
  }

  // ───────────── 만료 ─────────────

  @Test
  @DisplayName("만료가 1 분 여유를 넘겨 지났으면 INVALID")
  void expiredBeyondSkewIsInvalid() {
    assertInvalid(signed(valid().expirationTime(at(T0.minus(Duration.ofMinutes(2)))).build()));
  }

  @Test
  @DisplayName("만료가 없으면 INVALID")
  void missingExpirationIsInvalid() {
    assertInvalid(signed(valid().expirationTime(null).build()));
  }

  // ───────────── nonce ─────────────

  @Test
  @DisplayName("토큰의 nonce 가 보낸 nonce 와 다르면 INVALID")
  void differentNonceIsInvalid() {
    assertThat(reasonOf(verifier, signed(valid().build()), "a-different-nonce-value"))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("토큰에 nonce 가 없으면 INVALID")
  void missingNonceInTokenIsInvalid() {
    assertInvalid(signed(valid().claim("nonce", null).build()));
  }

  @Test
  @DisplayName("요청의 nonce 가 null 이면 INVALID")
  void nullRequestNonceIsInvalid() {
    assertThat(reasonOf(verifier, signed(valid().build()), null)).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("토큰에도 요청에도 nonce 가 없으면 INVALID — 「둘 다 없음」 을 같다고 보지 않는다")
  void bothNoncesMissingIsInvalid() {
    assertThat(reasonOf(verifier, signed(valid().claim("nonce", null).build()), null))
        .isEqualTo(Reason.INVALID);
  }

  // ───────────── sub · 형식 ─────────────

  @Test
  @DisplayName("sub 가 없으면 INVALID")
  void missingSubjectIsInvalid() {
    assertInvalid(signed(valid().subject(null).build()));
  }

  @Test
  @DisplayName("sub 가 빈 문자열이면 INVALID")
  void blankSubjectIsInvalid() {
    assertInvalid(signed(valid().subject("").build()));
  }

  @Test
  @DisplayName("JWT 가 아닌 문자열이면 INVALID")
  void garbageIsInvalid() {
    assertInvalid("not-a-jwt");
    assertInvalid("a.b.c");
    assertInvalid("");
  }

  // ───────────── 공개키를 못 받음 ─────────────

  @Test
  @DisplayName("공개키를 받아 오지 못하면(RemoteKeySourceException) UNAVAILABLE")
  void remoteKeySourceFailureIsUnavailable() {
    JWKSource<SecurityContext> down =
        (selector, context) -> {
          throw new RemoteKeySourceException("connect timed out", null);
        };
    GoogleIdTokenVerifier offline = new GoogleIdTokenVerifier(down, CLIENT_IDS, CLOCK);

    assertThat(reasonOf(offline, signed(valid().build()), NONCE)).isEqualTo(Reason.UNAVAILABLE);
  }

  @Test
  @DisplayName("키 묶음 쪽이 KeySourceException 을 내도 UNAVAILABLE")
  void keySourceFailureIsUnavailable() {
    JWKSource<SecurityContext> broken =
        (selector, context) -> {
          throw new KeySourceException("rate limited");
        };
    GoogleIdTokenVerifier offline = new GoogleIdTokenVerifier(broken, CLIENT_IDS, CLOCK);

    assertThat(reasonOf(offline, signed(valid().build()), NONCE)).isEqualTo(Reason.UNAVAILABLE);
  }

  // ───────────── 도우미 ─────────────

  /** 모든 검사를 통과하는 클레임. 시험마다 하나만 바꾼다. */
  private static JWTClaimsSet.Builder valid() {
    return new JWTClaimsSet.Builder()
        .issuer("https://accounts.google.com")
        .audience(WEB_CLIENT)
        .subject(SUB)
        .issueTime(at(T0.minusSeconds(60)))
        .expirationTime(at(T0.plus(Duration.ofHours(1))))
        .claim("nonce", NONCE)
        .claim("email", "traveler@example.com")
        .claim("email_verified", true)
        .claim("name", "김여행");
  }

  private static String signed(JWTClaimsSet claims) {
    return sign(new RSASSASigner(rsa(googleKey)), JWSAlgorithm.RS256, claims);
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

  private static MACSigner macSigner(byte[] secret) {
    try {
      return new MACSigner(secret);
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Date at(Instant instant) {
    return Date.from(instant);
  }

  private void assertInvalid(String idToken) {
    assertThat(reasonOf(verifier, idToken, NONCE))
        .as("token %s", idToken)
        .isEqualTo(Reason.INVALID);
  }

  private static Reason reasonOf(GoogleIdTokenVerifier v, String idToken, String nonce) {
    try {
      SocialIdentity accepted = v.verify(idToken, nonce);
      throw new AssertionError("거절되어야 하는데 받아들였다: " + accepted);
    } catch (SocialTokenException e) {
      return e.reason();
    }
  }
}
