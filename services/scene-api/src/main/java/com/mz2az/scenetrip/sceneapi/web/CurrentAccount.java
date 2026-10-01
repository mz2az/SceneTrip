package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.InvalidAccessTokenException;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 이 요청의 계정을 정한다 — 계약 「인증」 절의 규칙 그대로 (ADR 0018, 계획 §3).
 *
 * <pre>
 * Authorization 있음 ─> 서명·만료 ─> 토큰의 계정 (살아 있어야 한다)
 *                       ├ 만료                     ─> 401 ACCESS_TOKEN_EXPIRED
 *                       └ 깨짐·위조·탈퇴·합쳐짐     ─> 401 ACCESS_TOKEN_INVALID
 * Authorization 없음 ─> X-Install-Id ─> 계정 (없으면 비회원 계정을 만든다)
 *                       └ 그 계정이 가입 계정이면  ─> 401 SESSION_REQUIRED
 * </pre>
 *
 * <p><b>가입 계정은 설치 UUID 만으로 열리지 않는다.</b> 설치 UUID 는 비밀이 아니다(앱 설정 저장소에 있고 요청마다 평문 헤더로 나간다). 비회원은 잃을 것이
 * 적어 그것으로 충분하지만, 가입 계정까지 헤더 하나로 열리면 로그인이 무의미하다.
 *
 * <p>토큰과 설치 UUID 가 함께 오면 <b>토큰이 이긴다.</b> 설치 UUID 는 이 경로에서 쓰지 않는다 — 헤더가 필수인 창구라 값은 늘 오지만, 어느 계정인지는
 * 서명된 쪽이 말한다.
 *
 * <p>계정이 필요 없는 창구(검색·지도)는 이것을 부르지 않으므로 토큰을 보지도 않는다 — 만료된 토큰을 단 채 검색해도 결과가 나온다.
 *
 * <p>컨트롤러가 생성된 인터페이스를 구현해 메서드 모양을 바꿀 수 없으므로, 헤더는 요청에서 직접 읽는다. 주입되는 {@link HttpServletRequest} 는
 * 스프링이 요청마다 실제 요청으로 이어 주는 대리 객체다.
 */
@Component
public class CurrentAccount {

  private static final String BEARER = "Bearer ";

  private static final Logger log = LoggerFactory.getLogger(CurrentAccount.class);

  private final HttpServletRequest request;
  private final AccessTokens tokens;
  private final UserStore users;

  CurrentAccount(HttpServletRequest request, AccessTokens tokens, UserStore users) {
    this.request = request;
    this.tokens = tokens;
    this.users = users;
  }

  /**
   * 이 요청의 계정 id.
   *
   * @param installId {@code X-Install-Id} 헤더로 온 설치 UUID. 토큰이 있으면 쓰지 않는다
   * @throws ApiException 401 — {@code ACCESS_TOKEN_EXPIRED} · {@code ACCESS_TOKEN_INVALID} · {@code
   *     SESSION_REQUIRED}
   */
  public UUID resolve(UUID installId) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null) {
      return signedIn(authorization);
    }
    UserStore.Account account = users.lookup(installId);
    if (account.registered()) {
      throw ApiException.unauthorized(
          "SESSION_REQUIRED", "가입한 계정입니다 — Authorization 헤더로 토큰을 보내야 합니다");
    }
    return account.id();
  }

  /**
   * 토큰이 반드시 있어야 하는 창구({@code /me})의 계정 id.
   *
   * <p>토큰이 없으면 {@code ACCESS_TOKEN_INVALID} 다. 계약의 {@code Unauthorized} 표에서 앱이 할 일이 「토큰을 지우고 로그인
   * 화면」 인 코드이고, 토큰 없이 이 창구를 부르는 것은 앱이 로그인 상태를 잘못 알고 있다는 뜻이라 그 처리가 맞다.
   *
   * @throws ApiException 401 — {@code ACCESS_TOKEN_EXPIRED} · {@code ACCESS_TOKEN_INVALID}
   */
  public UUID requireSignedIn() {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null) {
      throw invalid("Authorization 헤더가 없습니다 — 로그인해야 부를 수 있는 창구입니다");
    }
    return signedIn(authorization);
  }

  private UUID signedIn(String authorization) {
    // 「Bearer 」 는 대소문자를 가리지 않는다(RFC 6750 이 RFC 7235 의 인증 방식 이름 규칙을 따른다).
    if (authorization.length() <= BEARER.length()
        || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      throw invalid("Authorization 헤더가 Bearer 토큰이 아닙니다");
    }
    UUID userId;
    try {
      userId = tokens.verify(authorization.substring(BEARER.length()).strip());
    } catch (InvalidAccessTokenException e) {
      if (e.reason() == InvalidAccessTokenException.Reason.EXPIRED) {
        throw ApiException.unauthorized("ACCESS_TOKEN_EXPIRED", "액세스 토큰이 만료됐습니다");
      }
      throw invalid(e.getMessage());
    }
    // 서명은 맞아도 그 사이 탈퇴했거나 다른 계정으로 합쳐졌을 수 있다. 토큰에 가입 여부를 싣지
    // 않은 이유가 이것이다 — 요청마다 DB 가 답한다. 방문 기록을 남기는 쿼리와 한 번에 묻는다.
    if (!users.touchSignedIn(userId)) {
      throw invalid("토큰의 계정이 없습니다 — 탈퇴했거나 합쳐졌습니다");
    }
    return userId;
  }

  /**
   * 응답에는 이유를 싣지 않는다.
   *
   * <p>「서명이 틀렸다」 「발급자가 다르다」 「서명 키가 없다」 를 응답으로 알려 주면 위조를 시도하는 쪽이 한 단계씩 맞춰 갈 수 있고, 마지막 것은 서버 설정을
   * 드러낸다. 앱이 할 일은 어느 경우든 같다(토큰을 버리고 다시 로그인). 이유는 서버 로그에만 남긴다 — 로그인 문제를 쫓는 사람에게는 필요하다.
   */
  static ApiException invalid(String why) {
    log.debug("액세스 토큰 거절: {}", why);
    return ApiException.unauthorized("ACCESS_TOKEN_INVALID", "액세스 토큰을 믿을 수 없습니다");
  }
}
