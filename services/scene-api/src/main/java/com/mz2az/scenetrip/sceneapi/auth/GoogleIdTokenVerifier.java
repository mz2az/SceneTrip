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
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 구글 ID 토큰을 검증한다 (계약 {@code POST /auth/google}, 계획 §5).
 *
 * <p>구글이 공개한 공개키(JWKS)로 서명을 확인하고, 다섯 가지를 본다.
 *
 * <ol>
 *   <li>발급자 — {@code accounts.google.com} (스킴이 붙은 것과 안 붙은 것 둘 다 구글이 쓴다)
 *   <li>대상({@code aud}) — <b>우리 클라이언트 ID</b>. 이것을 안 보면 남의 앱용으로 발급된 구글 토큰으로도 우리 서버에 로그인된다. 웹 클라이언트
 *       ID (Android 가 {@code serverClientId} 로 쓴다)와 iOS 클라이언트 ID 둘이다.
 *   <li>만료 — 구글 시계와 우리 시계가 조금 어긋날 수 있어 1 분 여유를 둔다.
 *   <li>{@code nonce} — 앱이 로그인 시도마다 만든 값과 같아야 한다. 남의 ID 토큰을 가로채 다시 보내는 것을 막는다. 구글은 원문을 그대로 넣는다(애플은
 *       해시를 넣는다).
 *   <li>{@code sub} 가 있어야 한다 — 사람의 구분이 이것이다.
 * </ol>
 *
 * <p>공개키는 받아서 캐시해 두고, 처음 보는 키 id 가 오면 다시 받는다(구글이 키를 돌린다). 받아 오지 못하면 {@link Reason#UNAVAILABLE} 이다 —
 * 사용자 잘못이 아니다.
 */
@Component
public class GoogleIdTokenVerifier {

  static final String PROVIDER = "google";

  private static final Set<String> ISSUERS =
      Set.of("https://accounts.google.com", "accounts.google.com");

  /** 구글과 우리 시계의 어긋남을 이만큼 봐준다. */
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

  private final DefaultJWTProcessor<SecurityContext> processor;
  private final Set<String> clientIds;
  private final Clock clock;

  @Autowired
  public GoogleIdTokenVerifier(
      @Value("${scenetrip.auth.google.jwks-uri}") String jwksUri,
      @Value("${scenetrip.auth.google.client-ids}") List<String> clientIds) {
    this(remote(jwksUri), clientIds, Clock.systemUTC());
  }

  /**
   * 테스트가 직접 만든다 — 공개키를 로컬 키로 바꿔 끼워 구글을 부르지 않는다.
   *
   * @param clientIds 받아 줄 {@code aud}. 비어 있으면 아무 토큰도 통과하지 못한다
   */
  public GoogleIdTokenVerifier(
      JWKSource<SecurityContext> keys, Collection<String> clientIds, Clock clock) {
    this.processor = new DefaultJWTProcessor<>();
    // 구글은 RS256 으로만 서명한다. 알고리즘을 못 박아 다른 것으로 바꿔 검증을 비켜 가는 길을 막는다.
    this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    // 클레임 검사는 아래에서 직접 한다 — 시계를 주입받아 테스트할 수 있어야 하고, 기본 검사기는
    // 시스템 시계를 쓴다.
    this.processor.setJWTClaimsSetVerifier((claims, context) -> {});
    this.clientIds =
        clientIds.stream()
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    this.clock = clock;
  }

  /**
   * 검증하고 신분을 돌려준다.
   *
   * @param idToken 앱이 구글 SDK 에서 받은 ID 토큰
   * @param nonce 앱이 이 로그인 시도에 만든 난수 — 원문
   * @throws SocialTokenException 믿을 수 없거나({@code INVALID}) 구글에 닿지 못했다({@code UNAVAILABLE})
   */
  public SocialIdentity verify(String idToken, String nonce) {
    JWTClaimsSet claims;
    try {
      claims = processor.process(idToken, null);
    } catch (KeySourceException e) {
      // 공개키를 받아 오다 실패했다(네트워크·구글 장애). 서명이 틀린 것과 다르다.
      throw new SocialTokenException(Reason.UNAVAILABLE, "구글 공개키를 받아 오지 못했습니다", e);
    } catch (ParseException | BadJOSEException | JOSEException e) {
      throw invalid("서명을 확인할 수 없습니다: " + e.getClass().getSimpleName());
    }

    // Set.of 로 만든 집합은 null 을 물으면 「없다」 가 아니라 예외를 던진다. 발급자가 없는 토큰이
    // 500 이 되지 않게 먼저 거른다 — 테스트(missingIssuerIsInvalid)가 잡은 것이다.
    String issuer = claims.getIssuer();
    if (issuer == null || !ISSUERS.contains(issuer)) {
      throw invalid("발급자가 구글이 아닙니다");
    }
    List<String> audience = claims.getAudience();
    if (audience == null
        || audience.stream().filter(Objects::nonNull).noneMatch(clientIds::contains)) {
      throw invalid("우리 클라이언트 앞으로 발급된 토큰이 아닙니다");
    }
    Date exp = claims.getExpirationTime();
    if (exp == null || !clock.instant().minus(CLOCK_SKEW).isBefore(exp.toInstant())) {
      throw invalid("만료됐습니다");
    }
    String tokenNonce = stringClaim(claims, "nonce");
    if (tokenNonce == null || nonce == null || !tokenNonce.equals(nonce)) {
      throw invalid("nonce 가 맞지 않습니다");
    }
    String subject = claims.getSubject();
    if (subject == null || subject.isBlank()) {
      throw invalid("sub 가 없습니다");
    }

    // 이메일은 구글이 확인한 것만 받는다. 확인 안 된 주소는 아무나 적을 수 있다 — 참고용이라도
    // 남의 주소를 내 계정에 남길 이유가 없다.
    String email =
        Boolean.TRUE.equals(booleanClaim(claims, "email_verified"))
            ? stringClaim(claims, "email")
            : null;
    return new SocialIdentity(PROVIDER, subject, email, stringClaim(claims, "name"));
  }

  private static String stringClaim(JWTClaimsSet claims, String name) {
    try {
      return claims.getStringClaim(name);
    } catch (ParseException e) {
      return null;
    }
  }

  private static Boolean booleanClaim(JWTClaimsSet claims, String name) {
    try {
      return claims.getBooleanClaim(name);
    } catch (ParseException e) {
      // 구글이 문자열 "true" 로 주던 시절이 있었다. 모양이 다르면 확인 안 된 것으로 본다.
      return Boolean.FALSE;
    }
  }

  private static SocialTokenException invalid(String why) {
    return new SocialTokenException(Reason.INVALID, why);
  }

  private static JWKSource<SecurityContext> remote(String jwksUri) {
    try {
      // 캐시(키를 매번 받지 않는다), 처음 보는 kid 면 다시 받기, 일시 장애 때 한 번 재시도.
      return JWKSourceBuilder.create(URI.create(jwksUri).toURL()).retrying(true).build();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("구글 JWKS 주소가 올바르지 않습니다: " + jwksUri, e);
    }
  }
}
