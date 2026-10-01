package com.mz2az.scenetrip.sceneapi.auth;

import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 한 번 — 가입·로그인·합치기와 리프레시 토큰 발급을 <b>한 트랜잭션</b>에서 (계획 §5, MZ2AZ-256).
 *
 * <p>검증된 소셜 신분과 이 설치본으로 셋 중 하나가 된다.
 *
 * <table>
 *   <caption>로그인의 세 갈래</caption>
 *   <tr><th>그 신분이</th><th>처리</th><th>isNewUser</th><th>merged</th></tr>
 *   <tr><td>처음</td><td>이 설치본의 비회원 계정에 붙여 가입 — 데이터는 안 움직인다</td><td>true</td><td>false</td></tr>
 *   <tr><td>이미 이 설치본의 계정에</td><td>아무것도 안 한다</td><td>false</td><td>false</td></tr>
 *   <tr><td>다른 계정 X 에</td><td>이 설치본의 비회원 데이터를 X 로 합친다</td><td>false</td><td>true</td></tr>
 * </table>
 *
 * <p>중간에 실패하면 전부 되돌아간다 — 「코스는 옮겼는데 장바구니는 남았다」 가 가장 나쁜 상태다. {@code @Transactional} 이 아니라 {@link
 * TransactionTemplate} 인 이유는 {@code CourseStore} 와 같다(통합 테스트가 스프링 없이 만든다).
 *
 * <h2>동시성</h2>
 *
 * <ul>
 *   <li><b>같은 소셜 신분으로 두 설치본이 동시에 첫 로그인</b>: 둘 다 「처음」 으로 보고 신분을 붙이려 한다. {@code user_identity} 의 기본키가
 *       늦은 쪽을 막고({@link DuplicateKeyException}), 트랜잭션이 통째로 되돌아간 뒤 <b>한 번 더</b> 돈다 — 이번에는 신분이 있으므로
 *       합치기로 간다.
 *   <li><b>같은 설치본의 로그인이 동시에 둘</b>: 설치본 행을 잠그므로 차례로 돈다.
 *   <li><b>합치는 중 같은 설치본의 다른 쓰기</b>: 막지 못하는 틈이 있다(계획 §5 「알려진 틈」). 앱이 로그인 전에 진행 중인 쓰기를 기다리고(계약), 남은
 *       것은 {@link #sweep} 이 갱신 때 쓸어 온다.
 * </ul>
 */
@Service
public class SignInService {

  private static final Logger log = LoggerFactory.getLogger(SignInService.class);

  private final UserStore users;
  private final AccountLinkStore links;
  private final RefreshTokenStore refreshTokens;
  private final TransactionTemplate transactions;

  public SignInService(
      UserStore users,
      AccountLinkStore links,
      RefreshTokenStore refreshTokens,
      TransactionTemplate transactions) {
    this.users = users;
    this.links = links;
    this.refreshTokens = refreshTokens;
    this.transactions = transactions;
  }

  /**
   * 로그인한다.
   *
   * @param identity 이미 검증된 신분 — 검증은 호출하는 쪽이 한다
   * @param installUuid {@code X-Install-Id}
   * @return 계정과 새 리프레시 토큰. 액세스 토큰은 호출하는 쪽이 이 계정으로 발급한다
   */
  public SignedIn signIn(SocialIdentity identity, UUID installUuid) {
    // 설치본 행을 트랜잭션 밖에서 먼저 만들어 둔다. 처음 보는 설치본이면 비회원 계정이 생긴다.
    // 안에서 만들면 같은 설치본의 첫 요청과 겹쳤을 때 기본키 위반이 트랜잭션을 망가뜨린다 —
    // PostgreSQL 은 실패한 문장 뒤의 트랜잭션을 더 쓰지 못하게 한다.
    users.lookup(installUuid);
    try {
      return transactions.execute(status -> signInLocked(identity, installUuid));
    } catch (DuplicateKeyException race) {
      log.info("같은 신분의 첫 로그인이 겹쳤습니다 — 다시 돌려 합치기로 갑니다: {}", identity.provider());
      return transactions.execute(status -> signInLocked(identity, installUuid));
    }
  }

  /**
   * 합쳐진 빈 행에 늦게 들어온 것을 이 계정으로 쓸어 온다 — {@link AccountLinkStore#sweepInto}.
   *
   * <p><b>실패해도 던지지 않는다.</b> 갱신 때 덤으로 하는 복구라, 이것 때문에 사용자의 갱신이 실패하면 안 된다.
   */
  public void sweep(UUID userId) {
    try {
      transactions.executeWithoutResult(status -> links.sweepInto(userId));
    } catch (RuntimeException e) {
      log.warn("합치기 뒤 쓸어 오기 실패 — 다음 갱신 때 다시 합니다: {}", userId, e);
    }
  }

  private SignedIn signInLocked(SocialIdentity identity, UUID installUuid) {
    UserStore.Account here = lockInstall(installUuid);
    Optional<UUID> owner = links.findIdentity(identity.provider(), identity.subject());

    if (owner.isEmpty()) {
      if (here.registered()) {
        // 이 설치본이 이미 다른 가입 계정을 가리킨다 — 로그아웃 없이 다른 소셜 계정으로 로그인했다.
        // 로그아웃이 설치본을 새 비회원으로 바꾸므로 정상 경로로는 생기지 않는다. 가입 계정에 신분을
        // 하나 더 붙이면 두 사람이 한 계정을 쓰게 되므로, 이 설치본을 새 비회원으로 바꾼 뒤 가입시킨다.
        users.detachInstall(installUuid, here.id());
        here = lockInstall(installUuid);
      }
      links.register(
          here.id(),
          identity.provider(),
          identity.subject(),
          identity.email(),
          identity.displayName());
      return new SignedIn(here.id(), true, false, refreshTokens.issue(here.id(), installUuid));
    }

    UUID to = owner.get();
    links.refreshIdentity(
        identity.provider(), identity.subject(), identity.email(), identity.displayName());
    if (to.equals(here.id())) {
      return new SignedIn(to, false, false, refreshTokens.issue(to, installUuid));
    }
    if (here.registered()) {
      // 이 설치본이 다른 가입 계정을 가리킨다. 가입 계정끼리는 합치지 않는다 — 설치본만 옮긴다.
      links.repointInstall(installUuid, to);
      return new SignedIn(to, false, false, refreshTokens.issue(to, installUuid));
    }
    links.merge(here.id(), to);
    return new SignedIn(to, false, true, refreshTokens.issue(to, installUuid));
  }

  private UserStore.Account lockInstall(UUID installUuid) {
    return links
        .lockInstallAccount(installUuid)
        .orElseThrow(() -> new IllegalStateException("설치본이 없습니다 — 트랜잭션 밖에서 먼저 만들었어야 합니다"));
  }

  /**
   * 로그인 결과.
   *
   * @param userId 로그인한 계정
   * @param isNewUser 이번 로그인으로 가입했는가
   * @param merged 이 설치본의 비회원 데이터를 이 계정으로 합쳤는가
   * @param refreshToken 새 로그인 사슬의 첫 리프레시 토큰
   */
  public record SignedIn(
      UUID userId, boolean isNewUser, boolean merged, IssuedToken refreshToken) {}
}
