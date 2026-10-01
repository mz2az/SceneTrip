package com.mz2az.scenetrip.sceneapi.auth;

import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 애플 로그인에 필요한 것을 한데 묶는다 — 토큰 검증, 코드 교환, 토큰 암호화, 탈퇴 때 연결 끊기 (MZ2AZ-337).
 *
 * <p>애플만의 일이 넷이라 컨트롤러가 그것을 하나하나 알 필요가 없게 했다. 가입·로그인·합치기는 구글과 같은 {@link SignInService} 를 탄다.
 *
 * <p><b>켜지는 조건</b>: 애플 개인 키({@link AppleClient})와 토큰 암호화 키({@link TokenCipher})가 둘 다 있어야 한다. 하나라도
 * 없으면 로그인 창구를 닫는다 — 로그인은 되는데 탈퇴 때 애플 연결을 못 끊는 상태(App Store 요건 위반)를 만들지 않는다.
 */
@Component
public class AppleLogin {

  private static final Logger log = LoggerFactory.getLogger(AppleLogin.class);

  private final AppleIdTokenVerifier verifier;
  private final AppleClient client;
  private final TokenCipher cipher;
  private final AccountLinkStore links;

  public AppleLogin(
      AppleIdTokenVerifier verifier,
      AppleClient client,
      TokenCipher cipher,
      AccountLinkStore links) {
    this.verifier = verifier;
    this.client = client;
    this.cipher = cipher;
    this.links = links;
  }

  /** 애플 로그인을 받을 수 있는가. */
  public boolean enabled() {
    return client.enabled() && cipher.enabled();
  }

  /**
   * 앱이 보낸 것으로 신분을 만든다 — 토큰 검증 → 코드 교환 → 애플 refresh token 암호화.
   *
   * <p>순서가 뜻을 가진다. 토큰부터 검증해, 믿을 수 없는 요청으로 애플 토큰 창구를 부르지 않는다. 둘 다 바깥 서버를 부르므로 DB 트랜잭션 밖에서 한다.
   *
   * @param givenName 첫 로그인에만 온다. 두 번째부터는 비어 있고, 저장된 이름을 덮지 않는다
   * @throws SocialTokenException 토큰·코드를 믿을 수 없거나({@code INVALID}) 애플에 닿지 못했다({@code UNAVAILABLE})
   */
  public SocialIdentity verify(
      String identityToken,
      String authorizationCode,
      String rawNonce,
      String givenName,
      String familyName) {
    SocialIdentity identity = verifier.verify(identityToken, rawNonce);
    String refreshToken = client.exchange(authorizationCode);
    return identity
        .withDisplayName(displayName(givenName, familyName))
        .withAppleRefreshTokenEnc(cipher.encrypt(refreshToken));
  }

  /**
   * 회원 탈퇴 전에 애플 연결을 끊는다.
   *
   * <p><b>던지지 않는다.</b> 계약상 탈퇴는 애플 사정과 상관없이 진행한다. 애플 신분이 없거나, 토큰을 받아 두지 못했거나, 풀 수 없거나(키가 바뀜), 애플이
   * 거절해도 로그만 남긴다.
   */
  public void revokeFor(UUID userId) {
    if (!enabled()) {
      return;
    }
    try {
      links
          .appleRefreshTokenEnc(userId)
          .ifPresent(cipherText -> client.revoke(cipher.decrypt(cipherText)));
    } catch (RuntimeException e) {
      log.warn("애플 연결 끊기를 준비하지 못했습니다 — 탈퇴는 진행합니다: {}", e.getMessage());
    }
  }

  /**
   * 애플이 따로 준 이름을 한 줄로.
   *
   * <p>한글 이름이면 성을 앞에 붙여 띄우지 않는다(「김철수」). 그 밖에는 이름·성 순서로 띄운다(「Jane Doe」). 둘 다 비면 {@code null} — 저장된
   * 이름을 덮지 않는다.
   */
  static String displayName(String givenName, String familyName) {
    String given = givenName == null ? "" : givenName.strip();
    String family = familyName == null ? "" : familyName.strip();
    if (given.isEmpty() && family.isEmpty()) {
      return null;
    }
    if (given.isEmpty() || family.isEmpty()) {
      return given.isEmpty() ? family : given;
    }
    return hangul(given) && hangul(family) ? family + given : given + " " + family;
  }

  private static boolean hangul(String s) {
    return s.codePoints()
        .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL);
  }
}
