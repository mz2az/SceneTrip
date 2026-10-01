package com.mz2az.scenetrip.sceneapi.auth;

import com.mz2az.scenetrip.sceneapi.auth.InvalidAccessTokenException.Reason;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 우리 액세스 토큰 — 서명된 JWT 를 발급하고 검증한다 (ADR 0018).
 *
 * <p><b>HS256 이다.</b> 서명과 검증을 같은 서버(scene-api)가 하므로 비밀키 하나면 된다. 다른 서비스가 직접 검증하게 되면 공개키 방식(ES256)으로
 * 옮긴다 — 그때 그 서비스에 서명 능력까지 나눠 줄 수는 없기 때문이다(계획 §11).
 *
 * <p>담는 것은 계정 id({@code sub})와 시각뿐이다. 앱은 토큰 안을 읽지 않고(계약 {@code bearerAuth}), 가입 여부처럼 바뀔 수 있는 것은 넣지
 * 않는다 — 넣으면 탈퇴·합치기 뒤에도 토큰이 만료될 때까지 옛 사실을 말한다. 계정이 살아 있는지는 요청마다 DB 가 답한다.
 *
 * <h2>키가 없으면</h2>
 *
 * <p>서버는 뜬다. 발급은 {@link IllegalStateException}, 검증은 언제나 {@link Reason#INVALID} 다 — <b>닫힌 쪽으로
 * 실패한다.</b> 키가 없으면 아무도 로그인할 수 없을 뿐 비회원 기능은 그대로 돈다. 카카오 키가 없을 때 길찾기만 멈추는 것과 같은 판단이다(KAKAO_REST_KEY).
 * 키가 <b>있는데 짧으면</b> 기동을 멈춘다 — 설정 실수를 조용히 넘기지 않는다.
 */
@Component
public class AccessTokens {

  /** 발급자. 남이 같은 비밀키로 만든 토큰을 받을 일은 없지만, 우리 토큰임을 한 번 더 확인하는 값이다. */
  static final String ISSUER = "scene-api";

  /** HS256 은 키가 해시 출력(256 비트)보다 짧으면 안 된다. Nimbus 도 이 아래는 거부한다. */
  static final int MIN_SECRET_BYTES = 32;

  private static final Logger log = LoggerFactory.getLogger(AccessTokens.class);

  private final MACSigner signer;
  private final MACVerifier verifier;
  private final Duration ttl;
  private final Clock clock;

  /**
   * @param secretBase64 서명 키(base64). {@code SCENETRIP_AUTH_JWT_SECRET} 환경변수로 들어온다. 비어 있으면 로그인이
   *     꺼진다.
   */
  @Autowired
  public AccessTokens(
      @Value("${scenetrip.auth.jwt-secret:}") String secretBase64,
      @Value("${scenetrip.auth.access-token-ttl}") Duration ttl) {
    this(decode(secretBase64), ttl, Clock.systemUTC());
    if (signer == null) {
      log.warn("JWT 서명 키가 없습니다 (SCENETRIP_AUTH_JWT_SECRET) — 로그인이 꺼집니다. 비회원 기능은 그대로입니다.");
    }
  }

  /**
   * 테스트와 다른 패키지가 직접 만든다.
   *
   * @param secret 서명 키. {@code null} 이거나 비어 있으면 로그인이 꺼진다. 있는데 32 바이트보다 짧으면 거부한다.
   */
  public AccessTokens(byte[] secret, Duration ttl, Clock clock) {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("액세스 토큰 수명은 양수여야 합니다: " + ttl);
    }
    this.ttl = ttl;
    this.clock = clock;
    if (secret == null || secret.length == 0) {
      this.signer = null;
      this.verifier = null;
      return;
    }
    if (secret.length < MIN_SECRET_BYTES) {
      throw new IllegalArgumentException(
          "JWT 서명 키가 너무 짧습니다 — "
              + MIN_SECRET_BYTES
              + " 바이트 이상이어야 합니다 (지금 "
              + secret.length
              + "). openssl rand -base64 48 로 만드세요");
    }
    try {
      this.signer = new MACSigner(secret);
      this.verifier = new MACVerifier(secret);
    } catch (JOSEException e) {
      throw new IllegalArgumentException("JWT 서명 키를 쓸 수 없습니다", e);
    }
  }

  /** 서명 키가 있어 로그인할 수 있는가. */
  public boolean enabled() {
    return signer != null;
  }

  /**
   * 이 계정의 액세스 토큰을 만든다.
   *
   * @throws IllegalStateException 서명 키가 없다 — 로그인이 꺼져 있다
   */
  public IssuedToken issue(UUID userId) {
    if (signer == null) {
      throw new IllegalStateException("JWT 서명 키가 없어 토큰을 발급할 수 없습니다");
    }
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(userId.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            // 토큰마다 다르게. 같은 초에 같은 계정으로 두 번 발급해도 문자열이 갈려, 로그에서
            // 「같은 토큰을 두 번 받았다」 와 「두 번 발급했다」 가 구분된다.
            .jwtID(UUID.randomUUID().toString())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("액세스 토큰 서명에 실패했습니다", e);
    }
    return new IssuedToken(jwt.serialize(), ttl.toSeconds());
  }

  /**
   * 토큰을 검증하고 그 계정 id 를 돌려준다.
   *
   * <p><b>만료는 서명이 맞은 뒤에만 말한다.</b> 위조 토큰에 「만료됐다」 고 답하면 앱이 갱신을 시도하고, 공격자에게는 그 값이 그럴듯했다는 신호가 된다.
   *
   * <p>계정이 살아 있는지(탈퇴)는 보지 않는다 — DB 를 아는 쪽이 본다.
   *
   * @throws InvalidAccessTokenException 믿을 수 없다. 이유는 만료 / 그 밖 둘뿐이다
   */
  public UUID verify(String token) {
    if (verifier == null) {
      throw invalid("서명 키가 없어 검증할 수 없습니다");
    }
    SignedJWT jwt;
    JWTClaimsSet claims;
    try {
      jwt = SignedJWT.parse(token);
      // 알고리즘을 못 박는다. 헤더의 alg 를 그대로 믿으면 "none" 이나 다른 알고리즘으로 바꿔
      // 검증을 비켜 가는 고전적인 공격이 열린다. 우리는 HS256 만 발급한다.
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
        throw invalid("알고리즘이 HS256 이 아닙니다");
      }
      if (!jwt.verify(verifier)) {
        throw invalid("서명이 맞지 않습니다");
      }
      claims = jwt.getJWTClaimsSet();
    } catch (ParseException | JOSEException | IllegalStateException e) {
      // IllegalStateException: 서명되지 않은 객체를 verify 하려 할 때 Nimbus 가 던진다.
      throw invalid("토큰 형식이 아닙니다");
    }

    if (!ISSUER.equals(claims.getIssuer())) {
      throw invalid("발급자가 다릅니다");
    }
    Date exp = claims.getExpirationTime();
    if (exp == null) {
      throw invalid("만료 시각이 없습니다");
    }
    UUID userId;
    try {
      userId = UUID.fromString(claims.getSubject());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw invalid("sub 가 계정 id 가 아닙니다");
    }
    // exp 는 초 단위라 경계에서 1 초 안쪽의 차이는 의미가 없다. exp 와 같은 초는 만료로 본다.
    if (!clock.instant().isBefore(exp.toInstant())) {
      throw new InvalidAccessTokenException(Reason.EXPIRED, "만료됐습니다");
    }
    return userId;
  }

  private static InvalidAccessTokenException invalid(String why) {
    return new InvalidAccessTokenException(Reason.INVALID, why);
  }

  private static byte[] decode(String secretBase64) {
    if (secretBase64 == null || secretBase64.isBlank()) {
      return null;
    }
    try {
      return Base64.getDecoder().decode(secretBase64.strip());
    } catch (IllegalArgumentException e) {
      // 값을 메시지에 싣지 않는다 — 비밀값이다.
      throw new IllegalArgumentException(
          "SCENETRIP_AUTH_JWT_SECRET 이 base64 가 아닙니다. openssl rand -base64 48 로 만드세요");
    }
  }
}
