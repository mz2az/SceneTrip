package com.mz2az.scenetrip.sceneapi.auth;

/**
 * 액세스 토큰을 믿을 수 없다.
 *
 * <p>이유를 둘로만 가른다. 앱이 할 일이 둘뿐이기 때문이다 — 만료면 갱신해서 다시 보내고, 그 밖의 모든 경우는 토큰을 버리고 다시 로그인한다. 「서명이 틀렸다」
 * 「발급자가 다르다」 를 더 잘게 나눠 알려 주면 위조를 시도하는 쪽에만 도움이 된다.
 *
 * <p>웹 계층이 이것을 {@code 401 ACCESS_TOKEN_EXPIRED} · {@code 401 ACCESS_TOKEN_INVALID} 로 바꾼다. 이 패키지는
 * HTTP 를 모른다.
 */
public class InvalidAccessTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 앱이 할 일에 따라 가른 이유. */
  public enum Reason {
    /** 서명은 맞는데 만료됐다 — 갱신 후 재시도. */
    EXPIRED,
    /** 그 밖의 전부 — 형식·서명·발급자·알고리즘이 맞지 않거나, 토큰을 검증할 키가 없다. 다시 로그인. */
    INVALID
  }

  private final Reason reason;

  InvalidAccessTokenException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
