package com.mz2az.scenetrip.sceneapi.auth;

/**
 * 웹 계층 시험이 검증기의 실패를 흉내 낼 때 쓴다.
 *
 * <p>{@link SocialTokenException} 의 생성자는 이 패키지 안에서만 보인다 — 검증기만 던지게 한 것이다. 다른 패키지의 시험이 목 검증기에서 그것을
 * 던지게 하려면 같은 패키지에서 만들어 건네야 한다.
 */
public final class SocialTokenFailures {

  private SocialTokenFailures() {}

  public static SocialTokenException invalid(String why) {
    return new SocialTokenException(SocialTokenException.Reason.INVALID, why);
  }

  public static SocialTokenException unavailable(String why) {
    return new SocialTokenException(
        SocialTokenException.Reason.UNAVAILABLE, why, new RuntimeException(why));
  }
}
