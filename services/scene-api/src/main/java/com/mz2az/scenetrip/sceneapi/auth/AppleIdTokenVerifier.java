package com.mz2az.scenetrip.sceneapi.auth;

import com.mz2az.scenetrip.sceneapi.auth.SocialTokenException.Reason;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 애플 identityToken 을 검증한다 (계약 {@code POST /auth/apple}, MZ2AZ-337).
 *
 * <p>{@link GoogleIdTokenVerifier} 와 같은 틀이고 다른 점은 셋이다.
 *
 * <ol>
 *   <li>발급자는 {@code https://appleid.apple.com} 하나다.
 *   <li>대상({@code aud})은 클라이언트 ID 목록이 아니라 <b>앱의 번들 ID</b> 하나다 — 네이티브 앱의 애플 로그인은 번들 ID 가 곧 클라이언트다.
 *   <li><b>nonce 는 해시로 비교한다.</b> 앱은 애플 요청에 원문의 SHA-256(16 진 소문자)을 넣고 서버에는 원문을 보낸다(계약 {@code
 *       AppleSignIn.nonce}). 애플은 받은 값을 그대로 토큰에 넣으므로, 서버는 원문을 해시해 토큰 안의 값과 비교한다. 원문이 토큰에 실리지 않아 토큰이
 *       새도 nonce 원문은 드러나지 않는다.
 * </ol>
 *
 * <p>이름은 토큰에 없다 — 애플은 첫 로그인에만 앱에 따로 준다(계약 {@code givenName}·{@code familyName}). 이메일은 사용자가 가리기를 고르면
 * 대리 주소다. 사람은 {@code sub} 로만 가른다.
 */
@Component
public class AppleIdTokenVerifier {

  static final String PROVIDER = "apple";
  static final String ISSUER = "https://appleid.apple.com";

  private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

  private final DefaultJWTProcessor<SecurityContext> processor;
  private final String clientId;
  private final Clock clock;

  @Autowired
  public AppleIdTokenVerifier(
      @Value("${scenetrip.auth.apple.jwks-uri}") String jwksUri,
      @Value("${scenetrip.auth.apple.client-id}") String clientId) {
    this(remote(jwksUri), clientId, Clock.systemUTC());
  }

  /** 테스트가 직접 만든다 — 공개키를 로컬 키로 바꿔 끼워 애플을 부르지 않는다. */
  public AppleIdTokenVerifier(JWKSource<SecurityContext> keys, String clientId, Clock clock) {
    this.processor = new DefaultJWTProcessor<>();
    // 애플은 RS256 으로만 서명한다. 알고리즘을 못 박는다(GoogleIdTokenVerifier 와 같은 이유).
    this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    this.processor.setJWTClaimsSetVerifier((claims, context) -> {});
    this.clientId = clientId == null ? "" : clientId.strip();
    this.clock = clock;
  }

  /**
   * 검증하고 신분을 돌려준다. 이름은 비어 있다 — 호출하는 쪽이 앱이 보낸 이름을 붙인다.
   *
   * @param identityToken 앱이 받은 {@code identityToken} (UTF-8 문자열)
   * @param rawNonce 앱이 만든 nonce <b>원문</b>
   * @throws SocialTokenException 믿을 수 없거나({@code INVALID}) 애플에 닿지 못했다({@code UNAVAILABLE})
   */
  public SocialIdentity verify(String identityToken, String rawNonce) {
    JWTClaimsSet claims;
    try {
      claims = processor.process(identityToken, null);
    } catch (KeySourceException e) {
      throw new SocialTokenException(Reason.UNAVAILABLE, "애플 공개키를 받아 오지 못했습니다", e);
    } catch (ParseException | BadJOSEException | JOSEException e) {
      throw invalid("서명을 확인할 수 없습니다: " + e.getClass().getSimpleName());
    }

    if (!ISSUER.equals(claims.getIssuer())) {
      throw invalid("발급자가 애플이 아닙니다");
    }
    List<String> audience = claims.getAudience();
    if (clientId.isEmpty()
        || audience == null
        || audience.stream().filter(Objects::nonNull).noneMatch(clientId::equals)) {
      throw invalid("우리 앱 앞으로 발급된 토큰이 아닙니다");
    }
    Date exp = claims.getExpirationTime();
    if (exp == null || !clock.instant().minus(CLOCK_SKEW).isBefore(exp.toInstant())) {
      throw invalid("만료됐습니다");
    }
    String tokenNonce = stringClaim(claims, "nonce");
    if (tokenNonce == null || rawNonce == null || !tokenNonce.equals(sha256Hex(rawNonce))) {
      throw invalid("nonce 가 맞지 않습니다");
    }
    String subject = claims.getSubject();
    if (subject == null || subject.isBlank()) {
      throw invalid("sub 가 없습니다");
    }

    // 애플은 email_verified 를 문자열 "true" 로 주기도 하고 불리언으로 주기도 한다.
    Object verified = claims.getClaim("email_verified");
    boolean emailVerified =
        Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
    String email = emailVerified ? stringClaim(claims, "email") : null;
    return new SocialIdentity(PROVIDER, subject, email, null);
  }

  /** 앱이 애플 요청에 넣는 값과 같은 꼴 — SHA-256 의 16 진 소문자. */
  static String sha256Hex(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  private static String stringClaim(JWTClaimsSet claims, String name) {
    try {
      return claims.getStringClaim(name);
    } catch (ParseException e) {
      return null;
    }
  }

  private static SocialTokenException invalid(String why) {
    return new SocialTokenException(Reason.INVALID, why);
  }

  private static JWKSource<SecurityContext> remote(String jwksUri) {
    try {
      return JWKSourceBuilder.create(URI.create(jwksUri).toURL()).retrying(true).build();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("애플 JWKS 주소가 올바르지 않습니다: " + jwksUri, e);
    }
  }
}
