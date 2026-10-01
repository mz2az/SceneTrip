package com.mz2az.scenetrip.sceneapi.auth;

/**
 * 구글·애플 토큰으로 로그인할 수 없다.
 *
 * <p>둘로 가른다. 토큰을 믿을 수 없는 것({@link Reason#INVALID} → {@code 401 SOCIAL_TOKEN_INVALID})과 제공자에 닿지 못한
 * 것({@link Reason#UNAVAILABLE} → {@code 503 AUTH_PROVIDER_UNAVAILABLE})이다. 앞의 것은 다시 로그인하게 하고, 뒤의 것은
 * 사용자 잘못이 아니므로 잠시 뒤 재시도하게 한다. 이 패키지는 HTTP 를 모른다 — 웹 계층이 바꾼다.
 */
public class SocialTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 앱이 할 일에 따라 가른 이유. */
  public enum Reason {
    /** 서명·발급자·대상·만료·nonce 중 하나가 맞지 않는다 */
    INVALID,
    /** 제공자의 공개키를 받아 오지 못했다 */
    UNAVAILABLE
  }

  private final Reason reason;

  SocialTokenException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  SocialTokenException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
