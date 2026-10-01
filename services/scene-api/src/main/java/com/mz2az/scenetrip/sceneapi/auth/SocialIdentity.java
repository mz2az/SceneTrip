package com.mz2az.scenetrip.sceneapi.auth;

/**
 * 구글·애플 ID 토큰을 검증해 얻은 신분.
 *
 * @param provider {@code google} · {@code apple} — {@code user_identity.provider} 값 그대로
 * @param subject 제공자 안에서 사람마다 고정인 값({@code sub}). 사람의 구분은 이것이다
 * @param email 제공자가 <b>확인했다고 한</b> 이메일만. 아니면 {@code null}. 참고용이며 사람을 가르지 않는다
 * @param displayName 이름. 없을 수 있다
 */
public record SocialIdentity(String provider, String subject, String email, String displayName) {}
