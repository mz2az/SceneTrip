package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.mz2az.scenetrip.sceneapi.auth.InvalidAccessTokenException.Reason;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 액세스 토큰의 발급·검증을 명세(계획 §3·§6, ADR 0018, 오류 문서의 인증 절)에 비춰 본다.
 *
 * <p>공격용 토큰은 Nimbus 로 직접 만든다. 검증 쪽이 쓰는 라이브러리로 만들어야 「형식은 멀쩡한데 믿으면 안 되는」 토큰이 된다 — 아무 문자열이나 넣으면 파싱에서
 * 떨어져, 서명·발급자·알고리즘 검사가 실제로 있는지는 확인되지 않는다.
 */
@DisplayName("AccessTokens — 발급과 검증")
class AccessTokensTest {

  // HS512 토큰을 같은 키로 만들려면 64 바이트가 필요하다. 그래서 기본 키를 64 바이트로 둔다 —
  // 「다른 알고리즘이지만 키는 맞는」 토큰이어야 알고리즘 고정이 검증된다.
  private static final byte[] SECRET = bytes(64, (byte) 7);
  private static final byte[] OTHER_SECRET = bytes(64, (byte) 9);
  private static final Duration TTL = Duration.ofMinutes(30);
  private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

  private final MutableClock clock = new MutableClock(T0);
  private final AccessTokens tokens = new AccessTokens(SECRET, TTL, clock);
  private final UUID user = UUID.randomUUID();

  // ───────────── 정상 경로 ─────────────

  @Test
  @DisplayName("발급한 토큰을 검증하면 같은 계정 id 가 나온다")
  void roundTrip() {
    IssuedToken issued = tokens.issue(user);

    assertThat(tokens.verify(issued.value())).isEqualTo(user);
  }

  @Test
  @DisplayName("expiresInSeconds 는 설정한 수명의 초다")
  void expiresInIsTtlSeconds() {
    assertThat(tokens.issue(user).expiresInSeconds()).isEqualTo(TTL.toSeconds());
  }

  @Test
  @DisplayName("HS256 으로 서명되고 sub·iat·exp·jti·iss 를 담는다")
  void claimsFollowSpec() throws ParseException {
    SignedJWT jwt = SignedJWT.parse(tokens.issue(user).value());
    JWTClaimsSet claims = jwt.getJWTClaimsSet();

    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
    assertThat(claims.getSubject()).isEqualTo(user.toString());
    assertThat(claims.getIssuer()).isEqualTo(AccessTokens.ISSUER);
    assertThat(claims.getJWTID()).isNotBlank();
    assertThat(claims.getIssueTime()).isNotNull();
    assertThat(claims.getExpirationTime().toInstant())
        .isEqualTo(claims.getIssueTime().toInstant().plus(TTL));
  }

  @Test
  @DisplayName("같은 시각에 같은 계정으로 두 번 발급해도 토큰이 다르다 — jti")
  void tokensAreDistinctPerIssue() {
    assertThat(tokens.issue(user).value()).isNotEqualTo(tokens.issue(user).value());
  }

  @Test
  @DisplayName("수명이 다하기 직전까지는 유효하다")
  void validJustBeforeExpiry() {
    IssuedToken issued = tokens.issue(user);
    clock.advance(TTL.minusSeconds(1));

    assertThat(tokens.verify(issued.value())).isEqualTo(user);
  }

  @Test
  @DisplayName("Spring 생성자(base64 키)로 만든 쪽이 같은 키의 토큰을 검증한다")
  void springConstructorAcceptsSameKey() {
    AccessTokens fromBase64 = new AccessTokens(Base64.getEncoder().encodeToString(SECRET), TTL);
    // 시스템 시계를 쓰는 쪽이라 발급도 시스템 시계로 맞춘다.
    AccessTokens issuer = new AccessTokens(SECRET, TTL, Clock.systemUTC());

    assertThat(fromBase64.enabled()).isTrue();
    assertThat(fromBase64.verify(issuer.issue(user).value())).isEqualTo(user);
  }

  // ───────────── 만료 ─────────────

  @Test
  @DisplayName("수명이 지나면 EXPIRED")
  void expiredAfterTtl() {
    IssuedToken issued = tokens.issue(user);
    clock.advance(TTL.plusSeconds(1));

    assertThat(reasonOf(issued.value())).isEqualTo(Reason.EXPIRED);
  }

  @Test
  @DisplayName("exp 시각 그 자체는 이미 만료다 (RFC 7519 — 현재 시각이 exp 보다 앞서야 유효)")
  void expiredExactlyAtExp() {
    IssuedToken issued = tokens.issue(user);
    clock.advance(TTL);

    assertThat(reasonOf(issued.value())).isEqualTo(Reason.EXPIRED);
  }

  // ───────────── 믿을 수 없는 토큰 — 언제나 INVALID ─────────────

