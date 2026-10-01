package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.AuthApi;
import com.mz2az.scenetrip.sceneapi.api.model.AppleSignIn;
import com.mz2az.scenetrip.sceneapi.api.model.AuthProvider;
import com.mz2az.scenetrip.sceneapi.api.model.AuthSession;
import com.mz2az.scenetrip.sceneapi.api.model.GoogleSignIn;
import com.mz2az.scenetrip.sceneapi.api.model.LinkedIdentity;
import com.mz2az.scenetrip.sceneapi.api.model.Me;
import com.mz2az.scenetrip.sceneapi.api.model.RefreshTokenBody;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.GoogleIdTokenVerifier;
import com.mz2az.scenetrip.sceneapi.auth.IssuedToken;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.auth.SocialIdentity;
import com.mz2az.scenetrip.sceneapi.auth.SocialTokenException;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 창구 (ADR 0018, 계획 {@code docs/project/plans/social-login.md}).
 *
 * <p>구글·애플 로그인(가입 겸)·갱신·로그아웃·내 계정·탈퇴. 애플 로그인은 애플 개인 키와 토큰 암호화 키가 있는 환경에서만 열린다 — 없으면 {@code 501}(앱은
 * 애플 버튼을 숨긴다).
 */
@RestController
class AuthController implements AuthApi {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final AccessTokens tokens;
  private final RefreshTokenStore refreshTokens;
  private final UserStore users;
  private final CurrentAccount accounts;
  private final GoogleIdTokenVerifier google;
  private final SignInService signIn;
  private final AppleLogin apple;

  AuthController(
      AccessTokens tokens,
      RefreshTokenStore refreshTokens,
      UserStore users,
      CurrentAccount accounts,
      GoogleIdTokenVerifier google,
      SignInService signIn,
      AppleLogin apple) {
    this.tokens = tokens;
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.accounts = accounts;
    this.google = google;
    this.signIn = signIn;
    this.apple = apple;
  }

  /**
   * 구글로 로그인 — 처음이면 가입.
   *
   * <p>순서가 뜻을 가진다. 구글 토큰을 먼저 검증하고(밖의 서버를 부를 수 있다), 그다음에야 DB 트랜잭션을 연다. 트랜잭션 안에서 구글을 기다리면 그동안 이 설치본의
   * 행을 잠근 채 커넥션을 붙잡는다.
   */
  @Override
  public ResponseEntity<AuthSession> signInWithGoogle(UUID xInstallId, GoogleSignIn body) {
    // 서명 키가 없으면 로그인이 꺼진 것이다. 가입까지 시켜 놓고 토큰을 못 주면 「가입은 됐는데
    // 로그인이 안 된다」 가 된다 — 아무것도 하기 전에 멈춘다. 서버 설정 문제라 500 이다.
    if (!tokens.enabled()) {
      throw new IllegalStateException("JWT 서명 키가 없어 로그인할 수 없습니다");
    }
    SocialIdentity identity;
    try {
      identity = google.verify(body.getIdToken(), body.getNonce());
    } catch (SocialTokenException e) {
      throw socialFailure(e);
    }
    return completeSignIn(identity, xInstallId);
  }

  /**
   * 애플로 로그인 — 처음이면 가입.
   *
   * <p>애플 개인 키·토큰 암호화 키가 없는 환경이면 {@code 501} 이다. 로그인은 되는데 탈퇴 때 애플 연결을 못 끊는 상태(App Store 요건 위반)를 만들지
   * 않으려고 창구 자체를 닫는다. 앱은 501 이면 애플 버튼을 숨긴다.
   *
   * <p>순서는 구글과 같다 — 바깥 서버(애플 공개키, 애플 토큰 창구)를 먼저 부르고 그다음에 DB 트랜잭션을 연다.
   */
  @Override
  public ResponseEntity<AuthSession> signInWithApple(UUID xInstallId, AppleSignIn body) {
    if (!apple.enabled()) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
    if (!tokens.enabled()) {
      throw new IllegalStateException("JWT 서명 키가 없어 로그인할 수 없습니다");
    }
    SocialIdentity identity;
    try {
      identity =
          apple.verify(
              body.getIdentityToken(),
              body.getAuthorizationCode(),
              body.getNonce(),
              body.getGivenName(),
              body.getFamilyName());
    } catch (SocialTokenException e) {
      throw socialFailure(e);
    }
    return completeSignIn(identity, xInstallId);
  }

