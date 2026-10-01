package com.mz2az.scenetrip.sceneapi.auth;

/**
 * 발급한 토큰 하나 — 앱에 그대로 내려보낼 문자열과 남은 수명.
 *
 * <p>수명을 시각이 아니라 <b>초</b>로 준다. 계약의 {@code accessTokenExpiresIn} · {@code refreshTokenExpiresIn} 이
 * 그렇다 — 앱의 시계가 서버와 어긋나도 「몇 초 뒤」 는 어긋나지 않는다.
 *
 * @param value 토큰 원문. 액세스 토큰은 JWT, 리프레시 토큰은 난수다. <b>로그에 남기지 않는다.</b>
 * @param expiresInSeconds 발급 시점부터 만료까지 초
 */
public record IssuedToken(String value, long expiresInSeconds) {

  @Override
  public String toString() {
    // 기본 toString 은 원문을 찍는다. 예외 메시지나 디버그 로그에 record 가 통째로 실리는
    // 일은 흔하므로 여기서 막는다.
    return "IssuedToken[value=<hidden>, expiresInSeconds=" + expiresInSeconds + "]";
  }
}