  @Test
  @DisplayName("본문(payload)을 고치면 INVALID")
  void tamperedPayloadIsInvalid() {
    String[] parts = tokens.issue(user).value().split("\\.");
    String forged =
        base64Url(
            new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(AccessTokens.ISSUER)
                .issueTime(Date.from(T0))
                .expirationTime(Date.from(T0.plus(TTL)))
                .build()
                .toString());

    assertThat(reasonOf(parts[0] + "." + forged + "." + parts[2])).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("서명을 고치면 INVALID")
  void tamperedSignatureIsInvalid() {
    String[] parts = tokens.issue(user).value().split("\\.");
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 0x01;

    assertThat(reasonOf(parts[0] + "." + parts[1] + "." + base64Url(signature)))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("다른 키로 서명한 토큰은 INVALID")
  void otherSecretIsInvalid() {
    assertThat(reasonOf(sign(JWSAlgorithm.HS256, OTHER_SECRET, validClaims().build())))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("alg=none(서명 없음) 토큰은 INVALID")
  void algNoneIsInvalid() {
    String unsigned = new PlainJWT(validClaims().build()).serialize();

    assertThat(reasonOf(unsigned)).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("alg=none 헤더에 원래 서명을 붙여도 INVALID")
  void algNoneHeaderWithSignatureIsInvalid() {
    String[] parts = tokens.issue(user).value().split("\\.");
    String noneHeader = base64Url("{\"alg\":\"none\"}");

    assertThat(reasonOf(noneHeader + "." + parts[1] + "." + parts[2])).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("같은 키라도 HS512 로 서명했으면 INVALID — HS256 만 받는다")
  void hs512IsInvalid() {
    assertThat(reasonOf(sign(JWSAlgorithm.HS512, SECRET, validClaims().build())))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("JWT 가 아닌 문자열과 빈 문자열은 INVALID")
  void garbageIsInvalid() {
    assertThat(reasonOf("not-a-jwt")).isEqualTo(Reason.INVALID);
    assertThat(reasonOf("a.b.c")).isEqualTo(Reason.INVALID);
    assertThat(reasonOf("")).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("발급자가 우리가 아니면 INVALID — 키가 맞아도")
  void foreignIssuerIsInvalid() {
    String token = sign(JWSAlgorithm.HS256, SECRET, validClaims().issuer("someone-else").build());

    assertThat(reasonOf(token)).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("발급자가 없으면 INVALID")
  void missingIssuerIsInvalid() {
    String token = sign(JWSAlgorithm.HS256, SECRET, validClaims().issuer(null).build());

    assertThat(reasonOf(token)).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("sub 가 UUID 가 아니면 INVALID")
  void nonUuidSubjectIsInvalid() {
    String token = sign(JWSAlgorithm.HS256, SECRET, validClaims().subject("user-42").build());

    assertThat(reasonOf(token)).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("만료된 위조 토큰은 EXPIRED 가 아니라 INVALID — 만료는 서명이 맞은 뒤에만 말한다")
  void expiredForgeryIsInvalidNotExpired() {
    // 만료 시각을 과거로 잡고 다른 키로 서명한다. 「만료」 라고 답하면 앱이 갱신을 시도하고,
    // 위조하는 쪽에는 그 토큰이 그럴듯했다는 신호가 된다.
    String token =
        sign(
            JWSAlgorithm.HS256,
            OTHER_SECRET,
            validClaims()
                .issueTime(Date.from(T0.minus(TTL).minusSeconds(60)))
                .expirationTime(Date.from(T0.minusSeconds(60)))
                .build());

    assertThat(reasonOf(token)).isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("고친 토큰을 만료 뒤에 보내도 INVALID")
  void tamperedTokenAfterExpiryIsInvalid() {
    String[] parts = tokens.issue(user).value().split("\\.");
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[signature.length - 1] ^= 0x01;
    clock.advance(TTL.plusSeconds(60));

    assertThat(reasonOf(parts[0] + "." + parts[1] + "." + base64Url(signature)))
        .isEqualTo(Reason.INVALID);
  }

  @Test
  @DisplayName("만료됐고 발급자도 다른 토큰은 INVALID")
  void expiredForeignIssuerIsInvalid() {
    String token =
        sign(
            JWSAlgorithm.HS256,
            SECRET,
            validClaims()
                .issuer("someone-else")
                .issueTime(Date.from(T0.minus(TTL).minusSeconds(60)))
                .expirationTime(Date.from(T0.minusSeconds(60)))
                .build());

    assertThat(reasonOf(token)).isEqualTo(Reason.INVALID);
  }

  // ───────────── 키가 없을 때 — 닫힌 쪽으로 실패 ─────────────

  @Test
  @DisplayName("키가 null 이면 꺼진다 — 발급은 IllegalStateException, 검증은 INVALID")
  void nullSecretDisablesLogin() {
    assertDisabled(new AccessTokens((byte[]) null, TTL, clock));
  }

  @Test
  @DisplayName("키가 빈 바이트면 꺼진다")
  void emptySecretDisablesLogin() {
    assertDisabled(new AccessTokens(new byte[0], TTL, clock));
  }

  @Test
  @DisplayName("Spring 생성자에 빈·공백 키가 오면 꺼진다")
  void blankBase64SecretDisablesLogin() {
    assertDisabled(new AccessTokens("", TTL));
    assertDisabled(new AccessTokens("   ", TTL));
  }

  @Test
  @DisplayName("키가 있으면 켜져 있다")
  void secretEnablesLogin() {
    assertThat(tokens.enabled()).isTrue();
  }

  // ───────────── 설정 실수는 기동을 멈춘다 ─────────────

  @Test
  @DisplayName("32 바이트보다 짧은 키는 거부한다")
  void shortSecretIsRejected() {
    assertThatThrownBy(() -> new AccessTokens(bytes(31, (byte) 1), TTL, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("정확히 32 바이트 키는 받는다")
  void minimumSecretIsAccepted() {
    AccessTokens minimal = new AccessTokens(bytes(32, (byte) 1), TTL, clock);

    assertThat(minimal.verify(minimal.issue(user).value())).isEqualTo(user);
  }

  @Test
  @DisplayName("수명이 0 이거나 음수면 거부한다")
  void nonPositiveTtlIsRejected() {
    assertThatThrownBy(() -> new AccessTokens(SECRET, Duration.ZERO, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokens(SECRET, Duration.ofSeconds(-1), clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("base64 가 아닌 키는 거부하고, 메시지에 그 값을 싣지 않는다")
  void nonBase64SecretIsRejectedWithoutLeaking() {
    String notBase64 = "fake-secret-%%%-not-base64-0123456789";

    IllegalArgumentException e =
        catchThrowableOfType(
            IllegalArgumentException.class, () -> new AccessTokens(notBase64, TTL));

    assertThat(e).isNotNull();
    assertThat(String.valueOf(e.getMessage())).doesNotContain(notBase64);
    // 원인 예외의 메시지에 값 일부가 실려 올라오는 경우도 막혀야 한다 — 로그는 원인까지 찍는다.
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      assertThat(String.valueOf(cause.getMessage())).doesNotContain(notBase64);
    }
  }

  @Test
  @DisplayName("Spring 생성자도 짧은 키는 거부한다")
  void springConstructorRejectsShortSecret() {
    String shortKey = Base64.getEncoder().encodeToString(bytes(16, (byte) 3));

    assertThatThrownBy(() -> new AccessTokens(shortKey, TTL))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ───────────── IssuedToken ─────────────

  @Test
  @DisplayName("IssuedToken.toString 은 토큰 원문을 드러내지 않는다")
  void issuedTokenToStringHidesValue() {
    IssuedToken fake = new IssuedToken("fake-token-value-for-test", 1800);

    assertThat(fake.toString()).doesNotContain("fake-token-value-for-test");
  }

  @Test
  @DisplayName("발급한 액세스 토큰의 toString 에도 원문이 없다")
  void issuedAccessTokenToStringHidesValue() {
    IssuedToken issued = tokens.issue(user);

    assertThat(issued.toString()).doesNotContain(issued.value());
  }

  // ───────────── 도우미 ─────────────

  private void assertDisabled(AccessTokens disabled) {
    assertThat(disabled.enabled()).isFalse();
    assertThatThrownBy(() -> disabled.issue(user)).isInstanceOf(IllegalStateException.class);

    // 꺼진 서버는 어떤 키로 서명했든 받지 않는다 — 진짜 키로 만든 멀쩡한 토큰까지.
    InvalidAccessTokenException e =
        catchThrowableOfType(
            InvalidAccessTokenException.class, () -> disabled.verify(tokens.issue(user).value()));
    assertThat(e).isNotNull();
    assertThat(e.reason()).isEqualTo(Reason.INVALID);
  }

  private Reason reasonOf(String token) {
    InvalidAccessTokenException e =
        catchThrowableOfType(InvalidAccessTokenException.class, () -> tokens.verify(token));
    assertThat(e).as("검증이 예외를 던져야 한다").isNotNull();
    return e.reason();
  }

  /** 지금 시계로 유효한 클레임. 테스트마다 한 곳씩만 비튼다. */
  private JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .subject(user.toString())
        .issuer(AccessTokens.ISSUER)
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(clock.instant()))
        .expirationTime(Date.from(clock.instant().plus(TTL)));
  }

  private static String sign(JWSAlgorithm alg, byte[] secret, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims);
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String base64Url(String json) {
    return base64Url(json.getBytes(StandardCharsets.UTF_8));
  }

  private static String base64Url(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static byte[] bytes(int length, byte fill) {
    byte[] b = new byte[length];
    Arrays.fill(b, fill);
    return b;
  }

  /** 테스트가 시간을 앞으로 돌리는 시계. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
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