  /** 검증된 신분으로 가입·로그인·합치기를 하고 토큰 묶음을 만든다 — 구글·애플 공통. */
  private ResponseEntity<AuthSession> completeSignIn(SocialIdentity identity, UUID installId) {
    SignInService.SignedIn result = signIn.signIn(identity, installId);
    UserStore.Profile profile =
        users
            .profile(result.userId())
            .orElseThrow(() -> new IllegalStateException("방금 로그인한 계정이 없습니다: " + result.userId()));
    log.info(
        "로그인: {} 계정 {} (가입 {}, 합침 {})",
        identity.provider(),
        result.userId(),
        result.isNewUser(),
        result.merged());
    return ResponseEntity.ok(
        session(profile, result.refreshToken(), result.isNewUser(), result.merged()));
  }

  /**
   * 갱신 — 받은 리프레시 토큰을 쓰고 새 두 토큰을 준다.
   *
   * <p>거절 이유(모름·만료·폐기·재사용)는 응답에서 하나다. 앱이 할 일이 같기 때문이다. 이유는 로그에 남긴다 — 특히 재사용은 탈취이거나 앱의 「동시에 한 번만」 이
   * 새는 것이라 추적할 가치가 있다.
   */
  @Override
  public ResponseEntity<AuthSession> refreshSession(RefreshTokenBody body) {
    // 서명 키가 없으면 로그인이 꺼진 것이다 — 새 액세스 토큰을 만들 수 없다. 리프레시 토큰을 쓰지
    // 않은 채 거절한다(닫힌 쪽 실패, AccessTokens 와 같은 판단). 앱은 로그인 화면으로 가고, 거기서
    // 로그인도 안 되는 것이 「로그인이 꺼짐」 의 실제 모습이다.
    if (!tokens.enabled()) {
      log.warn("리프레시 거절: JWT 서명 키가 없어 로그인이 꺼져 있습니다");
      throw refreshInvalid();
    }
    RefreshTokenStore.Rotation rotation = refreshTokens.rotate(body.getRefreshToken());
    if (rotation instanceof RefreshTokenStore.Rejected rejected) {
      log.info("리프레시 거절: {}", rejected.reason());
      throw refreshInvalid();
    }
    RefreshTokenStore.Rotated rotated = (RefreshTokenStore.Rotated) rotation;
    UserStore.Profile profile =
        users
            .profile(rotated.userId())
            .orElseThrow(
                () -> {
                  // 토큰은 살아 있는데 계정이 가입 계정이 아니다. 정상 경로로는 생기지 않는다 —
                  // 탈퇴는 CASCADE 로 토큰을 지우고, 가입 계정은 합쳐지지 않는다. 남은 토큰을
                  // 끊고 거절한다.
                  log.warn("리프레시 토큰의 계정이 가입 계정이 아닙니다: {}", rotated.userId());
                  refreshTokens.revokeAll(rotated.userId());
                  return refreshInvalid();
                });
    // 합치기 직후 빈 행에 늦게 떨어진 쓰기를 쓸어 온다(계획 §5). 갱신은 30 분마다 오므로 늦어도
    // 그 안에 복구된다. 실패해도 갱신은 성공시킨다 — sweep 이 삼킨다.
    signIn.sweep(rotated.userId());
    return ResponseEntity.ok(session(profile, rotated.next(), false, false));
  }

