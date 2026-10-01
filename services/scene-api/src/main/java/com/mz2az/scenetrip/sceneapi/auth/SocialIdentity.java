package com.mz2az.scenetrip.sceneapi.auth;

/**
 * 구글·애플 ID 토큰을 검증해 얻은 신분.
 *
 * @param provider {@code google} · {@code apple} — {@code user_identity.provider} 값 그대로
 * @param subject 제공자 안에서 사람마다 고정인 값({@code sub}). 사람의 구분은 이것이다
 * @param email 제공자가 <b>확인했다고 한</b> 이메일만. 아니면 {@code null}. 참고용이며 사람을 가르지 않는다
 * @param displayName 이름. 없을 수 있다
 * @param appleRefreshTokenEnc 애플만 — 탈퇴 때 연결 끊기에 쓸 애플 refresh token 의 <b>암호문</b>({@link
 *     TokenCipher}). 원문은 이 record 에 싣지 않는다. 구글은 {@code null}
 */
public record SocialIdentity(
    String provider,
    String subject,
    String email,
    String displayName,
    byte[] appleRefreshTokenEnc) {

  /** 저장할 제공자 토큰이 없는 신분(구글). */
  public SocialIdentity(String provider, String subject, String email, String displayName) {
    this(provider, subject, email, displayName, null);
  }

  /** 앱이 따로 보낸 이름을 붙인다 — 애플은 이름을 토큰이 아니라 첫 로그인의 앱 응답으로만 준다. */
  public SocialIdentity withDisplayName(String name) {
    return new SocialIdentity(provider, subject, email, name, appleRefreshTokenEnc);
  }

  /** 애플 refresh token 의 암호문을 붙인다. */
  public SocialIdentity withAppleRefreshTokenEnc(byte[] cipherText) {
    return new SocialIdentity(provider, subject, email, displayName, cipherText);
  }

  @Override
  public String toString() {
    // 이메일과 토큰 암호문은 로그에 남기지 않는다.
    return "SocialIdentity[provider=" + provider + ", subject=" + subject + "]";
  }
}
