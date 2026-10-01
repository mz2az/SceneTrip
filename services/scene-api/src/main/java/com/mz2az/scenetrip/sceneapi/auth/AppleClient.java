package com.mz2az.scenetrip.sceneapi.auth;

import com.mz2az.scenetrip.sceneapi.auth.SocialTokenException.Reason;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 애플 서버에 우리 서버가 직접 보내는 두 요청 — 인가 코드 교환과 연결 끊기 (MZ2AZ-337).
 *
 * <p><b>왜 개인 키(.p8)가 필요한가.</b> 로그인 검증({@link AppleIdTokenVerifier})은 애플 공개키면 된다. 그런데 회원 탈퇴 때 애플에 연결
 * 끊기 (revoke)를 알려야 하고(App Store 요건), revoke 에는 그 사용자의 애플 refresh token 이 필요하다. 앱이 로그인 때 주는 것은 5 분짜리
 * 일회용 인가 코드뿐이라, 로그인할 때 서버가 그것을 refresh token 으로 바꿔 받아 둔다. 두 요청 모두 애플은 「SceneTrip 개발팀이 보냈다」 는 증명을
 * 요구한다 — 고정 비밀번호가 아니라 <b>.p8 로 서명한 짧은 JWT 를 client secret 으로</b> 쓰게 한다. 애플은 우리가 등록한 키의 공개키로 그 서명을
 * 확인한다.
 *
 * <p>client secret 은 요청마다 새로 만들고 5 분만 유효하게 한다. 애플은 최대 6 개월까지 받지만 길게 만들어 둘 이유가 없다.
 *
 * <h2>키가 없으면</h2>
 *
 * <p>서버는 뜨고 애플 로그인만 꺼진다({@link #enabled()} false). 로그인은 되는데 탈퇴 때 연결을 못 끊는 상태를 만들지 않으려고, 키가 없으면 로그인
 * 창구 자체를 닫는다({@code /auth/apple} 이 {@code 501}).
 */
@Component
public class AppleClient {

  private static final Logger log = LoggerFactory.getLogger(AppleClient.class);

  /** client secret 의 수명. 요청마다 새로 만든다. */
  private static final Duration SECRET_TTL = Duration.ofMinutes(5);

  private final RestClient http;
  private final String clientId;
  private final String teamId;
  private final String keyId;
  private final ECPrivateKey privateKey;
  private final Clock clock;

  @Autowired
  public AppleClient(
      @Value("${scenetrip.auth.apple.base-url}") String baseUrl,
      @Value("${scenetrip.auth.apple.client-id}") String clientId,
      @Value("${scenetrip.auth.apple.team-id}") String teamId,
      @Value("${scenetrip.auth.apple.key-id}") String keyId,
      @Value("${scenetrip.auth.apple.private-key:}") String privateKeyBase64,
      @Value("${scenetrip.auth.apple.timeout-seconds}") int timeoutSeconds) {
    this(
        restClient(baseUrl, timeoutSeconds),
        clientId,
        teamId,
        keyId,
        parsePrivateKey(privateKeyBase64),
        Clock.systemUTC());
    if (privateKey == null) {
      log.warn("애플 개인 키가 없습니다 (SCENETRIP_AUTH_APPLE_PRIVATE_KEY) — 애플 로그인이 꺼집니다(/auth/apple 501).");
    }
  }

  /** 테스트가 직접 만든다 — 애플 대신 로컬 가짜 서버를 가리키는 RestClient 와 테스트 키를 준다. */
  public AppleClient(
      RestClient http,
      String clientId,
      String teamId,
      String keyId,
      ECPrivateKey privateKey,
      Clock clock) {
    this.http = http;
    this.clientId = clientId;
    this.teamId = teamId;
    this.keyId = keyId;
    this.privateKey = privateKey;
    this.clock = clock;
  }

  /** 개인 키가 있어 애플과 통신할 수 있는가. */
  public boolean enabled() {
    return privateKey != null;
  }

  /**
   * 인가 코드를 애플 refresh token 으로 바꾼다.
   *
   * @param authorizationCode 앱이 받은 코드 — 5 분, 한 번만 쓸 수 있다
   * @return 애플 refresh token 원문. <b>로그에 남기지 않는다</b>
   * @throws SocialTokenException 코드가 만료·재사용·위조면 {@code INVALID}, 애플에 닿지 못하면 {@code UNAVAILABLE}
   */
  public String exchange(String authorizationCode) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret());
    form.add("code", authorizationCode);
    form.add("grant_type", "authorization_code");
    Map<?, ?> body;
    try {
      body =
          http.post()
              .uri("/auth/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(Map.class);
    } catch (HttpClientErrorException e) {
      // 400 invalid_grant: 코드가 만료됐거나 이미 썼거나 우리 앱 것이 아니다 — 사용자 쪽 토큰 문제다.
      // 401 invalid_client: 우리 client secret 이 틀렸다 — 서버 설정 문제라 로그에 크게 남긴다.
      if (e.getStatusCode().value() == 401) {
        log.error("애플이 client secret 을 거절했습니다 — Team ID·Key ID·개인 키·번들 ID 를 확인하세요");
      }
      throw new SocialTokenException(Reason.INVALID, "애플 인가 코드를 교환할 수 없습니다: " + e.getStatusCode());
    } catch (RestClientException e) {
      throw new SocialTokenException(Reason.UNAVAILABLE, "애플 토큰 창구에 닿지 못했습니다", e);
    }
    Object token = body == null ? null : body.get("refresh_token");
    if (!(token instanceof String refreshToken) || refreshToken.isBlank()) {
      throw new SocialTokenException(Reason.UNAVAILABLE, "애플 응답에 refresh_token 이 없습니다");
    }
    return refreshToken;
  }

  /**
   * 회원 탈퇴 — 애플 쪽 연결을 끊는다.
   *
   * <p><b>실패해도 던지지 않는다.</b> 계약상 탈퇴는 애플이 응답하지 않아도 진행한다 — 사용자가 탈퇴를 요청했는데 남의 서버 사정으로 거절할 수 없다. 실패는 로그로
   * 남긴다.
   *
   * @return 애플이 받아들였으면 {@code true}
   */
  public boolean revoke(String refreshToken) {
    if (privateKey == null) {
      // 키가 없으면 끊을 수 없다. 「실패해도 던지지 않는다」 는 약속은 이 경우에도 지킨다.
      log.warn("애플 개인 키가 없어 연결을 끊지 못했습니다 — 우리 쪽 탈퇴는 진행합니다");
      return false;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret());
    form.add("token", refreshToken);
    form.add("token_type_hint", "refresh_token");
    try {
      http.post()
          .uri("/auth/revoke")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RestClientException e) {
      log.warn("애플 연결 끊기 실패 — 우리 쪽 탈퇴는 진행합니다: {}", e.getMessage());
      return false;
    }
  }

  /** .p8 로 서명한 client secret(ES256 JWT). 요청마다 새로 만든다. */
  String clientSecret() {
    if (privateKey == null) {
      throw new IllegalStateException("애플 개인 키가 없습니다");
    }
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(teamId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(SECRET_TTL)))
            .audience("https://appleid.apple.com")
            .subject(clientId)
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
    try {
      jwt.sign(new ECDSASigner(privateKey));
    } catch (JOSEException e) {
      throw new IllegalStateException("애플 client secret 서명에 실패했습니다", e);
    }
    return jwt.serialize();
  }

  /**
   * .p8 을 읽는다. 환경변수에는 파일 전체(PEM)를 base64 한 줄로 넣는다 — 줄바꿈이 든 값을 환경변수·Secret 으로 옮기다 깨지는 일을 피한다.
   *
   * @return 비어 있으면 {@code null}(애플 로그인 꺼짐). 형식이 틀리면 기동을 멈춘다
   */
  static ECPrivateKey parsePrivateKey(String base64Pem) {
    if (base64Pem == null || base64Pem.isBlank()) {
      return null;
    }
    try {
      String pem =
          new String(Base64.getDecoder().decode(base64Pem.strip()), StandardCharsets.UTF_8);
      String body =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(body);
      return (ECPrivateKey)
          KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
      // 값을 메시지에 싣지 않는다.
      throw new IllegalArgumentException(
          "SCENETRIP_AUTH_APPLE_PRIVATE_KEY 가 애플 .p8(PEM)의 base64 가 아닙니다");
    }
  }

  private static RestClient restClient(String baseUrl, int timeoutSeconds) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }
}