  /**
   * 로그아웃 — 이 설치본에서만.
   *
   * <p>토큰의 사슬을 끊고, 이 설치본이 그 계정을 가리키고 있으면 새 비회원 계정으로 바꿔 단다. 모르는 토큰이어도 {@code 204} 다 — 앱은 결과와 상관없이
   * 저장한 토큰을 지운다(계약).
   */
  @Override
  public ResponseEntity<Void> signOut(UUID xInstallId, RefreshTokenBody body) {
    refreshTokens
        .revokeFamily(body.getRefreshToken())
        .ifPresent(userId -> users.detachInstall(xInstallId, userId));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Me> getMe() {
    UUID userId = accounts.requireSignedIn();
    // requireSignedIn 이 살아 있는 가입 계정임을 이미 확인했다. 그 사이 탈퇴하면 비어 있다.
    UserStore.Profile profile =
        users
            .profile(userId)
            .orElseThrow(() -> CurrentAccount.invalid("토큰을 확인한 직후 계정이 사라졌습니다 — 동시에 탈퇴"));
    return ResponseEntity.ok(me(profile));
  }

  /**
   * 탈퇴.
   *
   * <p>애플로 가입한 계정이면 애플 쪽 연결도 끊는다(App Store 요건). 애플이 응답하지 않아도 우리 쪽 삭제는 진행한다(계약).
   */
  @Override
  public ResponseEntity<Void> deleteMe() {
    UUID userId = accounts.requireSignedIn();
    // 애플 연결 끊기는 계정 행을 지우기 전에 — 토큰 암호문이 그 행에 매달려 있다. 실패해도 진행한다.
    // revokeFor 는 던지지 않기로 했지만 탈퇴를 그 약속 하나에 걸지 않는다 — 사용자가 탈퇴를 요청했는데
    // 애플 쪽 사정으로 500 이 나면 안 된다(계약).
    try {
      apple.revokeFor(userId);
    } catch (RuntimeException e) {
      log.warn("애플 연결 끊기 중 예외 — 탈퇴는 진행합니다", e);
    }
    users.delete(userId);
    log.info("탈퇴: {}", userId);
    return ResponseEntity.noContent().build();
  }

  /** 계정 모습과 새 리프레시 토큰으로 토큰 묶음을 만든다 — 액세스 토큰은 여기서 발급한다. */
  private AuthSession session(
      UserStore.Profile profile, IssuedToken refresh, boolean isNewUser, boolean merged) {
    IssuedToken access = tokens.issue(profile.id());
    return new AuthSession(
        access.value(),
        Math.toIntExact(access.expiresInSeconds()),
        refresh.value(),
        Math.toIntExact(refresh.expiresInSeconds()),
        me(profile),
        isNewUser,
        merged);
  }

  private static Me me(UserStore.Profile profile) {
    Me me =
        new Me(
            profile.id(),
            profile.identities().stream()
                .map(i -> new LinkedIdentity(AuthProvider.fromValue(i.provider())).email(i.email()))
                .toList(),
            profile.registeredAt());
    return me.displayName(profile.displayName()).email(profile.email());
  }

  /** 구글·애플 토큰 실패를 응답으로. 이유는 로그에만 — {@link CurrentAccount#invalid} 와 같은 판단이다. */
  private static ApiException socialFailure(SocialTokenException e) {
    if (e.reason() == SocialTokenException.Reason.UNAVAILABLE) {
      log.warn("소셜 로그인 제공자에 닿지 못했습니다", e);
      return ApiException.unavailable(
          "AUTH_PROVIDER_UNAVAILABLE", "로그인 제공자에 닿지 못했습니다 — 잠시 뒤 다시 시도하세요");
    }
    log.info("소셜 토큰 거절: {}", e.getMessage());
    return ApiException.unauthorized("SOCIAL_TOKEN_INVALID", "로그인 토큰을 믿을 수 없습니다 — 다시 로그인하세요");
  }

  private static ApiException refreshInvalid() {
    return ApiException.unauthorized("REFRESH_TOKEN_INVALID", "리프레시 토큰을 쓸 수 없습니다 — 다시 로그인하세요");
  }
}
